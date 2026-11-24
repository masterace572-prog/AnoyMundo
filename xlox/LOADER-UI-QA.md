# Login / Splash / navigation follow-up — loader UI only

This follow-up keeps the accepted Home direction and extends it to Login and
Splash. It does **not** change the games, native payloads, game FPS settings,
AARs, permission/library/licence gates, signing, flavors, version or live panel
selections. Version remains **2.4.3 (21)**. The verification builds recorded here
did not publish or replace a release. The subsequent owner-approved publication
is a separate guarded replacement of internal GitHub test artifacts; see
[release scope](RELEASE-2.4.3.md). **2.4.2 (20) is the last user-distributed release;
earlier 2.4.3 (21) builds were internal tests only.**

## Changed presentation and interaction

- Login shares Home's bundled static Manrope, 20dp gutters, black background and
  blue primary action. The monospace field is a single, horizontally scrolling
  line; its two 48dp controls are an eye and a clipboard, not a Paste text button.
- The eye describes the **next action**: eye / “Show licence key” when masked;
  crossed eye / “Hide licence key” when visible. Selected blue tint and an
  accessibility state description also communicate visibility. Only the text
  transformation changes, so the key, selection range, typeface and keyboard
  input type stay intact. Backgrounding masks it without clearing the key.
- Splash uses the same Manrope, a smaller fitted brand line and a blue final Hax.
  Loader → Hax is a short fade-through with a 6dp opposing drift, not two strongly
  superimposed words. Its measured slot stays fixed during animation; only alpha
  and translation change. Text layers are individual and temporary. The extra
  perpetual name-breathing animation is removed; the existing status arc remains.
  Reduced motion and async update/failure/retry gates are retained.
- The expiry label/value remain end-aligned. Active and Safe share a baseline
  below them when they fit, wrapping into end-aligned rows for long panel text or
  larger fonts. Full expiry and the licence summary accessibility text remain.
- A **page swipe** left goes Home → Settings, right goes Settings → Home. The
  capsule additionally supports **dragging its blue selector** towards the desired
  tab: right to Settings, left to Home. ViewPager's fake-drag API maps one tab
  pitch to one page; page-origin strokes still use original MotionEvents and its
  regular drag engine. Android cancels label/button presses when dragging takes
  over. Cancellation returns the capsule gesture to its starting page.
- Only the blue decoration is inset by **2dp on each edge**. Label hit targets
  remain at least 48dp. Indicator travel is the full tab pitch, not its narrower
  visual width. Both pages remain opaque and retained.
- Announcement cards share the game cards' background and no-stroke treatment.
  Their heading and megaphone stay blue; the server-fetched message is explicitly
  white. Fetching, sanitization, empty-note visibility and accessibility text are
  unchanged. The game-update warning card keeps its separate warning colors.
- Unchanged status renders reuse their text and tinted dot; redundant text/color
  updates are skipped. No file/network work is added to gesture or frame paths.

## Regression evidence

The owner still could not slide navigation in the previous internal test build. The new
`draggingTheBottomSelectorSwitchesBothWaysWithoutTabTaps` regression reproduced
one concrete missing path in [run 45428597782](https://github.com/masterace572-prog/AnoyMundo/actions/runs/45428597782),
source `f05e723f847e1bc8a05f2ef35ea032b34109cb3b`: root-dispatched capsule drags
stayed on Home on host Android 21 and 28. The older page-swipe checks passed;
they had not exercised dragging the bottom control. This does not prove that it
was the only cause on the user's physical phone.

The expanded host suite has 28 navigation/presentation, 9 Login/Splash and 8 read-only OBB
cases per Android version and brand: **180 checks total** across API 21/28 and
Anoy/KennyPvt. It covers root-dispatched gestures, partial tracking, cancellation,
vertical scroll/button safety, both tap/drag directions, 2dp selector geometry,
header wrapping, icon hit targets, long keys/large fonts, visibility semantics,
font/selection/IME stability, status reuse, Splash frame properties and reduced
motion. The test host does not initialize a game or authenticate a live key.
Run the actual Android tests with a full JDK + Android SDK/NDK:

```sh
cd xlox
python3 -B scripts/ui_quality_gate.py
python3 -B scripts/check_branding.py
python3 -B scripts/test_runtime_fixes.py
python3 -B scripts/test_profile_loader_ui.py  # parser fixtures, NOT a measurement
python3 -B scripts/prepare_robolectric.py --dir ../.cache/xlox-robolectric
export XLOX_ROBOLECTRIC_DIR="$(cd ../.cache/xlox-robolectric && pwd)"
./gradlew :app:testAnoyDebugUnitTest :app:testKennyDebugUnitTest \
  :app:assembleAnoyRelease :app:assembleKennyRelease \
  :app:lintAnoyRelease :app:lintKennyRelease --no-daemon
```

A `[verify-2.4.3]` push runs both brands' tests/build/lint/compiled APK/signature
gates without publication. After the first user-facing release, its backup guard
now pins **457073966**, tag `loaders-v2.4.3-21`, source **f8f5884** and that
release's exact APK digests. It still refuses an unreviewed source/asset change.
Before publication it pinned internal test release **456953450/source96356a5**;
backup **14941666853** archives that predecessor. Older backup **14934004032**
archives **456878910** instead. These are distinct archives, not interchangeable.
The UI verification itself did not delete or replace releases.

## First user-facing publication completed

[Release 457073966](https://github.com/masterace572-prog/AnoyMundo/releases/tag/loaders-v2.4.3-21),
source **`f8f58847a8e9eebac008913700cd0ff343122016`** /
[run 45443061179](https://github.com/masterace572-prog/AnoyMundo/actions/runs/45443061179),
passed **180/180** host checks and both release-build/lint/compiled/signature gates.
Its app/test inputs are unchanged from accepted `cfc442e`. Public notes describe
**2.4.2 (20) → 2.4.3 (21)**; they do not assume an existing user code-21 population.
The superseded publication attempt 45442509778 was cancelled by branch concurrency
before publishing, so the incorrect same-version update wording was not released.

Only internal test release 456953450 and its same-version tag were replaced.
The four older releases, all their assets/metadata (except download counts) and
exact tag refs were verified unchanged. No live panel/library/APK selection was
changed. Full release checksums and original-certificate identity are recorded
in [release scope](RELEASE-2.4.3.md).

## Final announcement build accepted for publication

[Run 45436589864](https://github.com/masterace572-prog/AnoyMundo/actions/runs/45436589864),
source **`cfc442ea28fcecce8101548274142359d8fb2e29`**, passed **180/180** host Android
checks across both brands/API 21/28, both release builds/lints and all compiled
APK/signature gates. Its announcement matches the game-card surface, keeps the
heading/icon blue and makes the server message white.

The current-release backup **14941666853** archives **456953450/source96356a5**.
CI verified the downloaded asset hashes and both original APK signatures before
retaining it for 90 days. This is distinct from the older 456878910 archive.
The owner subsequently approved replacing only 2.4.3 with the verified inputs;
that publication does not change live panel selections or certify device FPS.

## Earlier build-only verification (before the announcement color follow-up)

[Run 45432509435](https://github.com/masterace572-prog/AnoyMundo/actions/runs/45432509435),
source **`5e15155fb476402c4eaf092762641e583ef797c8`**, passed. Build job
**138581657343**, panel checks **138581370733**:

- **176/176** host Android checks; zero failures, errors or skips. Both brands'
  suites were present and enforced, including the previously failing capsule
  gesture and the explicit large-status wrap case. The wrap fixture supplies
  intrinsic width minima because host legacy glyph metrics are not phone metrics.
- Both release APKs assembled/linted and passed compiled version, package/brand,
  permission, callback, Kenny artwork and native-alignment gates. Version stays
  **2.4.3 (21), minSdk 21 / targetSdk 28 / compileSdk 36**.
- Both new APKs and both archived current-release APKs match the original signer
  SHA-256 **77f05d53ce8bf1855caef38ce87f13a8bb2b1b2cdd2d48da9d3ba897eac4549e**.
- Signed verification bundles: **Anoy-APK** artifact **14937984704**;
  **KennyPvt-APK** artifact **14937714878**. Each is a ZIP containing its APK,
  retained through **2026-12-08**. Artifact ZIP sizes are not APK sizes/digests.
- Test reports **14938704406** and lint reports **14938514487** are on the run.
  The verified backup **14939621572** archives current release **456953450** /
  source **96356a5**, with its downloaded asset hashes checked and both original
  signatures verified. It is retained for 90 days, through **2027-02-22**.
- Publication was explicitly **skipped**. A read-only comparison confirmed all
  five published releases, their assets/metadata (apart from download counters)
  and exact tag refs unchanged; stable/latest remains **456953450**.

No local APK installation, emulator screenshot run, phone gesture test or device
frame-time measurement is claimed by these build/host results.

## Device acceptance still required

Use the **original, matching-brand APK** from the relevant verification run.
Record its SHA-256 and source commit: version 2.4.3/code 21 alone cannot identify
which UI iteration is installed. For an internal tester already on code 21, update in
place with the original signer. **Never uninstall or clear app/container data.**

- Swipe both pages left/right from blank areas, game cards/buttons and scrolled
  Settings. Hold at half travel: both pages should be visible side by side.
- Drag the selected capsule segment towards the other tab in both directions;
  verify pixels follow the finger before release. Cancel a partial gesture,
  check rapid repeated gestures, and then tap each label. No game may launch
  from a swipe. Vertical scrolling and its retained position must still work.
- Check expiry/Active/Safe and warning/unknown/expired/long server states at
  normal and 2× font scale, on a narrow phone, landscape and with system insets.
  No collisions or clipped controls; the selector is only slightly inset.
- Login: paste empty/valid/long keys; edit and select a range; repeatedly show/
  hide; open/close the keyboard and return from background. Confirm the action
  icons, TalkBack labels/state, selection and key contents. Check inline key
  errors versus neutral connection/retry notices. Mandatory gates still block
  sign-in exactly as before.
- Splash: cold launch, slow update check, failure/retry and reduced motion. The
  system launch mark must still match each brand; no heavy double-word overlap,
  clipping, background hand-off surprises or extra navigation after destruction.
- Check Back → Home, Help, red Sign out/confirmation/Forget, masked Settings key,
  per-game readiness/progress and independent launch/stop behavior. Device game
  tests require the proper libraries/account; host UI checks are not that proof.

## 60Hz frame timing — target, not a host-test claim

No device frame timing is established by the structural/host tests. A 60fps
Lottie source, short duration, hardware layer or screenshot is **not** evidence
that the app meets a 16.67ms frame budget on the user's phone. The inherited
screenshot workflow disables animations and is not a performance benchmark.

For a non-destructive, opt-in **interactive loader** capture on Android 23+:

```sh
cd xlox
# With the matching original APK already installed and Home in the foreground:
python3 -B scripts/profile_loader_ui.py --package com.xlox --seconds 15 \
  --output ../qa-logs/loader-frames-anoy
# KennyPvt: use --package com.kennypvt and a separate output directory.
```

During capture manually exercise page and capsule drags; stay inside the loader
and do not tap Launch. The tool checks foreground loader components before/after,
resets only gfxinfo counters, and saves raw stats plus completion-latency p50/
p90/p95/p99 and frames over the 16.67ms target. It ignores flagged, incomplete,
duplicate, virtual-game and web-provider rows. Fewer than 60 complete frames is
reported as **insufficient_frames**, not a success. It never installs/re-signs an
APK, changes animation settings, clears data, calls the panel or launches a game.

These are rendered-frame latencies, not average FPS or a cross-device guarantee.
Compare repeated captures of the old/new original APKs on the same device,
refresh rate, thermal/power conditions and workload. This interactive capture is
not a cold-start Splash benchmark; use a device FrameTimeline/Perfetto trace for
that transition. No such physical-device trace has been collected here.
