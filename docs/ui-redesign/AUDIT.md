# Android UI redesign — Part A audit

**Status: read-only. No UI code has been changed. Parts B–D have not started.**
**Date:** 2026-11-05 · **Branch:** `arena/b2fac70f-anoymundo` · **Tree:** `xlox/`

This document is the mandatory Part A deliverable. It is a line-by-line audit of
the current Android UI. Nothing was edited: `git status` was clean before and is
clean apart from this file. I will stop here and wait for the exact reply `go`
before touching any screen.

**Scope.** `xlox/` is the only Android source tree in the repo.
`_extract/xlox/XLOX-Loader-Source/` is a git-ignored, local-only archive of the
original APK source and is out of scope. `web/`, `supabase/`, `dist/` are out of
scope. `xlox/app/src/main/jni/main.cpp` is native JNI + HTTP glue; it contains
no ImGui/menu/widget code, so the app's visible UI is 100% Java + XML.

**Path shorthand.** In tables below, `res/…`, `java/…`, `AndroidManifest.xml`
mean `xlox/app/src/main/res/…`, `xlox/app/src/main/java/…`,
`xlox/app/src/main/AndroidManifest.xml`. `app/build.gradle` means
`xlox/app/build.gradle`.

**Do-not-touch identifiers (prior user constraint, reconfirmed):**
`targetSdk 28` (`app/build.gradle:30`), `android:pageSizeCompat="enabled"`
(`AndroidManifest.xml:44`), `applicationId com.xlox` (`app/build.gradle:25`),
`LOCAL_MODULE := XloX`, and every `Java_com_xlox_*` JNI symbol. The working
release is `xlox-v1.0.0-run6`; the latest shipped UI is version `1.1.0`
(`app/build.gradle:34-35`, commits `c1865aa` / `11078c7`).

---

## 1. UI stack

| Item | Finding |
| --- | --- |
| Paradigm | **100% XML Views + Java 8.** No Jetpack Compose, no Kotlin, no `@Composable`, no fragments, no Navigation component, no `setContent {}`. |
| Activities | 3 × `AppCompatActivity`: `LogAct`, `MAct`, `FacebookActivity`. Plus `utils/AppManager.java:26` extends `AppCompatActivity` only to host an `AsyncTask` — not an activity, not in the manifest. |
| Layouts | 6 (`activity_login.xml`, `activity_main.xml`, `activity_facebook.xml`, `ios_loading.xml`, `item_time_box.xml`, `progress_dialog.xml`). |
| Binding | `findViewById` + `setContentView`. `viewBinding true` at `app/build.gradle:68-70` but **zero** binding classes used — dead build flag. |
| Custom views | 1: `java/com/xlox/ui/AnoyButton.java` (extends `AppCompatButton`, draws its own dots/progress). |
| State delivery | `Handler`, `AsyncTask`, `runOnUiThread`, `postDelayed`, raw `Toast`/`AlertDialog`/`Dialog`. No architecture-component state holders, no loading/empty/error state objects. |
| Resources | 30 XML drawables, 5 raster assets, 1 font TTF (unused), `values/` + `values-night/` only (no `sw*/`, no `-v23/-v27`, no `dimens.xml`, no `attrs.xml`, no `motion`/`shape` token files). |
| Local AARs | `app/libs/mundo-release.aar`, `app/libs/recommendpermission-release.aar` — inspected, contain **no `res/` entries**, so they contribute no UI resources. They are the virtual-container SDK and must stay. |

---

## 2. Theme setup and dynamic color

| Item | Finding |
| --- | --- |
| Applied theme | `AndroidManifest.xml:49` (`application`) and `:56`, `:66`, `:72` (each activity redundantly) → `@style/AppTheme`. |
| Parent | `res/values/styles.xml:6` and `res/values-night/styles.xml:5`: **`Theme.MaterialComponents.DayNight.NoActionBar` — Material 2**, not Material 3. The two definitions are near-duplicates (`:6-23`, `:5-22`). |
| Second, conflicting theme file | `res/values/themes.xml:5` defines an **unused** `Base.AppTheme` with parent `Theme.Material3.DayNight.NoActionBar`, plus unused `ActiveIndicator` (`:13-15`), `CustomCheckbox02` (`:16-20`). It is explicitly commented as "kept for compatibility" and is never referenced. This is a second, Material-3 theme source that must be deleted/merged. |
| `colorX` overrides | Only `colorPrimary`, `colorPrimaryDark`, `colorAccent`, `android:colorBackground`, `android:windowBackground`, `android:editTextColor`, `android:textColorHint`, `android:textColorPrimary`, `android:textColorSecondary` (`res/values/styles.xml:7-21`). **No** `colorSurface`, `colorOnSurface`, `colorSurfaceVariant`, `colorOutline`, `colorError`, `colorPrimaryContainer`, `colorOnPrimary`, `colorSecondaryContainer`, `colorControlActivated/Highlight/Normal`, `textAppearance*`, or `shapeAppearance*` overrides. Widgets therefore fall back to Material defaults. |
| Status / nav bars | Opaque `@color/anoyBg` at `res/values/styles.xml:12-13`, `res/values-night/styles.xml:11-12`, `res/values/themes.xml:10-11`; `windowLightStatusBar/NavigationBar` hardcoded `true`/`false` (`:14-15` / `:13-14`); `windowTranslucentStatus/Navigation=false` (`:16-17` / `:15-16`). **Not transparent, not edge-to-edge.** |
| Runtime override | `java/com/xlox/ui/ThemeMode.java:52-75` re-applies opaque status/nav colors and legacy `SYSTEM_UI_FLAG_LIGHT_*_BAR` flags via deprecated `View.setSystemUiVisibility`. This fights any edge-to-edge setup and has no `WindowInsetsControllerCompat`. |
| Appearance switch | `ThemeMode.java:24-49` uses `AppCompatDelegate.setDefaultNightMode` with `MODE_NIGHT_FOLLOW_SYSTEM / YES / NO` and a `SharedPreferences("anoy_appearance")` key `mode`. Works, but it is an AppCompat-level theme mode, not a design-token switch. |
| Dynamic color | **Not used.** No `DynamicColors`, `MaterialYou`, wallpaper color extraction, or `monet` anywhere. It must be explicitly *disabled/never enabled* in the new theme: keeping the M2 parent and never calling `DynamicColors.applyToActivitiesIfAvailable()` satisfies this; if the unused M3 `Base.AppTheme` were ever activated it would still be opt-in, but the file should be removed. |
| Default Material purple/teal | **Not present in the palette** (all 86 color definitions are iOS blue/gray/green/red/orange). Risk is the *un-overridden* Material widget defaults (M2/M3 `colorPrimary`, `colorSecondary`, `shapeAppearance*`, dialog elevation) — see §9. |
| `configChanges` | `AndroidManifest.xml:54,65,71` = `screenSize|orientation` on all three activities; `uiMode` is absent, so AppCompat dark-mode recreation works. Rotation is handled manually by the activity (no layout re-inflation). |

---

## 3. Existing design tokens

There is **no token layer** — only two flat color palettes, one type-appearance
file, and hardcoded radii/spacing/durations scattered through drawables,
layouts and Java.

| Token kind | Current state | Location |
| --- | --- | --- |
| Colors | 43 names in light + 43 names in dark. 17 real `anoy*` names (`anoyBg`, `anoySurface`, `anoySurfaceAlt`, `anoyFill`, `anoyFillStrong`, `anoySeparator`, `anoyText`, `anoyTextSecondary`, `anoyTextTertiary`, `anoyBlue`, `anoyBluePress`, `anoyOnBlue`, `anoyGreen`, `anoyRed`, `anoyOrange`, `anoyIconButton`, `anoyTrack`) + 26 legacy aliases (`colorPrimary`, `colorPrimaryDark`, `colorAccent`, `black`, `white`, `bg_main`, `card_bg`, `input_bg`, `accent_gold`, `text_primary`, `text_secondary`, `divider`, `status_safe`, `status_ready`, `primaryText`, `hintText`, `darker_gray`, `button`, `buttonPrimary`, `primary`, `primaryVariant`, `secondary`, `background`, `background2`, `error`, `secondary2`). All are iOS-blue / iOS-gray. | `res/values/colors.xml:4-48`, `res/values-night/colors.xml:5-49` |
| Typography | 9 `Ios*` text appearances built on system `sans-serif` / `sans-serif-medium`, with **negative letter spacing** and 34/26/17/15/13/11sp sizes. No serif, no Inter, no mono, no `font/` resource family. | `res/values/styles.xml:25-93` |
| Spacing | **No `dimens.xml`.** 174 lines contain raw `dp`/`sp` literals across layouts, drawables and styles (full list in §6). No 8dp grid, no `space_*` tokens. | §6 |
| Shape | Radii hardcoded in every drawable: 14dp buttons, 20dp cards/dialogs, 999dp capsules/progress, 13dp/22dp icon frames. No `shapeAppearance*`, no `cornerSize` tokens. | §6 |
| Elevation | No explicit elevation in layouts (`stateListAnimator="@null"` at `res/layout/activity_login.xml:118`, `activity_main.xml:231`, `ios_loading.xml:54`). Elevation is currently achieved by **white surface on gray background**, not by shadows. Default `Dialog` / `MaterialAlertDialogBuilder` still inherits Material elevation. | — |
| Motion | **No motion tokens.** Durations are hardcoded in Java: 110/180/220/250/260/400/480/600/650/700/900/1500/2500/3000/3200/7000/9000ms, `DecelerateInterpolator(1.4f)`, `LinearInterpolator`, `INFINITE` repeat counts. | §9 |
| Ripple / selection | No custom ripple: pressed states are solid color selectors (`bg_ios_button.xml:3-8`, `bg_ios_secondary.xml:3-8`, `bg_blue_action.xml:3-8`). No `colorControlHighlight` override, no `textPrimary @ 8%` ripple, no `accentSubtle` selection. | — |
| Component library | **None.** Only `AnoyButton`. No shared buttons/text fields/cards/chips/dialogs/snackbars/top bar/bottom nav/lists/tabs/empty states. Every screen restyles raw `LinearLayout`/`TextView` inline. No `designsystem` package exists. `java/com/xlox/ui/` contains only `AnoyButton.java` and `ThemeMode.java`. | — |
| Strings | 25 entries in `res/values/strings.xml`; 8 unused (`copying`, `copyright`, `dismiss`, `get_key`, `login_title`, `main_title`, `telegram_url`, `updating`). Most visible text is hardcoded in Java (§8). | `res/values/strings.xml:1-31` |

---

## 4. Screens, activities, dialogs and routes

No fragments, no composables, no navigation graph, no bottom navigation, no
tabs, no settings screen, no list screen, no detail screen. "Routes" are the
three manifest activities plus surfaces shown from Java.

| # | Route / surface | Class · layout | One-line description |
| --- | --- | --- | --- |
| 1 | **Launcher / sign in** (most frequent) | `LogAct` · `res/layout/activity_login.xml`; `AndroidManifest.xml:52-61` MAIN/LAUNCHER | Access-key sign-in with logo, key field + eye toggle, one primary button, paste-from-clipboard, get-a-key Telegram link, theme toggle; permission requests and a VPN-detection dialog on entry. |
| 2 | **Main dashboard** (second) | `MAct` · `res/layout/activity_main.xml`; `AndroidManifest.xml:63-67` | Subscription / expiry / time-left / device / panel-status cards, panel credit text, one primary launch/update/install/stop button with in-button download/OBB progress, compact status row, download row, theme toggle, Telegram link. |
| 3 | **WebView** (orphan route) | `FacebookActivity` · `res/layout/activity_facebook.xml`; `AndroidManifest.xml:69-73` | Full-screen `WebView` loading `https://www.facebook.com/login` with a centered indeterminate spinner. **Declared but never launched** — `startFB` opens the Telegram URL instead (`java/com/xlox/MAct.java:210-212`). Keep the activity and its manifest entry (navigation destinations must not change); restyle it, do not delete or reroute. |
| 4 | VPN-detection dialog ×2 | `java/com/xlox/LogAct.java:76-80`, `:161-165` | Platform `android.app.AlertDialog.Builder` with "VPN detected" / "Turn off VPN to continue." / "OK"; shown before `setContentView` on entry and again on sign-in. Same visual surface, two call sites. |
| 5 | Login failure | `java/com/xlox/LogAct.java:220` | `Toast.makeText` with the native `Check()` result. |
| 6 | Status / error strip | `res/layout/activity_main.xml:236-272`; `java/com/xlox/MAct.java:663-695` | 42dp in-card row with spinner / error icon / label / value; errors auto-dismiss after 3000ms (`:683`). The only error surface. |
| 7 | Download / progress row | `res/layout/activity_main.xml:274-310` | Hidden-by-default row with 20dp spinner, "Updating"/"Copying" text, percent, and a 0×0 dead `loadingCursor`. The only loading surface. |
| 8 | OBB-copy progress dialog | `res/layout/progress_dialog.xml`; `java/com/xlox/utils/AppManager.java:59-78`, `:101-107` | Bare `android.app.Dialog` with a horizontal progress bar and "0%" text. Only created when `showDialog=true`; `MAct.copyObbFiles()` passes `false` (`java/com/xlox/MAct.java:789`), so it is currently unreachable but must be kept as a component. |
| 9 | OBB result dialog | `java/com/xlox/utils/AppManager.java:260-266` | `MaterialAlertDialogBuilder` "Success"/"Error" + "OK"; only shown when `callback == null` — `MAct` always supplies a callback, so currently unreachable. |
| 10 | Diagnostics Toasts | `java/com/xlox/MAct.java:269`, `:278`, `:714`, `:769`, `:775` | Long/short `Toast`s for native load errors, ELF alignment, stop failure, OBB copy result. |
| 11 | Loading dialog layout (dead) | `res/layout/ios_loading.xml` | 280dp dialog with logo, "Sign in" headline, 6dp progress, "0%", hidden "OK" button. **Never inflated** anywhere (`grep R.layout.ios_loading` → 0 hits). |
| 12 | Countdown time box (hidden) | `res/layout/item_time_box.xml`, included ×4 at `res/layout/activity_main.xml:327-330` | 70dp card with "00" / "TIME"; lives inside `legacyControls` (`activity_main.xml:314-337`) which is `0dp × 0dp`, `visibility=gone`. Java still binds and updates it (`java/com/xlox/MAct.java:161-170`, `:312-317`, `:400-408`). |
| 13 | Legacy hidden controls | `res/layout/activity_main.xml:314-337` | 1dp/`gone` buttons and TextViews retained so existing Java binds on startup: `startText`, `actionIcon`, `gameName`, `packageName`, `gameStatus`, `appVersionText`, `startFB`, `obbButton`, `restartButton` ("CLEAR DATA"). Must remain as IDs; restyle not needed (never drawn). |

**Screens by frequency for migration:** 1 (launcher) → 2 (main dashboard) →
3 (WebView) → 4–10 (dialogs / status / error / loading) → 11–13 (dead/empty
surfaces, last). There is no detail screen, no settings screen and no list
screen in this app.

---

## 5. Hardcoded colors (`file:line`)

**Direct literal colors (outside the palette files) — 10 XML + 4 Java:**

| Location | Literal |
| --- | --- |
| `res/drawable/checkbox_checked.xml:7` | `#00C853` (`android:tint`) |
| `res/drawable/checkbox_checked.xml:10` | `#00C853` (`android:fillColor`) |
| `res/drawable/checkbox_unchecked.xml:6` | `#0A1733` (`android:tint`) |
| `res/drawable/checkbox_unchecked.xml:9` | `#00000000` (`android:fillColor`) |
| `res/drawable/checkbox_unchecked.xml:11` | `#0A1733` (`android:strokeColor`) |
| `res/drawable/ic_eye.xml:8` | `#8E8E93` |
| `res/drawable/ic_facebook_logo.xml:3` | `#1877F2` |
| `res/drawable/ic_facebook_logo.xml:4` | `#FFFFFF` |
| `res/drawable/ic_theme.xml:8` | `#FFFFFF` |
| `res/drawable/ic_twitter_logo.xml:3` | `#111111` |
| `java/com/xlox/LogAct.java:102` | `Color.argb(28, 0, 122, 255)` |
| `java/com/xlox/LogAct.java:106` | `Color.argb(28, 0, 122, 255)` |
| `java/com/xlox/ui/AnoyButton.java:66` | `Color.argb(72, 255, 255, 255)` |
| `java/com/xlox/ui/AnoyButton.java:68` | `Color.WHITE` |

**Palette definitions (all hardcoded hex, duplicated light/dark):**

- `res/values/colors.xml:4-21` — 17 `anoy*` tokens: `#F2F2F7`, `#FFFFFF`, `#FFFFFF`, `#E9E9ED`, `#DEDEE3`, `#E3E3E8`, `#1C1C1E`, `#6D6D72`, `#AEAEB2`, `#007AFF`, `#0062CC`, `#FFFFFF`, `#248A3D`, `#FF3B30`, `#B25000`, `#FFFFFF`, `#E9E9ED`.
- `res/values/colors.xml:23-48` — 26 legacy aliases (see §3): `#F2F2F7`, `#E5E5EA`, `#007AFF`, `#1C1C1E`, `#FFFFFF`, `#F2F2F7`, `#FFFFFF`, `#E9E9ED`, `#007AFF`, `#1C1C1E`, `#6D6D72`, `#E3E3E8`, `#007AFF`, `#248A3D`, `#1C1C1E`, `#AEAEB2`, `#6D6D72`, `#007AFF`, `#007AFF`, `#F2F2F7`, `#FFFFFF`, `#FFFFFF`, `#F2F2F7`, `#FFFFFF`, `#FF3B30`, `#FFFFFF`.
- `res/values-night/colors.xml:5-22` — 17 `anoy*` tokens: `#000000`, `#1C1C1E`, `#2C2C2E`, `#2C2C2E`, `#3A3A3C`, `#38383A`, `#FFFFFF`, `#98989D`, `#636366`, `#0A84FF`, `#409CFF`, `#FFFFFF`, `#30D158`, `#FF453A`, `#FF9F0A`, `#1C1C1E`, `#2C2C2E`.
- `res/values-night/colors.xml:24-49` — 26 legacy aliases: `#000000`, `#000000`, `#0A84FF`, `#000000`, `#FFFFFF`, `#000000`, `#1C1C1E`, `#2C2C2E`, `#0A84FF`, `#FFFFFF`, `#98989D`, `#38383A`, `#0A84FF`, `#30D158`, `#FFFFFF`, `#636366`, `#98989D`, `#0A84FF`, `#0A84FF`, `#000000`, `#1C1C1E`, `#1C1C1E`, `#000000`, `#1C1C1E`, `#FF453A`, `#1C1C1E`.

**Semantic colors applied from Java (not from a token), all `ContextCompat.getColor`:**

| Location | Color use |
| --- | --- |
| `java/com/xlox/LogAct.java:99-106` | `anoyBlue` dot color + ARGB blue progress fill for `pasteKey` / `buyKey` |
| `java/com/xlox/MAct.java:248-249` | status text `anoyGreen` / `anoyOrange` |
| `java/com/xlox/MAct.java:356`, `:363` | subscription `anoyRed` / `anoyGreen` |
| `java/com/xlox/MAct.java:575`, `:581` | "Not installed" orange + `ic_menu_save` blue tint |
| `java/com/xlox/MAct.java:599`, `:603` | "Stop" red text + `ic_delete` red tint |
| `java/com/xlox/MAct.java:619`, `:623` | "Launch" blue text + `ic_media_play` blue tint |
| `java/com/xlox/MAct.java:673-674` | error row text `anoyRed` / `anoyText` |
| `java/com/xlox/ui/ThemeMode.java:55-57` | opaque `anoyBg` status/nav bar color |

No `android:textColor="#…"` literals exist in layouts; all layout colors are
`@color/anoy*` resource references — but those resources are the legacy iOS
palette, not the B.1 tokens, and the *semantic* color choice is hardcoded in
Java.

---

## 6. Hardcoded dimensions and font sizes (`file:line`)

**174 lines contain raw `dp`/`sp` literals.** No `dimens.xml`, no 8dp grid, no
`?attr/` spacing. Full line list (all lines in a file that contain a literal):

| File | Lines with literals | Line numbers |
| --- | --- | --- |
| `res/layout/activity_login.xml` | 40 | 20, 21, 22, 23, 26, 27, 30, 31, 36, 37, 48, 56, 64, 67, 77, 78, 82, 83, 87, 98, 102, 103, 115, 116, 126, 128, 130, 131, 132, 143, 150, 151, 153, 154, 155, 165, 166, 168, 169, 172 |
| `res/layout/activity_main.xml` | 69 | 18, 19, 20, 21, 33, 34, 37, 38, 43, 44, 51, 53, 66, 76, 79, 88, 98, 99, 100, 101, 108, 109, 110, 120, 137, 142, 162, 166, 171, 182, 189, 191, 195, 206, 219, 228, 229, 239, 240, 244, 245, 249, 250, 255, 256, 260, 262, 279, 283, 287, 288, 293, 295, 307, 308, 316, 317, 333, 334, 336, 345, 347, 351, 352, 357, 358, 360, 361, 364 |
| `res/layout/ios_loading.xml` | 11 | 3, 7, 12, 13, 15, 24, 30, 41, 46, 49, 53 |
| `res/layout/item_time_box.xml` | 4 | 3, 4, 9, 21 |
| `res/layout/progress_dialog.xml` | 3 | 6, 13, 24 |
| `res/drawable/bg_blue_action.xml` | 2 | 5, 11 |
| `res/drawable/bg_icon_button.xml` | 2 | 3, 5 |
| `res/drawable/bg_ios_button.xml` | 3 | 5, 11, 17 |
| `res/drawable/bg_ios_card.xml` | 1 | 3 |
| `res/drawable/bg_ios_field.xml` | 1 | 3 |
| `res/drawable/bg_ios_progress_fill.xml` | 1 | 4 |
| `res/drawable/bg_ios_progress_track.xml` | 1 | 3 |
| `res/drawable/bg_ios_secondary.xml` | 2 | 5, 11 |
| `res/drawable/bg_light_dialog.xml` | 1 | 3 |
| `res/drawable/bg_light_ok_button.xml` | 1 | 3 |
| `res/drawable/bg_login_card.xml` | 2 | 3, 5 |
| `res/drawable/bg_login_input.xml` | 1 | 3 |
| `res/drawable/bg_logo_frame.xml` | 2 | 3, 5 |
| `res/drawable/bg_orange_action.xml` | 1 | 3 |
| `res/drawable/bg_red_action.xml` | 1 | 3 |
| `res/drawable/bg_ref_stop_button.xml` | 1 | 3 |
| `res/drawable/bg_reference_card.xml` | 1 | 3 |
| `res/drawable/bg_reference_input.xml` | 1 | 3 |
| `res/drawable/bg_status_badge.xml` | 1 | 3 |
| `res/drawable/bg_white_black_card.xml` | 2 | 3, 5 |
| `res/drawable/checkbox_checked.xml` | 2 | 3, 4 |
| `res/drawable/checkbox_unchecked.xml` | 2 | 2, 3 |
| `res/drawable/ic_eye.xml` | 2 | 3, 4 |
| `res/drawable/ic_facebook_logo.xml` | 1 | 2 |
| `res/drawable/ic_theme.xml` | 2 | 3, 4 |
| `res/drawable/ic_twitter_logo.xml` | 1 | 2 |
| `res/values/styles.xml` | 9 | 28, 36, 44, 52, 59, 66, 73, 80, 88 |

Total: **174 lines**. Representative sizes that conflict with B.3: 54dp buttons (`res/layout/activity_login.xml:115`, `res/layout/activity_main.xml:228`), 14dp/20dp/999dp radii (drawables above), 70dp time boxes (`res/layout/item_time_box.xml:4`), 42dp status row (`res/layout/activity_main.xml:239`), 40dp theme button (`res/layout/activity_login.xml:165-166`, `res/layout/activity_main.xml:357-358`), 44dp footer link (`res/layout/activity_main.xml:345`), 46dp dialog button (`res/layout/ios_loading.xml:46`), 15sp inline text size (`res/layout/activity_login.xml:98`, `res/layout/ios_loading.xml:49`), and the 9 style text sizes at `res/values/styles.xml:28,36,44,52,59,66,73,80,88`. Java also hardcodes pixel math: `java/com/xlox/ui/AnoyButton.java:205,211,223-233` (`getHeight()/2f`, `11f*density`, `3.6f*density`, `5f*density`), `java/com/xlox/LogAct.java:181,200,206`, `java/com/xlox/MAct.java:305` (translation `-24f`, `16f`, `28f`, `20f`), and press-scale `0.975f` (`java/com/xlox/ui/AnoyButton.java:244`).

---

## 7. Hardcoded fonts (`file:line`)

| Location | Value |
| --- | --- |
| `res/values/styles.xml:22` | `android:fontFamily = sans-serif` (theme) |
| `res/values/styles.xml:27,35,43,72,79,87` | `sans-serif-medium` |
| `res/values/styles.xml:51,58,65` | `sans-serif` |
| `res/values-night/styles.xml:21` | `sans-serif` |
| `res/layout/activity_login.xml:91` | `android:fontFamily="sans-serif"` on the key `EditText` (inline override) |
| `res/font/press_start_2p.ttf` | Bundled Press Start 2P pixel font, **0 references** in any XML/Java/Gradle file; no OFL license file shipped with it |
| `res/values/styles.xml:30,38,46` | `android:letterSpacing` `-0.02`, `-0.015`, `-0.01` (tracked-out type; B.2 requires **0**) |

No Source Serif 4, Inter, JetBrains Mono, downloadable-fonts, `androidx.core`
`ResourcesCompat.getFont`, or `Typeface.createFromAsset` usage exists. No
`textAppearance*` token names: the 9 `Ios*` styles are applied one-by-one via
`style="@style/…"` in layouts (20 references).

---

## 8. Hardcoded user-facing strings (`file:line`)

**A. Literal `android:text` in layouts (not `@string/…`):**

| Location | Literal |
| --- | --- |
| `res/layout/activity_main.xml:131` | `--` |
| `res/layout/activity_main.xml:153` | `--` |
| `res/layout/activity_main.xml:185` | `--` |
| `res/layout/activity_main.xml:209` | `--` |
| `res/layout/activity_main.xml:304` | `0%` |
| `res/layout/activity_main.xml:336` | `CLEAR DATA` |
| `res/layout/ios_loading.xml:40` | `0%` |
| `res/layout/ios_loading.xml:47` | `OK` |
| `res/layout/item_time_box.xml:15` | `00` |
| `res/layout/item_time_box.xml:22` | `TIME` |
| `res/layout/progress_dialog.xml:23` | `0%` |

Note `res/layout/activity_main.xml:336` `"CLEAR DATA"`,
`res/layout/item_time_box.xml:22` `"TIME"`, `res/layout/item_time_box.xml:15`
`"00"`, `res/layout/ios_loading.xml:47` `"OK"`: all-caps / placeholder text.

**B. Hardcoded user-facing strings in Java (not `R.string.…`):**

| Location | Literal |
| --- | --- |
| `java/com/xlox/LogAct.java:77` | `"VPN detected"` |
| `java/com/xlox/LogAct.java:78` | `"Turn off VPN to continue."` |
| `java/com/xlox/LogAct.java:79` | `"OK"` |
| `java/com/xlox/LogAct.java:157` | `"Enter your key"` |
| `java/com/xlox/LogAct.java:162` | `"VPN detected"` |
| `java/com/xlox/LogAct.java:163` | `"Turn off VPN to continue."` |
| `java/com/xlox/LogAct.java:164` | `"OK"` |
| `java/com/xlox/LogAct.java:231` | `"Loader library missing - reinstall the app"` |
| `java/com/xlox/LogAct.java:233` | `"OK"` |
| `java/com/xlox/MAct.java:156` | `"Android device"` |
| `java/com/xlox/MAct.java:165` | `"DAYS"` |
| `java/com/xlox/MAct.java:166` | `"HOURS"` |
| `java/com/xlox/MAct.java:167` | `"MINUTES"` |
| `java/com/xlox/MAct.java:168` | `"SECONDS"` |
| `java/com/xlox/MAct.java:230` | `"--"` |
| `java/com/xlox/MAct.java:268` | `"Library missing"` |
| `java/com/xlox/MAct.java:277` | `"Library incompatible"` |
| `java/com/xlox/MAct.java:313` | `"--"` |
| `java/com/xlox/MAct.java:314` | `"--"` |
| `java/com/xlox/MAct.java:315` | `"--"` |
| `java/com/xlox/MAct.java:316` | `"--"` |
| `java/com/xlox/MAct.java:329` | `"--"` |
| `java/com/xlox/MAct.java:330` | `"--"` |
| `java/com/xlox/MAct.java:344` | `"--"` |
| `java/com/xlox/MAct.java:355` | `"Expired"` |
| `java/com/xlox/MAct.java:358` | `"Expired"` |
| `java/com/xlox/MAct.java:362` | `"Active"` |
| `java/com/xlox/MAct.java:414` | `"Version " + localVersion` |
| `java/com/xlox/MAct.java:454` | `label + " " + percent + "%"` |
| `java/com/xlox/MAct.java:459` | `"Ready"` |
| `java/com/xlox/MAct.java:471` | `"Updating"` |
| `java/com/xlox/MAct.java:472` | `"Updating"` |
| `java/com/xlox/MAct.java:473` | `"0%"` |
| `java/com/xlox/MAct.java:490` | `"Ready"` |
| `java/com/xlox/MAct.java:491` | `"100%"` |
| `java/com/xlox/MAct.java:502` | `"Launch"/"Install"` |
| `java/com/xlox/MAct.java:506` | `"Loader download unavailable"` |
| `java/com/xlox/MAct.java:514` | `"Launch"/"Install"` |
| `java/com/xlox/MAct.java:526` | `progress + "%"` |
| `java/com/xlox/MAct.java:527` | `"Updating"` |
| `java/com/xlox/MAct.java:565` | `"BGMI"` |
| `java/com/xlox/MAct.java:567` | `"Ready"` |
| `java/com/xlox/MAct.java:572` | `"Not installed"` |
| `java/com/xlox/MAct.java:574` | `"Not installed"` |
| `java/com/xlox/MAct.java:577` | `"Install"` |
| `java/com/xlox/MAct.java:578` | `"Install"` |
| `java/com/xlox/MAct.java:596` | `"Stop"` |
| `java/com/xlox/MAct.java:598` | `"Stop"` |
| `java/com/xlox/MAct.java:607` | `"Running"` |
| `java/com/xlox/MAct.java:608` | `"100%"` |
| `java/com/xlox/MAct.java:616` | `"Update"/"Launch"/"Install"` |
| `java/com/xlox/MAct.java:618` | `"Update"/"Launch"` |
| `java/com/xlox/MAct.java:637` | `"Launch failed"` |
| `java/com/xlox/MAct.java:641` | `"Launch failed"` |
| `java/com/xlox/MAct.java:647` | `"Downloading"` |
| `java/com/xlox/MAct.java:655` | `"Launching"` |
| `java/com/xlox/MAct.java:714` | `"Could not stop"` |
| `java/com/xlox/MAct.java:749` | `"0%"` |
| `java/com/xlox/MAct.java:755` | `"Copying"` |
| `java/com/xlox/MAct.java:765` | `"Ready"` |
| `java/com/xlox/MAct.java:766` | `"100%"` |
| `java/com/xlox/MAct.java:769` | `"Ready"` |
| `java/com/xlox/MAct.java:772` | `"APPLY OBB"` |
| `java/com/xlox/MAct.java:775` | `"Copy failed"` |
| `java/com/xlox/MAct.java:783` | `"Copying"` |
| `java/com/xlox/MAct.java:786` | `"Copying"` |
| `java/com/xlox/utils/AppManager.java:74` | `"0%"` |
| `java/com/xlox/utils/AppManager.java:106` | `values[0] + "%"` |
| `java/com/xlox/utils/AppManager.java:137` | `"Sandbox install failed!"` |
| `java/com/xlox/utils/AppManager.java:144` | `"Package not found!"` |
| `java/com/xlox/utils/AppManager.java:160` | `"OBB folder not found!"` |
| `java/com/xlox/utils/AppManager.java:176` | `"Main BGMI OBB file not found!"` |
| `java/com/xlox/utils/AppManager.java:263` | `"Success"/"Error"` |
| `java/com/xlox/utils/AppManager.java:264` | `"App installed successfully." / "OBB ready successfully."` |
| `java/com/xlox/utils/AppManager.java:265` | `"OK"` |
| `java/com/xlox/utils/Downtwo.java:114` | `"Invalid download URL provided"` |
| `java/com/xlox/utils/Downtwo.java:118` | `"Cannot reach the panel version endpoint"` |
| `java/com/xlox/utils/Downtwo.java:136` | `"Background error: …"` |
| `java/com/xlox/utils/Downtwo.java:181` | `"Version check failed: …"` |
| `java/com/xlox/utils/Downtwo.java:204` | `"No loader release uploaded in the panel yet (Admin -> Library)"` |
| `java/com/xlox/utils/Downtwo.java:207` | `"Loader download failed with HTTP …"` |
| `java/com/xlox/utils/Downtwo.java:229` | `"Panel did not return a valid loader ZIP (…)"` |
| `java/com/xlox/utils/Downtwo.java:234` | `"Cannot create staging directory"` |
| `java/com/xlox/utils/Downtwo.java:240` | `"Loader ZIP has no libbgmi.so / lib.so ELF library"` |
| `java/com/xlox/utils/Downtwo.java:243` | `"Could not install the loader library"` |
| `java/com/xlox/utils/Downtwo.java:246` | `"Installed loader library is not usable"` |
| `java/com/xlox/utils/Downtwo.java:258` | `"Loader download failed: …"` |
| `java/com/xlox/utils/LibManager.java:300` | `"loader library is missing"` |
| `java/com/xlox/utils/LibManager.java:303` | `"loader library is not a valid ELF file"` |
| `java/com/xlox/utils/LibManager.java:305` | `"This device uses … -byte memory pages but …"` |
| `java/com/xlox/utils/LibManager.java:355` | `"unknown error"` |

`java/com/xlox/utils/Downtwo.java` and `java/com/xlox/utils/LibManager.java`
are listed because their failure strings are *surfaced to the UI* —
`MAct.java:505-515` puts `Downtwo.getFailureMessage()` into the status strip,
and `MAct.java:266-279` puts `LibManager.lastLoadError()` /
`alignmentProblem()` into Toasts. Moving these into `strings.xml` is a
presentation change (allowed) and will require passing `Context.getString(…)`
into those utils — a minimal UI-state plumbing change to document in
`REPORT.md`.

**C. Strings that are in `strings.xml` but unused (Lint `UnusedResources`):**
`res/values/strings.xml:4` `main_title`, `:5` `login_title`, `:6` `copyright`
(contains `©`), `:10` `telegram_url`, `:18` `get_key`, `:25` `updating`,
`:26` `copying`, `:30` `dismiss`. Eight of 25 entries are dead.
`res/values/strings.xml:6` is the only non-ASCII symbol in the entire UI tree;
it is not an emoji. No emoji, no lorem, no "coming soon", no `TODO`/`FIXME`
comments in UI code (`grep` over all XML + Java → 0 hits). Em-dashes appear
only in log/Javadoc text (`java/com/xlox/BoxApplication.java:70,94`,
`java/com/xlox/utils/Downtwo.java:157`), never in user-facing copy.
Exclamation marks appear only in four OBB error strings
(`java/com/xlox/utils/AppManager.java:137,144,160,176`) — Part D.5 forbids them.

**D. Placeholder data text (Part C "no placeholder text"):**
`res/layout/activity_main.xml:131,153,185,209` (`"--"`), `:304` (`"0%"`),
`res/layout/ios_loading.xml:40`, `res/layout/progress_dialog.xml:23` (`"0%"`),
`res/layout/item_time_box.xml:15` (`"00"`), `:22` (`"TIME"`),
`java/com/xlox/MAct.java:230,313-316,329-330,344` (`"--"`). These are the
current "empty" states; they must become real loading/empty/error states
(serif headline + one `textSecondary` sentence + one action).

---

## 9. Part C prohibition violations

| # | Prohibition | Current violations (`file:line`) |
| --- | --- | --- |
| C1 | **No emojis** | **0 found.** Compliant today. |
| C2 | **No gradients** | **0 `<gradient>` / `Shader` / `Brush` / `LinearGradient` found.** Compliant today. `res/drawable/logo.webp` and `res/drawable/logo_transparent.png` are unused, colourful anime illustrations with soft shading — delete, do not reuse. |
| C3 | **No neon / saturated / glowing colors, colored shadows, blur, glassmorphism, glow borders** | Saturated iOS blue `#007AFF`/`#0A84FF` (`res/values/colors.xml:13,25,31,35,40,41`; `res/values-night/colors.xml:14,26,32,36,41,42`), neon green `#30D158` (`res/values-night/colors.xml:17`), `#00C853` (`res/drawable/checkbox_checked.xml:7,10`), `#1877F2` (`res/drawable/ic_facebook_logo.xml:3`), saturated blue app mark (`res/drawable/ic_anoy.png`), red/orange semantic fills (`res/drawable/bg_red_action.xml:4`, `res/values/colors.xml:17-18`). Explicit **glow**: `res/drawable/anim_logo_glow.xml:3-4` (infinite alpha pulse + 360° rotation), `res/layout/activity_login.xml:28-33` and `res/layout/activity_main.xml:35-40` (`logoGlow`, alpha 0, rotated infinitely at `java/com/xlox/LogAct.java:190-196`, `java/com/xlox/MAct.java:284-297`). No colored shadows / blur found. |
| C4 | **No text inside colored pill / capsule backgrounds** | `res/drawable/bg_status_badge.xml:3` (`999dp` capsule), applied at `res/layout/activity_main.xml:97-103` with `anoyGreen` text; status text recolored green/orange at `java/com/xlox/MAct.java:248-249`; subscription text recolored red/green at `java/com/xlox/MAct.java:356,363`. Also `res/values/themes.xml:13-15` defines an M3 bottom-nav `ActiveIndicator` colored pill (unused but present). `res/drawable/bg_ios_progress_fill.xml:4` and `bg_ios_progress_track.xml:3` are 999dp capsules (progress only, no text — keep as a 4dp linear progress, restyle radius). |
| C5 | **No uppercase tracked-out labels/buttons, no letter spacing > 0** | `res/values/styles.xml:30,38,46` (letter spacing `-0.02/-0.015/-0.01`, must be 0); `res/layout/activity_main.xml:336` `"CLEAR DATA"`; `res/layout/item_time_box.xml:22` `"TIME"`; `res/layout/ios_loading.xml:47` `"OK"`; `java/com/xlox/MAct.java:165-168` (`DAYS/HOURS/MINUTES/SECONDS`); `java/com/xlox/MAct.java:772` `"APPLY OBB"`; `java/com/xlox/LogAct.java:79,164` `"OK"`; `java/com/xlox/utils/AppManager.java:265` `"OK"`. `textAllCaps="false"` is set explicitly at `res/layout/activity_login.xml:120,135,158`, `res/layout/activity_main.xml:233`, `res/layout/ios_loading.xml:48` — good intent, but raw `<Button>` at `res/layout/ios_loading.xml:43` and the `AppCompatButton`s at `res/layout/activity_main.xml:333-336` have no `textAllCaps` handling. |
| C6 | **No more than one accent-filled button per screen** | **Currently satisfied on the three rendered screens** (one each: `res/layout/activity_login.xml:117`, `res/layout/activity_main.xml:230`, `res/layout/ios_loading.xml:51`). At risk: 5 unused accent/red-filled button backgrounds survive — `res/drawable/bg_blue_action.xml:5-13`, `bg_orange_action.xml:4`, `bg_ref_stop_button.xml:4`, `bg_red_action.xml:4`, `bg_light_ok_button.xml:4`. Also `pasteKey`/`buyKey` get blue dot + blue ARGB progress fills (`java/com/xlox/LogAct.java:99-106`) while `btnLogin` also has one, so the login screen can briefly show three accent-colored controls. Delete the extras. |
| C7 | **No filled / multicolor / mixed / category-colored icons** | Filled vectors: `res/drawable/ic_eye.xml:8`, `ic_theme.xml:8`, `checkbox_checked.xml:11`, `ic_facebook_logo.xml:3-4` (2-color brand), `ic_twitter_logo.xml:3`, `checkbox_unchecked.xml:11`. Mixed families: 5 custom vectors + `res/drawable/ic_anoy.png` raster mark + **3 platform drawables** `android.R.drawable.ic_menu_save` / `ic_delete` / `ic_media_play` (`java/com/xlox/MAct.java:580,602,622`). Category/status-colored icons: `java/com/xlox/MAct.java:581,603,623` (blue/red `setColorFilter`), blue tint on theme toggle (`res/layout/activity_login.xml:175`, `res/layout/activity_main.xml:367`), blue indeterminate tints (`res/layout/activity_facebook.xml:18`, `res/layout/activity_main.xml:251,289`), `res/values/themes.xml:19` (`buttonTint=anoyBlue`). `statusErrorIcon` (`res/layout/activity_main.xml:253-257`) has no `src` at all. No single icon family, no Material Symbols / Lucide. |
| C8 | **No Material default purple/teal, dynamic color, default M3 shapes/elevation overlays** | No purple/teal in the palette and dynamic color is off. Violations: un-overridden `colorSurface`/`colorOnSurface`/`colorOutline`/`colorError`/`colorPrimaryContainer`/`textAppearance*`/`shapeAppearance*` (see §2) fall back to Material defaults; unused M3 parent at `res/values/themes.xml:5`; `MaterialAlertDialogBuilder` at `java/com/xlox/utils/AppManager.java:262` and bare `Dialog` at `:61` inherit default Material shape + elevation; platform progress styles at `res/layout/progress_dialog.xml:11`, `res/layout/ios_loading.xml:28`, `res/layout/activity_facebook.xml:14`; `res/values/styles.xml:73-93` define `AlertDialogStyle` / `DarkAlertDialogStyle` / `CustomProgressBarStyle` from Material parents (unused). |
| C9 | **No drop shadows on cards/buttons/bars/FABs** | No explicit `elevation`/`translationZ`/shadow in visible layouts (good). But `MaterialAlertDialogBuilder` (`java/com/xlox/utils/AppManager.java:262`) and `new Dialog(ctx)` (`:61`) bring default dialog elevation; `androidx.cardview:cardview:1.0.0` (`app/build.gradle:84`) is a shadow-capable dependency with zero usage — remove it. |
| C10 | **No bouncy / looping / decorative animations, no shimmer, no colored skeletons** | Infinite loops: `res/drawable/anim_logo_glow.xml:3-4`; `java/com/xlox/LogAct.java:184-188` (logo float, `INFINITE`), `:192-196` (glow rotation, `INFINITE`); `java/com/xlox/MAct.java:287-290` (logo float, `INFINITE`), `:293-297` (glow rotation, `INFINITE`); `java/com/xlox/ui/AnoyButton.java:162-169` (dots, `INFINITE`, `LinearInterpolator`), `:230-233` (sine-wave hover/bounce). Overshoot-ish/bouncy: `java/com/xlox/LogAct.java:182,201,207` (`DecelerateInterpolator(1.4f)`, 700/600/650ms), `java/com/xlox/MAct.java:306` (480ms staggered fade-up, 80ms steps), `java/com/xlox/ui/AnoyButton.java:244-250` (press `scaleX/scaleY 0.975f` with 110/180ms). Other non-spec motion: `java/com/xlox/MAct.java:499` (400ms), `:669`, `:692` (260ms), `java/com/xlox/ui/AnoyButton.java:186` (220ms `DecelerateInterpolator`), `java/com/xlox/MAct.java:371,460,501-502,626-628,656-659,683` (1000/650/700/1000/2500/3000ms delays), `java/com/xlox/LogAct.java:109,185,201,207` (650/800/90/140ms delays), `java/com/xlox/ui/AnoyButton.java:109` (min 250ms). **No respect for system "Remove animations"**: zero references to `ValueAnimator.areAnimatorsEnabled()`, `Settings.Global.ANIMATOR_DURATION_SCALE`, or `WindowInsetsController` animation flags anywhere. No shimmer skeletons / Lottie (good). |
| C11 | **No hardcoded colors / dimensions / fonts / user-facing strings in layouts** | §5 (14 literal colors + 86 palette definitions + 8 Java semantic choices), §6 (174 lines), §7 (11 font references + 1 TTF), §8 (11 XML literals + 91 Java literals). Also hardcoded `alpha` values: `res/layout/activity_login.xml:32,104` (`0`, `0.9`), `res/layout/activity_main.xml:39` (`0`), `java/com/xlox/LogAct.java:115,118` (`0.55f`, `1f`), `java/com/xlox/ui/AnoyButton.java:232` (dot alpha `90 + 165*wave`). |
| C12 | **No placeholder / lorem / "coming soon" / TODO in UI code** | No lorem / "coming soon" / `TODO` / `FIXME` (0 hits). Placeholder data text does exist: see §8.D (`"--"`, `"00"`, `"TIME"`, `"0%"`, `"CLEAR DATA"`) and `res/layout/activity_main.xml:314-337` (hidden legacy controls). |
| C13 | **Semantic colors only on text / 1dp borders, never backgrounds** | `res/drawable/bg_red_action.xml:4` (solid `anoyRed` background, unused), `res/drawable/checkbox_checked.xml:10` (solid `#00C853` background), `res/values/colors.xml:35-36` (`status_safe`/`status_ready` semantic colors), `java/com/xlox/MAct.java:248-249,356,363,575,599,619,673-674` (semantic colors on text — allowed — but they sit on capsules/tinted cards, which is not). |
| C14 | **Sentence case, no exclamation marks** | `java/com/xlox/utils/AppManager.java:137,144,160,176` (`"… failed!"`, `"… not found!"`). All-caps strings in C5. `xlox/README.md:26` mentions the *panel-provided* `credit` text `"Anti-Cheat is High..!!"` — that is remote data, not app-owned copy; leave as data. |

---

## 10. Broken / missing insets, edge-to-edge and keyboard handling

**There is no inset handling anywhere in the app.** Zero occurrences of
`WindowCompat`, `setDecorFitsSystemWindows`, `WindowInsetsControllerCompat`,
`ViewCompat.setOnApplyWindowInsetsListener`, `WindowInsetsCompat`,
`android:fitsSystemWindows`, `imePadding`, `safeDrawing`, `displayCutout`,
`adjustResize`, `adjustPan`, or `android:windowSoftInputMode` in
`res/`, `java/` or `AndroidManifest.xml`.

| # | Location | Problem |
| --- | --- | --- |
| I1 | `AndroidManifest.xml:52-61` (`LogAct`), `:63-67` (`MAct`), `:69-73` (`FacebookActivity`) | No `android:windowSoftInputMode`. The only text field (`res/layout/activity_login.xml:85-98`, `imeOptions="actionDone"` at `:93`) has no `adjustResize`/IME-inset handling; the keyboard can cover the field and the two buttons beneath it. |
| I2 | `res/values/styles.xml:12-17`, `res/values-night/styles.xml:11-16`, `res/values/themes.xml:10-11` | Opaque status/nav bar colors, `windowTranslucent*` explicitly `false`. Bars are drawn by the window, content is clamped below them — no edge-to-edge, no transparent bars. |
| I3 | `res/values/styles.xml:14-15` | `windowLightStatusBar/NavigationBar` hardcoded `true` in the *default* (`DayNight`) theme; only `values-night` flips them. With the in-app light/dark toggle, theme-attribute and runtime flag can disagree on API 23/26 edge cases. |
| I4 | `java/com/xlox/ui/ThemeMode.java:52-75` | Re-applies opaque bar colors on every activity setup (`:55-57`) and uses deprecated `decor.setSystemUiVisibility(flags)` (`:59-74`). No `WindowCompat.setDecorFitsSystemWindows(window,false)`, no `WindowInsetsControllerCompat`, no `isAppearanceLightStatusBars`. This code must be replaced for edge-to-edge. |
| I5 | `res/layout/activity_login.xml:18-23` | `res/layout/activity_login.xml:20-23`: `mainContainer` hardcoded `paddingStart/End=28dp`, `paddingTop=70dp`, `paddingBottom=44dp`; `ScrollView` (`fillViewport=true`, `:8-12`) has no inset consumption. With transparent bars the top 70dp guess will not match the real status-bar height (including cutouts) and the bottom 44dp will not match the nav bar. |
| I6 | `res/layout/activity_login.xml:165-169`, `:172`, `:175` | Theme toggle 40×40dp pinned `top|end` with `marginTop=18dp`, `marginEnd=20dp` — will sit **under** the status bar once edge-to-edge is on. Also below 48dp touch target (§12). |
| I7 | `res/layout/activity_login.xml:143-157` | "Need a key?" / Telegram block anchored near the bottom with fixed 26dp/40dp/2dp offsets and `minWidth=190dp` (`:143,150,151,153`) and no bottom inset; keyboard and gesture-nav overlap risk. |
| I8 | `res/layout/activity_main.xml:18-21` | `paddingStart/End=20dp`, `paddingTop=30dp`, `paddingBottom=96dp`. The 96dp bottom is an ad-hoc guess to clear the fixed footer, not `safeDrawing` / navigationBars / ime insets. |
| I9 | `res/layout/activity_main.xml:341-353` | Footer Telegram `TextView` is `layout_gravity="bottom|center_horizontal"` with `marginBottom=24dp` — will be overlapped by the navigation bar / gesture area in edge-to-edge. |
| I10 | `res/layout/activity_main.xml:355-367` | Theme toggle 40×40dp `top|end`, `marginTop=18dp` — under status bar in edge-to-edge; no `contentDescription`-safe inset handling. |
| I11 | `res/layout/activity_facebook.xml:2-10` | `RelativeLayout` + `WebView` both `match_parent` with no insets; web content will render behind transparent system bars. Spinner at `:12-19` is centered on the raw window, not on the safe area. |
| I12 | `res/layout/progress_dialog.xml:2-7`, `res/layout/ios_loading.xml:2-8` | Fixed 280dp width / 20-24dp padding dialog roots; no display-cutout or IME inset handling; dialog window uses default decor insets. |
| I13 | `java/com/xlox/MAct.java:683` | Errors auto-hide after 3000ms — an error state that disappears before it is read; no persistent error state (Part D requires polished loading/empty/error). |
| I14 | `java/com/xlox/MAct.java:253-257`, `:305-309` | `statusErrorIcon` has no drawable and `loadingCursor` is 0×0dp — two dead "state" views that Java toggles. |

**Technical constraint for the fix:** `minSdk 21` / `targetSdk 28` means platform
edge-to-edge APIs (`Window.setDecorFitsSystemWindows`, `WindowInsetsController`)
are unavailable. Use androidx.core (`WindowCompat`,
`WindowInsetsControllerCompat`, `ViewCompat.setOnApplyWindowInsetsListener`,
`WindowInsetsCompat.Type.safeDrawing()/ime()`) — the transitive `androidx.core`
from `com.google.android.material:material:1.11.0` is new enough. Keep
`targetSdk 28` unchanged. `android:windowLightNavigationBar` (API 26) needs a
`-v27` qualifier or the compat controller, not the current `values/` default.

---

## 11. Current fonts, icon sets and UI libraries

**Fonts.** System `sans-serif` / `sans-serif-medium` only (11 references, §7).
`res/font/press_start_2p.ttf` is bundled but unused and has no OFL license file.
No Source Serif 4, Inter, JetBrains Mono, no downloadable fonts, no custom
`Typeface` usage. B.2 requires three OFL families to be bundled.

**Icons.** No single family. Mixed set:
- Custom filled vectors: `ic_eye.xml`, `ic_theme.xml`, `checkbox_checked.xml`,
  `checkbox_unchecked.xml`, `ic_facebook_logo.xml` (brand, 2 colors),
  `ic_twitter_logo.xml` (brand).
- Raster: `ic_anoy.png` (blue rounded-square "A", **also the launcher icon**
  at `AndroidManifest.xml:47`), `ic_facebook.png`, `bgmi.webp`, `logo.webp`,
  `logo_transparent.png`.
- Platform stock icons in Java: `android.R.drawable.ic_menu_save`,
  `ic_delete`, `ic_media_play` (`java/com/xlox/MAct.java:580,602,622`).
- Unused drawables (Lint `UnusedResources` candidates): `anim_logo_glow`,
  `bg_blue_action`, `bg_light_progress`, `bg_login_card`, `bg_login_input`,
  `bg_logo_frame`, `bg_orange_action`, `bg_red_action`, `bg_ref_stop_button`,
  `bg_reference_card`, `bg_reference_input`, `bg_white_black_card`, `bgmi`,
  `ic_facebook`, `ic_facebook_logo`, `ic_twitter_logo`, `logo`,
  `logo_transparent`, `checkbox_*` (only reachable from the unused
  `CustomCheckbox02` style), plus the whole `res/layout/ios_loading.xml` and
  unused strings in §8.C. That is **18 unused drawables** (plus 3 `checkbox_*`
  vectors reachable only from the unused `CustomCheckbox02` style) and 1 unused
  layout. **`res/drawable/ic_anoy.png` is the launcher icon and
  will not be changed.**

**Libraries (`xlox/app/build.gradle:80-87`):**

| Dependency | UI use | Decision for Part B |
| --- | --- | --- |
| `androidx.multidex:multidex:2.0.1` | none (app infra) | keep |
| `fileTree('libs')` → 2 local AARs | none (virtual-container SDK, no `res/`) | keep — business logic |
| `androidx.appcompat:appcompat:1.2.0` | `AppCompatActivity`, `AppCompatButton`, `ActivityCompat`, `ContextCompat` | keep (needed for Views + `AppCompatDelegate` theme switching). Old version may emit Lint warnings on `compileSdk 36` — check in quality gates. |
| `androidx.cardview:cardview:1.0.0` | **0 usages** | **remove** (Part E: remove unnecessary UI libraries) |
| `com.google.android.material:material:1.11.0` | **only** `MaterialAlertDialogBuilder` (`java/com/xlox/utils/AppManager.java:15,262`) | remove if the redesigned dialog is a custom themed `Dialog`; otherwise keep and override all M3 shape/elevation defaults. Decision: remove. |
| `net.lingala.zip4j:zip4j:2.9.0` | none (ZIP extraction) | keep — business logic |
| `viewBinding true` (`app/build.gradle:68-70`) | 0 usages | disable or use it for the new design-system binding; decision: use it for typed access in redesigned activities, no new lib. |
| AGP `8.2.1`, `compileSdk 36`, `minSdk 21`, `targetSdk 28`, Java 8 (`app/build.gradle:5-8,24-30,63-66`) | build | unchanged, except `lint { abortOnError false }` (`:19-22`) must become `true` for the Part D quality gates. Empty `xlox/app/lint-baseline.xml` currently hides new issues. |

**No** Compose, Kotlin, Navigation, RecyclerView, ConstraintLayout, Coil/Glide,
Lottie, shimmer, icon libraries, or animation libraries. No icon-font or SVG
library is present, so the Material Symbols Outlined vectors must be vendored
as `res/drawable` vector XML. No new UI library will be added: fonts come as
bundled OFL `.ttf` files and icons as vector drawables.

**Sandbox network note (decided approach, no new dependency):** outbound access
is limited to `github.com`, `codeload.github.com`, `api.github.com`,
`registry.npmjs.org`, `pypi.org`, `files.pythonhosted.org`. The three OFL fonts
will be sourced from the `@fontsource/inter`, `@fontsource/source-serif-4`,
`@fontsource/jetbrains-mono` npm packages (or the `google/fonts` GitHub repo)
and copied into `res/font/` with their OFL license text; Material Symbols
Outlined paths will come from the `material-symbols` npm package or
`google/material-design-icons` on GitHub and be converted to vector drawables.
Nothing is fetched at runtime and no Gradle dependency is added.

---

## 12. Accessibility baseline (for Part D)

| Item | Finding |
| --- | --- |
| Icon-only controls without `contentDescription` | `res/layout/activity_main.xml:247-252` (`statusLoader`), `:253-257` (`statusErrorIcon`), `:285-289` (`downloadProgressBar`), `:305-309` (`loadingCursor`), `res/layout/activity_facebook.xml:12-19` (`facebookProgressBar`), `res/layout/progress_dialog.xml:9-15`, `res/layout/ios_loading.xml:26-33`. Present and correct: `res/layout/activity_login.xml:163-175` (`themeToggle`), `:100-108` (`eyeToggle`), `res/layout/activity_main.xml:355-367`, `res/layout/ios_loading.xml:10-16`. Decorative `logoGlow` (`activity_login.xml:28-33`, `activity_main.xml:35-40`) has a label but should be `importantForAccessibility="no"`. |
| Touch targets < 48×48dp | `res/layout/activity_login.xml:102-103` (eye toggle 22×22), `:126` (`pasteKey` 44dp), `:150` (`buyKey` 40dp), `:165-166` (theme 40×40); `res/layout/activity_main.xml:239` (status row 42dp), `:345` (footer 44dp), `:357-358` (theme 40×40); `res/layout/ios_loading.xml:46` (OK 46dp). No `minWidth`/`minHeight` anywhere. |
| Contrast | `anoyTextTertiary` `#AEAEB2` on `#FFFFFF` (~2.1:1) and `#636366` on `#000000` (~2.5:1) fail WCAG AA; `anoyBlue` `#007AFF` on white (~4.0:1) fails AA for small text. Must be re-verified against B.1 tokens. |
| Focus order | No `nextFocusDown/Forward` on `res/layout/activity_login.xml:85-98`; theme toggle is at the visual top-right but declared last (`activity_login.xml:160-175`, `activity_main.xml:355-367`); hidden 1dp `legacyControls` (`activity_main.xml:314-337`) are still focusable in principle. |
| TalkBack | `textUsername.setError("Enter your key")` (`java/com/xlox/LogAct.java:157`) is a hardcoded error with no `contentDescription`; `eyeToggle` alpha-only state change (`:115,118`) has no announced state; `Toast`s are not reliably announced with custom actions. |
| Font scale | No `sp`-safe testing done; fixed `54dp`/`42dp`/`44dp`/`70dp` heights and 280dp dialog width will clip at `font_scale 1.3`. |

---

## 13. Constraints, risks and decisions recorded before implementation

1. **Read-only gate.** This audit is the only file created. No `go` has been
   received; no `res/`, `java/`, `gradle`, manifest or asset file was modified.
2. **Behavior freeze.** `java/com/xlox/utils/AppManager.java:153-269` (OBB copy),
   `java/com/xlox/MAct.java:743-790` (`copyObbFiles`), `java/com/xlox/utils/Downtwo.java`,
   `java/com/xlox/utils/LibManager.java`, `java/com/xlox/BoxApplication.java`,
   `jni/main.cpp`, all panel URLs and all JNI signatures are business logic.
   Only the UI-facing callbacks/labels move. `MAct.copyObbFiles()` control flow
   was previously noted as independently unverified — do not "fix" it during a
   presentation change. Any minimal logic change (splitting loading/empty/error,
   moving strings to resources, replacing Toasts with snackbars) will be listed
   in `REPORT.md`.
3. **Navigation freeze.** `FacebookActivity` is an orphan route; keep its
   manifest entry and its `WebView` destination. Do not add/remove screens,
   fragments, bottom nav, tabs or settings routes to satisfy the B.6 component
   checklist. B.6 components with no current usage (bottom nav, tabs, list rows,
   chips, switches/checkboxes/radios, avatars, filter chips) will be *defined*
   in the design system as styles/drawables but not instantiated into new UI —
   documented in `REPORT.md`. Implemented-and-used components: Primary /
   Secondary / Text buttons, text field, 56dp top app bar, cards, progress,
   dialogs, snackbar (replacing Toasts), status text, empty/error state,
   icon buttons.
4. **Launcher icon / brand.** `AndroidManifest.xml:47` `@drawable/ic_anoy` stays
   **unchanged** (explicit B.4). The same file is used as the *in-app* mark at
   `res/layout/activity_login.xml:40`, `res/layout/activity_main.xml:47`,
   `res/layout/ios_loading.xml:14`; for the in-app mark I will use a
   monochrome `textPrimary` outline of the existing "A" mark (no new logo, no
   trademark, no new brand asset) rather than the saturated blue raster. No
   Anthropic/Claude name, logo or asset will be introduced.
5. **Theme decision.** Keep the existing `Theme.MaterialComponents.DayNight.NoActionBar`
   (Material 2) parent — explicitly allowed by Part D — and override **all**
   `colorX` attributes, all `textAppearance*` for the 12 B.2 styles, all
   `shapeAppearance*` and the ripple/selection attrs in one `AppTheme`.
   Rationale: switching to the M3 parent would re-import default M3 shapes and
   elevation overlays that Part C forbids. Delete `res/values/themes.xml`
   entirely (unused M3 `Base.AppTheme`, `ActiveIndicator`, `CustomCheckbox02`).
   Dynamic color is never enabled.
6. **Edge-to-edge decision.** `ThemeMode.applyWindow()` becomes a compat-based
   helper: transparent status/nav bars, `WindowCompat.setDecorFitsSystemWindows(false)`,
   `WindowInsetsControllerCompat` for theme-aware icon contrast, and one shared
   `applySafeDrawingInsets(view, applyTop, applyBottom, applyIme)` used by all
   three activities. Replace `setSystemUiVisibility` (deprecated) entirely.
7. **Toolchain / verification.** This sandbox has **no Java, Gradle, Android SDK,
   NDK, clang, aapt2, adb, emulator or device**. APK builds already run in
   `.github/workflows/build-xlox.yml` on `ubuntu-24.04` with Android SDK 34/36 +
   NDK 24. Part D's install + light/dark screenshots + `font_scale 1.3` test
   will be implemented as CI steps: build `debug`/`release` APK →
   `sdkmanager`/`avdmanager` arm64 emulator → `adb install` →
   `adb shell cmd uimode night yes/no` → `adb exec-out screencap -p` into
   `docs/ui-redesign/screenshots/<screen>_<light|dark>.png` →
   `adb shell settings put system font_scale 1.3` then restore `1.0` →
   `./gradlew :app:lintDebug` with `abortOnError true` and a re-generated, empty
   `lint-baseline.xml`. If a real device/emulator cannot be attached to CI,
   this will be recorded honestly in `REPORT.md` rather than claimed.
8. **Known pre-existing build risks.** `appcompat 1.2.0` + `compileSdk 36` may
   produce new Lint warnings; `lint { abortOnError false }` currently masks
   them; `viewBinding true` is unused; 8 strings, 18 drawables and 1 layout are
   unused (`UnusedResources`), plus 3 checkbox vectors behind an unused style. Release `minifyEnabled true` /
   `shrinkResources true` (`app/build.gradle:51-52`) with `targetSdk 28` must be
   re-checked after removing `material` and `cardview`.

---

## 14. Proposed migration order (Part D sequence)

1. **Shared theme + token layer.** Single `AppTheme` (light + `values-night`),
   B.1 color tokens in one file per appearance, `dimens.xml` 8dp scale
   (`4/8/12/16/20/24/32/40/48`), shape appearances (buttons 10dp, inputs 8dp,
   cards 12dp, sheets 24dp top, dialogs 16dp, avatars circle, images 12dp),
   typography (`TextAppearance.*` for all 12 B.2 styles) with bundled OFL fonts
   in `res/font/`, motion tokens (150/250/300ms, `FastOutSlowIn`), ripple
   `textPrimary @ 8%`, selection `accentSubtle`, all `colorX` overridden,
   dynamic color disabled, `abortOnError true`. Delete `themes.xml`.
2. **Component library** in `java/com/xlox/ui/designsystem/` (+ matching
   `res/drawable`/`res/values` definitions and a light/dark preview layout):
   Primary / Secondary / Text buttons (48dp, loading swaps label for indicator
   with no width change, disabled 38%), text fields (52dp, `surfaceSubtle`,
   label above / helper-error below, error 1dp border, no floating label),
   56dp top app bar, cards, dialogs, snackbars, status text, progress
   (4dp linear / 20·40dp circular), icon buttons, and empty/error states.
   Define (but do not instantiate) bottom nav, list rows, chips, tabs,
   switches/checkboxes/radios styles per decision 3 above. Refactor
   `AnoyButton` into the design-system Primary button, dropping dots/sine
   wave/press-scale/progress-fill in favour of the B.6 loading indicator.
3. **Icons.** Replace all filled / multicolor / platform / brand icons with one
   monochrome Material Symbols Outlined family (24dp bars/list leading, 20dp
   inline, 18dp in buttons), `textSecondary` at rest / `textPrimary` active /
   accent only for a selected nav item. Delete all unused icon and button
   drawables; `ic_anoy.png` stays only as the launcher icon.
4. **Edge-to-edge + insets.** Compat helper (decision 6), applied to all three
   activities, dialogs and the WebView; remove the 70dp/30dp/96dp/24dp guesses;
   IME padding on the login field; theme-aware bar icon contrast.
5. **Screens by frequency:** (a) `LogAct` / `activity_login.xml`,
   (b) `MAct` / `activity_main.xml`, (c) `FacebookActivity` /
   `activity_facebook.xml`, (d) dialogs (VPN ×2, OBB progress, OBB result,
   `ios_loading` removal), (e) status/loading/empty/error states last —
   including the hidden `legacyControls` IDs which stay bound but undrawn.
   Each screen: one serif headline, `textSecondary` supporting text, 20dp
   horizontal padding, 32dp section gaps, exactly one accent-filled control,
   no shadows, first-class dark mode.
6. **Strings.** Move every user-facing string (§8 A–D plus `Downtwo`/`LibManager`
   failure text) into `strings.xml`, sentence case, plain/direct, no
   exclamation marks, no emojis, zero letter spacing; remove the 8 unused
   strings and all placeholder `"--"`/`"00"`/`"TIME"` text by introducing
   proper state strings.
7. **Verification.** CI build + emulator install; light + dark screenshots to
   `docs/ui-redesign/screenshots/<screen>_<light|dark>.png`; inspect clipping,
   overlap, misalignment, colors, grid spacing, contrast, system bars and
   keyboard overlap; `font_scale 1.3` then restore `1.0`.
8. **Accessibility.** WCAG AA contrast on all 16 B.1 token pairs,
   `contentDescription` on every icon-only control, 48dp minimum targets,
   sensible focus order, TalkBack labels and announced toggle states.
9. **Quality gates.** Zero new compiler warnings; `./gradlew :app:lintDebug`
   with no new issues; `UnusedResources` cleanup; grep for zero hits **outside
   `designsystem`** of `Color(0x`, `#[0-9A-Fa-f]{6}`, `.dp`, `.sp`,
   `textSize=`, `android:textColor="#`, or justify each remaining hit; zero
   Part C leftovers (emoji, gradient, pill, all-caps, looping animation,
   mixed icons, shadow, dynamic color).
10. **Report.** `docs/ui-redesign/REPORT.md`: before/after pair for every
    screen, full token + component lists, removed assets/dependencies, any
    minimal logic changes, remaining known issues (**must be empty**), and
    instructions for adding a new screen with the design system.

---

## 15. Gate

Part A is complete. **No UI code has been touched. I will stop here and wait for
the exact reply `go` before starting Part B.**

