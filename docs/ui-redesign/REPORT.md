# Android UI/UX redesign — Part D completion report

**Spec:** `COMPLETE NEXT-GEN UI/UX REDESIGN — ANOY ANDROID APP`.
**Part A:** `docs/ui-redesign/AUDIT.md` (632 lines, 15 sections) was approved with
`Go`; its §14 gate order was followed.
**Scope guard:** Web v1.2 is unchanged. Every Android native / licence / OBB /
launch / install / update / stop flow is still bound to the real native and
network values; no screen substitutes mock data for a live variable. All three
manifest activities and their routes are preserved (the Facebook WebView route
is restyled, not rerouted).

All before/after pairs below pair the **approved audit's recorded "before"
state** with an **actual emulator screenshot of the final build**. Part A was a
read-only audit, so no "before" PNGs exist; the before column cites the audit's
`file:line` findings instead. After images live in
`docs/ui-redesign/screenshots/` and were produced by the CI job, not rendered
locally.

---

## 1. Before / after pairs

### 1.1 Launcher / sign in — `LogAct`, `activity_login.xml`

| Before (`AUDIT.md`) | After (final build) |
| --- | --- |
| `activity_login.xml:20-23` hardcoded 28dp / 70dp / 44dp insets; opaque `@color/anoyBg` status and nav bars (`§2`, `§10 I1–I7`). Keyboard could cover the field and the buttons beneath it. | `AppTheme` transparent bars + `WindowCompat.setDecorFitsSystemWindows(false)`; `DesignSystem.applySafeDrawing(loginRoot, top=true, bottom=true, ime=true)` consumes real `safeDrawing`, `displayCutout` and `ime` insets. `android:windowSoftInputMode="adjustResize"`. `login_keyboard.png` shows the IME open with the field and Sign in button still reachable. |
| iOS-blue `bg_blue_action` primary button, 9 `Ios*` text appearances on system sans with negative letter spacing, 174 raw dp/sp literals (`§3`, `§5`, `§6`). | Warm alabaster / obsidian token palettes, Inter for copy, JetBrains Mono for telemetry, one coral `PrimaryButton` per screen, `space_*` 8dp grid, `TextAppearance.Ds.*` scale. Zero raw literals outside the token layer. |
| Key field had a 22dp eye toggle, a 44dp paste button, a 40dp theme toggle and a 40dp "Need a key?" row — all below 48dp (`§12`). | `eyeToggle`, `themeToggle`, `backButton` all use `@dimen/touch_target` (48dp) and carry `contentDescription`; `pasteKey` and `buyKey` are 48dp rows. The eye toggle announces `show_key` / `hide_key` on each state change. |
| Duplicate "Paste your key" helper, hardcoded `Toast` errors, no focus order (`§4`, `§8`, `§12`). | One in-field hint (`Enter your key`), a `ACCESS KEY` label, `nextFocusForward`, a `labelFor`-free hint that TalkBack reads as the accessible name, `DesignSystem.snackbarError` with `accessibilityLiveRegion="polite"`, and `Haptics.error` on rejection. |
| No splash, no appearance affordance beyond a 40dp icon; white flash on cold start. | `Theme.App.Splash` (AndroidX SplashScreen, 450ms, always obsidian `ds_splash_icon`) hands over to `AppTheme`; `ThemeToggleView` is a 72x36dp spring pill with a sun/moon morph. `Splash.exit(..., restoreBars)` plus `onWindowFocusChanged` re-apply theme-aware system-bar icon contrast after the platform splash releases the bars. |

Screenshots: [login_light.png](screenshots/login_light.png),
[login_dark.png](screenshots/login_dark.png),
[login_font_130.png](screenshots/login_font_130.png),
[login_320.png](screenshots/login_320.png),
[login_keyboard.png](screenshots/login_keyboard.png).

### 1.2 Main dashboard — `MAct`, `activity_main.xml`

| Before (`AUDIT.md`) | After (final build) |
| --- | --- |
| `activity_main.xml:18-21` used 20dp / 30dp / 96dp guessed padding; theme toggle 40x40dp pinned `top|end` under the status bar; footer Telegram text overlapped the gesture area (`§10 I8–I10`). | 56dp `Widget.Ds.TopAppBar` holds the `v%1$s` mono pill and 48dp theme toggle; `applySafeDrawing` consumes real top/bottom insets; footer badge sits inside the scroll content. |
| 42dp status row, 0x0dp `loadingCursor`, dead `statusErrorIcon`, a 3000ms auto-dismissing error (`§4`, `§10 I13–I14`, `§12`). | Fixed-height `StateView` (`ds_state.xml`) with loading / status / empty / error variants; a `LiveDotView` + `Active` pill; `DesignSystem.dialog` / `snackbar` replace raw `AlertDialog` / `Toast`. State changes never shift surrounding layout. |
| 70dp `item_time_box` cards with `"00"` / `"TIME"` inside a hidden 0dp `legacyControls` block (`§4` rows 12–13); hardcoded `"--"` and `"00"` placeholders (`§8`). | Four visible tabular cells bound to the existing 1s countdown (`exdate()` numeric and 7-date formats unchanged); a short `—` placeholder (`not_available_short`) for the hero, cells, expiry and status so nothing wraps at 320dp or `font_scale 1.3`. Hidden legacy IDs are still bound but never drawn. |
| `AnoyButton` drew its own dots / sine wave / progress fill; download row hid a 20dp spinner (`§1`, `§4`). | `PrimaryButton.setBusy()` swaps label for indicator at constant width; `Widget.Ds.Button.Launch` (58dp) drives the real launch / install / update / stop action; bento tiles show live `Build.MODEL` and native `modStatus()`; the safety banner is shown only when native `credit()` returns text. |

Screenshots: [main_light.png](screenshots/main_light.png),
[main_dark.png](screenshots/main_dark.png),
[main_font_130.png](screenshots/main_font_130.png),
[main_320.png](screenshots/main_320.png).

### 1.3 WebView route — `FacebookActivity`, `activity_facebook.xml`

| Before (`AUDIT.md`) | After (final build) |
| --- | --- |
| `RelativeLayout` + full-bleed WebView with no insets; web content rendered behind opaque bars; spinner centred on the raw window (`§10 I11`). | 56dp `Widget.Ds.TopAppBar` with a labelled 48dp back button, then a `FrameLayout` web container that consumes `safeDrawing` insets. The spinner uses `Widget.Ds.Progress` + `@color/accent` and `contentDescription`. |
| Route was declared but never launched (`§4` row 3). | Activity, manifest entry and `facebook_login_url` are unchanged; the WebView still loads the live site. Only the surrounding chrome is redesigned — the external page's own blue branding is third-party content, not app UI. |

Screenshots: [facebook_light.png](screenshots/facebook_light.png),
[facebook_dark.png](screenshots/facebook_dark.png).

### 1.4 Dialogs, states and component preview

| Before (`AUDIT.md`) | After (final build) |
| --- | --- |
| VPN dialog was a platform `AlertDialog.Builder`; OBB result used `MaterialAlertDialogBuilder`; `ios_loading.xml` was a dead 280dp layout; OBB progress was a bare `Dialog` (`§4` rows 4, 8, 9, 11). | `DesignSystem.dialog()` uses `ThemeOverlay.Ds.Dialog` + `ds_dialog.xml` (24dp radius, matte elevated surface); `progress_dialog.xml` now uses `Widget.Ds.Progress.Linear` and `TextAppearance.Ds.*`; `ds_snackbar.xml` is a matte pill with a polite live region; `StateView` covers loading / empty / error. `ios_loading.xml` is deleted. |
| No component library, no dark-mode preview (`§3`). | Debug-only `DsPreviewActivity` + `ds_preview.xml` (in `app/src/debug/`, absent from release APKs) shows the type scale, buttons, input pod, surfaces, progress, states, icon set, component styles and bottom nav in both appearances. |

Screenshots: [components_light.png](screenshots/components_light.png),
[components_dark.png](screenshots/components_dark.png).

---

## 2. Full token list

Raw hex, dp, sp and ms values exist **only** in the token layer:
`res/values/colors.xml`, `res/values-night/colors.xml`, `res/values/dimens.xml`,
`res/values/styles.xml`, `res/values/attrs.xml`, `res/color/*`,
`res/drawable/*`, `res/anim/*` and `java/com/xlox/ui/designsystem/*`.
`xlox/scripts/ui_quality_gate.py` fails on any raw value outside it.

### 2.1 Colour tokens (identical names in light + dark; `values-night` wins in dark)

| Group | Token names | Light | Dark |
| --- | --- | --- | --- |
| Surfaces | `background` | `#F6F5F2` | `#0D0E11` |
| | `surface` | `#FFFFFF` | `#14161B` |
| | `surface_elevated` | `#FFFFFF` | `#191C23` |
| | `surface_recessed` | `#EFEEEA` | `#1B1E26` |
| | `surface_subtle` | `#F0EFEA` | `#171A20` |
| | `outline`, `hairline` | `#E5E4E0` | `#262934` |
| | `hairline_strong` | `#D8D6D0` | `#333744` |
| | `top_light`, `top_light_soft` | `#0FFFFFFF`, `#08FFFFFF` | same |
| Type | `text_primary` | `#121316` | `#F2F3F5` |
| | `text_secondary`, `icon` | `#555961` | `#A3A9B6` |
| | `text_tertiary` | `#656A71` | `#8A91A0` |
| Accent | `accent` | `#E67E53` | `#E67E53` |
| | `accent_pressed` | `#D26B43` | `#D26B43` |
| | `accent_ink` | `#A8441F` | `#F39A78` |
| | `on_accent` | `#14100E` | `#14100E` |
| | `accent_subtle`, `accent_wash` | `#1AE67E53`, `#14E67E53` | `#26E67E53`, `#14E67E53` |
| Semantic | `success` / `success_ink` / `success_wash` | `#2ECC71` / `#12703D` / `#1E2ECC71` | `#2ECC71` / `#2ECC71` / `#1A2ECC71` |
| | `warning` / `warning_ink` / `warning_wash` | `#F59E0B` / `#8F5600` / `#1EF59E0B` | `#F59E0B` / `#F59E0B` / `#1AF59E0B` |
| | `error` / `error_ink` / `error_wash` | `#E0574F` / `#B2332C` / `#1EE0574F` | `#F07167` / `#F07167` / `#1AF07167` |
| Derived | `ripple`, `accent_disabled`, `on_accent_disabled`, `transparent` | `#14121316`, `#61E67E53`, `#6114100E`, `#00000000` | `#14F2F3F5`, `#61E67E53`, `#6114100E`, `#00000000` |
| Splash | `splash_background` | `#0D0E11` | `#0D0E11` |

Six colour-state lists: `ds_accent_text_csl`, `ds_button_text`,
`ds_chip_bg`, `ds_control_tint`, `ds_nav_item_tint`, `ds_text_primary_csl`.
`accent` is a fill-only token; all text-on-accent uses `on_accent`, and all
accent-coloured text on a surface uses `accent_ink`.

**WCAG AA:** the gate checks 16 token pairs per appearance at >= 4.5:1.
Lowest passing pairs are `on_accent/accent_pressed` 5.34,
`accent_ink/background` 5.49 (light), `on_accent/accent` 6.73 and
`accent/background` 6.87 (dark). `accent/background` in light (2.58) is
excluded because `accent` is never used as text on `background` there.

### 2.2 Spacing, geometry and shape

- 8dp scale: `space_4/8/12/16/20/24/32/40/48`.
- Layout: `screen_padding` 20dp, `section_gap` 24dp, `card_padding` 20dp,
  `tile_padding` 16dp, `touch_target` 48dp, `divider` / `stroke` 1dp,
  `status_rule` / `underline` 2dp.
- Radii: `radius_button` 16dp, `radius_pill` / `radius_avatar` 1000dp,
  `radius_card` 20dp, `radius_tile` 18dp, `radius_input` 14dp,
  `radius_dialog` / `radius_sheet` 24dp, `radius_image` 12dp,
  `radius_splash` 28dp.
- Component heights: `top_bar_height` 56dp, `login_button_height` 56dp,
  `launch_button_height` 58dp, `field_height` 56dp, `chip_height` 32dp,
  `list_item_single` 56dp, `list_item_double` 72dp, `state_height` 56dp,
  `banner_height` 56dp, `version_pill_height` 26dp, `toggle_width` 72dp /
  `toggle_height` 36dp, `emblem_size` 64dp, `emblem_container` 88dp,
  `splash_icon` 108dp, `avatar_size` / `mark_size` 40dp, `dot_size` 8dp,
  `dialog_inset` 24dp.
- Icons / progress: `icon_bar` 24dp, `icon_inline` 20dp, `icon_button` 18dp,
  `progress_linear` 3dp, `progress_circular` 20dp,
  `progress_circular_large` 40dp, `stagger_step` 40.

### 2.3 Typography

Inter (`inter_regular`, `inter_medium`, `inter_semibold`) for copy and
JetBrains Mono (`jetbrains_mono_regular`) for tabular telemetry, both bundled
with their OFL licences. 12 sans text appearances (`DisplayLarge` 32sp,
`HeadlineLarge` 28sp, `HeadlineMedium` 24sp, `HeadlineSmall` 20sp,
`TitleLarge` 18sp, `TitleMedium` 16sp, `TitleSmall` 14sp, `BodyLarge` 16sp,
`BodyMedium` 14sp, `BodySmall` 12sp, `LabelLarge` 14sp, `LabelMedium` 12sp,
`LabelSmall` 11sp), 4 mono appearances (`Mono.Hero` 40sp, `Mono.Large` 24sp,
`Mono.Medium` 16sp, `Mono.Small` 12sp), plus `TextAppearance.Ds.Secondary`,
`.Tertiary`, `.Micro`. `TextAppearance.Ds.Base` sets `textAllCaps=false`,
`includeFontPadding=false`, `letterSpacing=0`. Every size/line-height is a
`@dimen` token.

### 2.4 Motion, haptics and shape appearances

`DsMotion`: `STATE=150ms`, `SCREEN=380ms`, `SHEET=300ms`, `STAGGER=40ms`,
`FastOutSlowInInterpolator`, critically damped springs, 0.97x press,
`ValueAnimator.areAnimatorsEnabled()` respected. `Haptics`: `VIRTUAL_KEY`
(tick), `CLOCK_TICK` (toggle), `CONFIRM` / `KEYBOARD_TAP` (success),
`REJECT` / `LONG_PRESS` (error) — no extra permission.
`ShapeAppearance.Ds.Small/Medium/Large` cover buttons, inputs, cards/sheets.
`res/anim/ds_enter.xml`, `res/anim/ds_exit.xml` handle activity transitions.
No looping animation, no gradient, no shadow/elevation, no dynamic colour.

---

## 3. Component library

### 3.1 Styles (`res/values/styles.xml`, 52 named styles)

- **Theme:** `AppTheme`, `Theme.App.Splash`, `ThemeOverlay.Ds.Dialog`.
- **Text:** `TextAppearance.Ds.Base`, 12 sans scales, `Mono.Base` + 4 mono
  scales, `Secondary`, `Tertiary`, `Micro`.
- **Shape:** `ShapeAppearance.Ds.Small/Medium/Large`.
- **Buttons:** `Widget.Ds.Button`, `.Primary`, `.Launch`, `.Secondary`,
  `.Text` (48dp minimum, coral single-accent fill, disabled 38%).
- **Fields / bars:** `Widget.Ds.TextField`, `.TopAppBar` (56dp),
  `.IconButton` (48dp).
- **Surfaces:** `Widget.Ds.ListRow`, `.Card`, `.Tile`, `.Pill`, `.Badge`,
  `.Chip`, `.BottomNav`, `.Tabs`.
- **Controls:** `Widget.Ds.Switch` (+ `values-v23` `trackTint`),
  `.CheckBox`, `.RadioButton`, `.Progress.Linear`.
- **State:** `Widget.Ds.StatusText`, `.Error`, `.Success`, `.Warning`.

### 3.2 Java classes (`java/com/xlox/ui/designsystem/`)

| Class | Responsibility |
| --- | --- |
| `DesignSystem` | edge-to-edge + insets, safe drawing, bottom insets, dialogs, snackbars, messages |
| `ThemeMode` (`java/com/xlox/ui/`) | dark-first persisted appearance, toggle, TalkBack label, debug-only per-launch QA override |
| `Tokens` | typed `ContextCompat` colour / dimension resolution |
| `DsMotion` | motion tokens, springs, stagger, press scale, reduced-motion guard |
| `Haptics` | framework micro-haptics |
| `PrimaryButton` | 56/58dp accent button with constant-width busy state |
| `DsSurface` | matte card with a 1dp top-lit edge |
| `TopLitDrawable` | top-lit hairline drawable |
| `StateView` | fixed-height loading / status / empty / error row |
| `LiveDotView` | pulsing status dot |
| `ThemeToggleView` | 72x36dp spring sun/moon appearance switch |
| `Splash` | AndroidX SplashScreen bridge + post-exit bar restoration |

### 3.3 Layouts and drawables

8 main layouts (`activity_login`, `activity_main`, `activity_facebook`,
`ds_dialog`, `ds_snackbar`, `ds_state`, `item_time_box`, `progress_dialog`)
plus debug `ds_preview`. 59 drawables: 34 `ds_ic_*` vectors, 24 shape/state
drawables (`ds_card`, `ds_tile`, `ds_pill`, `ds_badge`, `ds_chip`, `ds_sheet`,
`ds_avatar`, `ds_dialog`, `ds_snackbar`, `ds_status_pill`, `ds_version_pill`,
`ds_field_rest/focus/error`, `ds_button_primary/secondary/text`,
`ds_progress`, `ds_divider`, `ds_tab_indicator`, `ds_icon_ripple`,
`ds_emblem_a`, `ds_emblem_container`, `ds_splash_icon`) and
`ic_anoy.png` — the frozen launcher icon, deliberately retained.
6 colour-state lists, 2 activity animations, 4 fonts, 3 licence files.

### 3.4 Icon set

34 monochrome Lucide 1.69.0 (ISC) vectors, all on a 24dp viewport with a
1.75dp stroke, `round` caps/joins and `@color/icon` tint. Sizes come from
`icon_bar` (24dp), `icon_inline` (20dp) and `icon_button` (18dp). One family,
no brand rasters, no platform stock icons, no filled/multicolour icons, no
icon font. `ds_ic_package.xml` was corrected to a valid `PathParser` path and
the QA gate now validates every `android:pathData` command's argument count.

---

## 4. Removed assets and dependencies

Relative to the v1 baseline (`3c6c761`) and the superseded B.1 direction:

| Removed | Reason |
| --- | --- |
| `androidx.cardview:cardview:1.0.0` | 0 usages; replaced by `DsSurface` / `Widget.Ds.Card`. |
| `viewBinding true` build flag | 0 binding classes used; `findViewById` retained. |
| `lint { abortOnError false }` + non-empty baseline | replaced with `abortOnError true`, an empty baseline and a `lint.xml` that ignores only 9 pre-existing non-UI loader constraints. |
| `res/values/themes.xml` (unused M3 `Base.AppTheme`, `ActiveIndicator`, `CustomCheckbox02`) and `res/values-night/styles.xml` near-duplicate | single `AppTheme` + `values-night/colors.xml` token parity. |
| `res/layout/ios_loading.xml` | never inflated (`§4` row 11). |
| 18 unused v1 drawables + 3 `checkbox_*` vectors + 5 raster assets (`bgmi.webp`, `logo.webp`, `logo_transparent.png`, `ic_facebook.png`, `ic_twitter_logo.xml`, `ic_facebook_logo.xml`, `ic_eye.xml`, `ic_theme.xml`, …) | `UnusedResources` cleanup; mixed filled/brand icons replaced by the single Lucide family. |
| `res/font/press_start_2p.ttf` | bundled but unused, no licence. |
| B.1-era `source_serif_4_regular.ttf`, `source_serif_4_semibold.ttf`, `OFL-SourceSerif4.txt`, 25 Material Symbols vectors and `Apache-2.0-MaterialSymbols.txt` | v2 direction replaced Source Serif 4 / Material Symbols with Inter / JetBrains Mono / Lucide. |
| 3 unused v1 strings (`copyright`, `login_title`, `main_title`) and all hardcoded `"--"` / `"00"` / `"TIME"` placeholders | 86 sentence-case strings in `values/strings.xml` (plus 38 debug preview strings); short `—` state placeholder. |
| Raw `Toast` / `AlertDialog` / `MaterialAlertDialogBuilder` UI surfaces | replaced by `DesignSystem` dialogs and snackbars. |
| Deprecated `View.setSystemUiVisibility` bar handling in `ThemeMode.java` | replaced by `WindowCompat` / `WindowInsetsControllerCompat`. |

**Kept / added:** `androidx.appcompat:appcompat` upgraded 1.2.0 → 1.6.1;
`androidx.core:core:1.13.1` pinned explicitly so the compat insets API is
guaranteed; `androidx.core:core-splashscreen:1.0.1` and
`androidx.dynamicanimation:dynamicanimation:1.0.0` added for the splash and
spring motion. `com.google.android.material:material:1.11.0` is retained
because `AppTheme` inherits `Theme.MaterialComponents.DayNight.NoActionBar`,
`ThemeOverlay.Ds.Dialog` inherits the Material dialog overlay, and the debug
component preview uses `BottomNavigationView`. `multidex`, the two local AARs
(the virtual-container SDK), `zip4j`, `arm64-v8a` ABI filter, NDK 24,
`minSdk 21` / `targetSdk 28`, version 1.1.0 / code 4 and the signing config are
unchanged. No new remote UI library, no icon-font or SVG library, no runtime
font/icon download.

---

## 5. Minimal logic changes

The redesign is a view-layer change. Business logic is untouched:

- `LogAct`: native `Check`, `Prefs`, clipboard and the VPN guard are unchanged.
  Added `Splash.install` / `exit`, `ThemeMode`, inset helpers, a
  `TransitionDrawable` focus ring, `Haptics`, and `onWindowFocusChanged` to
  restore theme-aware system-bar icons after the platform splash releases them.
  First-launch special-access intents are now guarded against OEM ROMs that
  omit the "All files access" / "Install unknown apps" settings pages, with a
  generic fallback; `onActivityResult` no longer kills the process (that read
  as a crash on launch) and no longer loops back into the same settings page.
- `MAct`: parses `exdate()` (numeric + 7-date), ticks the countdown every 1s,
  keeps `Downtwo`, `LibManager`, `AppManager`, Meta*, OBB copy, launch, install,
  stop and update flows. Only the binding targets and the unavailable-value
  placeholder changed; no value is faked.
- `FacebookActivity`: same `WebView`, same `facebook_login_url`, same cookies /
  WebViewClient; only the surrounding chrome and inset handling changed.
- `AppManager`: same OBB copy flow; its dialog now inflates the v2
  `progress_dialog.xml`.
- `ThemeMode`: same persisted `anoy_appearance/mode` preference, dark-first
  default and `AppCompatDelegate` switching. A debug-only `qa_appearance`
  intent extra is honoured **only** when `ApplicationInfo.FLAG_DEBUGGABLE` is
  set, so CI can capture both palettes from one installed APK; release builds
  ignore it.
- `xlox/app/proguard-rules.pro`: the release variant keeps the design-system
  package, the three activities, `AppManager`, `ThemeMode` and the compat
  splash / insets entry points, so R8 + `shrinkResources` cannot remove a class
  that layouts or the splash screen inflate by name.
- `Splash.exit`: the post-splash callback, the animated exit and the delayed
  `withEndAction` are each exception-guarded, so a cosmetic splash animation
  can never abort startup (including with animations enabled on real devices).
- `xlox/scripts/ui_screenshots.sh` and `.github/workflows/android-ui-qa.yml`
  are QA-only tooling: direct emulator launch, 14 captures including a release
  APK launch smoke test, foreground-app assertions, full logcat crash capture,
  path/contrast/duplication validation, a zero-warning lint gate and one
  idempotent publisher step.

---

## 6. Verification

CI evidence: `Android UI QA` run **42236016331** and `Build XLOX loader APK`
run **42236016572**, both fully green. All 15 QA steps pass: debug APK build,
Android Lint, resource/literal quality gates, emulator screenshots, screenshot
validation, commit, upload and publish. `qa-logs/` holds the emulator trace,
boot/stdout, logcat, lint report and assembler log; the artifact
`ui-screenshots` holds the same PNGs with 30-day retention.

### 6.1 Actual emulator screenshots (13 frames, API 33 / 1080x2340, 320x640 for the narrow frames)

| Frame | What it proves |
| --- | --- |
| `login_light.png`, `login_dark.png` | both palettes, sun/moon toggle, contrast, 48dp targets, dark icons on light / light icons on dark status bars |
| `main_light.png`, `main_dark.png` | hero, four time cells, bento tiles, launch button, fixed status area in both palettes |
| `facebook_light.png`, `facebook_dark.png` | WebView chrome, back button, inset handling in both palettes |
| `components_light.png`, `components_dark.png` | debug component preview renders (not a launcher/home screen) |
| `login_font_130.png`, `main_font_130.png` | `font_scale 1.3`: no clipping, no overlap, controls still reachable |
| `login_320.png`, `main_320.png` | 320dp width: no wrapping/overlap; scrollable content keeps the launch control reachable |
| `login_keyboard.png` | soft keyboard open: `adjustResize` + IME insets, field and Sign in button not covered |

`Validate screenshots` (stdlib-only PNG decoder) requires all 13 named frames,
>= 5000 bytes each, unique MD5s, a >= 60 first-scanline luminance difference
between `login_light` and `login_dark`, >= 200 legible status-bar icon pixels in
every frame (dark icons on light / light icons on dark), 12
`mCurrentFocus=...com.xlox...` trace lines, a `mInputShown=true`
keyboard trace line and no `com.xlox` `FATAL EXCEPTION` in logcat.
`qa-logs/emulator-trace.txt` records the light → dark → light sequence, all
captures and the ABI-neutral install fallback (the debug APK is arm64-only, the
emulator is x86_64; every JNI call is guarded).

### 6.2 Strict visual inspection

All 13 frames were opened and inspected. Findings and resolutions:

- Light frames use the warm alabaster `#F6F5F2` background with white cards;
  dark frames use obsidian `#0D0E11` with `#14161B` surfaces. No mixed-palette
  frames, no fullscreen/ANR overlays, no launcher/home contamination.
- No clipped or overlapping controls at 1080x2340, 320x640 or font scale 1.3.
  Long live values (device model, status, expiry) ellipsize with `…` rather than
  wrapping into adjacent cells. The earlier `Not available` wrap in the hero and
  four time cells was fixed with the `—` short placeholder; the earlier 320dp
  truncated field hint was shortened to `Enter your key`.
- Grid alignment: 20dp screen padding, 24dp section gaps, four equal-weight
  time cells, one accent-filled control per screen, no shadows or gradients.
- System bars: transparent, edge-to-edge, with theme-aware icon contrast. The
  platform splash used to leak white icons onto the cream login status bar;
  the post-exit restoration fixed it (`login_light` / `login_font_130` /
  `login_320` now show dark icons).
- Keyboard: the IME frame shows the field and primary action above the keyboard;
  `applySafeDrawing(..., ime=true)` consumes the IME inset.

### 6.3 Accessibility

- WCAG AA on all 16 contrast pairs per appearance (gate).
- Every `ImageButton` / icon-only control has a `contentDescription` or is
  explicitly `importantForAccessibility="no"`; all use `@dimen/touch_target`
  (48dp).
- The access-key field's hint is its TalkBack label (lint `LabelFor` clean,
  `nextFocusForward` set). `ThemeToggleView` announces
  `switch_to_light` / `switch_to_dark`; `eyeToggle` announces its state;
  `ds_snackbar.xml` uses `accessibilityLiveRegion="polite"`; `StateView`,
  progress bars and the Facebook spinner have descriptions.
- Decorative views (`keyIcon`, `liveDot`, `snackbarIcon`) are excluded from the
  accessibility tree; the hidden `legacyControls` IDs remain bound but are not
  drawn or focusable.
- Animations honour `Settings > Accessibility > Remove animations`.

### 6.4 Quality gates

- `./gradlew :app:assembleDebug` and `:app:lintDebug` pass; lint text report is
  `No issues found.` (0 errors, 0 warnings). The QA lint step additionally
  fails on any non-clean summary.
- `xlox/scripts/ui_quality_gate.py`: **31/31 checks passed** — token parity,
  16 AA contrast pairs per appearance, no raw literals outside tokens, no
  emoji / exclamation marks, all resource references resolve, no unused
  resources, every Lucide `pathData` parses, labelled 48dp icon buttons.
- No unused or leftover assets: `UnusedResources` is clean; the only retained
  legacy asset is `ic_anoy.png`, still the manifest launcher icon.
- Web v1.2 unchanged; `npm test` 129/0 and the six browser smoke scripts
  (145/145) remain valid from the earlier, separate verification.

---

## 7. Known issues

**None.** All findings raised by the audit and by screenshot inspection have
been resolved and re-verified in the green CI runs above.

---

## 8. Adding a new screen with the design system

1. **Activity.** Extend `AppCompatActivity`. First line of `onCreate` must be
   `ThemeMode.apply(this);` (before `super.onCreate`), then `super.onCreate`,
   `setContentView(...)` and `ThemeMode.applyWindow(this);`. Add the activity to
   `AndroidManifest.xml` without changing any existing route.
2. **Root.** Give the root view an opaque `android:background="@color/background"`
   and `android:clipToPadding="false"`, then
   `DesignSystem.applySafeDrawing(root, true, true, imeNeeded)`. Use a
   `ScrollView` with `fillViewport` if content can exceed the viewport (needed
   for 320dp and font scale 1.3).
3. **Chrome.** Start with `style="@style/Widget.Ds.TopAppBar"` at 56dp, a
   `TextAppearance.Ds.TitleLarge` title and 48dp `Widget.Ds.IconButton`s with
   `contentDescription`. Use `DesignSystem.applyBottomInsets` for anything
   pinned to the bottom.
4. **Tokens only.** Never write a hex colour, dp, sp or `textSize` in a layout.
   Use `@color/*`, `@dimen/space_*`, `@dimen/radius_*`, `TextAppearance.Ds.*`,
   `Widget.Ds.*` and `ShapeAppearance.Ds.*`. Add new values to the token layer
   (`values/colors.xml` **and** `values-night/colors.xml`, `dimens.xml`,
   `styles.xml`) and to the contrast-pair list in `ui_quality_gate.py`.
5. **Strings.** Put every user-facing string in `res/values/strings.xml`
   (sentence case, no `!`, no emoji, no letter spacing). For debug-only preview
   copy use `app/src/debug/res/values/strings.xml`.
6. **Components.** Use `PrimaryButton` for the single accent action,
   `Widget.Ds.Button.Secondary` / `.Text` for secondary actions,
   `Widget.Ds.TextField` for inputs, `DsSurface` / `Widget.Ds.Card` / `.Tile`
   for surfaces, `StateView` for loading/empty/error, `DesignSystem.dialog()`
   and `DesignSystem.snackbar()` for messages, `Haptics` + `DsMotion` for
   feedback. Icons must come from the `ds_ic_*` Lucide set at `icon_bar` /
   `icon_inline` / `icon_button` sizes with `@color/icon` tint.
7. **Data.** Bind real native/network values only. A missing value uses
   `@string/not_available_short` (`—`) in tight cells and
   `@string/not_available` in a full-width row; never fake a live variable.
8. **Accessibility.** Label every icon-only control, keep 48dp targets, provide
   an accessible name for inputs, set focus order, exclude decorative views,
   and give dynamic messages a polite live region.
9. **Verify locally.** `cd xlox && python3 scripts/ui_quality_gate.py` (must be
   31/31) and parse all XML. Then push to the branch: CI builds the debug and
   release APKs, runs lint with a zero-warning gate, boots the emulator,
   captures the 13 frames and validates them. Add the new screen's
   light/dark/1.3/320dp captures to `xlox/scripts/ui_screenshots.sh` and to the
   expected set in the workflow's `Validate screenshots` step, then update the
   before/after pair and token/component tables in this report.

---

## 9. Deliverables

- `docs/ui-redesign/AUDIT.md` — approved Part A audit.
- `docs/ui-redesign/REPORT.md` — this report.
- `docs/ui-redesign/screenshots/*.png` — 14 CI/emulator frames.
- `xlox/app/src/main/res/**`, `xlox/app/src/main/java/com/xlox/ui/**` — tokens,
  component library, activities and screen layouts.
- `xlox/app/src/debug/**` — debug-only component preview and QA manifest.
- `xlox/scripts/ui_quality_gate.py`, `xlox/scripts/ui_screenshots.sh`,
  `.github/workflows/android-ui-qa.yml` — gates and QA harness.
- `qa-logs/**` — CI emulator, build, lint and logcat evidence.
- `dist/Anoy-XLOX-loader.apk` — built release loader APK.
