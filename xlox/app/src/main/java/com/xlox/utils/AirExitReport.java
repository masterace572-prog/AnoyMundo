package com.xlox.utils;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.*;
import java.util.*;
import java.util.regex.*;

/** Allowlisted crash facts only. No raw log, description, memory, command line or paths. */
public final class AirExitReport {
    public static final int MAX_REPORT = 32 * 1024;
    public static final int MAX_TRACE = 8 * 1024 * 1024;
    public static final int MAX_ROOT = 128 * 1024;
    private AirExitReport() {}

    public static byte[] readBounded(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
            if (n == 0) throw new IOException("Empty read");
            if (n > max - out.size()) throw new IOException("Diagnostic input limit");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    public static String safeFact(String text) {
        if (text == null) return "unknown";
        String s = text.replaceAll("(?i)[A-Z0-9]+-[0-9]+[HD]-[A-Z0-9]+", "[redacted]")
                .replaceAll("(?i)[a-z][a-z0-9+.-]*://\\S+|\\S+@\\S+|(?:/[^\\s/]+){2,}", "[redacted]")
                .replaceAll("[^A-Za-z0-9 ._+()\\[\\]-]", "_");
        return s.substring(0, Math.min(s.length(), 80));
    }

    public static boolean ownPackage(String pkg) {
        return "com.xlox".equals(pkg) || "com.kennypvt".equals(pkg);
    }

    public static final class Exit {
        public final int pid, uid, reason, status;
        public final String process;
        public final long when;
        public Exit(int pid, int uid, String process, long when, int reason, int status) {
            this.pid = pid; this.uid = uid; this.process = process;
            this.when = when; this.reason = reason; this.status = status;
        }
    }

    public static boolean matches(Exit e, String host, int uid, AirCrashSession session,
                                  Set<Integer> guests, long now) {
        if (!ownPackage(host) || session == null || e.pid <= 0 || e.uid != uid
                || e.when < session.wall || e.when > now || e.process == null) return false;
        if (!guests.isEmpty()) return guests.contains(e.pid);
        // Without the Air callback marker this is explicitly only a candidate.
        return e.process.matches(Pattern.quote(host) + ":stub[0-9]+");
    }

    public static String reason(int reason) {
        String[] names = {"UNKNOWN", "EXIT_SELF", "SIGNALED", "LOW_MEMORY", "JAVA_CRASH",
                "NATIVE_CRASH", "ANR", "INITIALIZATION_FAILURE", "PERMISSION_CHANGE",
                "EXCESSIVE_RESOURCE_USAGE", "USER_REQUESTED", "USER_STOPPED", "DEPENDENCY_DIED",
                "OTHER", "FREEZER", "PACKAGE_STATE_CHANGE", "PACKAGE_UPDATED"};
        return reason >= 0 && reason < names.length ? names[reason] : "UNKNOWN";
    }

    public static String exitLine(Exit e, boolean confirmed) {
        return (confirmed ? "Air callback matched" : "Host-stub candidate (Air identity unconfirmed)")
                + ": pid=" + e.pid + " reason=" + reason(e.reason) + " (" + e.reason
                + ") status=" + e.status + " exitTimeMs=" + e.when + "\n";
    }

    /** Root output is never copied verbatim, even on errors or unknown OEM formats. */
    public static String rootSummary(String raw, String host, int uid, AirCrashSession session,
                                     Set<Integer> guests, long now) {
        if (raw == null || raw.length() > MAX_ROOT || !ownPackage(host) || session == null)
            return "Root exit details: unavailable or outside scope.\n";
        StringBuilder out = new StringBuilder();
        int count = 0;
        for (String block : raw.split("ApplicationExitInfo #[0-9]+:")) {
            try {
                Matcher time = Pattern.compile("\\btimestamp=([0-9-]{10} [0-9:.]{12})\\s+pid=").matcher(block);
                if (!time.find()) continue;
                SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
                format.setLenient(false);
                ParsePosition pos = new ParsePosition(0);
                Date date = format.parse(time.group(1), pos);
                if (date == null || pos.getIndex() != time.group(1).length()) continue;
                Matcher process = Pattern.compile("\\bprocess=([^\\s]+)\\s+reason=").matcher(block);
                if (!process.find()) continue;
                Exit e = new Exit(integer(block, "pid"), integer(block, "packageUid"), process.group(1),
                        date.getTime(), integer(block, "reason"), integer(block, "status"));
                if (!matches(e, host, uid, session, guests, now)) continue;
                out.append(exitLine(e, guests.contains(e.pid)));
                if (++count == 4) break;
            } catch (IllegalArgumentException ignored) {
                // Discard descriptions, state data, trace paths and unfamiliar formats.
            }
        }
        return count == 0 ? "Root exit details: no matching recent record (or unsupported format).\n"
                : "Root app-scoped exit details:\n" + out;
    }

    private static int integer(String text, String name) {
        Matcher m = Pattern.compile("\\b" + name + "=(-?[0-9]{1,10})(?=\\s|$)").matcher(text);
        if (!m.find()) throw new IllegalArgumentException();
        return Integer.parseInt(m.group(1));
    }

    /** Android 12+ ApplicationExitInfo native traces use AOSP debuggerd/proto/tombstone.proto.
     * Field IDs are documented in docs/AIR_NATIVE_ISOLATION.md. Only the faulting
     * thread's module basenames, relative PCs and hex BuildIds are exported.
     */
    public static String nativeTrace(InputStream input, int expectedPid, int expectedUid) throws IOException {
        Proto p = new Proto(readBounded(input, MAX_TRACE));
        int pid = -1, uid = -1, tid = -1;
        long signal = -1, code = 0, address = 0;
        boolean hasAddress = false;
        Map<Integer, List<String>> threads = new HashMap<>();
        int threadCount = 0;
        while (p.more()) {
            int tag = p.tag(), field = tag >>> 3;
            if (field == 5 && p.wire(tag, 0)) pid = p.id();
            else if (field == 6 && p.wire(tag, 0)) tid = p.id();
            else if (field == 7 && p.wire(tag, 0)) uid = p.id();
            else if (field == 10 && p.wire(tag, 2)) {
                Proto s = p.message();
                while (s.more()) {
                    int st = s.tag(), sf = st >>> 3;
                    if (sf == 1 && s.wire(st, 0)) signal = s.varint();
                    else if (sf == 3 && s.wire(st, 0)) code = s.varint();
                    else if (sf == 8 && s.wire(st, 0)) hasAddress = s.varint() != 0;
                    else if (sf == 9 && s.wire(st, 0)) address = s.varint();
                    else s.skip(st);
                }
            } else if (field == 16 && p.wire(tag, 2) && threadCount++ < 64) {
                Proto entry = p.message();
                int key = -1;
                List<String> frames = new ArrayList<>();
                while (entry.more()) {
                    int et = entry.tag();
                    if ((et >>> 3) == 1 && entry.wire(et, 0)) key = entry.id();
                    else if ((et >>> 3) == 2 && entry.wire(et, 2)) frames = frames(entry.message());
                    else entry.skip(et);
                }
                if (key > 0) threads.put(key, frames);
            } else p.skip(tag);
        }
        if (pid != expectedPid || uid != expectedUid || pid <= 0 || uid <= 0)
            return "Native trace rejected: PID/UID does not match this exit.\n";
        StringBuilder out = new StringBuilder("Native trace: signal=").append(signal)
                .append(" code=").append((int) code);
        if (hasAddress) out.append(" faultAddress=0x").append(Long.toHexString(address));
        out.append(" crashingTid=").append(tid).append('\n');
        List<String> frames = threads.get(tid);
        if (frames == null || frames.isEmpty()) out.append("No crashing-thread frames in retained trace.\n");
        else for (int i = 0; i < frames.size(); i++) out.append('#').append(i).append(' ').append(frames.get(i)).append('\n');
        return out.toString();
    }

    private static List<String> frames(Proto thread) throws IOException {
        List<String> frames = new ArrayList<>();
        while (thread.more()) {
            int t = thread.tag();
            if ((t >>> 3) != 4 || !thread.wire(t, 2) || frames.size() >= 32) { thread.skip(t); continue; }
            Proto frame = thread.message();
            long rel = 0;
            String module = "[module withheld]", build = "";
            while (frame.more()) {
                int ft = frame.tag(), f = ft >>> 3;
                if (f == 1 && frame.wire(ft, 0)) rel = frame.varint();
                else if (f == 6 && frame.wire(ft, 2)) module = module(frame.string());
                else if (f == 8 && frame.wire(ft, 2)) {
                    String candidate = frame.string();
                    if (candidate.matches("[a-fA-F0-9]{1,128}")) build = candidate;
                } else frame.skip(ft);
            }
            frames.add("pc 0x" + Long.toHexString(rel) + " " + module + (build.isEmpty() ? "" : " BuildId=" + build));
        }
        return frames;
    }

    private static String module(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1).replace(" (deleted)", "");
        if (name.length() > 96 || !safeFact(name).equals(name)) return "[module withheld]";
        return name.matches("lib[A-Za-z0-9_.+-]+\\.so|(?:base|split_[A-Za-z0-9_.+-]+)\\.apk|linker64?|\\[vdso\\]")
                ? name : "[module withheld]";
    }

    private static final class Proto {
        final byte[] data;
        int at;
        final int end;
        Proto(byte[] data) { this(data, 0, data.length); }
        Proto(byte[] data, int at, int end) { this.data = data; this.at = at; this.end = end; }
        boolean more() { return at < end; }
        boolean wire(int tag, int wanted) { return (tag & 7) == wanted; }
        int tag() throws IOException {
            long value = varint();
            if (value <= 0 || value > Integer.MAX_VALUE || (value >>> 3) == 0) throw new IOException("Invalid tag");
            return (int) value;
        }
        long varint() throws IOException {
            long v = 0;
            for (int i = 0; i < 10; i++) {
                if (at >= end) throw new EOFException();
                int b = data[at++] & 255;
                if (i == 9 && (b & 254) != 0) throw new IOException("Varint overflow");
                v |= (long) (b & 127) << (i * 7);
                if ((b & 128) == 0) return v;
            }
            throw new IOException("Invalid varint");
        }
        int id() throws IOException {
            long value = varint();
            if (value < 0 || value > Integer.MAX_VALUE) throw new IOException("Invalid process/thread identity");
            return (int) value;
        }
        int length() throws IOException {
            long len = varint();
            if (len < 0 || len > end - at) throw new EOFException();
            return (int) len;
        }
        Proto message() throws IOException {
            int len = length();
            Proto result = new Proto(data, at, at + len);
            at += len;
            return result;
        }
        String string() throws IOException {
            int len = length();
            if (len > 2048) { at += len; return ""; }
            String text = new String(data, at, len, StandardCharsets.UTF_8);
            at += len;
            return text;
        }
        void skip(int tag) throws IOException {
            switch (tag & 7) {
                case 0: varint(); return;
                case 1: advance(8); return;
                case 2: advance(length()); return;
                case 5: advance(4); return;
                default: throw new IOException("Unsupported wire type");
            }
        }
        void advance(int n) throws IOException {
            if (n < 0 || n > end - at) throw new EOFException();
            at += n;
        }
    }
}
