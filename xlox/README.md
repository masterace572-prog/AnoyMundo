# Anoy loader (Android, v2.0.0)

Android (arm64-v8a) loader app `com.xlox`, wired to the **Anoy** panel and
built in CI. minSdk 21, targetSdk 28, compileSdk 36.

## Screens and flow

```
SplashActivity ──► LogAct (update gate ► licence-key sign in) ──► MAct (home)
```

| Screen | What it does |
|---|---|
| **Splash** (`SplashActivity`, launcher) | Black screen, “Anoy” wordmark, caption and version. No logo image. ~1 s, then sign in. |
| **Update gate** (`ui/UpdateGate`, dialog over sign in) | Always checks `GET /api/lib/manifest` first. **Update required** when the panel has a newer library or none is installed: the only button is **Update now**, Back closes the app, sign in stays disabled until the download is verified and installed. Shows real progress (`42% · 1.2 MB of 2.9 MB`). Network problems show a plain reason (offline, timeout, DNS, TLS, server error, no space) and **Try again**. If the library is current the dialog closes by itself. |
| **Sign in** (`LogAct`) | Login-style screen that takes the licence key (`ANOY-7D-GKFHJ`). Upper-case input, paste, show/hide, inline errors for unknown / expired / blocked keys, device limit, clock, invalid token, maintenance message, offline, timeout and VPN. |
| **Home** (`MAct`) | Grouped list: licence status, expiry, live time left, game + state, library version, server status, device. One blue primary action: **Launch**, **Stop**, **Install game** or **Sign in again**. |

The gate runs again silently whenever the app returns to the foreground (at
most once a minute, never during a launch), so a new release published while
the app is open is still enforced.

## Design

Black background, white text, blue rounded buttons, iOS-style grouped rows.
Text never has a background of its own. No Material Components, no splash
library, no images — everything is vector/shape drawables, the Inter and
JetBrains Mono fonts (`res/font`) and four layouts:

| File | Contents |
|---|---|
| `res/values/colors.xml` | the whole palette (black, white, three text greys, blue, green, orange, red, separator) |
| `res/values/styles.xml` | `AppTheme`, `AppTheme.Splash`, `AnoyDialog`, text styles `Text.*`, `Button.Primary`, `Button.Text` |
| `res/values/dimens.xml`, `strings.xml` | sizes and every user-visible string |
| `res/layout/` | `activity_splash`, `activity_login`, `activity_main`, `dialog_update` |
| `java/com/xlox/ui/Ui.java` | small helpers: reveal animation, busy buttons, haptics, byte formatting |

`scripts/ui_quality_gate.py` enforces this (no logo, palette-only colours, no
text backgrounds, black/white/blue theme, every resource exists, format
arguments match) and runs in CI.

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
python3 scripts/check_elf_alignment.py --fail-on-unaligned ../dist/Anoy-XLOX-loader.apk
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

CI (`.github/workflows/build-xlox.yml`) runs on pushes touching `xlox/**`,
installs the SDK/NDK, builds the signed release APK, runs Android Lint,
checks ELF alignment, uploads the `xlox-apk` artifact and creates a GitHub
release `xlox-v2.0.0-run<N>`. `workflow_dispatch` can additionally publish the
starter ZIP to `/lib` when `publish_loader_zip` is ticked (needs the
`PANEL_OWNER_PASSWORD` repository secret). `android-ui-qa.yml` captures
emulator screenshots of every screen into `docs/ui-redesign/screenshots`.

Signing uses `app/xlox.jks` (`key0` / `11111111`). CI regenerates a local
keystore if the bundled one is unusable, so replace it with your own keystore
for a real release — APKs signed with different keys cannot be updated in place.
