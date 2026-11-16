package com.xlox;

import android.content.Context;
import android.os.Build;
import android.os.Process;
import android.util.Log;

import java.io.File;
import java.io.FileFilter;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

/** Writes bounded, app-private text reports for uncaught Java exceptions. */
public final class CrashReporter {

    private static final String TAG = "CrashReporter";
    private static final String REPORT_PREFIX = "crash-";
    private static final String REPORT_SUFFIX = ".txt";
    private static final int MAX_REPORTS = 5;
    private static final Object INSTALL_LOCK = new Object();
    private static final Object REPORT_LOCK = new Object();
    private static boolean installed;
    private static int nextFileSequence;

    private CrashReporter() {
    }

    /** Wraps, rather than replaces, the process's existing uncaught handler. */
    public static void install(Context context) {
        if (context == null) return;
        synchronized (INSTALL_LOCK) {
            if (installed) return;
            try {
                Context app = context.getApplicationContext();
                final Context appContext = app != null ? app : context;
                final Thread.UncaughtExceptionHandler previous =
                        Thread.getDefaultUncaughtExceptionHandler();
                Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
                    try {
                        writeReport(appContext, thread, error);
                    } catch (Throwable ignored) {
                        // Always hand the original crash to Android's handler.
                    }
                    try {
                        if (previous != null) {
                            previous.uncaughtException(thread, error);
                        } else {
                            // No handler was installed by the runtime; do not swallow the crash.
                            terminateProcess();
                        }
                    } catch (Throwable ignored) {
                        // If the previous handler itself fails, still terminate the crashed process.
                        terminateProcess();
                    }
                });
                installed = true;
            } catch (Throwable ignored) {
                // Crash capture is best-effort and must not affect startup.
            }
        }
    }

    /** Logs a single local notice; reports are deliberately left in place. */
    public static void logExistingReports(Context context) {
        try {
            File directory = crashDirectory(context);
            File[] reports = reportFiles(directory);
            if (reports.length > 0) {
                Log.w(TAG, reports.length + " crash report(s) saved in " + directory.getAbsolutePath());
            }
        } catch (Throwable ignored) {
            // A missing or unreadable report directory must not affect startup.
        }
    }

    private static void writeReport(Context context, Thread thread, Throwable error) {
        synchronized (REPORT_LOCK) {
            File report = null;
            boolean written = false;
            try {
                File directory = crashDirectory(context);
                if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) return;

                long now = System.currentTimeMillis();
                report = newReportFile(directory, now, thread == null ? -1L : thread.getId());
                if (report == null) return;

                byte[] contents = buildReport(now, thread, error).getBytes(StandardCharsets.UTF_8);
                try (FileOutputStream output = new FileOutputStream(report, false)) {
                    output.write(contents);
                    output.flush();
                    try {
                        output.getFD().sync();
                    } catch (Throwable ignored) {
                        // The write is already complete; keep the report if sync is unsupported.
                    }
                }
                written = true;
                pruneOldReports(directory);
            } catch (Throwable ignored) {
                // Never let report-writing failures interfere with crash handling.
            } finally {
                if (!written && report != null) {
                    try {
                        report.delete();
                    } catch (Throwable ignored) {
                        // Best effort cleanup of an incomplete report.
                    }
                }
            }
        }
    }

    private static void terminateProcess() {
        try {
            Process.killProcess(Process.myPid());
        } catch (Throwable ignored) {
            // Best effort fallback when the previous handler is unavailable.
        }
        try {
            System.exit(10);
        } catch (Throwable ignored) {
            // The original uncaught exception has already been handed off.
        }
    }

    private static File crashDirectory(Context context) {
        Context app = context.getApplicationContext();
        return new File((app != null ? app : context).getFilesDir(), "crash");
    }

    private static File newReportFile(File directory, long now, long threadId) {
        for (int attempt = 0; attempt < 100; attempt++) {
            int sequence = nextFileSequence++;
            File report = new File(directory,
                    REPORT_PREFIX + now + "-" + threadId + "-" + sequence + REPORT_SUFFIX);
            try {
                if (report.createNewFile()) return report;
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    private static String buildReport(long now, Thread thread, Throwable error) {
        StringWriter stack = new StringWriter();
        PrintWriter writer = new PrintWriter(stack);
        if (error != null) {
            // Throwable.printStackTrace includes the complete stack and causes.
            error.printStackTrace(writer);
        } else {
            writer.println("<no throwable supplied>");
        }
        writer.flush();

        StringBuilder report = new StringBuilder(stack.getBuffer().length() + 1024);
        report.append("Timestamp: ").append(timestamp(now)).append('\n');
        report.append("versionName: ").append(BuildConfig.VERSION_NAME).append('\n');
        report.append("versionCode: ").append(BuildConfig.VERSION_CODE).append('\n');
        report.append("Build.MODEL: ").append(oneLine(Build.MODEL)).append('\n');
        report.append("Build.VERSION.SDK_INT: ").append(Build.VERSION.SDK_INT).append('\n');
        report.append("Thread: ").append(oneLine(thread == null ? null : thread.getName())).append('\n');
        report.append("\nStack trace:\n").append(stack);
        if (stack.getBuffer().length() == 0 || stack.getBuffer().charAt(stack.getBuffer().length() - 1) != '\n') {
            report.append('\n');
        }
        report.append("\nBreadcrumbs:\n");
        String breadcrumbs = Breadcrumbs.dump();
        report.append(breadcrumbs.isEmpty() ? "(none)" : breadcrumbs).append('\n');
        return report.toString();
    }

    private static void pruneOldReports(File directory) {
        File[] reports = reportFiles(directory);
        if (reports.length <= MAX_REPORTS) return;
        Arrays.sort(reports, (left, right) -> {
            int newestFirst = Long.compare(right.lastModified(), left.lastModified());
            return newestFirst != 0 ? newestFirst : right.getName().compareTo(left.getName());
        });
        for (int i = MAX_REPORTS; i < reports.length; i++) {
            try {
                reports[i].delete();
            } catch (Throwable ignored) {
                // Best effort; keep writing the current crash report.
            }
        }
    }

    private static File[] reportFiles(File directory) {
        File[] reports = directory.listFiles(new FileFilter() {
            @Override
            public boolean accept(File file) {
                return file.isFile() && file.getName().startsWith(REPORT_PREFIX)
                        && file.getName().endsWith(REPORT_SUFFIX);
            }
        });
        return reports == null ? new File[0] : reports;
    }

    private static String timestamp(long timeMillis) {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)
                .format(new Date(timeMillis));
    }

    private static String oneLine(String value) {
        if (value == null || value.trim().isEmpty()) return "unknown";
        return value.replace('\r', ' ').replace('\n', ' ').trim();
    }
}
