# Installed split-APK games and Lite/Air assets

## Scope

The loader prepares the complete game already installed on the phone. A normal
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
   Do not drop ABI, language, configuration, feature or asset APK splits.
2. Reject missing/duplicate metadata, missing/empty/unreadable code paths and
   APKs without a readable ZIP/manifest. Android verifies the installed APK set;
   these additional completeness checks do not replace signature verification.
3. Read back the SDK package after its public initial-scan barrier. Compare
   package, version, split names, sizes and ZIP-entry names/CRCs/sizes. This avoids
   treating a same-size stale split as ready without hashing GBs every launch.
4. If repair/import is needed, call the original SDK's public **non-moving**
   `MetaApplicationInstaller.installAppsByPath(directory)` with the **whole set**.
   Use the installed directory only if its APKs exactly match PackageManager;
   otherwise stream just that set into a private temporary directory. Bound
   staging memory, check free space and clean temporary files even on failure.
5. Require the full SDK package to be readable afterwards. Recheck the host
   install's version, update time and code-path/content stamps to avoid mixing
   files during a store update. A failed/partial setup does not proceed to launch.
6. Keep existing launch/data/signing behavior. No merging, re-signing, game
   uninstall, data clear, private-SDK reflection, source-APK moves or AAR swap.

### Why this API, not `cloneApp`

The original `mundo-release.aar` was inspected through its actual wrapper and
service implementation, not inferred from API names:

- `cloneApp(package)` selects mode **2**. This unregisters an internal code copy
  in favor of external-package mode; it is **not** a complete split APK import.
- `installAppsByPath(directory)` selects mode **1**. The service's `o5` dispatch
  calls `i1`'s directory parser: enumerate `.apk`, `parseApkLite`, import base and
  every split, extract native binaries from **each** APK and populate
  `splitNames`, `splitSourceDirs`, `splitPublicSourceDirs` and revision metadata.
- The non-moving path copies original APK bytes. It reuses an existing package's
  user map rather than clearing its data. `l2` preserves split metadata on public
  PackageInfo read-back. The initial-scan barrier is bounded to ten seconds.

Do not replace this with `installAppByPath(base.apk)` or an `AndMove` variant.
The SDK still owns actual dex/resource/asset loading and native-linker behavior;
passing the full set cannot itself prove that its engine supports every game.

## No OBB requirement for Lite/Air

- `GameTarget.requiresObb()` is true for BGMI, false for Lite/Air.
- Lite/Air reports `AppManager.ObbState.NOT_REQUIRED`, even when shared storage
  is blocked. It never reads/creates an OBB directory or starts a copy.
- Its Launch check skips All-files/OBB Files access; the SDK install gate remains.
  General permission explanations for BGMI can still be shown and declined.
- A split-based **BGMI** install still needs BGMI's OBB. Asset policy is
  package-specific, not guessed from the presence of splits.

## Verification and remaining device checks

The host suite includes **32 APK-set cases**, plus 11 OBB/permission-policy cases,
28 navigation/presentation cases and 9 Login/Splash cases, each on host Android
21/28 for both brands (**320 checks**). APK-set cases use temporary APK-shaped
ZIPs and an injected SDK adapter: exact-set imports/staging, single/split installs,
stale/missing/same-size wrong splits, read-back failure, update races, low space,
cleanup and retaining originals/data. They do **not** start the real SDK/game.

Required physical-device checks before calling Lite/Air runtime-compatible:

- Install the complete legitimate split set through a trusted installer/store.
  Open it normally once to finish any asset downloads; never install just base.apk.
- Launch Lite/Air with **no OBB folder** and shared-storage permissions denied;
  have its own usable `libbgmilite.so`, licence and SDK activation available.
- Check ABI libraries, dex, resources and asset splits, then resume, stop/relaunch
  and repeat after an installed-game update. Repeat for both loader brands.
- Verify ordinary single-APK behavior, BGMI OBB preparation and existing container
  saves/logins. Exercise second-game cancellation/ten-second confirmation.
- Dynamic features/Play Asset Delivery beyond installed splits, OAuth/provider
  rules, anti-container restrictions and failures inside native SDK/game code may
  still require a supported SDK update and device logs.

Controlled setup errors prevent known partial launches. They are not a promise
that every third-party split distribution or game engine will be crash-free.
