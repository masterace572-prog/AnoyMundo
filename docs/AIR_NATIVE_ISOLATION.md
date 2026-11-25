# Temporary Air native-injection diagnostic

**This is a diagnostic test, not a confirmed crash fix. Do not distribute it as
an ordinary loader update.** Public releases and live panel choices are unchanged.

## What changes

Only the explicitly opted-in diagnostic build skips the `System.load` handoff
for **BGMI Air / `libbgmilite.so`**. The original Mundo SDK, full base/split APK
preparation, library validation, licence checks, BGMI injection and login routing
stay in place. Air still needs its installed library to pass the usual gates;
the diagnostic does not download a different payload or delete the existing one.
There is no runtime toggle or Intent extra that enables this mode.

Normal builds default to injection **on**. Diagnostics show a persistent
**“Air diagnostic: injected library OFF”** note on the Air card and an **Air
diagnostic** suffix in Settings. The version remains **2.4.3 (21)**, the signer
and brand/package identities are unchanged. A diagnostic run must not log Air's
library as loaded or record a successful load.

## On the phone

1. Stop any running virtual games using the existing Stop control. Install the
   diagnostic APK **over the matching brand** (Anoy over Anoy, KennyPvt over
   KennyPvt). These temporary test builds can replace the existing same-code
   test APK in place. **Do not uninstall, clear storage or delete container data.**
2. Sign in normally if needed. Verify the **Air diagnostic** note is visible
   on the Air card. If it is absent, this is not the intended test APK.
3. Launch **Air only**, using the usual installed game and files. No injected
   menu/features are expected in this test. Check whether it still closes at
   the logo/loading point, or reaches the lobby and remains there for a minute.
4. If Air closes, return to the loader, wait a few seconds, then tap **Copy Air
   crash report** on the Air card **within 15 minutes**. Review the preview and
   tap **Copy report**, then paste that report into the conversation. This is
   separate from Settings → Copy support info, which includes the licence key.
   No full-device log or ADB command is needed. Report the result separately
   for each brand; testing one does not establish the other's current result.
   **For the separately approved native-record diagnostic on a rooted device:**
   tap **Read native trace (root)**, review its new confirmation, then tap
   **Allow native read** and approve the root-manager prompt. Wait up to 20
   seconds, review the replacement preview and tap **Copy report**. A readable
   matching record may not exist. The older **Add root exit details** action is
   still separate and reads exit numbers only; there is no need to repeat it
   when it already confirmed the signal. Neither opening nor copying the report
   requests root or scans native files.
5. When testing is finished, manually install the matching normal signed test
   APK over the diagnostic to restore Air injection. Keep app/container data.
   Do not change live panel APK/library choices for this test.

For a short logcat capture, the exact diagnostic marker is:

```text
AIR-NATIVE-ISOLATION: libbgmilite.so injection skipped
```

## What the result means

- **Air passes the previous failure point:** implicates injection or its
  interaction with the game/container. It does not prove that the downloaded
  library alone is faulty, or that the whole game is compatible.
- **Air still closes after the skip marker:** the injected library is not
  required for that reproduced failure; investigate game/container compatibility
  and obtain a native crash backtrace / exit trace if available.
- **No diagnostic note/marker, library gate fails, or game never starts:**
  inconclusive. Do not treat it as an injection-on/off comparison.

## Build and release safeguards

- Local build opt-in: `-PairNativeIsolation=true`; omission defaults to `false`.
  Any property value other than `true` / `false` fails configuration.
- CI dispatch: set `air_native_isolation=true` and **`publish_release=false`**.
- CI push: use **both** `[verify-2.4.3]` and `[air-native-isolation]` in the commit
  message. A diagnostic push without the verify-only marker fails.
- Diagnostic publication is blocked by both workflow conditions and a shell
  guard. APK and artifact names include `Air-diagnostic`, with separate test
  instructions and checksums. No new release, tag, panel choice or deployment.
- Regressions compile the actual callback with the flag both off and on using
  JVM-only adapters, check the per-game scope, and inspect generated release
  flags / compiled DEX markers in both branded APKs. Host checks do **not** prove
  Android linker behavior, native-game execution or physical save preservation.

## Private in-app crash report

The reporting diagnostic writes only tiny numeric launch and Air-callback
markers in the loader's private `air_crash_diagnostics` directory. It never
modifies game files or clears container data. Markers expire after 15 minutes,
reject clock/reboot mismatches and retain at most 16 guest files. The callback
marker confirms that Air reached the injection-skip branch; without it, any
recent host-stub exit is explicitly an **unconfirmed candidate**, not proven Air.

The dedicated report reads at most 32 own-package Android exit-history records
and exports at most four matching recent exits. It includes loader/game version,
Android/model, reason/status, and (if Android retained a readable native trace)
the crashing thread's signal, relative PCs, allowlisted module basenames and hex
BuildIds. It does **not** copy licence/session keys, Android/advertising IDs,
free-form exit descriptions, abort strings, command lines, function/thread names,
logs, registers, memory contents or full paths. Nothing is uploaded automatically.
The preview precedes copying, and the clipboard is marked sensitive on API 33+.

Android 12+ native traces use the [AOSP tombstone protobuf schema](https://github.com/aosp-mirror/platform_system_core/blob/main/debuggerd/proto/tombstone.proto).
The bounded decoder reads top-level PID/TID/UID (fields 5/6/7), signal (10),
thread map (16), current backtrace (thread field 4), and relative-PC/module/BuildId
(frame fields 1/6/8). Everything else is skipped, including embedded log and
memory data. PID and UID must match the selected exit. Input is limited to 8 MiB,
at most 64 thread entries and 32 crashing-thread frames; the report is at most
32 KiB. Unknown/malformed/oversized traces are reported unavailable, never copied
raw. Older Android and signal-only exits may provide no native frames.

The existing **Add root exit details** button requires its own explicit
confirmation. Its only command is
`/system/bin/dumpsys activity exit-info <own-loader-package>`, where
the package is pinned to `com.xlox` or `com.kennypvt`. It does not scan tombstone
directories, run full-device logcat, attach a debugger, read game memory, alter
signals/protections or modify settings. Root output is bounded to 128 KiB and a
20-second read timeout, parsed for matching numeric exit facts only, and never
saved or copied verbatim (including error output). Denied/unsupported root reads
are harmless. Closing the sheet or destroying Home cancels the worker/root
request. Android may still have no trace; root availability is not a fix.

## Separate bounded root native-record action (v2)

**Read native trace (root)** has a fresh confirmation on **every request**. An
approval for **Add root exit details** does not approve this action. It never
runs from startup, opening/copying the preview, the old root-history action or a
stored preference. Both root buttons and Copy are disabled while a read is
pending; Close remains available and cancels the worker/root process. No debugger
is attached and no signal handlers, protections, game/SDK files or settings are
changed. Air injection remains OFF in these diagnostic builds only.

Before any `su` command, the reader requires a fresh launch marker, at least one
own-UID Air injection-skip callback marker and a matching own-package Android
exit-history entry (API 30+, at most 32 entries). **Only the newest confirmed
exit** from this launch is considered; it must be a native crash or a supported
native-signal exit. Unconfirmed host-stub candidates are insufficient. No root
fallback broadens this scope if the API/callback identity is unavailable.

The fixed, read-only shell protocol:

1. Inspects at most 100 regular, readable, non-symlink
   `/data/tombstones/tombstone_[0-9][0-9]` text headers. Each is limited to **4096
   bytes / 32 lines**. Only candidate numeric PID/UID and a bounded timestamp are
   emitted; other apps' command lines, names and free text are discarded in the
   shell. No arbitrary directory/file picker, wildcard recursion or raw logcat.
2. Selects at most **one** record with the confirmed Air PID/own UID and a valid
   timestamp inside this launch, not in the future, and between **30 seconds
   before / 2 seconds after** that exit. The newest matching record is selected;
   older same-PID records outside the window are not a fallback.
3. Requires a matching, regular, non-symlink `.pb` companion's bounded **4096-byte
   identity prefix** (AOSP fields timestamp/PID/UID: 4/5/7). The decoder stops
   before non-header fields; unsupported ordering, missing/truncated identity
   or an absent companion fails closed. The raw prefix exists only in bounded
   worker memory, is never persisted/copied, and no logs/memory are decoded.
4. Rechecks text PID/UID/timestamp during a matched **65536-byte / 512-line** text
   read. It emits only signal/code/optional fault address and at most **32
   sequential frames from the first backtrace**. Function/thread names, abort
   messages, registers, later threads, embedded logs and memory are discarded.
   Java independently validates the protocol and allowlists module basenames,
   hex relative PCs and hex BuildIds. For signal-only exits the native signal
   must also agree with the Android exit status.
5. Re-reads the same bounded `.pb` identity prefix after capture and requires
   the same identity and identical prefix bytes. Changed/rotated identities or
   malformed output suppress all captured facts. This is a bounded correlated
   read, not an atomic snapshot of the system's crash storage.

All four possible root commands share a **20-second deadline**. Lookup/capture
protocol outputs are each capped at **16 KiB**; the native section at **16 KiB**
and the entire preview/copy at **32 KiB**. Raw stdout/stderr (including root
errors) is never saved, logged or copied. There is no automatic upload.

A **no readable match** result means only that this bounded, identity-checked
lookup could not export a matching retained record. Files may be absent,
restricted, rotated, unsupported, outside the time/size limits, or missing their
structured companion. It does **not** prove that no tombstone exists or identify
a faulting module. A matched backtrace is evidence to investigate, not a crash
fix or proof of the final cause. In particular, `SIGNALED(2)/status 11` is not
itself a module/backtrace; the v1 API trace path reads only reason-5 native crashes.

Tests cover marker scope/expiry/retention, Android 21/35 adapter entry points,
separate consent/cancel/copy/lifecycle UI, latest-exit UID/PID/time filtering,
malformed protobuf/protocols, file types/rotation, byte/line/frame bounds and
secret/path redaction. The production shell strings are executed against
**synthetic disposable fixtures under POSIX sh and bash**, without `su` or real
Android files. These tests do not exercise a physical device, game, root manager,
Samsung shell/SELinux policy or genuine native tombstone service. Only Anoy's
previous injection-OFF crash was physically reported; KennyPvt remains untested.
