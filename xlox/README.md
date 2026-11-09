# Anoy loader (Android)

Android (arm64-v8a) loader app `com.xlox`, wired to the **Anoy** panel and
built in CI. minSdk 21, targetSdk 28, compileSdk 36. The current version is in
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
| **Splash** (launcher) | `SplashActivity`, `activity_splash` | Wordmark film (~3 s, reduced motion respected) and a status line while `AppUpdater.check` asks `GET /api/app/update`. A newer version code opens the update screen; "up to date" continues on its own; a failed check shows the reason and **Retry**. |
| **App update** | `AppUpdateActivity`, `activity_app_update`, `item_changelog` | Changelog, **Update now** (and **Not now** unless mandatory). `AppUpdater` downloads over https (redirects https-only, retries for 5xx/408/429), checks the APK is `com.xlox` with the announced, higher version code, then commits it to a `PackageInstaller` session. Errors are shown inline with **Try again**. |
| **Library gate** | `ui/UpdateGate`, `dialog_update` (bottom sheet) | Checks `GET /api/lib/manifest`. When a newer library exists, or none is installed, the only action is **Update now**; Back closes the app; sign in stays blocked until the download is verified (size + SHA-256) and installed. Also re-runs silently when the app returns to the foreground, and backs Settings → Re-download library. |
| **Sign in** | `LogAct`, `activity_login` | Takes the licence key (`ANOY-7D-GKFHJ`); the native code calls `POST /connect` and verifies the token. Readable errors for unknown / expired / blocked keys, device limit, clock, maintenance, offline, timeout, VPN. |
| **Home** | `MAct`, `activity_main` | Licence expiry card (IST countdown), game card (bundled BGMI icon, library, state, server), announcement, one primary action: **Launch** / **Stop** / **Install game** / **Sign in again**. Launch copies the OBB (`utils/AppManager`) and starts the game inside the container. |
| **Settings** | `MAct.openSettings`, sheets `sheet_*` | Licence (masked key, status, expiry), Updates (check for app updates, re-download library), device, support info, Telegram report, Sign out / Forget. Permissions are requested through `ui/PermissionSheet` (`sheet_permission`). |

## Design

Black background, white text, one accent (`#0A84FF`). Views + XML only (no
Compose), Material Components 1.13 (`MaterialButton`, cards, bottom sheets),
Lottie for two small animations (`res/raw/anoy_sync.json`,
`anoy_success.json`), fonts Inter and JetBrains Mono (`res/font`).

| File | Contents |
|---|---|
| `res/values/colors.xml` | every colour token (raw colours appear only here) |
| `res/values/styles.xml` | `AppTheme`, `Text.*` type scale, `Widget.Anoy.*` buttons / cards / sheets / progress, rows |
| `res/values/dimens.xml`, `motion.xml`, `strings.xml` | spacing, motion timings, every user-visible string |
| `res/layout/` | 5 activities (`activity_*`), 5 bottom sheets (`sheet_*`, `dialog_update`), 2 list items |
| `java/com/xlox/ui/Ui.java` | helpers: busy buttons, reveal animation, haptics, byte formatting |

`scripts/ui_quality_gate.py` enforces the rules statically (palette-only
colours, styled MaterialButtons, no Toast / default ProgressBar, every
referenced resource exists, format arguments match) and runs in every build.

## Reliability

- **Downloads** (`utils/Downtwo`): manifest with legacy `/api/lib/version`
  fallback; up to 3 attempts with back-off for retryable errors; free-space
  check; 64 MB cap; streams to a temporary file; verifies size and SHA-256
  from the manifest; checks ZIP and ELF magic; extracts into staging and swaps
  the library in atomically; the installed version is recorded only after
  everything succeeded. Cancelling or leaving the screen stops the download.
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

All panel URLs live in **one** file: `app/src/main/jni/panel_config.h`.
`ANOY_STATIC_WORDS` must equal the panel's `STATIC_WORDS`.

## Panel contract used by the app

- `GET /api/lib/manifest` → `{ ok, version, available, size, sha256, … }`.
- `GET /api/lib/download` → ZIP containing `libbgmi.so` (or legacy `lib.so`),
  headers `X-Release-Version` and `X-Content-SHA256`; 302 for external files;
  404 when nothing is published.
- `POST /connect` with `game=PUBG&user_key=…&serial=…` →
  `{ status, data: { token, EXP, mod_status, credit, rng, … }, reason }`.

The library is installed as `files/loader/libbgmi.so` and `BoxApplication`
loads it inside the `com.pubg.imobile` virtual process.

## Publishing a loader library

Build a ZIP whose root contains the real arm64-v8a `libbgmi.so` (16 KB
aligned, see below), then upload it at **/lib** in the panel, or:

```bash
zip -j my-loader.zip libbgmi.so
PANEL_ADMIN_USERNAME=Anoy PANEL_ADMIN_PASSWORD='…' \
  node scripts/publish-release.mjs my-loader.zip 1.11
```

`scripts/publish-release.mjs` signs in, uploads the ZIP to `/lib`, then checks
`/api/lib/version` and that `/api/lib/download` returns the same bytes. Every
installed app must update to the new version before it can sign in again.

`loader-stub/bgmi_stub.c` compiles a harmless test `libbgmi.so`
(`JNI_OnLoad` only); CI packages it as `xlox-loader.zip`. It is **not** a game
library.

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
`-Wl,-z,common-page-size=16384`, and the starter ZIP is built with the same
flags. CI fails the build if `libXloX.so` or `libbgmi.so` regresses.

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
python3 scripts/check_elf_alignment.py --fail-on-unaligned app/build/outputs/apk/release/app-release.apk
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
./gradlew assembleRelease
```

CI (`.github/workflows/build-xlox.yml`) runs on pushes touching `xlox/**`:
static UI gates, SDK/NDK install, signed release APK, Android Lint, APK
permissions check (`scripts/check_apk_permissions.py`), ELF alignment, the
`xlox-apk` artifact (7 days) and the GitHub release
`anoy-v<versionName>-<versionCode>` (Latest; all other releases and tags are
deleted). Nothing is committed back. `workflow_dispatch` can additionally
publish the starter ZIP to `/lib` when `publish_loader_zip` is ticked (needs
the `PANEL_OWNER_PASSWORD` repository secret). Manual workflows:
`android-ui-qa.yml` (emulator screenshots of every screen, uploaded as the
`ui-qa` artifact) and `app-update-e2e.yml` (in-app update against the real
release asset on an emulator).

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
`build-xlox.yml` still compiles and lints, but builds an unsigned APK and
skips the GitHub release. The UI QA workflow re-signs
its test install with a throwaway key either way. CI never generates a new
key: APKs signed with a different key cannot update an installed copy.
