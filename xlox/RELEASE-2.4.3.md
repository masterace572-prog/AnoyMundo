# 2.4.3 replacement — versionCode 21

## Requested scope

- Build **Anoy** (`com.xlox`) and **KennyPvt** (`com.kennypvt`), preserving their
  separate branding, owner channels, JNI namespace and original signing key.
- Include BGMI Air / Lite (`com.krafton.bgmilite` → `libbgmilite.so`) alongside
  BGMI (`com.pubg.imobile` → `libbgmi.so`). [Air/bundle details](BGMI-AIR.md).
- Replace GitHub releases newer than **2.4.2** with this **2.4.3 (21)** release.
  Preserve 2.4.2 and every older release. This is an explicit one-time operation,
  not a release-pruning policy. Live panel library/APK selections are not changed.

## Navigation

The loader page is **Home**. A persistent bottom capsule contains **Home** and
**Settings**, both with at least 48dp touch targets. Tap a label or drag either
page horizontally. The two full-width, opaque pages are physically adjacent:
at a half-width drag the viewport shows half of Home and half of Settings, not
a crossfade. Home's two game actions move with Home; they are not overlaid on
Settings. Each page retains its vertical scroll position.

- Settings contains the `?` Help entry and a centered, red **Sign out** label.
  Help remains user-opened only; Sign out still confirms, and Forget this key
  still removes only the saved local key.
- Back from Settings returns to Home before normal activity Back handling.
- The licence key is masked again on page selection, motion and backgrounding.
- The Home countdown pauses while away/in motion and resumes when Home settles.
  Game-state reconciliation and licence/update behavior are otherwise retained.
- System-bar/cutout insets pad the page host and capsule once. The persistent
  capsule is also the Snackbar anchor; an offscreen Home bar cannot anchor one.
- With Remove animations enabled, tab taps switch immediately. Finger drags
  still track the touch and show the two adjacent pages.
- Hidden-page actions are excluded from accessibility traversal. Both page roots
  remain visible and opaque for partial-swipe rendering.

Implementation: `ui/HomeSettingsPager`, `ui/HomeSettingsNavigation`,
`ui/CapsuleNavigation`, `layout/{activity_main,page_home,page_settings}.xml`.
AndroidX ViewPager 1.0.0 supports the existing minSdk 21; no SDK, Material,
AppCompat, Fragment or native-library upgrade was introduced. JUnit/Robolectric
are test-only dependencies.

## Installing a replacement with the same code

The owner explicitly requested **versionName 2.4.3 / versionCode 21**.
The updater correctly requires a **strictly greater** code; it was not weakened.

- An existing code-20 installation can receive a code-21 update through its
  configured owner APK channel, if the Owner chooses this APK there.
- An existing code-21 installation does **not** discover this equal-code build
  as a new OTA version. Download the matching brand's signed APK and perform a
  manual in-place update. Do not uninstall or clear app/container data.
- Publishing to GitHub does not select an APK or a game-library release in the
  panel. Air stays Coming soon until its own usable file is installed from the
  correct Owner's library bundle.

## Verification

Local SDK-free checks executed during preparation:

- 14 UI/resource gates and 20 branding/channel source gates.
- 12 existing Android wiring checks; 15 runtime and 15 contained-web JVM groups.
- 6 Air wiring checks and 17 dual-game production-class JVM groups.
- 7 new navigation structure/lifecycle wiring checks.
- Java 8 syntax parsing, XML/Python parsing and whitespace checks.

CI must additionally build and lint **both release variants**, execute actual
Android view inflation/geometry/tap/touch-swipe tests for both brands on host
Android 21/28, inspect the compiled APK versions, permissions, callback manifest
and artwork, verify native alignment, and compare each APK signer to the original
keystore certificate. A build-only run precedes any removal of the old release.
Its explicit backup mode archives and SHA-256-verifies the old 2.4.3 assets and
release/tag metadata in a retained Actions artifact; it does not delete anything.

```sh
cd xlox
python3 -B scripts/ui_quality_gate.py
python3 -B scripts/check_branding.py
python3 -B scripts/test_runtime_fixes.py
# With a full JDK + Android SDK/NDK:
./gradlew :app:testAnoyDebugUnitTest :app:testKennyDebugUnitTest \
  :app:assembleAnoyRelease :app:assembleKennyRelease \
  :app:lintAnoyRelease :app:lintKennyRelease --no-daemon
```

Preparation evidence is ignored under `../qa-logs/release-2.4.3/`; the prior Air
feature evidence remains separately preserved under `../qa-logs/bgmiair/`.
A host Android view test is not a physical-device game test. Real arm64 game
launch, Air asset delivery/provider authentication, native payload compatibility
and simultaneous-game memory/thermal behavior still need authorized device QA.
The existing contained BGMI web-login candidate is included, not certified as a
phone-validated provider fix.
