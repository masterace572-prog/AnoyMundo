# Liquid — navigation-only Android experiment

A separate, buildable copy of the restored **2.4.3 (21)** Android project in
`../xlox/`. **The working loader is untouched.** Only the bottom Home/Settings
navigation is being changed, for both Anoy and KennyPvt. Inherited changelogs and
release documents describe the baseline, not device acceptance of this experiment.

## What the navigation does

- **Clear floating glass:** the black footer and solid blue selection are gone.
  Unchanged Home/Settings pages remain directly visible through the translucent
  capsule; the moving selector supplies the stronger glass refraction.
- **A slightly frosted moving lens:** a bounded, downsampled in-memory crop of
  the actual sibling pager is softened and locally magnified underneath the
  selected segment. This is real backdrop rendering, not a gradient labelled blur.
- **Water-like interaction:** the lens stays exactly aligned with the finger/page
  offset while its trailing edge stretches, its surface subtly squashes and a
  damped shape spring settles after taps/drags. There is no permanent wobble.
- **Reflection:** thin Fresnel-like light/dark rims, a moving meniscus, a soft
  cool edge and touch-position specular light. Text is composited afterwards and
  is never blurred or warped. No opaque tinted fallback.
- **Still usable:** tap either label, drag the capsule, or swipe the page. Existing
  cancel/multi-touch/vertical-gesture safety, selected-state accessibility and
  keyboard focus remain. Targets stay at least 48dp. Reduced motion disables the
  water springs. Scroll clearance keeps final page controls above the glass.

No Home/card/Login/Splash redesign, shader dependency, permission, SDK swap,
licence/game/native change or new live service is introduced. Glass works on
API 21+ without relying on API-31-only window blur/RenderEffect. Rendering is
bounded; on unavailable software sampling it keeps transparent rim/tint optics.
Actual phone smoothness and appearance still need device review.

## Isolation and signing

`BASELINE.json` records SHA-256 for all **322 original project files**. The scope
gate checks that `xlox/` has not changed and that copied non-navigation behavior,
assets, dependencies and permissions still match it. The only Gradle changes are
the project name and referring to the existing signing key instead of duplicating
private material. No local SDK path, secret config, cache or APK was copied.

Application IDs, versions, channels and original signer are deliberately retained;
this is **a separate source project, not a separately installable app identity**.
A signed Liquid APK would manually replace its matching brand in place. Do not
install one over a working loader until you choose to test it. Never uninstall,
clear app/container data, withdraw a library or publish/select this experiment.

Release signing reads the existing `../xlox/app/xlox.jks` by reference, or
`XLOX_STORE_FILE` / ignored `local.properties`. Passwords stay in environment/
Actions secrets. No new key is generated or stored in this copy.

## Build and verification

Open **this `Liquid/` directory** as the Android Studio/Gradle project. It uses the
same JDK 17, Android 36 and pinned NDK 24.0.8215888 as the working baseline.

```sh
cd Liquid
python3 scripts/check_liquid_scope.py
python3 scripts/ui_quality_gate.py
python3 scripts/test_navigation.py
# Requires the JDK and Android SDK/NDK:
./gradlew :app:testAnoyDebugUnitTest :app:testKennyDebugUnitTest \
  :app:assembleAnoyRelease :app:assembleKennyRelease \
  :app:lintAnoyRelease :app:lintKennyRelease --no-daemon
```

The dedicated **Liquid navigation — isolated Android QA** workflow builds only
this copy, has read-only repository permissions, and uploads separately named
APK/test/lint/render artifacts. It cannot publish a release or change panel data.
It retains the baseline gesture/login/OBB tests, adds deterministic spring tests,
API 21/28/35 geometry/lifecycle/clearance tests, and API 28 native-Skia pixel tests.
Pixel tests check real frost, a transparent fallback and live backdrop changes.
Their PNGs include an explicit synthetic fixture plus the copied Home layout, but
they are still host-rendered previews, not a phone screenshot, game, signed-in
licence screen or measurement of physical-device performance.

Until a completed run is recorded, those are configured checks, not claimed
passing results. No physical device, game or live account is exercised here.

## Code map

| File | Role |
|---|---|
| `ui/CapsuleNavigation.java` | Original drag engine + glass lifecycle/interaction; exact selector positioning |
| `ui/LiquidGlassRenderer.java` | Clear shell, refractive frosted lens, asymmetric shape and reflected edge light |
| `ui/LiquidGlassBackdrop.java` | Pager-only crop; at most 12,288 pixels per bitmap, two bitmaps + two scratch arrays; max ~31 Hz sampling, no idle timer |
| `ui/LiquidGlassMotion.java` | Bounded spring deformation and touch pressure; fully settles and respects reduced motion |
| `ui/HomeSettingsNavigation.java` | Existing page/accessibility state + glass binding and idempotent scroll clearance |
| `res/layout/activity_main.xml` | Floating clear navigation over the unchanged pager pages |
| `res/values/colors.xml` | Additional low-alpha optics tokens only; original colors unchanged |

All Java paths above are under `app/src/main/java/com/xlox/`. Screenshot/render
outputs and build caches stay ignored. Production releases and live choices stay
where they were before this experiment.
