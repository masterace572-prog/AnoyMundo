# Liquid — isolated Android UI redesign plan

## Goal and boundaries

Build a polished, native Android glass-inspired redesign in a new `liquid/`
project. Keep the existing `xlox/` project byte-for-byte unchanged. This is a UI
fork, not another Mundo replacement or game-login investigation.

Baseline: `393e759ecff598d44c5bf5e2f0c8454f06fdb3bd`. A SHA256 inventory was
recorded before any implementation. The original Mundo AAR is unchanged:
`368293d93076aea5db252d307614ba10a23bae84eb298830e6dab42aa3f90ba0`.

Deliver both existing brands as optional in-place UI previews. Keep package and
signing identities unchanged to avoid unvalidated JNI/container package changes.
Use 2.5.0-liquid / code 21, allowing an in-place return to retained 2.4.3/code 21.
Do not uninstall or clear data; Android normally refuses a downgrade to code 20.
Never change live panel release selections for this work.

## Research findings

- Apple's guidance treats glass as a distinct floating control/navigation layer,
  with restraint, selective tinting and strong text legibility—not transparent
  glass nested over more glass. Use those principles, not a pixel-for-pixel claim
  of running Apple's proprietary material on Android.
  [1](https://developer.apple.com/videos/play/wwdc2025/219/)
- Material 3 Expressive provides useful hierarchy, emphasized typography,
  component grouping and coherent motion. Adopt the design principles without
  rewriting this existing Java/XML app into an unrelated framework.
  [1](https://m3.material.io/get-started)
- Android's accelerated View blur uses RenderEffect on API 31+. Older versions
  need an intentional fallback; do not blur the text/children of a control and
  mistake that for backdrop blur. Do not introduce deprecated RenderScript.
  [3](https://developer.android.com/guide/topics/renderscript/migrate)
- Android recommends scalable type, 4.5:1 text contrast, 3:1 non-text contrast,
  and at least 48dp interactive targets. These remain acceptance criteria with
  effects disabled and enlarged system fonts.
  [4](https://developer.android.com/design/ui/mobile/guides/foundations/accessibility)
- Lucide specifies consistent 24x24 geometry with 2px rounded strokes. Use one
  coherent vector family, keeping the bundled ISC attribution.
  [2](https://lucide.dev/contribute/icons/specification)

The existing site already supplies a useful brand language: Inter, JetBrains
Mono for technical values, a blue action accent, quiet grouped rows and simple
line icons. The Android app already bundles those licensed fonts, so improve
hierarchy/spacing/weights rather than add arbitrary font downloads.

## Visual system

**Direction: Obsidian / ice glass.** A near-black ink canvas with restrained cool
ambient light, crisp white type, pale blue-gray secondary text and one blue
primary-action tint. Keep the existing Anoy/Kenny logos and per-brand contacts.
No rainbow/neon outlines, oversized shadows, random icon families, or excessive
explanatory text.

- Glass: actual backdrop treatment where supported, a deliberate frosted
  fallback elsewhere, fine light-catching edges, restrained depth and clipped
  rounded corners. Content remains stable and readable beneath controls.
- Surfaces: 24–28dp rounded content groups; floating capsules for toolbar/action
  controls. No glass-on-glass nesting or transparency behind essential small text.
- Spacing: 4/8dp rhythm, 20–24dp screen gutters, consistent section spacing.
- Type: refined Inter hierarchy; approximately 28–32sp headlines, 16sp body,
  14sp supporting copy. JetBrains Mono only for keys, versions and numeric data.
- Controls: approximately 56dp primary buttons and 56–60dp key fields; 48dp icon
  targets. Clear default, pressed, focused, disabled, loading and error states.
- Icons: consistent vector stroke/optical size; play, stop, settings, help, key,
  clipboard, eye, download, refresh, chevron and status glyphs.
- Motion: restrained press compression/highlight, short screen transitions and
  coherent sheet movement. Honor system reduced motion; stop animations when
  the app is backgrounded. Never animate blur continuously over a running game.

## Screen-by-screen scope

### 1. Splash

A single recognizable brand mark, a short light/reveal gesture and a smooth
handoff into the next screen. Keep real update-check status separate from the
intro animation. No fake progress and no new artificial wait before sign-in.
Use a static/short-fade fallback when animations are disabled.

### 2. Licence sign-in

Clear brand/header and headline; one refined key-entry group with reveal/paste
controls; persistent field label and reserved error space; one dominant Login
action; quiet Buy one link. Keyboard, long keys, copy/paste, offline/VPN errors
and slow-verification states must remain usable on a small phone.

### 3. Home / game launcher

A cleaner greeting and floating utility controls, an immediately readable
licence-expiry summary, a better BGMI card with its existing artwork and real
library/game/server state, and one dominant Launch/Stop action. Panel notices
remain verbatim and only appear when present. Loading/preparing/failed/running
states use the same component language without shifting the whole page.

### 4. Settings

Readable grouped rows for Licence, Updates, Device/Support and Account. Align
icons, labels, values and chevrons. Keep reveal/copy/re-download/sign-out
behavior intact. Destructive actions retain clear labels and confirmation.

### 5. APK updates, library updates and every app-owned sheet

Restyle APK update content, release notes, download/verification progress,
mandatory/optional controls, retry/error states, library gates, permissions,
Help, support-info preview, re-download and sign-out sheets. Keep cancellation,
verification and required-update rules unchanged.

### 6. Existing in-app web-login shell

Only the app-owned toolbar/loading/error appearance may inherit the new theme.
Do not change OAuth routing, provider behavior, third-party web content or the
underlying game/SDK. The cancelled login task remains cancelled.

## Implementation architecture

1. Copy the current Android project into `liquid/`, excluding build/cache/local
   environment files. Do not duplicate signing secrets. Keep native namespace
   and JNI contracts compatible, and reuse the original Mundo dependency.
2. Introduce a small shared Liquid component/token layer and apply it across
   the copied layouts and UI helpers. Preserve functional resource IDs/listeners
   and avoid rewriting licensing, installation, game launch, OBB handling or
   download transport for a visual redesign.
3. Keep minSdk 21 / targetSdk 28 / compileSdk 36 and arm64 compatibility unless a
   separately approved change is actually necessary. No SDK37 experiment.
4. Build through a dedicated Liquid workflow, not the normal Android publisher.
   Publish clearly named preview APK(s) as a non-latest prerelease, never as a
   replacement for the panel's active APK choice.
5. If a separate package is chosen, its data/update policy must be explicitly
   isolated from the production package; do not offer an incompatible normal
   APK as an automatic update. If an in-place build is chosen, preserve signing,
   stored preferences and a practical rollback path to a retained stable APK.

## Requested cleanup

The live panel currently selects Anoy 2.4.2 / code 20. Preserve that release and
all stable rollback releases. Remove only the abandoned, unselected test inputs
and experimental release downloads:

- `test-inputs/` (the abandoned LV10 input and preparation utility).
- `.github/workflows/anoy-mundo-test.yml` (depends on those retired inputs).
- GitHub prerelease `anoy-mundo-lv10-sdk36-test-f1132b2d`.
- GitHub prerelease `loaders-v2.4.4-webtest-21` (cancelled login experiment).

Recheck live APK URLs immediately before deletion. Do not remove the normal
Mundo AAR, the original Android source, active releases, stable APKs, licence
keys, device bindings, referrals or Supabase library/history data. Keep Git
source history; deleting a release does not require rewriting repository history.

## Acceptance / delivery

- Hash comparison proves every original `xlox/` file is unchanged.
- Native/core/archive/transport regression checks, Android resource compilation,
  minified release builds, lint, merged manifests, permissions and signatures.
- Actual Android screenshots across splash, sign-in, home, settings, updates and
  sheets; keyboard open/closed; long/error/loading states; 360dp and wider
  layouts; enlarged fonts; animations enabled/disabled; older-API fallback.
- UI-only emulator fixtures are clearly separated from production builds and
  never use or reveal live credentials. Emulator UI results are not presented
  as proof of a successful real-phone game login.
- Inspect and refine actual rendered screens before calling the design finished.
  Deliver the APK(s), checksums, source path and a short account of completed
  checks and any remaining real-device verification.

Status: source `71f8bba` passed the signed builds, lint and both emulator jobs in
[run 44921824876](https://github.com/masterace572-prog/AnoyMundo/actions/runs/44921824876):
API 33 completed 57 captures / 304 checks, API 23 completed 56 / 299. Complete
selected native frames were decoded and reviewed. That review prompted final
refinements to the API-23 Back glyph, 200% home heading, idle APK footer, tablet
surface widths and focused-button contrast. These refinements require a fresh
successful build and both emulator jobs before delivery. See
[`LIQUID_UI_QA.md`](LIQUID_UI_QA.md) for evidence and honest device limitations.
Authorized obsolete-test cleanup is complete; see
[`OBSOLETE_TEST_CLEANUP.md`](OBSOLETE_TEST_CLEANUP.md). Stable releases and the
live panel selection are unchanged.
