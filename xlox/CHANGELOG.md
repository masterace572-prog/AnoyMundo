# Anoy Loader — changelog

## 2.1.0 follow-up — monogram, launch error, home tidy

- **Splash**: new Anoy "A" monogram animation (white A, blue crossbar draws
  through it, mark lifts, "Anoy / LOADER" fades in, ~1.9 s). Replaces the ring /
  notch / dot. The system splash shows the same A, so the handoff is seamless.
  Slow start-ups get a gentle opacity breath, never a spinner.
- **App icon**: the blue square with a key is replaced by the monogram on near
  black. Adaptive and legacy icons, the splash and the Lottie share one geometry
  (`scripts/brand_mark.py`).
- **Launch**: no more false "Couldn't launch the game" after BGMI starts. A
  launch is a success once Android accepts it; a missing intent waits for the
  game to start before reporting; the after-launch UI can't raise the error.
- **Home**: DEVICE fits above the Launch bar on a typical phone; the notice no
  longer leaves an orphan word; long model names stay on one tidy line.

Details and screenshots: `docs/ui-redesign/FOLLOW-UP.md`.

## 2.1.0 (versionCode 6) — UI redesign

Presentation only. Sign-in and licensing logic, the `/connect` contract, JNI,
`Net`, `Downtwo`, `LibManager`, `AppManager`, download / verify / install, the
virtual container, permissions and game features are unchanged. targetSdk 28,
minSdk 21.

### Look and feel
- One token set (`res/values/colors.xml`, `dimens.xml`, `styles.xml`): black
  background, #141416 cards with a #26262A hairline, #1C1C1E tiles, white /
  #8E8E93 / #636366 text, a single blue (#0A84FF) and success / warning / danger
  only for status. The purple and teal tiles are gone, and so is every
  per-colour `bg_tile_*`.
- Type: Inter Regular and Semibold only (Inter Medium removed), with a fixed
  scale (32 / 22 / 17 / 16 / 13). JetBrains Mono is used for the key field,
  versions, sizes and the library name. The countdown uses 40sp tabular figures.
- Icons: Lucide, regenerated as white vectors and tinted in XML. No emoji
  anywhere in the UI. Panel notes have emoji stripped and ALL-CAPS notes are
  converted to sentence case.
- Edge-to-edge on API 21–37 with inset-aware padding. Dark only.
- Motion: 150 / 220 / 320 ms, FastOutSlowIn for enters and LinearOutSlowIn for
  sheets. Spring only on the success check. With animations off (reduced
  motion), Lottie jumps to its last frame and fades are instant.
- Haptics: CONFIRM / REJECT on API 30+, with fallbacks to standard taps.
- Snackbar (raised, 14dp corners, blue action) replaces every Toast.

### Screens
- **Splash:** the core-splashscreen icon is the static first frame of the new
  `anoy_mark` Lottie (ring draws, key notch, collapse to a dot, one blue glint),
  so the handoff is seamless. Then the "Anoy" / "LOADER" wordmark, a 200ms hold
  and a 320ms crossfade with an 8dp drift. No spinner. The version footer sits
  24dp above the nav bar.
- **Sign in:** a 64dp key tile, large title, 56dp field with 44dp show / paste
  icon buttons and a 1.5dp blue focus ring. Errors show a red border, an alert
  icon, a shake and REJECT. The pill button is pinned above the keyboard and
  shows "Signing in…" with an 18dp indicator, then a check with CONFIRM.
  Offline, server, VPN and maintenance errors appear in an inline notice card
  with "Try again". Telegram is a quiet text button.
- **Update gate:** a non-dismissable bottom sheet (28dp corners, no handle, 60%
  scrim) using the same callbacks.
  - Checking: `anoy_sync`.
  - Update required: download tile and a mono size chip.
  - Downloading: 6dp bar with mono progress text; indeterminate when the size
    is unknown. Installing is indeterminate.
  - Done: `anoy_success` with CONFIRM, then auto-dismiss.
  - Failures: a red tile, the reason and "Try again".
- **Home:**
  - Header and panel notice: "Welcome back / Anoy" with a 44dp sign-out button
    and a confirm sheet. The panel note is an amber notice card with a
    fallback message, hidden when there is no note.
  - Licence card: status chip, countdown, "Expires 7 Dec 2026, 11:15 pm", and a
    4dp bar when the key's duration is known. The expired state shows a red
    chip and "Sign in again". TalkBack reads the card as one sentence.
  - Rows and actions: neutral GAME / DEVICE rows with State / Server chips,
    skeletons while loading and a staggered reveal. The pinned action pill
    sits over a fade with a 0.98 press scale and shows
    "Preparing… 42%" / "Launching…".

### Dependencies
| Library | Version | Why this version |
|---|---|---|
| com.google.android.material | 1.13.0 | Newest release with minSdk 21 (1.14 requires 23) |
| com.airbnb.android:lottie | 6.7.1 | minSdk 16; animations use `renderMode=hardware`, stored in `res/raw` |
| androidx.core:core-splashscreen | 1.0.1 | 1.2.x requires minSdk 23 |
| androidx.appcompat | 1.7.0 | Matches Material 1.13 |
| androidx.core | 1.13.1 | core 1.16+ requires AGP 8.6 |

Lottie art is original and authored in-repo by `scripts/lottie_art.py`. Each
file is 60fps and under 60KB (provenance: `app/src/main/assets/licenses/LOTTIE.md`).
Licences for Lucide (ISC), Inter and JetBrains Mono (OFL 1.1) and lottie-android
(Apache 2.0) ship in `app/src/main/assets/licenses/`.

### Copy
Functional strings are unchanged except for the panel-note emoji warning and
ALL-CAPS labels, which are now sentence case (section headers are still shown
in small caps via the style).

### QA
`scripts/ui_quality_gate.py` enforces the brief statically: tokens only, no
purple, emoji, Toast, default spinner or plain Button, ≥ 44dp targets, every
referenced resource exists, and the Lottie budget. The `android-ui-qa` workflow
builds debug and minified release, runs lint and captures every screen and
state on an emulator in `docs/ui-redesign/after/`. Before / after pairs are in
`docs/ui-redesign/README.md`.
