# Anoy Loader 2.1 — UI redesign, before / after

Every image here was captured by CI (`.github/workflows/android-ui-qa.yml` →
`xlox/scripts/ui_screenshots.sh`) on an API 33 Pixel 5 emulator from the real
debug and minified release APKs. **Before** is 2.0.x at commit `4732395` and
**after** is 2.1.0. Nothing is mocked up. States that can't be reached on an
emulator without a real licence or a game install (for example, expired or
launching) are rendered from sample data through debug-only intent extras.
Those extras are compiled out of release builds (`BuildConfig.DEBUG`).

Animations are **off** for every still. That is the reduced-motion path: Lottie
shows its final frame and fades are instant. Shots prefixed `anim_` were taken
with animations **on**, and `after/anim_splash.mp4` is a screen recording of
the cold start.

## Before / after pairs

| Screen | Before | After |
|---|---|---|
| Splash | ![](before/splash.png) | ![](after/splash.png) |
| Update required | ![](before/update_required.png) | ![](after/live_gate_required.png) |
| Updating | ![](before/update_downloading.png) | ![](after/gate_downloading.png) |
| Sign in | ![](before/login.png) | ![](after/login.png) |
| Keyboard | ![](before/login_keyboard.png) | ![](after/login_keyboard.png) |
| Field error | ![](before/login_error.png) | ![](after/login_error_malformed.png) |
| Key visible | ![](before/login_key_visible.png) | ![](after/login_key_visible.png) |
| Home | ![](before/home.png) | ![](after/home.png) |
| Home, scrolled | ![](before/home_scrolled.png) | ![](after/home_scrolled.png) |
| Large text (1.3 → 2.0) | ![](before/login_font130.png) | ![](after/login_font20.png) |
| Large text, home | ![](before/home_font130.png) | ![](after/home_font20.png) |
| Narrow (320 → 360 dp) | ![](before/login_320.png) | ![](after/login_360.png) |
| Narrow, home | ![](before/home_320.png) | ![](after/home_360.png) |
| Release build | ![](before/release_login.png) | ![](after/release_login.png) |

## Every state (after)

**Splash.** Lottie frames 0 → 0.88 are `splash_f000` … `splash_f088`. The held
final frame with the wordmark is `splash`. The live cold start is
`splash_live_cold`, and the animations-on captures are `anim_splash_t05` /
`t10` / `t15` plus `anim_splash.mp4`.

**Update gate (bottom sheet).**
- Real network walkthrough: `live_gate_required` → `live_gate_downloading` →
  passes to sign in.
- Airplane mode: `gate_check_failed_live`.
- Every state: `gate_checking`, `gate_required`, `gate_downloading`,
  `gate_downloading_unknown` (indeterminate), `gate_installing`, `gate_done`,
  `gate_check_failed`, `gate_download_failed`.
- Animations on: `anim_gate_checking`, `anim_gate_done`.

**Sign in.**
- Field and keyboard: `login`, `login_keyboard`, `login_error_empty`,
  `login_error_malformed`, `login_key_visible`.
- Real airplane mode: `login_offline`.
- Server responses: `login_loading`, `login_wrong`, `login_maintenance`,
  `login_server`, `login_vpn`, `login_success`, then `login_ok_home`.
- After sign out: `signout_done`.

**Home.**
- `home` (panel note present, licence with progress), `home_scrolled`.
- Panel note: `home_note_empty` (card hidden), `home_note_custom` (emoji
  stripped, ALL-CAPS → sentence case).
- Licence: `home_expired`, `home_under_day` (countdown with seconds).
- Status: `home_loading` (skeletons), `home_server_warning`.
- Actions: `home_preparing`, `home_launching`, `home_error` (game files not
  found), `home_snackbar`, `home_signout_sheet`.
- "Game not installed" appears in every home shot, because BGMI isn't on the
  emulator.

**Accessibility and sizes.**
- Font scale 0.85 / 2.0: `login_font085`, `home_font085`,
  `home_font085_scrolled`, `gate_font085`, `login_font20`, `home_font20`,
  `home_font20_scrolled`, `gate_font20`.
- 360dp phone: `login_360`, `home_360`, `gate_360`.
- Tablet: `login_tablet`, `home_tablet`, `gate_tablet`.
- TalkBack reads the licence card as a single sentence. The CI trace
  (`qa-logs/emulator-trace.txt`) records it, e.g. *"Licence active, 29 days
  21 hours left, expires 7 December 2026"*.

## What changed

See [`xlox/CHANGELOG.md`](../../CHANGELOG.md). In short:
- Material 3 dark with every role mapped to the black / blue tokens.
- An authored Lottie mark, a seamless core-splashscreen handoff and a sheet-style
  update gate.
- Status shown as icon + text chips, a pinned action pill and skeletons.
- Snackbar replaces Toast, plus edge-to-edge layout and haptics.
- Purple, teal, emoji and default spinners are gone.

## Checks

- `scripts/ui_quality_gate.py`: 13 static gates (tokens, emoji, Toast, spinner,
  touch targets, resources, Lottie budget, licences).
- `assembleDebug`, `assembleRelease` (minify + shrinkResources) and
  `lintDebug` (errors fail the job).
- The emulator walkthrough fails CI on a missing or blank shot, on identical
  frames for different states, if the app isn't in front, on a crash in any
  logcat, if the update gate never passes, or if the licence card isn't read as
  one sentence.
