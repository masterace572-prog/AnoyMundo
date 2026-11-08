# Anoy Loader — changelog

## 2.1.0 follow-up — help and access

- **Help on home.** A ? button opens three short tips (launching, files and
  access, renewing) plus Help on Telegram. It shows once by itself on the
  first visit to home, never over a launch or a permission ask.
- **Copy support info.** It previews first, then copies the app, library and
  Android versions, the device model and the current home status. Never the
  licence key, the username or file paths. Nothing is sent automatically.
- **Access chip.** "Access ready" or "Access needs attention" next to Game.
  Tapping it when something is off opens the usual explanation, then Settings.
- **Panel: Copy for customer.** One tap on a key row copies the key with its
  duration and expiry reminder, with a "Copied for customer" toast.

## 2.1.0 follow-up — polish

- **Release signing passwords are out of the repository files.** They come from
  GitHub secrets or a private local file; the signing key is unchanged and CI
  never makes a new one.
- **A short explanation before Settings.** Before opening All files access or
  Install unknown apps, Anoy explains why and what to do, with Continue to
  Settings and Not now.
- **Sign out or Forget this key.** Sign out keeps your key saved for a one-tap
  sign in; Forget this key removes it from the phone.
- **Honest game state.** "Ready" only when everything Launch needs is in place,
  otherwise "Installed". Closing the game outside Anoy brings Launch back, with
  no error.
- **Licence time stays current.** Coming back to home refreshes the time left
  (changes made on the panel show up). An "Expires soon" nudge with Renew
  appears near the end. Offline, the last known time stays with a quiet "Last
  checked".

## 2.1.0 follow-up — permissions

- **Anoy asks for the three permissions the game-files copy needs**: All
  files access, Files, and Install unknown apps - one after another, opening
  the system page or dialog each time. With all three granted, Launch
  continues into the copy; refusing one shows a clear message and nothing
  launches. After Install unknown apps, Anoy restarts once (Android only
  opens the game folder to a fresh start) and Launch carries on after sign in.
- **Declared but never asked**: microphone, nearby devices, appear on top,
  picture-in-picture, location, notifications.
- **Removed**: calendar, call logs, camera, contacts and accounts, health,
  phone, physical activity and SMS no longer appear in App permissions.

## 2.1.0 follow-up — game files are copied before launch again

- **First install copies the game's OBB, then launches.** Launch shows
  "Preparing… N%" while the newest main OBB (and the newest patch, if any) is
  copied from `Android/obb/com.pubg.imobile` into the container's own OBB
  folder; every file is checked (same name and size, a different file) before
  "Launching…". Previously the copy could report success without copying.
- **Second launch skips the copy only when it is truly current** (the device
  file was found and the container holds a separate copy with the same name
  and size).
- **No launch on any failure.** No game files: "Game files not found…".
  Android blocking the game files folder: "Couldn't prepare game files: Android
  is blocking access to the game files folder on this phone." Container
  storage not ready (same folder as the device): retried, then a clear
  message.
- **Files access is requested by the app.** Launch (and home, once) opens the
  system "All files access" page (storage dialog on Android 5–10); after
  Allow, Launch continues by itself. Deny shows the storage message.
- Sign-in, splash, icon, update check and the launch-error fix are unchanged.

## 2.1.0 follow-up — smoother splash text

- "Welcome To" now dissolves upward (fade + 10 dp drift) instead of being
  sliced; "Loader" cross-dissolves into "Hax" instead of squashing, and the
  re-centre rides the same 400 ms motion. Same words, order and length.
- Cold start no longer dims the system "Anoy", flashes an empty black frame
  and fades the app in: the identical wordmark hands over instantly.

## 2.1.0 follow-up — Anoy wordmark and splash film

- **App icon**: just the word "Anoy", white on black, drawn as Inter outlines
  with icon-specific weight, spacing and a shortened y; inside the adaptive
  safe zone on every mask. Replaces the A monogram (and the earlier key).
- **Splash**: a ~3 s typography film: "Welcome To / Anoy Loader" arrives, the
  greeting is cut away, the name moves to centre and "Loader" becomes "Hax".
  The system splash shows the same static wordmark. Reduced motion skips the
  choreography. Sign-in, the update check and game launch are unchanged.

Details, timings and screenshots: `docs/ui-redesign/FOLLOW-UP.md`.

## 2.1.0 follow-up — launch error, home tidy (splash/icon since replaced)

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
