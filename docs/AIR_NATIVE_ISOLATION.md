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
   On a rooted test device, optionally tap **Add root exit details**, confirm
   **Allow root read**, grant the root-manager prompt and copy the updated report.
   Root is not requested automatically and cannot guarantee a native backtrace.
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

The **optional root button** requires a second explicit confirmation. Its only
command is `/system/bin/dumpsys activity exit-info <own-loader-package>`, where
the package is pinned to `com.xlox` or `com.kennypvt`. It does not scan tombstone
directories, run full-device logcat, attach a debugger, read game memory, alter
signals/protections or modify settings. Root output is bounded to 128 KiB and a
20-second read timeout, parsed for matching numeric exit facts only, and never
saved or copied verbatim (including error output). Denied/unsupported root reads
are harmless. Closing the sheet or destroying Home cancels the worker/root
request. Android may still have no trace; root availability is not a fix.

Tests cover marker scope/expiry/retention, Android 21/35 adapter entry points,
per-UID/PID/time filtering, callback-mode scope, malformed protobufs/input bounds,
native-frame caps and secret/path redaction. No automated test here exercises a
physical device, game, real root manager or genuine native tombstone service.
