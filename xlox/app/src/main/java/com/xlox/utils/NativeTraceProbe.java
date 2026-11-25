package com.xlox.utils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.*;
import java.util.*;
import java.util.regex.*;

/** Fixed read-only shell protocol. No command/path comes from UI, Intents or raw errors. */
public final class NativeTraceProbe {
    public static final int HEADER_BYTES = 4096;
    public static final int TEXT_BYTES = 65536;
    public static final int MAX_LOOKUP_BYTES = 16 * 1024;
    public static final int MAX_CAPTURE_BYTES = 16 * 1024;
    public static final int MAX_NATIVE_REPORT = 16 * 1024;
    public static final long MAX_EXIT_LAG_MS = 30_000L;
    public static final long MAX_TIMESTAMP_AHEAD_MS = 2000L;
    private NativeTraceProbe() {}

    public interface Runner {
        byte[] run(String command, int maxBytes) throws IOException, InterruptedException;
    }

    /** Only the newest confirmed Air callback exit in the fresh launch may reach root. */
    public static String collect(String host, int uid, AirCrashSession session, Set<Integer> guests,
                                 List<AirExitReport.Exit> exits, long now, Runner runner) {
        if (Thread.currentThread().isInterrupted()) return "Native record request cancelled.\n";
        if (!AirExitReport.ownPackage(host) || uid <= 0 || session == null || session.wall <= 0
                || guests == null || guests.isEmpty() || guests.size() > 16 || exits == null || runner == null
                || now < session.wall || now - session.wall > AirCrashSession.MAX_AGE_MS + 30_000L)
            return "Root native lookup not performed: no fresh, confirmed Air callback/exit scope.\n";
        AirExitReport.Exit latest = null;
        for (int i = 0; i < Math.min(32, exits.size()); i++) {
            AirExitReport.Exit e = exits.get(i);
            if (e != null && guests.contains(e.pid) && AirExitReport.matches(e, host, uid, session, guests, now)
                    && (latest == null || e.when > latest.when)) latest = e;
        }
        if (latest == null || !nativeLike(latest))
            return "Root native lookup not performed: latest confirmed Air exit is absent or not a native/signal exit.\n";
        StringBuilder out = new StringBuilder("Root native-record lookup v2 (read-only, separately requested)\n")
                .append(AirExitReport.exitLine(latest, true));
        try {
            Lookup lookup = parseLookup(new String(runner.run(lookupCommand(uid, Collections.singleton(latest.pid)),
                    MAX_LOOKUP_BYTES), StandardCharsets.UTF_8), uid, latest, session, now);
            if (!lookup.readable)
                return out + "Native crash-record headers unavailable/restricted. No native facts exported.\n";
            out.append("Bounded text headers inspected: ").append(lookup.checked).append('\n');
            if (lookup.match == null)
                return out + "No matching readable native record in this bounded lookup. A record may not have been written, retained or accessible.\n";
            Header h = lookup.match;
            byte[] before = runner.run(identityCommand(h), HEADER_BYTES);
            if (!AirExitReport.nativeIdentityMatches(before, h.pid, h.uid, h.stamp))
                return out + "Native candidate lacks a matching structured identity header; no frames exported. A verified readable match may be unavailable.\n";
            String raw = new String(runner.run(captureCommand(h), MAX_CAPTURE_BYTES), StandardCharsets.UTF_8);
            byte[] after = runner.run(identityCommand(h), HEADER_BYTES);
            if (!AirExitReport.nativeIdentityMatches(after, h.pid, h.uid, h.stamp) || !Arrays.equals(before, after))
                return out + "Native record changed/rotated during the read; no frames exported.\n";
            // Render only AFTER both structured identity checks. Never append a raw field/error.
            String facts = renderCapture(raw, h);
            out.append("Structured UID/PID/time header verified before and after text read.\n").append(facts);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); out.append("Native record request cancelled; no pending raw output copied.\n");
        } catch (IOException | RuntimeException e) {
            out.append("Native lookup denied, unsupported, changed, timed out, malformed or beyond limits; no raw output copied.\n")
                    .append("No verified native facts available; faulting module still unidentified.\n");
        }
        return out.substring(0, Math.min(out.length(), MAX_NATIVE_REPORT));
    }

    private static boolean nativeLike(AirExitReport.Exit e) {
        return e.reason == 5 || (e.reason == 2 && (e.status == 4 || e.status == 5 || e.status == 6
                || e.status == 7 || e.status == 8 || e.status == 11 || e.status == 31));
    }

    public static final class Header {
        public final String index, stamp;
        public final int pid, uid, expectedSignal;
        public final long when, exitWhen;
        private Header(String index, String stamp, AirExitReport.Exit exit, long when) {
            this.index = index; this.stamp = stamp; this.pid = exit.pid; this.uid = exit.uid;
            this.expectedSignal = exit.reason == 2 ? exit.status : 0;
            this.when = when; this.exitWhen = exit.when;
        }
    }

    public static final class Lookup {
        public final boolean readable;
        public final int checked;
        public final Header match;
        private Lookup(boolean readable, int checked, Header match) {
            this.readable = readable; this.checked = checked; this.match = match;
        }
    }

    private static String lines(String... lines) {
        StringBuilder out = new StringBuilder();
        for (String line : lines) out.append(line).append('\n');
        return out.toString();
    }

    private static void identity(int uid, int pid) {
        if (uid <= 0 || pid <= 0) throw new IllegalArgumentException("Invalid diagnostic identity");
    }

    // Up to 100 fixed regular, non-symlink text headers, 4 KiB/32 lines each.
    // Unrelated command lines/names/abort messages/register lines never leave the shell.
    public static String lookupCommand(int uid, Set<Integer> pids) {
        if (pids == null || pids.isEmpty() || pids.size() > 16) throw new IllegalArgumentException("No scoped PIDs");
        List<Integer> sorted = new ArrayList<>(pids);
        if (sorted.contains(null)) throw new IllegalArgumentException("No scoped PID");
        Collections.sort(sorted);
        StringBuilder choices = new StringBuilder();
        for (Integer pid : sorted) {
            identity(uid, pid);
            if (choices.length() > 0) choices.append('|');
            choices.append(pid);
        }
        return lines(
            "LC_ALL=C; export LC_ALL",
            "printf 'NATIVE_LOOKUP_V2\\n'",
            "if [ -L /data/tombstones ] || [ ! -d /data/tombstones ] || [ ! -r /data/tombstones ] || [ ! -x /data/tombstones ] || [ ! -x /system/bin/head ]; then",
            "  printf 'UNAVAILABLE\\nDONE|0\\n'; exit 0",
            "fi",
            "checked=0",
            "for file in /data/tombstones/tombstone_[0-9][0-9]; do",
            "  [ ! -L \"$file\" ] && [ -f \"$file\" ] && [ -r \"$file\" ] || continue",
            "  checked=$((checked+1)); [ \"$checked\" -le 100 ] || break",
            "  name=${file##*/}; index=${name#tombstone_}",
            "  /system/bin/head -c 4096 \"$file\" 2>/dev/null | (",
            "    pid=; uid=; stamp=; n=0; np=0; nu=0; nt=0",
            "    while IFS= read -r line || [ -n \"$line\" ]; do",
            "      n=$((n+1)); [ \"$n\" -le 32 ] || break",
            "      case \"$line\" in",
            "        'Timestamp: '*) stamp=${line#Timestamp: }; nt=$((nt+1));;",
            "        'pid: '*) value=${line#pid: }; pid=${value%%,*}; np=$((np+1));;",
            "        'uid: '*) uid=${line#uid: }; nu=$((nu+1));;",
            "      esac",
            "    done",
            "    [ \"$np\" = 1 ] && [ \"$nu\" = 1 ] && [ \"$nt\" = 1 ] || exit 0",
            "    [ \"$uid\" = " + uid + " ] && [ -n \"$stamp\" ] && [ \"${#stamp}\" -le 80 ] || exit 0",
            "    case \"$pid\" in " + choices + ") printf 'H|%s|%s|%s|%s\\n' \"$index\" \"$pid\" \"$uid\" \"$stamp\";; esac",
            "  )",
            "done",
            "printf 'DONE|%s\\n' \"$checked\""
        );
    }

    /** Only a bounded structured identity prefix; never persisted or exported. */
    public static String identityCommand(Header h) {
        validate(h);
        return lines("file=/data/tombstones/tombstone_" + h.index + ".pb",
                "[ ! -L /data/tombstones ] && [ ! -L \"$file\" ] && [ -f \"$file\" ] && [ -r \"$file\" ] || exit 1",
                "/system/bin/head -c 4096 \"$file\"");
    }

    public static String captureCommand(Header h) {
        validate(h);
        return lines(
            "LC_ALL=C; export LC_ALL; set -f",
            "file=/data/tombstones/tombstone_" + h.index,
            "[ ! -L /data/tombstones ] && [ ! -L \"$file\" ] && [ -f \"$file\" ] && [ -r \"$file\" ] || exit 1",
            "/system/bin/head -c 65536 \"$file\" 2>/dev/null | (",
            "  set -f",
            "  pid=; uid=; stamp=; n=0; np=0; nu=0; nt=0; ok=0; bt=0; count=0",
            "  while IFS= read -r line || [ -n \"$line\" ]; do",
            "    n=$((n+1)); [ \"$n\" -le 512 ] || break",
            "    if [ \"$ok\" = 0 ]; then",
            "      [ \"$n\" -le 32 ] || break",
            "      case \"$line\" in",
            "        'Timestamp: '*) stamp=${line#Timestamp: }; nt=$((nt+1));;",
            "        'pid: '*) value=${line#pid: }; pid=${value%%,*}; np=$((np+1));;",
            "        'uid: '*) uid=${line#uid: }; nu=$((nu+1));;",
            "      esac",
            "      case \"$line\" in 'signal '*)",
            "        [ \"$pid\" = " + h.pid + " ] && [ \"$uid\" = " + h.uid + " ] && [ \"$stamp\" = '" + h.stamp + "' ] || break",
            "        [ \"$np\" = 1 ] && [ \"$nu\" = 1 ] && [ \"$nt\" = 1 ] || break",
            "        printf 'NATIVE_CAPTURE_V2\\nH|" + h.index + "|%s|%s|%s\\n' \"$pid\" \"$uid\" \"$stamp\"",
            "        ok=1;;",
            "      esac",
            "      [ \"$ok\" = 1 ] || continue",
            "    fi",
            "    trim=${line#\"${line%%[![:space:]]*}\"}",
            "    set -- $trim",
            "    if [ \"${1-}\" = signal ]; then",
            "      sig=; code=?; fault=?; prev=",
            "      for word in \"$@\"; do",
            "        case \"$prev\" in signal) sig=${word%,};; code) code=${word%,};; addr) fault=${word%,};; esac",
            "        case \"$word\" in signal|code|addr) prev=$word;; *) prev=;; esac",
            "      done",
            "      case \"$sig\" in ''|*[!0-9]*) break;; esac",
            "      [ \"${#sig}\" -le 2 ] && [ \"${#code}\" -le 11 ] && [ \"${#fault}\" -le 18 ] || break",
            "      printf 'S|%s|%s|%s\\n' \"$sig\" \"$code\" \"$fault\"",
            "    fi",
            "    if [ \"$bt\" = 0 ] && [ \"$trim\" = backtrace: ]; then bt=1; continue; fi",
            "    [ \"$bt\" = 1 ] || continue",
            "    case \"${1-}\" in '#'[0-9][0-9]) ;; *) [ -z \"$trim\" ] && [ \"$count\" = 0 ] && continue; break;; esac",
            "    [ \"$#\" -ge 4 ] && [ \"${2-}\" = pc ] || break",
            "    rank=${1#\\#}; pc=$3; module=${4##*/}; build=",
            "    [ \"$rank\" = \"$(printf '%02d' \"$count\")\" ] && [ \"${#pc}\" -le 16 ] && [ \"${#module}\" -le 96 ] || break",
            "    case \"$pc\" in ''|*[!a-fA-F0-9]*) break;; esac",
            "    shift 4",
            "    while [ \"$#\" -gt 0 ]; do",
            "      if [ \"$1\" = '(BuildId:' ]; then shift; build=${1-}; build=${build%%)*}; break; fi",
            "      shift",
            "    done",
            "    [ \"${#build}\" -le 128 ] || build=",
            "    case \"$build\" in *[!a-fA-F0-9]*) build=;; esac",
            "    printf 'F|%s|%s|%s|%s\\n' \"$rank\" \"$pc\" \"$module\" \"$build\"",
            "    count=$((count+1)); [ \"$count\" -lt 32 ] || break",
            "  done",
            "  [ \"$ok\" = 1 ] && printf 'TRACE_END\\n'",
            "  exit 0",
            ")"
        );
    }

    private static void validate(Header h) {
        if (h == null || !h.index.matches("[0-9]{2}") || timestamp(h.stamp) != h.when)
            throw new IllegalArgumentException("Invalid native record identity");
        identity(h.uid, h.pid);
    }

    public static Lookup parseLookup(String raw, int uid, AirExitReport.Exit exit,
                                     AirCrashSession session, long now) throws IOException {
        if (raw == null || raw.length() > MAX_LOOKUP_BYTES || !raw.startsWith("NATIVE_LOOKUP_V2\n")
                || !raw.endsWith("\n") || session == null || uid <= 0 || exit == null || exit.uid != uid
                || exit.when < session.wall || exit.when > now || !nativeLike(exit))
            throw new IOException("Invalid lookup scope/envelope");
        String[] rows = raw.split("\n", -1);
        if (rows.length < 3 || rows.length > 103) throw new IOException("Lookup bounds");
        Matcher done = Pattern.compile("DONE\\|([0-9]{1,3})").matcher(rows[rows.length - 2]);
        if (!done.matches()) throw new IOException("Incomplete lookup");
        int checked = Integer.parseInt(done.group(1));
        if (checked > 100) throw new IOException("Lookup limit");
        if (rows.length == 4 && rows[1].equals("UNAVAILABLE") && checked == 0) return new Lookup(false, 0, null);
        Header match = null;
        Set<String> indices = new HashSet<>();
        for (int i = 1; i < rows.length - 2; i++) {
            String[] f = rows[i].split("\\|", -1);
            if (f.length != 5 || !f[0].equals("H") || !f[1].matches("[0-9]{2}") || !indices.add(f[1]))
                throw new IOException("Invalid/duplicate header envelope");
            int pid = positive(f[2]), owner = positive(f[3]);
            long when = timestamp(f[4]);
            long lag = exit.when - when;
            if (owner == uid && pid == exit.pid && when >= session.wall && when <= now
                    && lag >= -MAX_TIMESTAMP_AHEAD_MS && lag <= MAX_EXIT_LAG_MS
                    && (match == null || when > match.when)) match = new Header(f[1], f[4], exit, when);
        }
        if (indices.size() > checked) throw new IOException("Inconsistent lookup count");
        return new Lookup(true, checked, match);
    }

    public static String renderCapture(String raw, Header h) throws IOException {
        validate(h);
        String expected = "NATIVE_CAPTURE_V2\nH|" + h.index + "|" + h.pid + "|" + h.uid + "|" + h.stamp + "\n";
        if (raw == null || raw.length() > MAX_CAPTURE_BYTES || !raw.startsWith(expected) || !raw.endsWith("TRACE_END\n"))
            throw new IOException("Native file changed or unreadable");
        StringBuilder frames = new StringBuilder();
        int count = 0;
        String signal = null;
        String middle = raw.substring(expected.length(), raw.length() - "TRACE_END\n".length());
        for (String line : middle.split("\n", -1)) {
            if (line.isEmpty()) continue;
            String[] f = line.split("\\|", -1);
            if (f.length == 4 && f[0].equals("S") && signal == null && count == 0 && f[1].matches("[0-9]{1,2}")) {
                int number = Integer.parseInt(f[1]);
                if (number <= 0 || number > 64 || (h.expectedSignal != 0 && number != h.expectedSignal))
                    throw new IOException("Signal identity mismatch");
                StringBuilder s = new StringBuilder("Native record signal=").append(number);
                if (f[2].matches("-?[0-9]{1,10}")) {
                    try { s.append(" code=").append(Integer.parseInt(f[2])); } catch (NumberFormatException ignored) {}
                }
                if (f[3].matches("0x[0-9a-fA-F]{1,16}")) s.append(" faultAddress=").append(f[3]);
                signal = s.append('\n').toString();
            } else if (f.length == 5 && f[0].equals("F") && signal != null && count < 32 && f[1].matches("[0-9]{2}")
                    && Integer.parseInt(f[1]) == count && f[2].matches("[a-fA-F0-9]{1,16}") && f[3].length() <= 96) {
                frames.append('#').append(count++).append(" pc 0x").append(f[2]).append(' ')
                        .append(AirExitReport.module(f[3]));
                if (f[4].matches("[a-fA-F0-9]{1,128}")) frames.append(" BuildId=").append(f[4]);
                frames.append('\n');
            } else throw new IOException("Invalid native facts");
        }
        if (signal == null) throw new IOException("No native signal facts");
        return "Matching native text record: pid=" + h.pid + " recordTimeMs=" + h.when
                + " recordToExitMs=" + (h.exitWhen - h.when) + "\n" + signal
                + (count == 0 ? "No first-backtrace frames in bounded text record.\n" : frames.toString())
                + "A matched retained record is evidence, not proof of the final cause.\n";
    }

    private static int positive(String value) throws IOException {
        try {
            if (!value.matches("[0-9]{1,10}")) throw new NumberFormatException();
            int n = Integer.parseInt(value);
            if (n <= 0) throw new NumberFormatException();
            return n;
        } catch (NumberFormatException e) { throw new IOException("Invalid native identity"); }
    }

    private static long timestamp(String text) {
        if (text == null || text.length() > 80) return -1;
        Matcher m = Pattern.compile("([0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2})(?:\\.([0-9]{1,9}))?(Z|[+-][0-9]{2}:?[0-9]{2})?").matcher(text);
        if (!m.matches()) return -1;
        String fraction = (m.group(2) == null ? "" : m.group(2)) + "000";
        String zone = m.group(3);
        String value = m.group(1) + "." + fraction.substring(0, 3);
        SimpleDateFormat format;
        if (zone == null) format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
        else {
            value += zone.equals("Z") ? "+0000" : zone.replace(":", "");
            format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSSZ", Locale.US);
        }
        format.setLenient(false);
        ParsePosition pos = new ParsePosition(0);
        Date date = format.parse(value, pos);
        return date != null && pos.getIndex() == value.length() ? date.getTime() : -1;
    }
}
