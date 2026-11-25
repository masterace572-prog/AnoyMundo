# 2.4.3 — user update from 2.4.2 (versionCode 21)

> **Published: 2.4.2 (20) → 2.4.3 (21).** 2.4.2 is the last version previously
> distributed to users; earlier 2.4.3 APKs were internal tests only.
> [Release 457073966](https://github.com/masterace572-prog/AnoyMundo/releases/tag/loaders-v2.4.3-21), source **`f8f58847a8e9eebac008913700cd0ff343122016`**,
> [publication run 45443061179](https://github.com/masterace572-prog/AnoyMundo/actions/runs/45443061179).
> Both brands passed **180/180 host Android checks**, release build/lint,
> compiled APK and original-signature gates. App/test inputs are unchanged from
> accepted **cfc442e / run 45436589864**; only release documentation/notes changed.
> Only the older internal 2.4.3 GitHub artifacts (456953450/source96356a5) were
> replaced. All four releases through 2.4.2, their assets and exact tag refs are
> unchanged. Their live panel/library/APK selections were not changed.
> Predecessor backup **14941666853** retains the actual internal APKs plus
> release/tag metadata, with full checksums and original signers verified in CI.
> [Short changelog](CHANGELOG-2.4.3.md) · [UI/device QA limits](LOADER-UI-QA.md).

## Requested scope (completed publication)

- Build **Anoy** (`com.xlox`) and **KennyPvt** (`com.kennypvt`), preserving their
  separate branding, owner channels, JNI namespace and original signing key.
- Include BGMI Air / Lite (`com.krafton.bgmilite` → `libbgmilite.so`) alongside
  BGMI (`com.pubg.imobile` → `libbgmi.so`). [Air/bundle details](BGMI-AIR.md).
- Replace **only the older internal 2.4.3 (21) GitHub test artifacts** with
  this first user-facing 2.4.3 update. Preserve 2.4.2 and every older release.
  This is not a release-pruning policy. Live panel library/APK choices are unchanged.

## Published downloads

| Brand | Package | Signed APK |
|---|---|---|
| Anoy | `com.xlox` | [Anoy-XLOX-loader.apk](https://github.com/masterace572-prog/AnoyMundo/releases/download/loaders-v2.4.3-21/Anoy-XLOX-loader.apk) |
| KennyPvt | `com.kennypvt` | [KennyPvt-loader.apk](https://github.com/masterace572-prog/AnoyMundo/releases/download/loaders-v2.4.3-21/KennyPvt-loader.apk) |

[SHA256SUMS.txt](https://github.com/masterace572-prog/AnoyMundo/releases/download/loaders-v2.4.3-21/SHA256SUMS.txt):

```text
7342e7fb67c0f127b51bafdbfde077a0af80101c5d47fc036c250b056b44489e  Anoy-XLOX-loader.apk
0c1c11737776a49c66b7ffe7607b504fc7c71df9a30f1fa39b187d5a28a7c438  KennyPvt-loader.apk
```

GitHub's uploaded asset digests match the build's release checksums. CI's
structured verifier confirmed the original certificate for both APKs:
`77f05d53ce8bf1855caef38ce87f13a8bb2b1b2cdd2d48da9d3ba897eac4549e`.
The retained backup was downloaded/verified by CI; this sandbox's separate
artifact-download attempts were blocked at external storage. No local APK
verification, installation, physical-device gesture test or measured FPS claim.

## Navigation

The loader page is **Home**, headed by the smaller **Welcome back** greeting.
A persistent bottom capsule contains **Home** and **Settings**, both with at least
48dp touch targets. The selected segment is blue, with white text. Tap a label,
drag either page horizontally, or drag the blue selector towards the other tab.
Only the selector decoration is now inset by 2dp; its travel remains one full tab
pitch and the label targets stay unchanged. The two full-width, opaque pages are physically
adjacent: at a half-width drag the viewport shows half of Home and half of
Settings, not a crossfade. Each game's controls and task feedback are inside its
own scrolling card; there is no pinned game-action panel and nothing is overlaid
on Settings. Each page retains its vertical scroll position.

- Settings contains the `?` Help entry and a centered, red **Sign out** label.
  Help remains user-opened only; Sign out still confirms, and Forget this key
  still removes only the saved local key.
- Back from Settings returns to Home before normal activity Back handling.
- The licence key is masked again on page selection, motion and backgrounding.
- The Home countdown pauses while away/in motion and resumes when Home settles.
  Game-state reconciliation and licence/update behavior are otherwise retained.
- System-bar/cutout insets pad the page host and capsule once. The persistent
  capsule is also the Snackbar anchor; an offscreen game card cannot anchor one.
- With Remove animations enabled, tab taps switch immediately. Finger drags
  still track the touch and show the two adjacent pages.
- Hidden-page actions are excluded from accessibility traversal. Both page roots
  remain visible and opaque for partial-swipe rendering.

Implementation: `ui/HomeSettingsPager`, `ui/HomeSettingsNavigation`,
`ui/CapsuleNavigation`, `layout/{activity_main,page_home,page_settings}.xml`.
AndroidX ViewPager 1.0.0 supports the existing minSdk 21; no SDK, Material,
AppCompat, Fragment or native-library upgrade was introduced. JUnit/Robolectric
are test-only dependencies.

## Compact dashboard UI

- Compact, right-aligned expiry at the top: two useful units (`29d 23h`,
  `23h 59m`, or `59m 58s`). Licence and panel/server statuses sit below it,
  outside game cards. The exact formatted IST expiry remains in Settings and
  the summary's accessibility description. There is no standalone Home expiry
  card. Header text can wrap and follows the user's font scale.
- Each game card contains its supplied logo, name, installed game version,
  short readiness/OBB state and its own action, progress and feedback. Detailed
  library metadata is in Settings, not repeated as filenames on Home.
- **Ready** now requires readable OBB source files as well as the game's own
  library, access and container activation. A read-only background inspection
  uses the copier's existing package-specific lookup; missing files and blocked
  access are different states. The actual copy/verification and launch gates
  are retained. A stale inspection cannot override a newer request/copy result.
- Unavailable Air has only **Coming soon** in its disabled button; the rejected
  explanatory paragraph and duplicate coming-soon state are removed. Its own
  usable `libbgmilite.so` is still mandatory; shared version metadata cannot
  enable it.
- Announcement styling uses the existing blue/dark palette. Settings has
  quieter sentence-case section labels, more consistent rows and a centered
  red Sign out; Help and confirmation/Forget behavior are retained.
- Home, Settings and the capsule use bundled **static Manrope 400/500/600**
  resources through AppCompat on Android 21+. No variable font support, font
  provider/network access or new runtime dependency is required. The follow-up
  extends that family to Login/Splash; licence/support monospace text stays.
  Font provenance, reproducible preparation and OFL are in `assets/licenses/`.

### Swipe regression and evidence limits

[Reproduction run 45420146397](https://github.com/masterace572-prog/AnoyMundo/actions/runs/45420146397)
failed the new `diagonalSwipeFromScrolledSettingsContentReachesHome` regression
against the prior handler on host Android 21 and 28: the page stayed Settings.
The old perfectly level swipe/fake-drag tests had missed this path. This host
reproduction does not prove that it was the only cause on the user's phone.

The pager now decides the predominant axis from **original MotionEvents** before
a child can disallow interception. Horizontal streams use ViewPager's own drag,
velocity and settle logic for page-origin strokes (not alpha transitions).
The follow-up adds the standard fake-drag API specifically for the external
capsule selector, without replacing the original-event page handler.
Vertical streams stay vertical through release; taking a horizontal stream
cancels the child's pressed/scroll state, so dragging a Launch button cannot
also launch. Both pages remain opaque and adjacent during a partial swipe.
New host cases exercise root dispatch over a scrolled Settings page and game
buttons, vertical scrolling, cancellation, button taps and real in-flight
geometry in addition to the retained tap/scroll/accessibility/lifecycle tests.
Physical-device gestures, font rasterization, keyboard/TalkBack operation and
native game behavior still require device QA.

## Updating from the last user release (2.4.2)

The user upgrade is **2.4.2 / code 20 → 2.4.3 / code 21**. Earlier code-21 APKs
were internal test builds only, not releases distributed to users.

- Install the matching Anoy or KennyPvt APK over the existing app. The original
  signing key and package IDs are preserved. Do not uninstall or clear app/container data.
- The in-app updater can offer code 21 to code 20 once the Owner selects this
  APK in the matching channel; the strictly-greater-code check is unchanged.
- Publishing to GitHub does not select an APK or a game-library release in the
  panel. Air stays Coming soon until its own usable file is installed from the
  correct Owner's library bundle.

Internal testers already on code 21 can install the matching APK manually in
place. That technical note does not describe the user upgrade path.

## Verification

Local SDK-free checks executed during preparation:

- 15 navigation/presentation wiring checks, 14 UI gates and 20 branding gates.
- 15 runtime/transport, 15 contained-web-login and 17 dual-game JVM groups.
- 7 offline host-runtime preparation fixtures and 7 frame-parser fixtures
  (the parser fixtures are not phone frame-time measurements).
- 4 release-note/CLI fixtures, including normal 2.4.2 upgrade wording and
  refusing to clobber a release that belongs to a different source commit.
- Workflow YAML/shell syntax and whitespace checks.

CI builds and lints **both release variants**, executes actual Android view
inflation/geometry/tap/touch-swipe tests for both brands on host Android 21/28,
inspects compiled APK versions, permissions, callback manifests and artwork,
verifies native alignment, and compares each APK signer to the original keystore
certificate through the SDK's structured `ApkVerifier` API (not console labels).
A build-only run precedes any removal of the old release.
Its explicit backup mode archives and SHA-256-verifies the old 2.4.3 assets and
release/tag metadata in a retained Actions artifact; it does not delete anything.
Use manual `publish_release=false, backup_replaced_243=true`, or a branch push
whose commit message contains `[verify-2.4.3]`. That push marker also disables
publication, allowing verification when manual workflow dispatch is not permitted.

### Historical internal Home-refinement acceptance — before Login/Splash

[Build-only run 45422161757](https://github.com/masterace572-prog/AnoyMundo/actions/runs/45422161757)
passed on source **`aa28b2b07f5645132d1434f55bd441ac939f9fa5`**; publication was
explicitly skipped. Build job: **138544628846**.

- **112 host Android checks passed**: 20 UI tests and 8 read-only OBB tests ×
  Android 21/28 × Anoy/KennyPvt. CI enforces each suite/brand's presence and no
  failures, errors or skips. This includes the previously failing diagonal
  regression, real button-origin/vertical/cancelled gestures and font loading.
- Both release variants assembled and linted, with compiled **2.4.3 (21)**,
  **minSdk 21 / targetSdk 28**, correct identities/branding/permissions and
  callback manifests, exact Kenny artwork and 16 KiB loader-native alignment.
- Both new APKs **and both archived current APKs** verified against the original
  certificate through the structured SDK verifier. SHA-256:
  `77f05d53ce8bf1855caef38ce87f13a8bb2b1b2cdd2d48da9d3ba897eac4549e`.
- SHA-256-verified **then-current release** backup, artifact **14934004032**,
  `prior-release-2.4.3-21-backup`, retained for **90 days**. It archives release
  **456878910**, source/tag **9ae21239f12278ffc895d3b18b0fe679865ace49**, its two
  APKs, checksums and exact release/tag metadata. This is distinct from the
  historical backup below. The guard checks exact ID/source/asset sizes/digests
  before downloading and validates the downloaded bytes before retention.
- Both signed APK artifacts, navigation/OBB test reports and lint reports are
  retained on the build-only run. They were verified by CI; no local APK/report
  download or physical-device test is claimed.

Application code, resources, fonts, manifests, dependencies, branding and signer
remain byte-identical to this accepted source during publication preparation.
Only documentation, the non-executed screenshot harness's retired ID/bounds
checks, and release-note/test-summary wording change. The publication pipeline
repeats all APK gates before uploading either APK. All four older releases and
their tag objects are guarded separately before/after the targeted replacement.

### Historical build-only acceptance — previous iteration only

These results predate the user's swipe report and the new diagonal regression.
They are retained as history, not claimed as current UI/device acceptance.

[Run 45412360296](https://github.com/masterace572-prog/AnoyMundo/actions/runs/45412360296)
passed on source **`a35e20dcd7ad156a45576add756408da310f5b56`**, with publication
explicitly skipped:

- **44/44 host Android view tests**: 11 tests × Android 21/28 × Anoy/KennyPvt;
  no failures, errors or skipped tests. Includes actual touch swipes, half-page
  geometry, capsule bounds, tap animations, accessibility, retained scroll and
  recreation. The test host avoids native game/session initialization.
- Both release APKs assembled and linted; compiled **2.4.3 (21)**,
  **minSdk 21 / targetSdk 28**, correct package/branding and permissions.
- Callback manifests, Kenny artwork pixels and each loader's 16 KiB native
  alignment checks passed.
- Both new APKs **and both archived old 2.4.3 APKs** cryptographically verified
  against the exact original public certificate. Its SHA-256 is
  `77f05d53ce8bf1855caef38ce87f13a8bb2b1b2cdd2d48da9d3ba897eac4549e`.
- Verified prior-release archive **`prior-release-2.4.3-21-backup`**, artifact
  **14928877389**, retained for 90 days on that run. Both APK artifacts, Android
  navigation test reports and lint reports are also available there.
- Fresh release/tag comparison confirmed the four releases through 2.4.2 and
  their exact tag targets were unchanged before the one-time replacement.

For that previous publication, only the acceptance record changed between
verified source a35e20d and publication 9ae2123. The UI refinement above changes
application code/resources and has its own full acceptance run recorded above.

```sh
cd xlox
python3 -B scripts/ui_quality_gate.py
python3 -B scripts/check_branding.py
python3 -B scripts/test_runtime_fixes.py
# With a full JDK + Android SDK/NDK (optional verified offline host-runtime cache):
python3 -B scripts/prepare_robolectric.py --dir ../.cache/xlox-robolectric
export XLOX_ROBOLECTRIC_DIR="$(cd ../.cache/xlox-robolectric && pwd)"
./gradlew :app:testAnoyDebugUnitTest :app:testKennyDebugUnitTest \
  :app:assembleAnoyRelease :app:assembleKennyRelease \
  :app:lintAnoyRelease :app:lintKennyRelease --no-daemon
```

The active follow-up evidence is ignored under `../qa-logs/auth-splash-nav-2.4.3/`.
Completed Home-refinement evidence is under `../qa-logs/ui-polish-2.4.3/`.
The prior publication evidence remains in `../qa-logs/release-2.4.3/`; Air feature
evidence remains separately preserved under `../qa-logs/bgmiair/`. The inherited
emulator screenshot/install harness has not been run for this refinement.
CI prepares pinned Robolectric 4.14.1/i7 Android jars sequentially, checks their
published SHA-512 and framework entries, then runs the tests offline. The Google
Maven Central mirror avoids Central's per-test 429 responses; a canonical Central
fallback is bounded. These cached host runtimes are never packaged in an APK.
Seven offline downloader/cache integrity regressions also run before preparation.

A host Android view test is not a physical-device game test. Real arm64 game
launch, Air asset delivery/provider authentication, native payload compatibility
and simultaneous-game memory/thermal behavior still need authorized device QA.
The existing contained BGMI web-login candidate is included, not certified as a
phone-validated provider fix.
