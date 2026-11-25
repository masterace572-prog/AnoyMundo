# Installed split-APK games and Lite/Air assets

## Scope

The loader launches the complete game already installed on the phone. A normal
single APK and an installed base + split set use the same Launch button.
Lite/Air (`com.krafton.bgmilite`) does **not** use an OBB. BGMI
(`com.pubg.imobile`) still requires its own verified main OBB and optional patch.
Library availability, licence, SDK activation and second-game confirmation are
unchanged. This does not add an APK/APKS/XAPK file-import screen.

## Full package preparation

`utils/GamePackagePreparer` runs on Home's I/O executor before the container
launch call, after BGMI's OBB preparation (Lite/Air skips that step).

1. Read the installed package from Android PackageManager: base `sourceDir`,
   **every** `splitSourceDirs` entry and its corresponding `PackageInfo.splitNames`.
   This includes ABI, language, configuration, feature and asset APK splits.
2. Reject missing/duplicate split metadata, missing/empty/unreadable code paths
   and APKs without a readable ZIP/manifest. These are lightweight completeness
   checks, not a new APK-signature verifier; Android verifies the installed set.
3. Read back the SDK package. If its version, split set or APK sizes do not match,
   use the original SDK's public **`MetaApplicationInstaller.cloneApp(package)`**
   path. Never install only `base.apk` or one `split_config` file by path.
4. Require the complete SDK package to be readable afterwards. Recheck the host
   install's version, update timestamp and code-path stamps to avoid mixing
   files while a store/installer updates the game. A failed/partial setup is a
   controlled error and does not proceed to launch.
5. Keep the SDK's existing launch flow, game data and signatures. No APK merging,
   re-signing, game uninstall, data clear, private-SDK reflection or AAR swap.

The original AAR already exposes whole-package cloning and split-aware
ApplicationInfo handling. Its `installAppByPath` (single-file) API is deliberately
not used. Native libraries, dex, resources and split dependencies are handled by
the SDK's package loader, not by a partial custom APK merger.

## No OBB requirement for Lite/Air

- `GameTarget.requiresObb()` is true for BGMI, false for Lite/Air.
- Lite/Air reports `AppManager.ObbState.NOT_REQUIRED`, including when shared
  storage is blocked. It never reads/creates an OBB directory or starts a copy.
- Its Launch permission check does not require All-files/OBB Files access. The
  existing SDK install permission gate remains. BGMI retains all three gates.
- General Login/Home permission explanations can still be shown for BGMI and
  can be declined. They must not prevent Lite/Air launching without an OBB.
- A split-based **BGMI** install is not exempt from BGMI's OBB requirement.
  Asset policy is package-specific, not guessed from the presence of splits.

## Verification and limits

Host Android 21/28 tests run for both brands. They cover single and split APKs,
whole-set registration/read-back, repairing base-only/stale versions and split
sets, incomplete/corrupt files, SDK/linkage failure, install-update races,
retaining container data, no-OBB behavior and per-game permission policy.
These tests use temporary APK-shaped ZIPs and an injected SDK adapter. They do
**not** start the real SDK/game or establish crash-free phone compatibility.

Required physical-device checks before calling Lite/Air runtime-compatible:

- Install the complete legitimate split set through a trusted installer/store.
  Open it normally once to finish any asset downloads. Do not install just base.apk.
- Launch Lite/Air with **no OBB folder** and shared-storage permissions denied;
  with its own usable `libbgmilite.so`, licence and SDK activation available.
- Confirm the game can load ABI libraries, code, resources and asset splits;
  resume it, stop/relaunch it, and repeat after an installed-game update.
- Verify a normal single-APK game still launches and BGMI still verifies/copies
  its OBB. Confirm existing container saves/logins are retained.
- Exercise both brands and second-game cancellation/ten-second confirmation.
- Provider/OAuth rules, Play Asset Delivery beyond installed APK splits,
  anti-container restrictions, unsupported CPU architectures and failures inside
  native game/SDK code may still need a supported SDK update and device logs.

A missing complete installed set now yields a retry/install-complete message;
that is safer than launching a known partial package, not a promise that every
third-party split distribution or game engine will be crash-free.
