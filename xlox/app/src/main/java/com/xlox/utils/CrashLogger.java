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
}
