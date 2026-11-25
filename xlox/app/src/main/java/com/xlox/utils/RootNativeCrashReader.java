package com.xlox.utils;

import androidx.annotation.RequiresApi;
import com.xlox.BuildConfig;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Root is requested only by the native-record sheet's second, explicit consent. */
@RequiresApi(30)
final class RootNativeCrashReader {
    private RootNativeCrashReader() {}

    static String read(String host, int uid, AirCrashSession session, Set<Integer> guests,
                       List<AirExitReport.Exit> exits, long now) {
        if (!BuildConfig.AIR_NATIVE_ISOLATION || !AirExitReport.ownPackage(host))
            return "Root native lookup disabled for this build/host.\n";
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        return NativeTraceProbe.collect(host, uid, session, guests, exits, now,
                (command, maxBytes) -> command(command, maxBytes, deadline));
    }

    /** Private execution boundary; callers cannot pass UI/Intent strings or paths. */
    private static byte[] command(String command, int maxBytes, long deadline)
            throws IOException, InterruptedException {
        java.lang.Process process = null;
        FutureTask<byte[]> reader = null;
        try {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            if (System.nanoTime() >= deadline) throw new IOException("Native lookup deadline");
            process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
            final InputStream stream = process.getInputStream();
            reader = new FutureTask<>(() -> AirExitReport.readBounded(stream, maxBytes));
            Thread thread = new Thread(reader, "air-native-record-root-reader");
            thread.setDaemon(true);
            thread.start();
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new TimeoutException();
            byte[] bytes = reader.get(remaining, TimeUnit.NANOSECONDS);
            remaining = deadline - System.nanoTime();
            if (remaining <= 0 || !process.waitFor(Math.min(remaining, TimeUnit.SECONDS.toNanos(2)), TimeUnit.NANOSECONDS)
                    || process.exitValue() != 0) throw new IOException("Native root read unavailable");
            return bytes;
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            throw new IOException("Native root read unavailable");
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
