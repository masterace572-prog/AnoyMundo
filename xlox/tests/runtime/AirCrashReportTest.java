package com.xlox.tests;

import com.xlox.utils.AirCrashSession;
import com.xlox.utils.AirExitReport;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.*;

/** JVM tests of production report scoping, marker I/O and protobuf redaction. */
public final class AirCrashReportTest {
    private static int checks;
    private static final int PID = 12345, UID = 10234, TID = 12346;
    private static final String SECRET = "ANOY-7D-PRIVATEKEY";
    private static void expect(boolean yes, String why) {
        checks++; if (!yes) throw new AssertionError(why);
    }
    private static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    private static byte[] join(byte[]... inputs) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (byte[] in : inputs) b.write(in);
        return b.toByteArray();
    }
    private static byte[] var(long n) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        do { int b = (int) n & 127; n >>>= 7; out.write(n == 0 ? b : b | 128); } while (n != 0);
        return out.toByteArray();
    }
    private static byte[] number(int field, long value) throws IOException {
        return join(var((long) field << 3), var(value));
    }
    private static byte[] message(int field, byte[] value) throws IOException {
        return join(var(((long) field << 3) | 2), var(value.length), value);
    }
    private static byte[] tombstone(int pid, int uid, String module, int frameCount) throws IOException {
        byte[] frame = join(number(1, 0xabc), message(4, bytes(SECRET)), message(6, bytes(module)), message(8, bytes("aabb01")));
        ByteArrayOutputStream frames = new ByteArrayOutputStream();
        for (int i = 0; i < frameCount; i++) frames.write(message(4, frame));
        byte[] thread = join(message(2, bytes(SECRET)), message(5, bytes(SECRET)), frames.toByteArray());
        // Deliberately put threads BEFORE tid to exercise order independence.
        return join(message(16, join(number(1, TID), message(2, thread))), number(5, pid), number(6, TID),
                number(7, uid), message(10, join(number(1, 11), number(3, 1), number(8, 1), number(9, 0x112233))),
                message(14, bytes(SECRET)), message(18, bytes(SECRET)), message(19, bytes("/private/" + SECRET)));
    }
    private static String trace(byte[] b) throws IOException {
        return AirExitReport.nativeTrace(new ByteArrayInputStream(b), PID, UID);
    }
    private static void invalid(byte[] b) throws IOException {
        boolean failed = false;
        try { trace(b); } catch (IOException e) { failed = true; }
        expect(failed, "Malformed/oversized protobuf must be rejected");
    }
    private static String rootBlock(long now, int pid, int uid, String process) {
        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date(now));
        return "ApplicationExitInfo #0:\n timestamp=" + time + " pid=" + pid + " realUid=" + uid
                + " packageUid=" + uid + " definingUid=" + uid + " user=0\n process=" + process
                + " reason=2 (SIGNALED) subreason=0 status=7\n description=" + SECRET
                + " state=18 bytes trace=/data/private/" + SECRET + "\n";
    }

    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("air-report-fixture-").toFile();
        long now = 1_800_000_000_000L, elapsed = 2_000_000L;
        AirCrashSession.begin(dir, now - 1000, elapsed - 1000);
        AirCrashSession session = AirCrashSession.current(dir, now, elapsed);
        expect(session != null, "Fresh marker readable across processes");
        expect(AirCrashSession.current(dir, now + AirCrashSession.MAX_AGE_MS + 1,
                elapsed + AirCrashSession.MAX_AGE_MS + 1) == null, "Expired session refused");
        expect(AirCrashSession.current(dir, now, 10) == null, "Reboot/backwards monotonic clock refused");
        expect(AirCrashSession.current(dir, now + 60_000, elapsed) == null, "Changed wall clock is inconclusive");
        AirCrashSession.guest(dir, now - 500, elapsed - 500, PID, UID);
        AirCrashSession.guest(dir, now - 500, elapsed - 500, PID + 1, UID + 1);
        AirCrashSession.guest(dir, now - 2000, elapsed - 2000, PID + 2, UID);
        Set<Integer> guests = session.guestPids(dir, UID, now, elapsed);
        expect(guests.equals(Collections.singleton(PID)), "Only own-UID guest inside launch window");
        AirExitReport.Exit own = new AirExitReport.Exit(PID, UID, "com.xlox:stub1", now, 2, 7);
        expect(AirExitReport.matches(own, "com.xlox", UID, session, guests, now), "Exact own guest matches");
        expect(!AirExitReport.matches(own, "com.kennypvt", UID + 1, session, guests, now), "Other brand/UID refused");
        expect(!AirExitReport.matches(own, "com.evil;id", UID, session, guests, now), "Unknown host refused");
        expect(!AirExitReport.matches(new AirExitReport.Exit(PID + 1, UID, "com.xlox:stub2", now, 2, 7),
                "com.xlox", UID, session, guests, now), "Unmatched PID refused when callback exists");
        expect(!AirExitReport.matches(new AirExitReport.Exit(PID, UID, "com.xlox:stub1", now - 2000, 2, 7),
                "com.xlox", UID, session, guests, now), "Old reused PID refused");
        expect(!AirExitReport.matches(new AirExitReport.Exit(PID, UID, "com.xlox:stub1", now + 1, 2, 7),
                "com.xlox", UID, session, guests, now), "Future exit refused");
        expect(AirExitReport.matches(own, "com.xlox", UID, session, Collections.emptySet(), now), "No-marker host stub is a candidate");
        expect(!AirExitReport.matches(new AirExitReport.Exit(PID, UID, "com.pubg.imobile", now, 2, 7),
                "com.xlox", UID, session, Collections.emptySet(), now), "No-marker BGMI cannot become Air");
        expect(AirExitReport.exitLine(own, false).contains("unconfirmed"), "Candidate identity explicitly unconfirmed");

        String root = AirExitReport.rootSummary(rootBlock(now, PID, UID, "com.xlox:stub1"), "com.xlox", UID, session, guests, now);
        expect(root.contains("status=7") && root.contains("SIGNALED"), "Root numeric signal retained");
        expect(!root.contains(SECRET) && !root.contains("/data/") && !root.contains("description="), "Root descriptions/paths/state never exported");
        expect(!AirExitReport.rootSummary(rootBlock(now, PID, UID + 1, "com.xlox:stub1"), "com.xlox", UID, session, guests, now)
                .contains("status=7"), "Other UID root data rejected");
        expect(!AirExitReport.rootSummary(rootBlock(now - 5000, PID, UID, "com.xlox:stub1"), "com.xlox", UID, session, guests, now)
                .contains("status=7"), "Old root data rejected");
        expect(!AirExitReport.rootSummary("su: permission denied " + SECRET, "com.xlox", UID, session, guests, now)
                .contains(SECRET), "Raw root failures never copied");
        expect(AirExitReport.rootSummary(new String(new char[AirExitReport.MAX_ROOT + 1]), "com.xlox", UID, session, guests, now)
                .contains("unavailable"), "Root bound enforced");
        expect(!AirExitReport.safeFact(SECRET + " https://private.test /data/private/key").contains(SECRET), "Metadata key/link/path redacted");

        String nativeReport = trace(tombstone(PID, UID, "/data/private/com.xlox/lib/libLizard.so", 2));
        expect(nativeReport.contains("signal=11") && nativeReport.contains("faultAddress=0x112233"), "Signal facts decoded");
        expect(nativeReport.contains("pc 0xabc libLizard.so BuildId=aabb01"), "Module-relative PC and BuildId decoded");
        expect(!nativeReport.contains(SECRET) && !nativeReport.contains("/data/") && !nativeReport.contains("private"),
                "No function/thread names, abort strings, logs, memory or full paths exported");
        expect(trace(tombstone(PID + 1, UID, "libUE4.so", 1)).contains("rejected"), "Wrong tombstone PID refused");
        expect(trace(tombstone(PID, UID + 1, "libUE4.so", 1)).contains("rejected"), "Wrong tombstone UID refused");
        String many = trace(tombstone(PID, UID, "libUE4.so", 60));
        expect(many.contains("#31 ") && !many.contains("#32 "), "Native frames bounded at 32");
        expect(!trace(tombstone(PID, UID, "/private/" + SECRET + ".so", 1)).contains(SECRET), "Unexpected module names withheld");
        invalid(join(number(5, (1L << 32) + PID), number(6, TID), number(7, UID)));
        invalid(new byte[]{0});
        invalid(new byte[]{(byte) 0x80});
        invalid(new byte[]{0x0a, 0x7f, 0});
        invalid(new byte[]{0x0b});
        invalid(new byte[AirExitReport.MAX_TRACE + 1]);
        byte[] overflow = new byte[11]; Arrays.fill(overflow, (byte) 0xff); invalid(overflow);
        expect(AirExitReport.readBounded(new ByteArrayInputStream(new byte[128]), 128).length == 128, "Exact stream limit accepted");
        boolean over = false;
        try { AirExitReport.readBounded(new ByteArrayInputStream(new byte[129]), 128); } catch (IOException e) { over = true; }
        expect(over, "Stream bound enforced before output");
        for (int i = 0; i < 30; i++) AirCrashSession.guest(dir, now, elapsed, 20000 + i, UID);
        File[] retained = dir.listFiles(f -> f.getName().startsWith("guest-"));
        expect(retained != null && retained.length <= 16, "Guest marker retention bounded");
        // Only disposable fixture files, never application/game directories.
        for (File f : Objects.requireNonNull(dir.listFiles())) f.delete();
        dir.delete();
        System.out.println("PASS Air crash report production parser/session/privacy: " + checks + " assertions");
    }
}
