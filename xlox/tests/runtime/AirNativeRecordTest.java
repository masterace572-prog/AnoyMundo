package com.xlox.tests;

import com.xlox.utils.AirCrashSession;
import com.xlox.utils.AirExitReport;
import com.xlox.utils.NativeTraceProbe;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Synthetic disposable crash files ONLY. No su, Android files, game or genuine tombstone. */
public final class AirNativeRecordTest {
    private static int checks;
    private static final int PID = 12345, UID = 10234;
    private static final long NOW = 1_800_000_000_000L, RECORD = NOW - 2000, EXIT = NOW - 1000;
    private static final AirCrashSession SESSION = new AirCrashSession(NOW - 60_000, 1000);
    private static final AirExitReport.Exit OWN = exit(PID, UID, EXIT, 2, 11);
    private static final Set<Integer> GUESTS = Collections.singleton(PID);
    private static final String SECRET = "ANOY-7D-PRIVATEKEY";

    private static void expect(boolean yes, String why) { checks++; if (!yes) throw new AssertionError(why); }
    private static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    private static AirExitReport.Exit exit(int pid, int uid, long when, int reason, int status) {
        return new AirExitReport.Exit(pid, uid, "com.xlox:stub1", when, reason, status);
    }
    private static byte[] join(byte[]... inputs) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (byte[] input : inputs) b.write(input);
        return b.toByteArray();
    }
    private static byte[] var(long n) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        do { int v = (int) n & 127; n >>>= 7; b.write(n == 0 ? v : v | 128); } while (n != 0);
        return b.toByteArray();
    }
    private static byte[] number(int field, long value) throws IOException { return join(var((long) field << 3), var(value)); }
    private static byte[] message(int field, byte[] value) throws IOException {
        return join(var(((long) field << 3) | 2), var(value.length), value);
    }
    private static String stamp(long when) { return stamp(when, TimeZone.getTimeZone("UTC")); }
    private static String stamp(long when, TimeZone zone) {
        SimpleDateFormat time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
        SimpleDateFormat offset = new SimpleDateFormat("Z", Locale.US);
        time.setTimeZone(zone); offset.setTimeZone(zone);
        return time.format(new Date(when)) + "123456" + offset.format(new Date(when));
    }
    private static String row(String index, int pid, int uid, String time) {
        return "H|" + index + "|" + pid + "|" + uid + "|" + time + "\n";
    }
    private static String lookup(String... rows) { return "NATIVE_LOOKUP_V2\n" + String.join("", rows) + "DONE|" + rows.length + "\n"; }
    private static NativeTraceProbe.Lookup parse(String raw) throws IOException {
        return NativeTraceProbe.parseLookup(raw, UID, OWN, SESSION, NOW);
    }
    private static NativeTraceProbe.Header header() throws IOException { return parse(lookup(row("00", PID, UID, stamp(RECORD)))).match; }
    private static byte[] identity(int pid, int uid, String time) throws IOException {
        return join(number(1, 1), message(2, bytes(SECRET)), message(3, bytes(SECRET)), message(4, bytes(time)),
                number(5, pid), number(6, pid + 10), number(7, uid));
    }
    private static String capture(NativeTraceProbe.Header h, String facts) {
        return "NATIVE_CAPTURE_V2\n" + row(h.index, h.pid, h.uid, h.stamp) + facts + "TRACE_END\n";
    }
    private static String text(int pid, int uid, String time, int frames) {
        StringBuilder b = new StringBuilder("*** *** *** *** *** *** *** *** *** *** *** *** *** *** *** ***\n")
                .append("Build fingerprint: '").append(SECRET).append("'\nRevision: '").append(SECRET)
                .append("'\nABI: 'arm64'\nTimestamp: ").append(time).append("\nProcess uptime: 5s\nCmdline: ").append(SECRET)
                .append("\npid: ").append(pid).append(", tid: ").append(pid + 10).append(", name: ").append(SECRET)
                .append(" >>> ").append(SECRET).append(" <<<\nuid: ").append(uid)
                .append("\nsignal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0000000000000010\n")
                .append("Abort message: '").append(SECRET).append("'\n    x0 000000000000aabb x1 000000000000ccdd\n")
                .append("\nbacktrace:\n");
        for (int i = 0; i < frames; i++) b.append(String.format(Locale.US,
                "    #%02d pc 0000000000000abc  /data/private/%s/lib/libLizard.so (%s+64) (BuildId: aabb01)\n", i, SECRET, SECRET));
        return b.append("\nlogcat:\n").append(SECRET).append("\nmemory:\n").append(SECRET)
                .append("\nbacktrace:\n    #00 pc 000000000000dead /private/libOtherThread.so\n").toString();
    }
    private static void invalidLookup(String raw) {
        boolean rejected = false;
        try { parse(raw); } catch (IOException | RuntimeException e) { rejected = true; }
        expect(rejected, "Malformed lookup rejected");
    }
    private static void invalidCapture(String raw, NativeTraceProbe.Header h) {
        boolean rejected = false;
        try { NativeTraceProbe.renderCapture(raw, h); } catch (IOException | RuntimeException e) { rejected = true; }
        expect(rejected, "Malformed/mismatched capture rejected before facts returned");
    }
    private static String collect(NativeTraceProbe.Runner runner) {
        return NativeTraceProbe.collect("com.xlox", UID, SESSION, GUESTS, Collections.singletonList(OWN), NOW, runner);
    }
    private static void noRoot(String host, int uid, AirCrashSession session, Set<Integer> guests,
                               List<AirExitReport.Exit> exits, long now) {
        AtomicInteger calls = new AtomicInteger();
        String result = NativeTraceProbe.collect(host, uid, session, guests, exits, now, (c, max) -> {
            calls.incrementAndGet(); throw new IOException(SECRET);
        });
        expect(calls.get() == 0 && !result.contains(SECRET), "Insufficient/old/unrelated scope never requests root");
    }

    private static final class Fixture implements NativeTraceProbe.Runner, AutoCloseable {
        final Path dir = Files.createTempDirectory("air-native-record-fixture-");
        final String shell;
        int calls;
        Fixture(String shell) throws IOException { this.shell = shell; }
        void write(String name, String content) throws IOException { Files.write(dir.resolve(name), bytes(content)); }
        void pb(String name, int pid, int uid, String time) throws IOException { Files.write(dir.resolve(name), identity(pid, uid, time)); }
        @Override public byte[] run(String command, int maxBytes) throws IOException, InterruptedException {
            calls++;
            expect(command.contains("/data/tombstones") && command.contains("/system/bin/head"), "Production paths remain fixed");
            expect(!command.contains(dir.toString()), "Production API exposes no fixture/path override");
            // Adapt the GENERATED shell in this JVM test only, never in Android source.
            String fixture = command.replace("/data/tombstones", dir.toString()).replace("/system/bin/head", "/usr/bin/head");
            java.lang.Process process = new ProcessBuilder(shell, "-c", fixture).redirectErrorStream(true).start();
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0) throw new IOException(SECRET);
                return AirExitReport.readBounded(process.getInputStream(), maxBytes);
            } finally {
                process.destroyForcibly();
                process.getInputStream().close(); process.getOutputStream().close(); process.getErrorStream().close();
            }
        }
        @Override public void close() throws IOException {
            try (java.util.stream.Stream<Path> files = Files.walk(dir)) {
                for (Path p : (Iterable<Path>) files.sorted(Comparator.reverseOrder())::iterator) Files.delete(p);
            }
        }
    }

    private static void shellFixtures(String shell) throws Exception {
        try (Fixture f = new Fixture(shell)) {
            String time = stamp(RECORD);
            f.write("tombstone_00", text(PID, UID, time, 40));
            f.pb("tombstone_00.pb", PID, UID, time);
            f.write("tombstone_01", text(PID, UID + 1, time, 1));
            f.write("tombstone_02", text(PID + 1, UID, time, 1));
            f.write("tombstone_03", text(PID, UID, stamp(SESSION.wall - 1000), 1));
            Files.createDirectory(f.dir.resolve("tombstone_04"));
            Files.createSymbolicLink(f.dir.resolve("tombstone_05"), f.dir.resolve("tombstone_00"));
            java.lang.Process fifo = new ProcessBuilder("mkfifo", f.dir.resolve("tombstone_06").toString()).start();
            expect(fifo.waitFor(3, TimeUnit.SECONDS) && fifo.exitValue() == 0, "Disposable FIFO fixture created");
            f.write("tombstone_100", text(PID, UID, time, 1));
            f.write("tombstone_00.pb.extra", text(PID, UID, time, 1));
            String raw = new String(f.run(NativeTraceProbe.lookupCommand(UID, GUESTS), NativeTraceProbe.MAX_LOOKUP_BYTES), StandardCharsets.UTF_8);
            expect(!raw.contains(SECRET) && !raw.contains("/data/") && !raw.contains("uid:"), "Lookup shell emits only identity protocol");
            NativeTraceProbe.Lookup found = parse(raw);
            expect(found.checked == 4 && found.match != null && found.match.index.equals("00"), "Fixed path/type/PID/UID/time lookup");
            f.calls = 0;
            String report = collect(f);
            expect(f.calls == 4 && report.contains("verified before and after"), "Paired protobuf identity verified around one text read");
            expect(report.contains("signal=11 code=1 faultAddress=0x0000000000000010"), "Signal facts survive shell and Java filtering");
            expect(report.contains("#31 pc 0x0000000000000abc libLizard.so BuildId=aabb01") && !report.contains("#32 "), "First backtrace capped at 32 frames");
            expect(!report.contains(SECRET) && !report.contains("/data/") && !report.contains("x0 ") && !report.contains("libOtherThread"),
                    "No paths, names, functions, abort text, registers, later thread, logs or memory exported");
            expect(report.length() <= NativeTraceProbe.MAX_NATIVE_REPORT, "Native report bound");

            f.pb("tombstone_00.pb", PID + 1, UID, time); f.calls = 0;
            report = collect(f);
            expect(f.calls == 2 && !report.contains("pc 0x"), "Wrong structured PID stops before text read");
            f.pb("tombstone_00.pb", PID, UID + 1, time);
            expect(!collect(f).contains("pc 0x"), "Wrong structured UID stops text read");
            f.pb("tombstone_00.pb", PID, UID, stamp(RECORD - 1));
            expect(!collect(f).contains("pc 0x"), "Exact structured/text timestamp equality required");
            Files.delete(f.dir.resolve("tombstone_00.pb"));
            expect(!collect(f).contains("pc 0x"), "Missing structured companion fails closed, not text-only fallback");
            f.pb("tombstone_00.pb", PID, UID, time);
            Files.move(f.dir.resolve("tombstone_00.pb"), f.dir.resolve("private.pb"));
            Files.createSymbolicLink(f.dir.resolve("tombstone_00.pb"), f.dir.resolve("private.pb"));
            expect(!collect(f).contains("pc 0x"), "Structured companion symlink refused");
            Files.delete(f.dir.resolve("tombstone_00.pb")); f.pb("tombstone_00.pb", PID, UID, time);

            AtomicInteger step = new AtomicInteger();
            report = collect((command, max) -> {
                if (step.incrementAndGet() == 4) f.pb("tombstone_00.pb", PID + 1, UID, time);
                return f.run(command, max);
            });
            expect(report.contains("changed/rotated") && !report.contains("pc 0x"), "Rotation after capture suppresses all frames");
            f.pb("tombstone_00.pb", PID, UID, time); step.set(0);
            report = collect((command, max) -> {
                if (step.incrementAndGet() == 3) f.write("tombstone_00", text(PID + 1, UID, time, 1));
                return f.run(command, max);
            });
            expect(!report.contains("pc 0x"), "Text header rechecked at capture, not trusted from lookup");

            f.write("tombstone_00", text(PID, UID, time, 1).replace("backtrace:\n", "padding\n" + String.join("", Collections.nCopies(513, "padding\n")) + "backtrace:\n"));
            expect(!collect(f).contains("pc 0x"), "Matched text stops at 512 lines");
            f.write("tombstone_00", text(PID, UID, time, 1).replace("Abort message:", new String(new char[NativeTraceProbe.TEXT_BYTES]).replace('\0', 'X') + "Abort message:"));
            expect(!collect(f).contains("pc 0x"), "Matched text stops at 64 KiB");
            f.write("tombstone_00", "ignored\n" + String.join("", Collections.nCopies(32, "ignored\n")) + text(PID, UID, time, 1));
            expect(parse(new String(f.run(NativeTraceProbe.lookupCommand(UID, GUESTS), NativeTraceProbe.MAX_LOOKUP_BYTES), StandardCharsets.UTF_8)).match == null,
                    "Unmatched header stops at 32 lines");
            f.write("tombstone_00", new String(new char[4097]).replace('\0', 'X') + "\n" + text(PID, UID, time, 1));
            expect(parse(new String(f.run(NativeTraceProbe.lookupCommand(UID, GUESTS), NativeTraceProbe.MAX_LOOKUP_BYTES), StandardCharsets.UTF_8)).match == null,
                    "Unmatched header stops at 4 KiB");
            f.write("tombstone_00", "uid: " + UID + "\n" + text(PID, UID, time, 1));
            expect(!collect(f).contains("pc 0x"), "Duplicate identity header refused");
            f.write("tombstone_00", text(PID, UID, time, 1).replace(time, time + "'; echo " + SECRET + "; '"));
            expect(!collect(f).contains(SECRET), "Malformed/shell-like timestamp cannot become a capture command");
            f.write("tombstone_00", text(PID, UID, time, 1));
            f.write("tombstone_08", text(PID + 2, UID, stamp(RECORD + 500), 1)); f.pb("tombstone_08.pb", PID + 2, UID, stamp(RECORD + 500));
            report = NativeTraceProbe.collect("com.xlox", UID, SESSION, new HashSet<>(Arrays.asList(PID, PID + 2)),
                    Arrays.asList(OWN, exit(PID + 2, UID, EXIT + 500, 2, 11)), NOW, f);
            expect(report.contains("pid=" + (PID + 2)) && !report.contains("pid=" + PID + " "), "Only latest confirmed Air exit is selected");
        }
        try (Fixture f = new Fixture(shell)) {
            for (int i = 0; i < 100; i++) f.write(String.format(Locale.US, "tombstone_%02d", i), text(PID, UID, stamp(RECORD - i), 1));
            NativeTraceProbe.Lookup many = parse(new String(f.run(NativeTraceProbe.lookupCommand(UID, GUESTS), NativeTraceProbe.MAX_LOOKUP_BYTES), StandardCharsets.UTF_8));
            expect(many.checked == 100 && many.match.index.equals("00"), "100 bounded headers yield only the newest matching record");
        }
    }

    public static void main(String[] args) throws Exception {
        TimeZone old = TimeZone.getDefault(); TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        try {
            NativeTraceProbe.Header h = header();
            expect(h != null && h.pid == PID && h.when == RECORD && h.exitWhen == EXIT, "Scoped timestamp parsed to milliseconds");
            expect(parse(lookup(row("00", PID, UID, stamp(RECORD, TimeZone.getTimeZone("Asia/Kolkata"))))).match.when == RECORD, "Explicit timezone honored");
            expect(parse(lookup(row("00", PID, UID, stamp(RECORD).replace("+0000", "Z")))).match.when == RECORD, "UTC Z timezone supported");
            expect(parse(lookup(row("00", PID, UID, stamp(RECORD).replace("+0000", "+00:00")))).match.when == RECORD, "Colon timezone supported");
            expect(parse(lookup(row("00", PID, UID, stamp(RECORD).replace("+0000", "")))).match.when == RECORD, "No-zone OEM timestamp uses device timezone");
            expect(parse(lookup(row("00", PID, UID, stamp(EXIT - NativeTraceProbe.MAX_EXIT_LAG_MS)))).match != null, "Exact 30-second pre-exit bound accepted");
            expect(parse(lookup(row("00", PID, UID, stamp(EXIT - NativeTraceProbe.MAX_EXIT_LAG_MS - 1)))).match == null, "Earlier same-launch record refused");
            expect(parse(lookup(row("00", PID, UID, stamp(SESSION.wall - 1)))).match == null, "Record before launch refused");
            expect(parse(lookup(row("00", PID, UID, stamp(NOW + 1)))).match == null, "Future record refused");
            expect(parse(lookup(row("00", PID + 1, UID, stamp(RECORD)))).match == null, "Other PID refused");
            expect(parse(lookup(row("00", PID, UID + 1, stamp(RECORD)))).match == null, "Other UID refused");
            expect(parse(lookup(row("00", PID, UID, "2027-99-01 00:00:00+0000"))).match == null, "Invalid calendar timestamp refused");
            expect(parse("NATIVE_LOOKUP_V2\nUNAVAILABLE\nDONE|0\n").readable == false, "Restricted directory outcome");
            expect(parse("NATIVE_LOOKUP_V2\nDONE|0\n").match == null, "Empty bounded lookup is not proof of absence");
            invalidLookup(lookup(row("../secret", PID, UID, stamp(RECORD))));
            invalidLookup(lookup(row("00", 0, UID, stamp(RECORD))));
            invalidLookup(lookup(row("00", PID, UID, stamp(RECORD))).replace("DONE|1", "DONE|0"));
            invalidLookup(lookup(row("00", PID, UID, stamp(RECORD)), row("00", PID, UID, stamp(RECORD))));
            invalidLookup("NATIVE_LOOKUP_V2\nUNAVAILABLE\nDONE|1\n");
            invalidLookup("NATIVE_LOOKUP_V2\nDONE|101\n");
            invalidLookup("NATIVE_LOOKUP_V2\n" + SECRET + "\nDONE|0\n");
            invalidLookup("NATIVE_LOOKUP_V2\n"); invalidLookup(new String(new char[NativeTraceProbe.MAX_LOOKUP_BYTES + 1]));

            String time = h.stamp;
            byte[] pb = identity(PID, UID, time);
            expect(AirExitReport.nativeIdentityMatches(pb, PID, UID, time), "Bounded protobuf identity matches");
            expect(AirExitReport.nativeIdentityMatches(join(pb, new byte[]{0x42, (byte) 0xff}), PID, UID, time), "Stop before non-header payload, even if not complete in prefix");
            expect(!AirExitReport.nativeIdentityMatches(identity(PID + 1, UID, time), PID, UID, time), "Structured PID mismatch");
            expect(!AirExitReport.nativeIdentityMatches(identity(PID, UID + 1, time), PID, UID, time), "Structured UID mismatch");
            expect(!AirExitReport.nativeIdentityMatches(pb, PID, UID, stamp(RECORD - 1)), "Structured timestamp mismatch");
            expect(!AirExitReport.nativeIdentityMatches(join(pb, number(5, PID)), PID, UID, time), "Duplicate structured PID rejected");
            expect(!AirExitReport.nativeIdentityMatches(join(pb, number(7, UID)), PID, UID, time), "Duplicate structured UID rejected");
            expect(!AirExitReport.nativeIdentityMatches(join(pb, message(4, bytes(time))), PID, UID, time), "Duplicate structured timestamp rejected");
            expect(!AirExitReport.nativeIdentityMatches(join(message(4, bytes(time)), number(5, (1L << 32) + PID), number(7, UID)), PID, UID, time), "Overflowed PID rejected");
            expect(!AirExitReport.nativeIdentityMatches(join(message(8, bytes(SECRET)), pb), PID, UID, time), "Unsupported structured header order rejected");
            expect(!AirExitReport.nativeIdentityMatches(Arrays.copyOf(pb, pb.length - 1), PID, UID, time), "Truncated identity rejected");
            expect(!AirExitReport.nativeIdentityMatches(new byte[4097], PID, UID, time), "Structured prefix capped at 4 KiB");
            expect(!AirExitReport.nativeIdentityMatches(new byte[]{0}, PID, UID, time), "Malformed structured header rejected");
            expect(!AirExitReport.nativeIdentityMatches(join(message(4, bytes(time)), message(5, bytes("12345")), number(7, UID)), PID, UID, time), "Wrong wire type rejected");

            String good = capture(h, "S|11|1|0x10\nF|00|abc|libLizard.so|aabb01\n");
            String report = NativeTraceProbe.renderCapture(good, h);
            expect(report.contains("signal=11 code=1") && report.contains("pc 0xabc libLizard.so BuildId=aabb01"), "Filtered native facts render");
            expect(!report.contains("verified"), "Renderer alone cannot claim protobuf verification");
            report = NativeTraceProbe.renderCapture(capture(h, "S|11|" + SECRET + "|" + SECRET + "\nF|00|abc|" + SECRET + ".so|" + SECRET + "\n"), h);
            expect(!report.contains(SECRET) && report.contains("[module withheld]"), "Untrusted facts withheld, never escaped into report");
            invalidCapture(good.replace("S|11", "S|7"), h);
            invalidCapture(good.replace("H|00", "H|01"), h);
            invalidCapture(good.replace("F|00", "F|01"), h);
            invalidCapture(good.replace("TRACE_END\n", SECRET + "\nTRACE_END\n"), h);
            invalidCapture(capture(h, "F|00|abc|libUE4.so|aabb\n"), h);
            invalidCapture(capture(h, "S|11|1|0x10\nS|11|1|0x10\n"), h);
            invalidCapture(good.replace("TRACE_END\n", ""), h);
            invalidCapture(new String(new char[NativeTraceProbe.MAX_CAPTURE_BYTES + 1]), h);
            AtomicInteger calls = new AtomicInteger();
            report = collect((command, max) -> { calls.incrementAndGet(); throw new IOException(SECRET); });
            expect(calls.get() == 1 && !report.contains(SECRET) && report.contains("unidentified"), "Denied/timeout/oversized errors never copied raw");
            report = collect((command, max) -> { throw new InterruptedException(SECRET); });
            expect(report.contains("cancelled") && Thread.currentThread().isInterrupted() && !report.contains(SECRET), "Cancellation propagates without raw errors");
            Thread.interrupted();
            Thread.currentThread().interrupt();
            noRoot("com.xlox", UID, SESSION, GUESTS, Collections.singletonList(OWN), NOW); Thread.interrupted();

            noRoot("com.evil; id", UID, SESSION, GUESTS, Collections.singletonList(OWN), NOW);
            noRoot("com.xlox", UID + 1, SESSION, GUESTS, Collections.singletonList(OWN), NOW);
            noRoot("com.xlox", UID, null, GUESTS, Collections.singletonList(OWN), NOW);
            noRoot("com.xlox", UID, SESSION, Collections.emptySet(), Collections.singletonList(OWN), NOW);
            noRoot("com.xlox", UID, SESSION, Collections.singleton(PID + 1), Collections.singletonList(OWN), NOW);
            noRoot("com.xlox", UID, SESSION, GUESTS, Collections.singletonList(exit(PID, UID, SESSION.wall - 1, 2, 11)), NOW);
            noRoot("com.xlox", UID, SESSION, GUESTS, Collections.singletonList(exit(PID, UID, NOW + 1, 2, 11)), NOW);
            noRoot("com.xlox", UID, SESSION, GUESTS, Collections.singletonList(OWN), SESSION.wall + AirCrashSession.MAX_AGE_MS + 30_001);
            noRoot("com.xlox", UID, SESSION, GUESTS, Arrays.asList(OWN, exit(PID, UID, EXIT + 1, 2, 15)), NOW);
            List<AirExitReport.Exit> crowded = new ArrayList<>(Collections.nCopies(32, exit(PID + 1, UID, EXIT, 2, 11)));
            crowded.add(OWN); noRoot("com.xlox", UID, SESSION, GUESTS, crowded, NOW);
            boolean badIdentity = false;
            try { NativeTraceProbe.lookupCommand(0, GUESTS); } catch (IllegalArgumentException e) { badIdentity = true; }
            expect(badIdentity, "Invalid shell UID refused");
            badIdentity = false;
            try { NativeTraceProbe.lookupCommand(UID, Collections.singleton(null)); } catch (IllegalArgumentException e) { badIdentity = true; }
            expect(badIdentity, "Null shell PID refused");
            shellFixtures("/bin/sh"); shellFixtures("/bin/bash");
            System.out.println("PASS Air native record production protocol/scoping/privacy: " + checks + " assertions (synthetic POSIX sh + bash fixtures)");
        } finally { TimeZone.setDefault(old); }
    }
}
