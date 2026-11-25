# Anoy Loader — changelog

Screenshots, design notes (`docs/ui-redesign/…`) and the one-off generator
scripts mentioned below were removed from the tree in the repo cleanup after
2.3.2; they remain in git history.

## Unreleased follow-up — 20 MiB libraries and complete Lite/Air APK sets

- Raise the panel's shared `.so`/ZIP input **and total expanded** limit to 20 MiB;
  two 7.57 MiB game libraries now fit one bundle. Keep bounded inflation, CRC,
  owner isolation, direct signed uploads and the small legacy request cap.
  An additive private-bucket migration reserves 21 MiB for normalized ZIP overhead.
- Lite/Air no longer inspects, copies or requires an OBB, nor blocks Launch on
  OBB-only file access. BGMI retains its verified main/patch preparation.
- Prepare each complete installed APK set off-thread (base + ABI/config/language/
  feature/asset splits) using the original SDK's public non-moving multi-APK directory API.
  Repair a base-only/stale SDK package, read it back, and fail safely on partial
  files, SDK errors or an installed-game update race. Never merge/re-sign APKs,
  uninstall games or clear data. Single-APK games use the same path.
- Add 32 APK-set host cases and extend OBB/policy coverage to 11 cases per API
  and brand: **320 host Android checks** with the existing navigation/login suite.
  [Scope and required device checks](../docs/SPLIT_APK_SUPPORT.md).
- APK changes are **build-only pending device testing**, not a replacement of
  the published 2.4.3 release. No version/signing/AAR changes or live library/APK
  selections are part of this follow-up. No crash-free device claim.

## 2.4.3 — short release changelog (versionCode 21)

[Simple 2.4.2 → 2.4.3 changelog](CHANGELOG-2.4.3.md).
The [first user-facing 2.4.3 release](https://github.com/masterace572-prog/AnoyMundo/releases/tag/loaders-v2.4.3-21) includes the verified
loader-UI and announcement follow-ups below. Publication source `f8f5884` /
[run 45443061179](https://github.com/masterace572-prog/AnoyMundo/actions/runs/45443061179)
repeated both brands' build/lint/compiled/signature gates and **180/180** host
checks. App/test inputs are unchanged from accepted source `cfc442e`. Only the
older internal 2.4.3 GitHub test artifacts were replaced; all four releases
through 2.4.2 and their exact refs/assets remain unchanged.
**2.4.2 (20) is the last version distributed to users.** Earlier 2.4.3 builds were
internal tests only. Users upgrade normally to **2.4.3 (21)** in place, without
uninstalling or clearing app/container data.

## 2.4.3 loader-UI follow-up — pre-publication verification (versionCode 21)

- Match the announcement background and no-stroke treatment to the game cards;
  keep its heading/icon blue and make the server-fetched message explicitly white.
  Server fetching and all other card colors/behavior stay unchanged.
- Extend the accepted Home design to Login and Splash with static Manrope,
  consistent gutters, black surfaces and blue actions/final Hax word.
- Replace Login's Paste text button with a clipboard icon. Keep two 48dp icon
  targets and more key space; long keys scroll in-place. Show/hide changes only
  the transformation, preserving text, cursor range, typeface and IME input type.
  Use clear next-action icons/labels, visible/hidden accessibility state and
  re-mask on background without clearing the key.
- Replace Splash's strong overlapping words with a measured fade-through and
  small vertical exchange; animate render properties only, use individual
  temporary text layers, remove idle name breathing and clean up animator
  listeners before cancellation. Keep reduced motion and update/error/retry gates.
- Put Active and Safe on one baseline below the end-aligned expiry; wrap statuses
  into end-aligned rows when their measured widths do not fit. Skip redundant
  status text/color writes and reuse unchanged dot drawables.
- Reproduce the missing bottom-capsule drag path on host Android 21/28, then add
  touch-slop/axis arbitration, pixel tracking, label-press cancellation and
  cancelled/multi-pointer gesture cleanup. Both pages and the actual selector
  now have drag paths; retain native page scroll and existing button safety.
- Inset only the blue selector by 2dp per edge. Preserve full tab-pitch travel
  and at least 48dp hit targets. Keep the successful Home/game-card direction.
- Expand host coverage and add an opt-in non-destructive device frame-stat tool.
  **60fps is a target, not a phone-measured guarantee.** Games, native/runtime,
  version/signing, live panel choices and published releases are unchanged.
  [UI details and device acceptance](LOADER-UI-QA.md).

## Internal 2.4.3 UI test iteration — GitHub artifacts only (versionCode 21)

- Put each game's controls, task feedback and progress **inside its own scrolling
  card**. Keep only the Home / Settings capsule persistent, with a blue selected
  segment and contrasting white active text.
- Restore the smaller **Welcome back** greeting. Move a compact, two-unit expiry
  to the top right, with licence/server statuses below it; keep the exact IST
  expiry in Settings and accessibility text. Remove the separate Home licence
  card and the game-card server/library metadata.
- Use a matching dark-blue announcement, cleaner grouped Settings and bundled
  static **Manrope 400/500/600** dashboard fonts. Keep existing login/splash
  branding and monospaced licence/support text. No SDK/dependency upgrade or
  runtime font download; Android 21-compatible font instances include the OFL.
- Remove Air's explanatory paragraph and duplicate state copy. Without its own
  usable library, its disabled button says only **Coming soon**.
- Read OBB source availability off the UI thread through the existing copier's
  lookup. Distinguish missing files from blocked access; **Ready** requires
  readable source files, the game's own library, permissions and activation.
  This hint does not replace verified OBB preparation or authorize a launch.
- Reproduce a previously missed diagonal swipe failure through scrolled Android
  content. Lock each real touch stream to its predominant axis before a child
  ScrollView can claim it; retain ViewPager's native drag/velocity/settling.
  Verify button cancellation, vertical scrolling, real in-flight page geometry,
  both swipe directions, taps and retained navigation in host Android tests.
- Keep **2.4.3 / code 21**, both identities/original signer, every release through
  2.4.2 and live panel library/APK choices unchanged. This replaces only the
  current 2.4.3 release after fresh build-only verification and its own backup.
  This was an internal test iteration, not an update distributed to users.

## Internal 2.4.3 test iteration — BGMI Air and sliding navigation (versionCode 21)

- Add BGMI Air (`com.krafton.bgmilite`) with the supplied game icon and its own
  Launch/Stop action. BGMI retains `com.pubg.imobile` / `libbgmi.so`; Air loads
  only `libbgmilite.so` and remains Coming soon when it is unavailable.
- Install optional Air support from the same library bundle. A later BGMI-only
  bundle removes old Air support; an Air-only archive cannot stand in for BGMI.
- Allow simultaneous running only after a fresh RAM/heat/crash warning, a full
  ten-second wait, and explicit confirmation. Cancel never launches anything.
- Scope OBB preparation, launch/stop, running reconciliation, load diagnostics
  and permission/restart continuation to the selected game. Block Home library
  updates while either game is running. Keep existing licence/JNI/build contracts.
- Add credential-free dual-game JVM regressions and source wiring checks.
  Actual Air/container/linker/provider compatibility requires device QA.
- Replace the Settings overlay with native, opaque Home / Settings pages and a
  persistent bottom capsule. Tap either label or swipe horizontally; at midpoint,
  half of each page is visible. Page state, scroll position and Home's actions
  are retained; reduced-motion taps switch immediately.
- Name the loader page Home; move the `?` Help control to Settings and center
  Sign out with a red label. Keep its existing confirmation / Forget behavior.
- Re-mask the licence key on page movement/backgrounding; tie Back and the Home
  countdown to the selected/settled page, not retained-page visibility. Reserve
  system navigation/gesture insets below the capsule once.
- Owner requested version **2.4.3 / code 21**, the next user release after
  **2.4.2 / code 20**. Earlier 2.4.3 APKs were internal test builds only, not
  distributed updates. Preserve app/container data during an in-place upgrade.
  Panel, database and live library/APK choices are unchanged.
- Add host Android 21/28 view-inflation, tap, touch-swipe, half-page geometry,
  accessibility, reduced-motion and recreation regressions for both brands.
  [Release scope and verification](RELEASE-2.4.3.md).

## Earlier 2.4.4-webtest source candidate — Contained game web login (versionCode 21)

- Tester confirmed 2.4.3 did not fix login: stock BGMI keeps web login in-game;
  the loader opens it outside and the successful return targets real BGMI.
- Address the outbound path through the original SDK's public activity-handler
  chaining API, installed only in virtual game processes. Prefer the game's own
  handler; otherwise open a private in-loader WebView instead of an OS browser.
- Match returns dynamically against virtual BGMI's manifest, including App Links
  and scoped intent URIs; never send an unhandled URL to the outside game. Keep
  original activity-result arguments, private WebView profile, normal provider
  user agent, strict TLS and no native JavaScript bridge or credential logging.
- Non-latest prerelease, not a claimed phone-validated fix. Same code 21 allows
  an in-place return to 2.4.3 without deleting app/container data. Old APKs and
  both AARs are retained. Existing library-download fixes are unchanged.

## Original 2.4.3 — Game login returns and library waits (versionCode 21; superseded)

**Tester follow-up: Twitter's reported flow is still not fixed by this version.**

- Keep the original Mundo AAR; the quarantined replacement test is not used.
- Receive known Twitter/BGMI browser-return schemes and forward the unchanged
  URI to the running virtual BGMI, not the outside installed game. Facebook
  continues through Mundo's original handler. No new login WebView or credential
  logging. In-game browser selection and actual provider login still need device QA.
- Cancel a closed screen's network connection and queued work; separate silent
  library checks from interactive work. Bound retries/check time, use CDN caching
  for hash-verified binary downloads, and fetch fresh metadata on manual retry.
- Remove the duplicate BGMI-support manifest request. Slow text identifies server
  wait, transfer stall, or installation; received bytes count even if percentage
  has not changed. Integrity checks and existing installed data are retained.
- Credential-free JVM regressions and compiled callback-manifest checks run in CI.

## 2.3.8 — Splash motion only (versionCode 17)

- The Lottie animations stay only on the splash update check (quiet arc while
  checking, blue check for "Up to date").
- Login (key outline), home ("Preparing" arc) and the library update sheet
  are back to exactly 2.3.6: no key, plain line, the 20 dp green title check.

## 2.3.7 — Quiet status motion (versionCode 16)

Small Lottie animations (stroke only, white / blue #0A84FF), nothing else
changes; the splash film, launch flow and licensing are untouched:

- **Splash status:** the 14 dp spinner is a quiet 24 dp arc (2.4 s loop);
  "Up to date" draws a blue check once (0.7 s) instead of the green glyph.
- **Login:** a 40 dp key outline above the title draws itself once when sign
  in opens; the title keeps its position (24 + 40 + 8 = the old 72 dp) and the
  key hides with the keyboard.
- **Home:** a 20 dp arc beside the "Preparing game files" line only while the
  game files are copied; it stops the moment the copy reports back.
- **Library sheet:** a 40 dp arc on top while checking / downloading /
  installing, crossfading to the check when done (replaces the 20 dp title
  check); gone when an action is needed.
- Reduced motion: no movement (still arc, final frames). Old unused
  `anoy_sync` / `anoy_success` replaced by `anoy_arc` / `anoy_check` /
  `anoy_key` from `scripts/lottie_art.py`.

## 2.3.6 — Banned keys sign out (versionCode 15)

- A key banned in the panel (Keys → ⋯ → Ban) signs the phone out on the next
  licence check and the login page says "This licence key has been blocked."
- New key lengths from the panel (1H, 12H, 60D) show the right progress.

## 2.3.4 — UI polish (versionCode 13)

- **Tokens:** tertiary text is lighter (#85858C); the 14 dp spacing and radii
  are gone (16); every home card is r20 with 16 dp padding, controls r16; the
  game icon keeps its own corners; Mono blocks 13 sp; raw dp values in layouts
  moved to `dimens.xml`; home's task bar uses the default 4 dp progress style.
- **Words:** "Login" everywhere on the login side ("Log in with a new key",
  "Couldn't log in", "Log in again"); "Sign out" stays. Retry buttons read
  **Try again**, and a message next to that button no longer ends in its own
  "Try again.". "Welcome to", "Tap Continue to open Settings.", "Open Anoy
  again to continue.".
- **Waiting states:** nothing new appears in the first 400 ms; text swaps are
  100 ms out / 180 ms in; status lines keep their space so nothing jumps.
  - Splash: after 6 s "Taking longer than usual…", after 15 s "Still trying.
    Your connection may be slow."; Try again only after a failure.
  - Login: "Still logging in…" after 8 s; connection / VPN problems are a
    neutral grey block with a wifi-off glyph (key problems stay red).
  - Home: "Offline · last checked 12 min ago"; the first game-files copy says
    "First launch copies the game files. Keep Anoy open until it finishes.";
    15 s without progress says "Still preparing. Large game files take a while."
  - App update: a 4 dp bar above Update now, **Cancel download** while
    downloading, "Download is slow. Keep Anoy open." after 8 s without data,
    and "Update failed" over the reason.
  - Library sheet: "Taking longer than usual. Keep Anoy open." after 6 s.
  - Settings › Check for app updates: "Last checked today, 14:32" (IST) under
    the label, "Still checking…" after 6 s, "Couldn't check" keeps the last
    good time.
- **Settings:** App version moved into Updates (no About group); Sign out is a
  normal row (Forget this key stays red in its sheet); Cancel is tonal in every
  sheet.
- **Motion:** one set of tokens — 100 press / 180 fades / 280 screens / 320–240
  sheets / 450 splash only.

## 2.3.3 — speed and reliability (versionCode 12)

- **Splash update check:** one attempt with 8 s connect / 10 s read (was up
  to two attempts of 15 s / 30 s). Settings › Check for app updates keeps the
  patient check. The failure state (amber dot, reason, Try again) is unchanged.
- **"Up to date" remembered for 10 minutes:** after a successful check that
  found no newer version, the splash skips the network for 10 minutes. A found
  update is never remembered away and a failed check never counts as up to
  date. The time of the last successful check is stored (UTC) for Settings.
- **Panel:** the server now runs in Mumbai (Vercel `bom1`), next to its
  database (Supabase `ap-south-1`), instead of the US. The app update answer
  is cached by Vercel for up to a minute; signed-in pages read the account
  from a 15-second cache; client icons come from a cached sprite.

## 2.3.2 — app updates and CI (versionCode 11)

- **App update download:** a failed download says "Couldn't download the
  update (HTTP <code>). Try again later." with **Try again**; server errors
  (5xx, 408, 429) are retried, a web page instead of a file is refused.
- **APK check:** before PackageInstaller the downloaded APK must be
  `com.xlox` with the announced, higher version code, otherwise "This update
  file doesn't match the app." Nothing is installed.
- **Splash:** a published release that is not newer counts as up to date.
- **Launch / Stop:** Lucide play / stop glyph before the label (20 dp, tinted
  like the label); the busy state still shows the spinner.
- **Panel `/app-update`:** "Use latest build" (newest GitHub release), the
  APK link is fetched and must be a real APK before publishing, the version
  code must be higher than the current update, Remove, no history card.
- **CI:** releases are tagged `anoy-v<versionName>-<versionCode>`; older
  releases and tags are deleted after each publish. UI QA is manual; the
  build runs the static UI gates and the APK permissions check.

## 2.3.1 — post-redesign follow-up

Launch / Stop, game files, permissions, sign in, licensing and the library
and app update rules work exactly as in 2.3.0.

- **Splash.** The status line (spinner + text, every state) sits centred at
  the bottom, 32 dp above the navigation bar, and grows from the centre.
- **Sign in.** The button reads "Login" (also "Logging in…" / "Logged in").
  Under it: "Don't have a licence? Buy one" — only "Buy one" is tappable and
  opens the same Telegram link as before.
- **Home.** A compact expiry card: EXPIRES IN + status dot, one ticking line
  ("29d 23h 59m 58s", under a day "23h 59m 58s"), then the IST date. Expired
  shows "Expired" in red and Launch stays disabled as before. The game card
  now shows the real BGMI icon (bundled in the app, never downloaded at
  runtime).
- **Announcement.** The panel notice gets a megaphone and an ANNOUNCEMENT
  kicker above the text (still verbatim; no text = no banner).
- **Settings.** New *Licence* group (key masked as ANOY••••••A7K2, the eye
  reveals it, tap the row to copy it; status; expiry in IST) and *Updates*
  group (Check for app updates — the same check as the splash, with
  "Checking…", "Up to date" or "Couldn't check"; Re-download library with a
  confirmation and the usual progress sheet — a failure keeps the current
  library; Library version). The *Access and files* group is gone; Launch
  still asks for anything missing exactly as before.
- **Support info.** Now includes the licence key, licence status and expiry
  (IST), and says so: "Includes your licence key. Only share it with Anoy
  support."

## 2.3.0 — a calmer, more premium look (visual only)

Everything works exactly as in 2.2.0: Launch / Stop, game files, permissions,
sign in and licence, app and library updates. Only the look changed.

- **One design system.** Inter for text, JetBrains Mono for versions, keys
  and download numbers (tabular figures). Quiet black surfaces, system blue
  for the main action, and colour only where it means something: green for
  "fine", amber for "needs attention" or the last day, red for errors,
  "Expired" and destructive actions. No pills, chips, icon tiles or glows;
  a status is a small dot plus a word.
- **Splash and sign in.** Same film and words, tighter type and spacing.
- **Home.** "ANOY HAX / Welcome back", the panel notice as written, a time
  card with days / hours / min / sec columns (ticking every second, last
  day in amber, "Expired" in red), and a game card with the BGMI version,
  library version, state and server. Launch sits in a pinned bar.
- **Settings.** An inset grouped list: Device, Access and files, Report a
  problem, About (App version 2.3.0 (9)), Account. Anything missing is shown
  in amber with a chevron that opens the explanation.
- **Sheets.** Help, Support info, Sign out, permissions, the library update
  and the app update share one sheet style. Copy support info confirms on
  the button ("Copied"). The permission sheet now says why we ask and how to
  allow it, step by step.
- **Renewing.** The Renew button on the time card is gone; Help explains how
  to renew (time left shows on Home; renew on the panel or via Telegram).

## 2.2.0 — app updates, splash status, new home and Settings

- **In-app app updates.** At start-up Anoy asks the panel whether a newer app
  version is published. Versions are compared by version code.
  - **Mandatory** updates show the changelog and Update now only.
  - **Normal** updates also offer Not now.
  - The update downloads inside the app with progress, then Android's own
    installer opens. No browser.
  - Every failure shows its reason and Retry.
  - Library updates work exactly as before and run afterwards, at sign in.
- **Splash status line.** The version is now top right. A line at the bottom
  shows Checking for updates… → Up to date / Update available → Proceeding….
  Offline, it shows "Can't check for updates" and Retry, and never continues
  silently. The film itself is unchanged.
- **New home.** "Welcome back" header with ? and a Settings button. The panel's
  notice is shown as written; "Play fairly…" is no longer swapped in. A game
  card shows the icon, "BGMI v…", the library version and the honest state.
  Time left is smaller and ticks every second ("29d 23h 59m 12s").
- **Settings.**
  - Device model and Android version.
  - Whether All files access, Files and Install unknown apps are allowed. Tap
    a missing one to fix it.
  - Whether the game files (OBB) are copied.
  - Report on Telegram, Copy support info.
  - App version.
  - Sign out.
- **Help** no longer opens by itself; tap ? when you want it.
- **Panel:**
  - One Library publish card (file or https link; the file wins if both are
    given).
  - New App update page for the owner and admins.
  - Phone layout fixes for the dashboard expiry, the docs callouts and the
    Referral codes / API keys cards.
- **Version 2.2.0 (build code 8).**

## 2.1.1 — polish

- **Version 2.1.1 (build code 7)** so this release is easy to tell apart from
  2.1.0.
- **"Last checked" has more room** under the expiry line on the home card.
- **Panel: Copy for customer** now shows a clear "✓ Copied" on the button
  itself, as well as the usual message.
- **Plain-HTTP setting left on, on purpose.** All of Anoy's own connections use
  HTTPS, but the game container requires this setting and the game runs under
  Anoy's network rules. Turning it off could break the game after launch.

## 2.1.0 follow-up — home, splash and Indian time

- **Clearer home.** Time left and Launch lead. Launch carries a play, download
  or stop glyph. Device details are one quiet line, so short phones keep
  everything above the Launch bar. Every status and message stays visible.
- **Smoother splash.** "Loader" now cross-dissolves into "Hax": the two words
  overlap for a moment instead of popping, over a calmer 0.7 s. No clipping or
  squashing. Same words, same ~3 s, same hand-off.
- **Expiry in Indian time (IST).** The loader shows the expiry as
  "15 Nov 2026, 10:30 PM IST" whatever time zone the phone uses. That is
  exactly what the panel shows for the same key. Licence length is unchanged.

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

Details, timings and screenshots: `docs/ui-redesign/FOLLOW-UP.md` (git history).

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

Details and screenshots: `docs/ui-redesign/FOLLOW-UP.md` (git history).

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
state on an emulator (now uploaded as a workflow artifact; the old committed
screenshots and `docs/ui-redesign/README.md` are in git history).
