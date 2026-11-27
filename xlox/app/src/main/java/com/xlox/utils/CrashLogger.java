package com.xlox.utils;

import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import com.xlox.BuildConfig;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

/**
 * Last-crash recorder. The app ships no analytics: when an uncaught Java
 * exception kills the process, the stack trace is written to a private file
 * and the next home screen offers it as a copyable report. Nothing leaves the
 * phone unless the user pastes it somewhere.
 */
public final class CrashLogger {

    private static final String TAG = "CrashLogger";
    private static final String FILE = "last-crash.txt";
    private static final String ACTION_FILE = "last-action.txt";
    /** A report keeps at most this much text (a full trace is ~2-4 KB). */
    private static final int MAX_REPORT = 6000;

    private CrashLogger() {
    }

    /** Chains to the previous handler, so the system still reports the crash. */
    public static void install(final Application app) {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            if (isBenignFrameworkRace(error)) {
                // The container's broadcast relay can double-finish a
                // PendingResult on Android 16+: finish() defers sendFinished to
                // the queued-work thread, and a second finish re-runs it after
                // the result was already marked finished. The broadcast's work
                // is complete either way — fatal only because nobody catches it.
                Log.w(TAG, "Ignoring a benign framework race on " + thread.getName(), error);
                return;
            }
            try {
                write(app, thread, error);
            } catch (Throwable ignored) {
            }
            if (previous != null) {
                previous.uncaughtException(thread, error);
            } else {
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(10);
            }
        });
    }

    /** The Android 16+ double-finish race: a deferred sendFinished ran after
     *  the PendingResult was already finished. Matches the bare throw and the
     *  wrapped form (Play Services' LightweightExecutor rethrows it inside a
     *  RuntimeException, which is what used to kill the sign-in flow). */
    private static boolean isBenignFrameworkRace(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof IllegalStateException
                    && "Broadcast already finished".equals(t.getMessage())) {
                StackTraceElement[] trace = t.getStackTrace();
                if (trace.length > 0
                        && trace[0].getClassName().startsWith("android.content.BroadcastReceiver")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void write(Context ctx, Thread thread, Throwable error) {
        StringWriter trace = new StringWriter();
        error.printStackTrace(new PrintWriter(trace));
        String report = ("Anoy " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")\n"
                + "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n"
                + "Device: " + Build.MANUFACTURER + " " + Build.MODEL + "\n"
                + "Thread: " + thread.getName() + "\n\n") + trace;
        if (report.length() > MAX_REPORT) report = report.substring(0, MAX_REPORT);
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(new File(ctx.getFilesDir(), FILE));
            out.write(report.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.e(TAG, "Could not save the crash report", e);
        } finally {
            try {
                if (out != null) out.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** The saved report, or null when there is none (or it is unreadable). */
    public static String read(Context ctx) {
        File file = new File(ctx.getFilesDir(), FILE);
        if (!file.isFile()) return null;
        FileInputStream in = null;
        try {
            byte[] data = new byte[(int) Math.min(file.length(), MAX_REPORT + 16L)];
            in = new FileInputStream(file);
            int n = in.read(data);
            return n > 0 ? new String(data, 0, n, StandardCharsets.UTF_8) : null;
        } catch (Exception e) {
            return null;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Exception ignored) {
            }
        }
    }

    public static void clear(Context ctx) {
        try {
            new File(ctx.getFilesDir(), FILE).delete();
        } catch (Exception ignored) {
        }
    }

    /** Remembers what the app was about to do; survives a native (non-Java) death. */
    public static void noteAction(Context ctx, String action) {
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(new File(ctx.getFilesDir(), ACTION_FILE));
            out.write(action.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        } finally {
            try {
                if (out != null) out.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** The unfinished action recorded before the process last died, or null. */
    public static String pendingAction(Context ctx) {
        File file = new File(ctx.getFilesDir(), ACTION_FILE);
        if (!file.isFile()) return null;
        FileInputStream in = null;
        try {
            byte[] data = new byte[(int) Math.min(file.length(), 512L)];
            in = new FileInputStream(file);
            int n = in.read(data);
            return n > 0 ? new String(data, 0, n, StandardCharsets.UTF_8) : null;
        } catch (Exception e) {
            return null;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Exception ignored) {
            }
        }
    }

    public static void clearAction(Context ctx) {
        try {
            new File(ctx.getFilesDir(), ACTION_FILE).delete();
        } catch (Exception ignored) {
        }
    }

    /**
     * API 30+: the last native/container death the system recorded for this
     * app, as copyable text. The game runs in a virtualized process whose
     * native fault (abort/signal in the container, the game's own .so, or a
     * failed split mount) never reaches a Java catch — this is the only
     * on-device way to see it without ADB. Returns null when unavailable,
     * denied, or when there is no native exit to report.
     */
    public static String readNativeExit(Context ctx) {
        if (Build.VERSION.SDK_INT < 30) return null;
        try {
            android.app.ActivityManager am =
                    (android.app.ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return null;
            java.util.List<android.app.ApplicationExitInfo> exits =
                    am.getHistoricalProcessExitReasons(null, 0, 8);
            if (exits == null) return null;
            for (android.app.ApplicationExitInfo info : exits) {
                if (info.getReason() != android.app.ApplicationExitInfo.REASON_CRASH_NATIVE) continue;
                StringBuilder out = new StringBuilder();
                out.append("Process: ").append(info.getProcessName())
                        .append("  pid ").append(info.getPid()).append('\n')
                        .append("Importance: ").append(info.getImportance())
                        .append("  at ").append(info.getTimestamp()).append('\n');
                try {
                    java.io.InputStream trace = info.getTraceInputStream();
                    if (trace != null) {
                        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                        byte[] chunk = new byte[4096];
                        int n;
                        int total = 0;
                        while ((n = trace.read(chunk)) > 0 && total < MAX_REPORT) {
                            buf.write(chunk, 0, n);
                            total += n;
                        }
                        trace.close();
                        out.append("\n--- tombstone ---\n")
                                .append(new String(buf.toByteArray(), StandardCharsets.UTF_8));
                    } else {
                        out.append("(no tombstone trace exposed)");
                    }
                } catch (Throwable t) {
                    out.append("(tombstone unreadable: ").append(t.getClass().getSimpleName()).append(')');
                }
                String text = out.toString();
                return text.length() > MAX_REPORT ? text.substring(0, MAX_REPORT) : text;
            }
        } catch (Throwable t) {
            Log.w(TAG, "Native exit history unavailable", t);
        }
        return null;
    }
    /**
     * Last resort when ApplicationExitInfo is empty: walk every log/crash
     * location the app (or the root engine) can read and return the newest
     * file that names the dead package — its tail usually carries the abort
     * message or faulting library. Returns null when nothing readable matches.
     */
    public static String sweepContainerLogs(Context ctx, String pkg) {
        java.util.List<File> roots = new java.util.ArrayList<>();
        try {
            File files = ctx.getFilesDir();
            if (files != null) roots.add(files);
        } catch (Throwable ignored) {
        }
        try {
            File engine = net_62v.external.MetaStorageManager.obtainAppDataDir(pkg);
            if (engine != null && engine.isDirectory()) roots.add(engine);
        } catch (Throwable ignored) {
        }
        // The engine is root-adjacent; when it is, the system tombstones and
        // dropbox are the authoritative record of a native death.
        for (String p : new String[]{"/data/tombstones", "/data/system/dropbox",
                "/data/misc/perfetto_traces", "/sdcard/Android/data/" + ctx.getPackageName()}) {
            try {
                roots.add(new File(p));
            } catch (Throwable ignored) {
            }
        }
        File best = null;
        long bestTime = -1;
        String bestHead = null;
        for (File root : roots) {
            if (root == null) continue;
            File hit;
            try {
                hit = newestMentioning(root, pkg, 0);
            } catch (Throwable t) {
                continue; // a mocked/absent filesystem must never break the report
            }
            if (hit == null) continue;
            long mtime;
            try {
                mtime = hit.lastModified();
            } catch (Throwable t) {
                continue;
            }
            if (mtime > bestTime) {
                String tail = readTail(hit, 2500);
                if (tail != null) {
                    best = hit;
                    bestTime = mtime;
                    bestHead = tail;
                }
            }
        }
        if (best == null) return null;
        return "Log: " + best.getAbsolutePath() + "\n\n" + bestHead;
    }

    /** Deepest-first search for the newest readable file whose contents name pkg. */
    private static File newestMentioning(File root, String pkg, int depth) {
        if (root == null || depth > 5) return null;
        File best = null;
        File[] kids;
        try {
            kids = root.listFiles();
        } catch (Throwable t) {
            return null;
        }
        if (kids == null) return null;
        for (File f : kids) {
            try {
                if (f.isDirectory()) {
                    File sub = newestMentioning(f, pkg, depth + 1);
                    if (sub != null && (best == null || sub.lastModified() > best.lastModified())) best = sub;
                } else if (f.isFile() && f.length() > 0 && f.length() < 2_000_000 && mentions(f, pkg)) {
                    if (best == null || f.lastModified() > best.lastModified()) best = f;
                }
            } catch (Throwable ignored) {
            }
        }
        return best;
    }

    private static boolean mentions(File f, String pkg) {
        java.io.FileInputStream in = null;
        try {
            in = new java.io.FileInputStream(f);
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) {
                if (new String(chunk, 0, n, StandardCharsets.ISO_8859_1).contains(pkg)) return true;
            }
        } catch (Throwable t) {
            return false;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static String readTail(File f, int max) {
        java.io.RandomAccessFile raf = null;
        try {
            raf = new java.io.RandomAccessFile(f, "r");
            long len = raf.length();
            long start = Math.max(0, len - max);
            raf.seek(start);
            byte[] buf = new byte[(int) (len - start)];
            raf.readFully(buf);
            return new String(buf, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        } finally {
            try {
                if (raf != null) raf.close();
            } catch (Throwable ignored) {
            }
        }
    }
}

