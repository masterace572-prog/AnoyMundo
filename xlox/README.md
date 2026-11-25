# Anoy / KennyPvt loaders (Android)

Two Android arm64-v8a flavors, **Anoy** (`com.xlox`, channel `anoy`) and
**KennyPvt** (`com.kennypvt`, channel `kenny`), built together against one panel.
Each downloads only its own Owner's library and APK updates; licence keys,
billing and general settings remain shared. minSdk 21, targetSdk 28, compileSdk 36. The current version is in
`app/build.gradle` (`versionName` / `versionCode`); history in
[CHANGELOG.md](CHANGELOG.md).

## Screens and flow

```
SplashActivity ──► (AppUpdateActivity) ──► LogAct ──► MAct
  app update check    APK update          library gate   home + Settings
                                          + sign in
```

| Screen | Code / layout | What it does |
|---|---|---|
| **Splash** (launcher) | `SplashActivity`, `activity_splash` | Manrope wordmark film (~3 s, reduced motion respected), a blue final Hax and a quiet fade-through word change, plus a status line while `AppUpdater.check` asks `GET /api/app/update`. A newer version code opens the update screen; "up to date" continues on its own; a failed check shows the reason and **Retry**. |
| **App update** | `AppUpdateActivity`, `activity_app_update`, `item_changelog` | Changelog, **Update now** (and **Not now** unless mandatory). `AppUpdater` downloads over https (redirects https-only, retries for 5xx/408/429), checks the APK matches the running app's package (`com.xlox` or `com.kennypvt`) with the announced, higher version code, then commits it to a `PackageInstaller` session. Errors are shown inline with **Try again**. |
| **Library gate** | `ui/UpdateGate`, `dialog_update` (bottom sheet) | Checks `GET /api/lib/manifest`. When a newer library exists, or none is installed, the only action is **Update now**; Back closes the app; sign in stays blocked until the download is verified (size + SHA-256) and installed. Also re-runs silently when the app returns to the foreground, and backs Settings → Re-download library. |
| **Sign in** | `LogAct`, `activity_login` | Home-matched typography, a fitted single-line key field, clipboard icon and explicit show/hide control. Visibility changes keep the text, selection and keyboard input type; backgrounding masks again. The native code calls `POST /connect` and verifies the token. Readable errors for unknown / expired / blocked keys, device limit, clock, maintenance, offline, timeout, VPN. |
| **Home** | `MAct`, `activity_main`, `page_home` | Smaller Welcome back greeting; compact top-right expiry with baseline-aligned Active/Safe statuses that wrap when needed (exact IST date in Settings). Each BGMI/BGMI Air card contains its logo, installed game version, readiness/OBB hint and its own Launch/Stop/Install, feedback and progress. No pinned game-action bar or library-filename paragraphs. Unavailable Air shows only **Coming soon** in its disabled button. The persistent blue Home / Settings capsule supports taps and selector dragging; its blue decoration is only 2dp inset, with 48dp label targets. The pages also support horizontal/diagonal swipes over scrollable content/buttons. Launch prepares the full installed base/split APK set; BGMI additionally verifies/copies its OBB, while Lite/Air needs no OBB; a second game requires the 10-second warning and explicit confirmation. |
| **Settings** | `HomeSettingsNavigation`, `page_settings`, sheets `sheet_*` | Licence (masked key, status, expiry), Updates (check for app updates, re-download library), device, Help (`?`), support info, Telegram report, centered red Sign out / Forget. Permissions are requested through `ui/PermissionSheet` (`sheet_permission`). |

## BGMI Air in 2.4.3 (21)

The source follow-up supports complete installed split-APK sets and removes
Lite/Air's OBB requirement. BGMI still requires its own expansion files.
[Split-package behavior, tests and physical-device acceptance](../docs/SPLIT_APK_SUPPORT.md).
This follow-up is not yet part of the published APKs referenced below.

- BGMI: `com.pubg.imobile` → `libbgmi.so`.
- BGMI Air: `com.krafton.bgmilite` → `libbgmilite.so` (not `libbgmiair.so`).
- Include **both files together at the ZIP root** when enabling Air. Until that
  bundle is downloaded and installed, Air stays **Coming soon**. Normal game
  installation, licence, permission and container requirements still apply.
- Starting a second game is allowed only after ten seconds and **OK, launch
  anyway**. Cancel/Back/backgrounding never starts it. Stop affects only its game.
- [Bundle requirements, compatibility boundaries and device QA](BGMI-AIR.md).
  This is the **2.4.3 / code 21** user release after **2.4.2 / code 20**.
  Earlier 2.4.3 APKs were internal tests only. Users can upgrade the matching
  brand in place; live panel/library/APK selections are not changed.
- [Navigation, release scope and verification](RELEASE-2.4.3.md).

## Design

Black background, white text, one accent (`#0A84FF`). Views + XML only (no
Compose), Material Components 1.13 (`MaterialButton`, cards, bottom sheets),
Lottie for two small splash-status animations (`res/raw/anoy_arc.json`,
`anoy_check.json`). Home, Settings, Login and Splash share bundled static Manrope;
legacy sheets use Inter and licence/support text retains JetBrains Mono (`res/font`).

| File | Contents |
|---|---|
| `res/values/colors.xml` | every colour token (raw colours appear only here) |
| `res/values/styles.xml` | `AppTheme`, `Text.*` type scale, `Widget.Anoy.*` buttons / cards / sheets / progress, rows |
| `res/values/dimens.xml`, `motion.xml`, `strings.xml` | spacing, motion timings, every user-visible string |
| `res/layout/` | activity layouts (`activity_*`), retained Home / Settings pages (`page_*`), sheet/dialog layouts and list items |
| `java/com/xlox/ui/Ui.java` | helpers: busy buttons, reveal animation, haptics, byte formatting |

Home / Settings uses AndroidX ViewPager 1.0.0 (minSdk-compatible; no SDK,
Fragment, Material, AppCompat or native dependency upgrade). Its default physical
scroll places the full-width pages side by side, without an alpha transformer.
JUnit / Robolectric are host-test-only and are not packaged in either loader.

[Loader UI follow-up and device QA](LOADER-UI-QA.md) explains the gesture surfaces,
show/hide semantics, host coverage and the opt-in frame-timing tool. **60Hz is a
performance target, not a measured phone result from host tests.** Game FPS and
runtime behavior are unchanged. The verified UI is included in the
[first user-facing 2.4.3 release](https://github.com/masterace572-prog/AnoyMundo/releases/tag/loaders-v2.4.3-21)
(source `f8f5884`, 180/180 host checks plus both signed-APK gates).
See the [short 2.4.2 → 2.4.3 changelog](CHANGELOG-2.4.3.md) and
[release scope](RELEASE-2.4.3.md).

`scripts/ui_quality_gate.py` enforces the rules statically (palette-only
colours, styled MaterialButtons, no Toast / default ProgressBar, every
referenced resource exists, format arguments match) and runs in every build.

## Reliability

- **Downloads** (`utils/Downtwo`, `LibraryHttp`, `UpdateRequest`): fresh manifest
  with legacy `/api/lib/version` fallback; at most two attempts, a 20-second
  total metadata budget and 90-second total transfer budget (8-second connect /
  12-second idle-read limits). Closing the screen cancels queued work and its
  active connection, including while waiting for headers. Silent checks have a
  separate queue. The same manifest supplies the supported BGMI version; no
  duplicate home-screen request. Verified binaries may use the Storage/CDN cache,
  metadata may not. Retry after a failed transfer rechecks the manifest. Size,
  SHA-256, free space, ZIP/ELF validation, staging/atomic installation and the
  64 MB client cap remain in place. Local install time and response-header time
  are logged separately, without signed URLs or login credentials.
- **Contained game web login (candidate)** (`GameWebRouting`, `GameWebLoginActivity`):
  2.4.3's callback-only patch did not fix the tester's external-browser escape.
  The new game-process-only handler chains the original SDK's public activity
  interpreter, preserving result binder/request code/options. It prefers a
  matching virtual game web activity, otherwise opens a private loader WebView
  instead of the outside browser. The WebView resolves callback schemes, HTTPS
  App Links and scoped intent URIs dynamically against the running virtual BGMI,
  never the OS's real-game resolver. No native JS bridge, certificate bypass,
  user-agent spoofing, token logging, global browser filter or game restart.
  The old callback alias remains a fallback for already-external flows. See
  [contained-login QA and limits](../docs/CONTAINED_GAME_WEB_LOGIN.md).
- **Version/publication:** `-webtest`, `-test`, and `-rc` versions use non-latest
  prereleases. The owner-requested replacement is named 2.4.3 (21) and includes
  the existing contained-login candidate; this does not establish real-phone
  provider compatibility. Both original AARs and the original signer are retained.
  Preserve 2.4.2 and older releases; one-time replacement is not automated pruning.
- **Regression checks:** `python3 scripts/test_runtime_fixes.py` compiles and runs
  the production routing/transport classes on the JVM, plus Android wiring
  guards. Passing APK paths also verifies the actual merged callback manifest.
- **Errors** are mapped to short, readable messages (`utils/Net`,
  `utils/UpdateException`) — no raw exception text is shown to users.
- **Threads:** network, unzip and OBB copy run on background executors;
  callbacks return on the main thread and are ignored once the screen is gone.
- **OBB copy** (`utils/AppManager`): skips the copy when the container already
  has the current OBB, checks free space, copies to `.part` + fsync + rename,
  reports progress.
- **Native** (`jni/main.cpp`): null-safe JNI helpers, URL-encoded form fields,
  connect/read timeouts, one quiet retry for transient network errors, JSON
  `reason` shown for every rejection, `Panel returned HTTP <code>` for server
  errors, token verified against `STATIC_WORDS`, replay guard on `rng`.

Native endpoints live in `app/src/main/jni/panel_config.h` and are compiled
per Gradle flavor. Java's matching fallback base/channel/brand are in
`app/build.gradle` (`BuildConfig.PANEL_BASE_URL`, `RELEASE_CHANNEL`,
`DISPLAY_BRAND`). `ANOY_STATIC_WORDS` and the JNI namespace `com.xlox` stay
unchanged; the salt must equal the panel's `STATIC_WORDS`. KennyPvt overrides
all visible Anoy strings and launcher/system-splash wordmarks in
`app/src/kenny/res/`; class/resource names are deliberately not renamed.

## Panel contract used by the app

- `GET /api/lib/manifest?channel=anoy|kenny` → `{ ok, version, available, size, sha256, … }`.
- `GET /api/lib/download?channel=anoy|kenny` → ZIP containing `libbgmi.so` (or legacy `lib.so`),
  headers `X-Release-Version` and `X-Content-SHA256`; 302 for signed private Storage or external files;
  404 when nothing is published.
- `GET /api/app/update?channel=anoy|kenny&code=<installed>` → your own branded APK metadata.
- `POST /connect` with `game=PUBG&user_key=…&serial=…` →
  `{ status, data: { token, EXP, mod_status, credit, rng, … }, reason }`.

The library is installed as `files/loader/libbgmi.so` and `BoxApplication`
loads it inside the `com.pubg.imobile` virtual process.

## Publishing a loader library

Upload a raw arm64 `.so` **or** ZIP at **Library** in your signed-in Owner
panel, up to **10 MiB input and expanded contents**. Whatever the input's
filename, the main installed file is **libbgmi.so**. Valid sibling `.so`
dependencies in a ZIP are kept. Each Owner can publish only to their own
immutable channel. For scripted publication:

```bash
export PANEL_CHANNEL=anoy   # kenny for the other Owner
export PANEL_ADMIN_USERNAME=Anoy
# Set PANEL_ADMIN_PASSWORD securely in the environment, never in Git/logs.
node scripts/publish-release.mjs my-own-filename.so 1.4
# or: node scripts/publish-release.mjs my-library.zip 1.4
```

The script signs in, checks the session's channel, obtains a signed private
Storage PUT ticket, uploads binary bytes directly, then sends a small `/lib`
publication descriptor. It follows the final signed download and verifies
the **normalized** manifest size/SHA-256 (not the input ZIP's hash, which can
change during canonicalization). Old clients with no channel parameter stay
on Anoy; Kenny never falls back to Anoy. Installed clients must accept their
own channel's new library before signing in.

`loader-stub/bgmi_stub.c` is an optional harmless test library (`JNI_OnLoad`
only), **not** a game library. CI does not upload it to either live channel.

## Android 16 / 17 (API 36 / 37): "lib not loaded / ImGui menu does not show"

If the same APK works on an older phone but the menu disappears on a newer
one, check these three things in order. They are the only OS changes between
Android 15 and Android 17 that affect a downloaded, injected `.so`.

### 1. 16 KB memory pages (applies to **all** apps, any `targetSdk`)

Android 15+ devices can boot with 16 KB pages. The linker refuses to
`dlopen()` an ELF whose `PT_LOAD p_align` is smaller than the page size:

```
dlopen failed: "libbgmi.so" program alignment (4096) cannot be smaller
than system page size (16384)
```

The result is exactly "the lib did not load and the menu never appeared".
This repository used NDK r24, which links 4 KB-aligned binaries by default.
`Android.mk` now passes `-Wl,-z,max-page-size=16384` and
`-Wl,-z,common-page-size=16384`, for both flavors. CI fails the build if either APK's `libXloX.so` regresses.
Your separately uploaded library is not built or modified by Android CI.

**Your real `libbgmi.so` must be rebuilt too.** A 4 KB-aligned library in the
panel's release ZIP will still fail on a 16 KB device even with a fixed APK:

```bash
aarch64-linux-android21-clang -shared -fPIC -O2 \
  -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 \
  -o libbgmi.so your-loader.c
zip -j my-loader.zip libbgmi.so
# then upload my-loader.zip at /lib (Admin -> Library)
```

Check any `.so`, APK, AAR or loader ZIP with the bundled script (Python 3,
no dependencies):

```bash
python3 scripts/check_elf_alignment.py my-loader.zip
python3 scripts/check_elf_alignment.py --fail-on-unaligned app/build/outputs/apk/anoy/release/app-anoy-release.apk
```

On the device, confirm the page size and capture the exact linker error:

```bash
adb shell getconf PAGE_SIZE            # 4096 or 16384
adb logcat -s Load BoxApplication LibManager MAct LogAct Downtwo UpdateGate:V *:E
```

The APK now declares `android:pageSizeCompat="enabled"` on `<application>`.
On 16 KB devices this forces the linker's page-agnostic compatibility mode
(which also allows a 4 KB-aligned **downloaded** `libbgmi.so` to load), and it
suppresses the "app isn't 16 KB compatible" launch warning. It is ignored on
4 KB devices. `compileSdk` is 36 because the attribute was added in API 36;
`targetSdk` stays 28 deliberately (raising it breaks the hidden-API bypass).

If your ROM overrides the manifest setting, you can also turn compatibility on
at **Settings → Apps → [loader] → Advanced → Run app with page size compat
mode** (only shown when the kernel uses 16 KB pages), or via ADB:

```bash
adb shell setprop bionic.linker.16kb.app_compat.enabled true
adb shell setprop pm.16kb.app_compat.disabled false
```

Treat compatibility mode as a bridge, not a substitute for a real 16 KB build
of your injected library.

Note: `mundo-release.aar` still ships two prebuilt libraries that are 4 KB
aligned (`libmmkv.so`, plus `libLizard.so`/`libmsaoaidsec.so` which are already
compliant). `libmmkv.so` is Tencent MMKV built with clang 9; no class in this
project references it, but it cannot be relinked without the MMKV source. If
your device has 16 KB backcompat disabled globally (Android 17 supports
`bionic.linker.16kb.app_compat.enabled=fatal`), that AAR must be replaced with
a 16 KB-compatible build. The loader now reports this in logcat on startup.

### 2. Android 17 Safer Native Dynamic Code Loading

For apps targeting API 37, anything passed to `System.load()` must be
**read-only**. The old code called `setWritable(true)` on `libbgmi.so` right
before loading it — the exact failing case. `LibManager.makeReadOnly()` now
chmods it to `0444`, and `setPermissions()` no longer makes `.so` files
writable. This app targets API 28, so the change does not currently apply to
it, but the loader is now safe if `targetSdk` is ever raised.

### 3. Visible diagnostics

Previously a failed load was only written to logcat, so the user saw a missing
menu with no explanation. `BoxApplication` now logs the SDK level, ABI, real
page size, ELF alignment and the exact `dlopen` error, stores the last failure
in `filesDir/loader_error.txt` (readable across the virtual and UI processes),
and `MAct` shows it in the Library row and the status line when you return to
the app. It also tolerates virtual game-process
names with a suffix, which some newer Android builds report.

`ENABLE_IMGUI` is a leftover `public static boolean` from the original source
that this codebase never sets to `true`. If your injected library reads that
field via JNI to decide whether to draw the menu, set it after a successful
`System.load()` in `BoxApplication.onCreate(String, String)`.

## Building

Local (requires JDK 17, Android SDK platforms 34 and 36, build-tools 34.0.0
and NDK 24.0.8215888):

```bash
cd xlox
./gradlew :app:assembleAnoyRelease :app:assembleKennyRelease
./gradlew :app:lintAnoyRelease :app:lintKennyRelease
```

CI (`.github/workflows/build-xlox.yml`) runs panel/SQL tests, static UI and
two-brand isolation gates, **both** release builds/lint tasks, compiled APK
permissions/package/branding checks, native alignment and signatures. It
uploads separate `Anoy-APK` and `KennyPvt-APK` artifacts (14 days) and one
**`loaders-v<versionName>-<versionCode>`** GitHub release with both APKs and
SHA256SUMS. **No old releases/tags are deleted**, and neither live library nor
APK-update choice is automatically changed. A different source commit may
not overwrite an existing version; bump the version before a new app release.

Each Owner uses **App update → Use latest build** to select their own APK and
publish it independently. The manual Android screenshot and APK-update E2E
workflows keep using Anoy's preserved package and explicit Anoy flavor tasks;
compiled APK checks run for **both** flavors on every build.

### Release signing

The release key is `app/xlox.jks`. Its passwords are **not** in any tracked
file. Gradle reads them from environment variables, falling back to the
gitignored `xlox/local.properties`:

| Environment variable  | `local.properties` key | Notes                              |
|-----------------------|------------------------|------------------------------------|
| `XLOX_STORE_PASSWORD` | `xlox.storePassword`   | required for a signed release      |
| `XLOX_KEY_PASSWORD`   | `xlox.keyPassword`     | defaults to the store password     |
| `XLOX_KEY_ALIAS`      | `xlox.keyAlias`        | optional, defaults to the existing alias |
| `XLOX_STORE_FILE`     | `xlox.storeFile`       | optional, defaults to `xlox.jks`   |

In GitHub Actions, add `XLOX_STORE_PASSWORD` and `XLOX_KEY_PASSWORD` as
repository secrets (Settings → Secrets and variables → Actions). Without them
`build-xlox.yml` still compiles and lints, but builds two clearly labelled unsigned APKs and
skips the GitHub release. The UI QA workflow re-signs
its test install with a throwaway key either way. CI never generates a new
key: APKs signed with a different key cannot update an installed copy.
