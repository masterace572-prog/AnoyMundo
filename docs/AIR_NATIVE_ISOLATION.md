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
4. Report the result separately for **Anoy** and **KennyPvt**. No new full-device
   log is needed just to report “still closes” or “reaches lobby.” If asked for
   a scoped capture, collect it privately; do not upload another raw device log
   to this public repository or share unredacted Copy support info (it includes
   the licence key).
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
