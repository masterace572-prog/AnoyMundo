# Air native-copy preparation correction (build-only candidate)

This is a targeted loader correction, **not yet a confirmed fix for Air's
SIGSEGV/SIGBUS on a physical phone**. Anoy's reproduced injection-OFF crash shows
that the downloaded Air library is not necessary for that failure. No faulting
module/PC was obtained; do not infer the cause from a signal number.

## Verified gap and correction

The original, unchanged `mundo-release.aar` has a native-copy completeness gap:
its `i1` wrapper discards `NativeLibraryHelper.copyNativeBinaries`' return value,
converts extraction exceptions/unsupported ABI to zero, and its directory importer
ignores that result for splits too. The existing preparer checked the complete
APK set, default user and launch resolution, but not the extracted native bytes.
That can mark an incomplete native copy ready. It does not establish that this
happened on the tester's phone or caused the observed crash.

`AirNativeLibraries` and `GamePackagePreparer` now:

1. Use the installed, Android-verified **base plus every split** as the expected
   package. Select packaged `lib/arm64-v8a/lib*.so` entries because both loaders
   ship only arm64. A native 32-bit-only package is not treated as resource-only.
2. After SDK user/component readiness, check extracted files in the SDK-reported
   native directory for readability, exact length and full streaming CRC. This
   detects same-size/same-timestamp corruption too, without reading GBs of asset
   payloads. ZIP CRCs verify copy integrity, **not authenticity/signatures**.
   Ambiguous conflicting native basenames, malformed paths, escaping symlinks
   and empty libraries fail closed. Metadata is bounded to 128 APKs / 512 distinct
   arm64 libraries; streams use 64 KiB memory and check interruption.
3. Preserve Android's native-loading policy: API 21/22 require extraction; on
   API 23+ an extraction flag in either host or container requires copies. With
   neither flag set, legitimate direct-from-APK loading requires no invented
   extracted files. Any extracted file present must still match so a bad copy
   cannot shadow the packaged library. This does **not** hash direct-loaded APK
   payloads or prove SDK dex/resource/linker behavior.
4. If the APK set is otherwise ready but the native check fails, re-import the
   **entire installed set** using the original public, non-moving
   `MetaApplicationInstaller.installAppsByPath(directory)` API. Reuse the existing
   default-user map; no uninstall, data-clear, clone-mode fallback, game patch,
   custom extraction or library replacement is added. The SDK owns its normal
   internal code-copy replacement and extraction. The verifier only reads files.
5. Query Air's running state before staging and again immediately before any
   import. If running, ask the user to Stop Air rather than replacing mapped code
   or implicitly killing it. Recheck the host snapshot before import and after
   preparation. Keep private staging through read-back and remove only staging.
6. Allow at most **one SDK import per Launch**, whether that is a fresh import,
   APK refresh or native-only repair. Require full APK/user/component/native
   read-back afterwards. A failed copy stops Launch with a dedicated error; it
   cannot be marked ready by the SDK's ignored numeric result or retry forever.

An intact existing copy is checked but **not re-imported**. There is no persistent
"already verified" cache. Native game libraries such as UE4 can exceed 20 MiB;
this check does not apply the separate 20 MiB downloaded-loader upload limit to
legitimate game-packaged files. Hashing runs on Home's existing I/O executor,
not the UI thread; the first preparation may take longer.

## Scope and preservation

- Air only (`com.krafton.bgmilite`); no OBB or OBB-only permission is required.
  BGMI's native/preparation, OBB, injection and login paths are unchanged.
- Original SDK binary, licence gates, injected libraries, native code, signal
  handlers, protection settings, package identities and signer are unchanged.
- Version remains **2.4.3 / code 21**. Install over the matching brand; **never
  uninstall or clear app/container data** for this candidate.
- No new root command, native-record scan, log collection or upload is added.
  Existing optional native diagnostics still require their separate fresh consent.
- The normal build retains Air injection **ON**. The existing explicitly opted-in
  diagnostic build still skips **only Air injection**; it is a separate mode, not
  a workaround enabled silently by the repair.
- Build-only CI uses `[verify-2.4.3]` (without the diagnostic marker for normal
  injection). Public releases/tags/assets and live panel selections stay untouched.

## Verification

- `python3 -B xlox/scripts/test_air_native_preparation.py`: seven source/CI wiring
  guards plus 52 assertions using the actual verifier and disposable ZIP/files.
  Includes base/ABI/resource/asset combinations, same-size corruption, missing and
  truncated copies, direct-mode shadowing, conflicting duplicates, unsupported
  ABI, symlink/path boundaries, cancellation, limits and a >20 MiB game library
  streamed with a **32 MiB JVM heap**.
- `GamePackagePreparerTest`: 70 fixture cases per host Android 21/28/35 per brand,
  including 20 Air native repair/integration cases. CI requires at least **676**
  total Android host tests including the existing UI/OBB/diagnostic suites.
- Both release APKs must compile, lint, retain their original signatures and
  contain the compiled native-preparation markers after R8. The normal-mode
  artifact gate also requires that Air injection is not compiled out.

These are host/adapter tests, **not actual SDK installation, game execution,
physical save-retention or crash-resolution evidence**.

## Phone acceptance (normal candidate)

1. Stop running virtual games, then install the matching candidate APK over Anoy
   or KennyPvt. Keep the installed game, loader library, licence and all app data.
   Do not change the public release or live panel choices.
2. Launch Air normally and wait for Preparing to finish. No OBB folder or extra
   storage permission is needed. The normal candidate should have **no injected
   library OFF notice**; Air's ordinary library handoff remains enabled.
3. Check whether Air passes logo/loading, reaches the lobby and remains there for
   at least a minute. Then Stop/relaunch and verify existing saves/login remain.
   Test the other brand separately; Anoy's result does not establish KennyPvt's.
4. If preparation stops with an error, report that short message. If the game
   still closes, report that outcome. **Do not repeat an identical native lookup
   or collect another full-device log just to reconfirm the same signal.**

For a deliberately scoped log check, success emits only one of these fixed
preparation markers, without paths, licence keys or raw errors:

```text
Air native preparation: VERIFIED
Air native preparation: VERIFIED_AFTER_SDK_REIMPORT
```

`VERIFIED` may mean a valid direct-loading/resource-only set; it is not a statement
that every third-party runtime path was tested. `VERIFIED_AFTER_SDK_REIMPORT`
means the initial native check failed and a single SDK re-import passed all
read-back checks; it is not proof of the original SIGBUS cause. Continued failure
with verified copies still requires an actionable native fault or a supported
container-SDK compatibility correction, not signal suppression or protection
bypasses.
