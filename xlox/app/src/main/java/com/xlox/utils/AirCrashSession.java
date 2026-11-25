package com.xlox.utils;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Tiny private diagnostic markers, never game files, preferences or save data. */
public final class AirCrashSession {
    public static final long MAX_AGE_MS = 15 * 60_000L;
    public final long wall;
    public final long elapsed;

    public AirCrashSession(long wall, long elapsed) {
        this.wall = wall;
        this.elapsed = elapsed;
    }

    public static void begin(File dir, long wall, long elapsed) throws IOException {
        write(dir, "launch.txt", "1 " + wall + " " + elapsed);
    }

    public static void guest(File dir, long wall, long elapsed, int pid, int uid) throws IOException {
        if (pid <= 0 || uid <= 0) throw new IOException("Invalid marker");
        write(dir, "guest-" + pid + ".txt", "1 " + wall + " " + elapsed + " " + pid + " " + uid);
        File[] files = dir.listFiles(f -> f.getName().matches("guest-[0-9]+\\.txt(?:\\.tmp)?"));
        if (files != null && files.length > 16) {
            Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            for (int i = 16; i < files.length; i++) files[i].delete();
        }
    }

    private static void write(File dir, String name, String value) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("No diagnostic directory");
        File temp = new File(dir, name + ".tmp");
        try (OutputStream out = new FileOutputStream(temp)) {
            out.write(value.getBytes(StandardCharsets.US_ASCII));
        }
        if (!temp.renameTo(new File(dir, name))) {
            temp.delete();
            throw new IOException("Cannot replace diagnostic marker");
        }
    }

    private static long[] numbers(File file, int count) throws IOException {
        if (!file.isFile() || file.length() > 128) throw new IOException("Invalid marker");
        byte[] bytes;
        try (InputStream in = new FileInputStream(file)) {
            bytes = AirExitReport.readBounded(in, 128);
        }
        String[] tokens = new String(bytes, StandardCharsets.US_ASCII).split(" ", -1);
        if (tokens.length != count || !tokens[0].equals("1")) throw new IOException("Invalid marker");
        long[] result = new long[count];
        try {
            for (int i = 0; i < count; i++) {
                if (!tokens[i].matches("[0-9]{1,16}")) throw new NumberFormatException();
                result[i] = Long.parseLong(tokens[i]);
            }
        } catch (NumberFormatException e) {
            throw new IOException("Invalid marker");
        }
        return result;
    }

    public static AirCrashSession current(File dir, long wallNow, long elapsedNow) {
        try {
            long[] n = numbers(new File(dir, "launch.txt"), 3);
            long age = elapsedNow - n[2];
            long wallAge = wallNow - n[1];
            if (n[1] <= 0 || n[2] < 0 || age < 0 || age > MAX_AGE_MS || wallAge < 0
                    || Math.abs(wallAge - age) > 30_000L) return null;
            return new AirCrashSession(n[1], n[2]);
        } catch (IOException | SecurityException e) {
            return null;
        }
    }

    public Set<Integer> guestPids(File dir, int uid, long wallNow, long elapsedNow) {
        Set<Integer> result = new HashSet<>();
        File[] files = dir.listFiles(f -> f.getName().matches("guest-[0-9]+\\.txt"));
        if (files == null) return result;
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (int i = 0; i < Math.min(files.length, 16); i++) {
            try {
                long[] n = numbers(files[i], 5);
                if (n[1] >= wall && n[1] <= wallNow && n[2] >= elapsed && n[2] <= elapsedNow
                        && n[3] > 0 && n[3] <= Integer.MAX_VALUE && n[4] == uid
                        && files[i].getName().equals("guest-" + n[3] + ".txt")) result.add((int) n[3]);
            } catch (IOException | SecurityException ignored) {
                // A missing/interrupted diagnostic marker cannot affect startup.
            }
        }
        return result;
    }
}
