package com.xlox.utils;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.SystemClock;
import androidx.annotation.RequiresApi;
import com.xlox.BuildConfig;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Explicit diagnostic only. No root, network or exit-history reads during startup. */
public final class AirCrashDiagnostics {
    private AirCrashDiagnostics() {}
    private static File directory(Context context) {
        return new File(context.getFilesDir(), "air_crash_diagnostics");
    }

    public static void recordLaunch(Context context) {
        if (!BuildConfig.AIR_NATIVE_ISOLATION) return;
        try {
            AirCrashSession.begin(directory(context), System.currentTimeMillis(), SystemClock.elapsedRealtime());
        } catch (IOException | RuntimeException ignored) {
            // Diagnostic I/O cannot break or reroute a launch.
        }
    }

    public static void recordGuest(Context context) {
        if (!BuildConfig.AIR_NATIVE_ISOLATION) return;
        try {
            AirCrashSession.guest(directory(context), System.currentTimeMillis(), SystemClock.elapsedRealtime(),
                    android.os.Process.myPid(), android.os.Process.myUid());
        } catch (IOException | RuntimeException ignored) {
            // Never turn a marker failure into an injected-library error.
        }
    }

    /** Worker-thread only; includeRoot is set only by the report sheet's explicit consent. */
    public static String collect(Context context, boolean includeRoot) {
        if (!BuildConfig.AIR_NATIVE_ISOLATION) return "Air diagnostics are disabled in normal builds.";
        String host = context.getPackageName();
        if (!AirExitReport.ownPackage(host)) return "Diagnostic host identity unavailable.";
        StringBuilder out = new StringBuilder("Air crash report v1\n");
        appendDeviceFacts(context, out);
        out.append("Scope: latest Air launch in this build, own UID, at most 4 exits.\n")
                .append("Excludes licence keys, IDs, descriptions, full paths, logs, memory/register contents.\n\n");
        long now = System.currentTimeMillis(), elapsed = SystemClock.elapsedRealtime();
        AirCrashSession session = AirCrashSession.current(directory(context), now, elapsed);
        if (session == null) return out + "No fresh Air launch marker. Launch Air once in this build, then copy within 15 minutes.\n";
        Set<Integer> guests = session.guestPids(directory(context), android.os.Process.myUid(), now, elapsed);
        out.append("Air callback marker: ").append(guests.isEmpty() ? "not seen; exits are unconfirmed candidates" : "seen; injection skipped")
                .append("\nLaunch time ms: ").append(session.wall).append('\n');
        if (Build.VERSION.SDK_INT >= 30) Api30.append(context, host, session, guests, now, out);
        else out.append("Android exit history needs API 30+.\n");
        if (includeRoot) {
            if (Build.VERSION.SDK_INT >= 30) out.append(Api30.rootDetails(host, session, guests, now));
            else out.append("Root exit-history collection needs API 30+.\n");
        } else out.append("Root: not requested. Optional root button reads only this loader's exit history.\n");
        out.append("No native frames means the module is still unidentified; do not infer a fix from this report.\n");
        return out.substring(0, Math.min(out.length(), AirExitReport.MAX_REPORT));
    }

    /** Worker-thread only. Called ONLY by the separate native button's fresh consent.
     * It never extends collect(includeRoot), startup, opening or copying a report.
     */
    public static String collectNative(Context context) {
        if (!BuildConfig.AIR_NATIVE_ISOLATION) return "Air diagnostics are disabled in normal builds.";
        String host = context.getPackageName();
        if (!AirExitReport.ownPackage(host)) return "Diagnostic host identity unavailable.";
        StringBuilder out = new StringBuilder("Air native crash report v2\n");
        appendDeviceFacts(context, out);
        out.append("Scope: newest confirmed Air callback exit in the latest fresh launch; own UID/PID/time.\n")
                .append("Separate read-only native request; no raw tombstones, logs, memory or full paths copied.\n");
        long now = System.currentTimeMillis(), elapsed = SystemClock.elapsedRealtime();
        AirCrashSession session = AirCrashSession.current(directory(context), now, elapsed);
        if (session == null) return out + "Native lookup not performed: no fresh launch marker. Reproduce Air once in this build, then return within 15 minutes.\n";
        Set<Integer> guests = session.guestPids(directory(context), android.os.Process.myUid(), now, elapsed);
        out.append("Launch time ms: ").append(session.wall).append('\n');
        if (guests.isEmpty()) return out + "Native lookup not performed: no confirmed Air callback marker; host-stub candidates are not sufficient.\n";
        out.append("Air callback marker: seen; injection skipped\n");
        if (Build.VERSION.SDK_INT >= 30) out.append(Api30.nativeDetails(context, host, session, guests, now));
        else out.append("Native lookup not performed: Android exit-history scope needs API 30+.\n");
        out.append("No verified native frames does not prove no record exists. This diagnostic is not a fix.\n");
        return out.substring(0, Math.min(out.length(), AirExitReport.MAX_REPORT));
    }

    private static void appendDeviceFacts(Context context, StringBuilder out) {
        out.append("Loader: ").append(BuildConfig.DISPLAY_BRAND).append(' ')
                .append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
                .append("Mode: Air injected library OFF; diagnostic, not a fix\n")
                .append("Android: API ").append(Build.VERSION.SDK_INT).append(' ')
                .append(AirExitReport.safeFact(Build.VERSION.RELEASE)).append('\n')
                .append("Device: ").append(AirExitReport.safeFact(Build.MANUFACTURER)).append(' ')
                .append(AirExitReport.safeFact(Build.MODEL)).append('\n');
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(GameTarget.BGMI_AIR.packageName, 0);
            out.append("Air version: ").append(AirExitReport.safeFact(info.versionName)).append(" (")
                    .append(Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode).append(")\n");
        } catch (Exception ignored) { out.append("Air version: unavailable\n"); }
    }

    @RequiresApi(30)
    private static final class Api30 {
        static String nativeDetails(Context context, String host, AirCrashSession session, Set<Integer> guests, long now) {
            try {
                ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
                if (am == null) return "Native lookup not performed: scoped Android exit history unavailable.\n";
                List<ApplicationExitInfo> history = am.getHistoricalProcessExitReasons(host, 0, 32);
                List<AirExitReport.Exit> exits = new ArrayList<>();
                if (history != null) for (int i = 0; i < Math.min(history.size(), 32); i++) {
                    ApplicationExitInfo e = history.get(i);
                    if (e != null) exits.add(new AirExitReport.Exit(e.getPid(), e.getPackageUid(), e.getProcessName(),
                            e.getTimestamp(), e.getReason(), e.getStatus()));
                }
                return RootNativeCrashReader.read(host, android.os.Process.myUid(), session, guests, exits, now);
            } catch (RuntimeException ignored) {
                return "Native lookup not performed: matching Android exit history unavailable/restricted.\n";
            }
        }

        static void append(Context context, String host, AirCrashSession session, Set<Integer> guests,
                           long now, StringBuilder out) {
            try {
                ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
                if (am == null) { out.append("Android exit history unavailable.\n"); return; }
                List<ApplicationExitInfo> exits = am.getHistoricalProcessExitReasons(host, 0, 32);
                int found = 0;
                if (exits != null) for (ApplicationExitInfo info : exits) {
                    AirExitReport.Exit e = new AirExitReport.Exit(info.getPid(), info.getPackageUid(),
                            info.getProcessName(), info.getTimestamp(), info.getReason(), info.getStatus());
                    if (!AirExitReport.matches(e, host, android.os.Process.myUid(), session, guests, now)) continue;
                    out.append(AirExitReport.exitLine(e, guests.contains(e.pid)));
                    if (Build.VERSION.SDK_INT >= 31 && info.getReason() == ApplicationExitInfo.REASON_CRASH_NATIVE) {
                        try (InputStream trace = info.getTraceInputStream()) {
                            if (trace == null) out.append("Native trace: Android retained no readable tombstone.\n");
                            else out.append(AirExitReport.nativeTrace(trace, e.pid, e.uid));
                        } catch (IOException | RuntimeException ignored) {
                            out.append("Native trace unavailable, invalid or over the 8 MiB input limit.\n");
                        }
                    } else out.append("Native trace not read: record is not an API 31+ native crash.\n");
                    if (++found == 4 || out.length() >= AirExitReport.MAX_REPORT - 2048) break;
                }
                if (found == 0) out.append("Android exit history: no matching recent record yet. Wait a few seconds after the crash and retry.\n");
            } catch (RuntimeException ignored) {
                out.append("Android exit history unavailable/restricted; optional root may provide numeric exit details.\n");
            }
        }

        static String rootDetails(String host, AirCrashSession session, Set<Integer> guests, long now) {
            if (!AirExitReport.ownPackage(host)) return "Root command refused: unknown host.\n";
            java.lang.Process process = null;
            FutureTask<byte[]> reader = null;
            try {
                // Exactly one read-only, package-pinned command. Never logcat-all,
                // tombstone directories, game files, memory or signal/protection changes.
                process = new ProcessBuilder("su", "-c", "/system/bin/dumpsys activity exit-info " + host)
                        .redirectErrorStream(true).start();
                final InputStream stream = process.getInputStream();
                reader = new FutureTask<>(() -> AirExitReport.readBounded(stream, AirExitReport.MAX_ROOT));
                Thread thread = new Thread(reader, "air-exit-root-reader");
                thread.setDaemon(true);
                thread.start();
                byte[] bytes = reader.get(20, TimeUnit.SECONDS);
                if (!process.waitFor(2, TimeUnit.SECONDS) || process.exitValue() != 0)
                    return "Root exit details unavailable/denied (no raw error copied).\n";
                return AirExitReport.rootSummary(new String(bytes, StandardCharsets.UTF_8), host,
                        android.os.Process.myUid(), session, guests, System.currentTimeMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "Root request cancelled.\n";
            } catch (IOException | ExecutionException | TimeoutException | RuntimeException ignored) {
                return "Root exit details unavailable, denied, timed out or over the output limit.\n";
            } finally {
                if (process != null) {
                    process.destroyForcibly();
                    try { process.getInputStream().close(); } catch (IOException ignored) {}
                    try { process.getOutputStream().close(); } catch (IOException ignored) {}
                    try { process.getErrorStream().close(); } catch (IOException ignored) {}
                }
                if (reader != null) reader.cancel(true);
            }
        }
    }
}
