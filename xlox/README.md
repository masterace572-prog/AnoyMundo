# Anoy loader — panel integration

Android (arm64-v8a) loader app, now wired to the **Anoy** panel and buildable in CI. The source was extracted from the archive
into this directory (the original archive stays at the repo root; the local
`_extract/` copy is git-ignored).

## Anoy UI (1.1.0)

The app UI is a clean, low-text iOS-style layout with **light and dark**
appearances (the round button in the top-right switches; the default follows
the system setting). Palettes live in `res/values/colors.xml` (light) and
`res/values-night/colors.xml` (dark); the type scale is in
`res/values/styles.xml` (`IosLargeTitle`, `IosHeadline`, `IosBody`, …).
Primary actions are blue; the in-app mark is `res/drawable/ic_anoy.png`.

`com.xlox.ui.AnoyButton` is a reusable button that draws its own feedback:

- **Three hovering dots** the moment any action is tapped (login, paste, buy a
  key, launch). Long-running actions keep them until the result arrives.
- **Progress inside the button** — a fill that grows across the blue pill with
  a compact `Updating 42%` / `Copying 42%` label — while the panel release is
  downloading or the OBB is being copied. No separate dialog appears.
- A subtle press-scale animation on every button.

The **Status** card shows the real `data.mod_status` from `POST /connect`
(`Safe` or `Anti-Cheat is High..!!`) and the admin-configured `data.credit`
safe text, captured by the native layer. It is never hardcoded in the UI.
`@libAkAudioVisiual` is shown on both screens as the buy-a-key contact.

> The Java package (`com.xlox`), the native library name (`libXloX.so`) and the
> keystore are build/JNI identifiers, not user-facing text — they are kept so
> the JNI signatures and existing installs continue to work. No `XLOX` text is
> displayed anywhere in the app.

`AppManager.copyObbFolderAsync(...)` and all OBB copy logic are unchanged; only
the UI callbacks around it were updated.

## What was broken / what changed

| Area | Before | Now |
|---|---|---|
| Version endpoint | `https://onlinelib.ggff.net/...ver15` (dead third-party host) | `GET https://anoymundo-panel.vercel.app/api/lib/version` |
| Loader ZIP endpoint | `https://onlinelib.ggff.net/...zip13` | `GET https://anoymundo-panel.vercel.app/api/lib/download` |
| Licence check | `https://vipercheat.authapi.xyz/server` | `POST https://anoymundo-panel.vercel.app/connect` |
| Telegram / social links | bare `https://t.me/`, facebook.com, twitter.com | `https://t.me/libAkAudioVisiual` |
| `startWorkflow()` | only reset UI flags — **never checked the version or downloaded a missing lib** | checks `/api/lib/version`; auto-downloads when the library is missing/corrupt; exposes UPDATE AVAILABLE |
| Lib validation | `new File(..., "loader/libbgmi.so").exists()` — an HTML 404 page or a nested ZIP counted as success | HTTP status + ZIP magic + ELF magic validation, staging extraction, recursive `libbgmi.so`/`lib.so` resolution, atomic install to one canonical path |
| Failure reporting | failures silently swallowed ("UPDATE_UNAVAILABLE" treated as success) | real error message shown in the status toggle and logged |
| `BoxApplication` | swallowed `UnsatisfiedLinkError`, only looked at one exact path | logs native-load failures, resolves nested/legacy library names, makes the file executable before `System.load()` |
| `Check()` native | stale globals could return OK after a failed login; stray `+` token; no HTTP status check | resets state per call, checks HTTP code + `rng`, verifies md5 token against the panel's `STATIC_WORDS`, handles maintenance `reason` |
| Build | LSParanoid / BlackObfuscator / JitPack / Sonatype snapshot plugins | only Android Gradle Plugin 8.2.1 from Google Maven + Maven Central |

All panel URLs live in **one** file: `app/src/main/jni/panel_config.h`.
Change `ANOY_PANEL_BASE` (and the three endpoint macros) to point the APK at a
different deployment, then rebuild. `ANOY_STATIC_WORDS` must equal the panel's
`STATIC_WORDS` env var or the md5 token check will fail.

## Panel contract used by the app

- `GET /api/lib/version` → plain text, one line (e.g. `1.0`). Stored in
  `com.xlox.download/version` only **after** the library is verified.
- `GET /api/lib/download` → `application/zip` whose archive contains
  `libbgmi.so` (or the legacy `lib.so`). A missing release returns HTTP 404 and
  the app now shows `No loader release uploaded in the panel yet (Admin -> Library)`.
- `POST /connect` with `game=PUBG&user_key=...&serial=...` → `{status, data:{token, EXP, rng, ...}, reason}`.
  Same JSON shape as the original loader, so no panel changes were needed.

The app downloads the ZIP into `files/loader_staging`, finds the ELF library,
installs it as `files/loader/libbgmi.so` (plus sibling `.so` dependencies) and
`BoxApplication` loads it in the `com.pubg.imobile` virtual process.

## Publishing a real loader library

A harmless starter ZIP (`libbgmi.so` compiled from `loader-stub/bgmi_stub.c`)
is currently published as version `1.0.0`, so `/api/lib/version` returns
`1.0.0` and `/api/lib/download` returns HTTP 200 with a valid ZIP. Replace it
with the real arm64-v8a library before shipping. Build a ZIP whose root contains
the real `libbgmi.so`:

```bash
zip -j my-loader.zip libbgmi.so
```

Then either upload it in the panel at **`/lib`** (Admin → Library), or publish
from CI / a terminal:

```bash
PANEL_ADMIN_PASSWORD='…' \
  node scripts/publish-release.mjs my-loader.zip 1.0.1
```

`scripts/publish-release.mjs` logs in, POSTs the base64 ZIP to `/lib`, then
verifies `/api/lib/version` and byte-for-byte `/api/lib/download`.

For a harmless end-to-end test, `loader-stub/bgmi_stub.c` compiles a tiny
`libbgmi.so` (JNI_OnLoad only) with the NDK; the workflow packages it as
`xlox-loader.zip`. It proves the download/extract/load chain but is **not** a
game library — replace it with the real one.

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
adb logcat -s Load BoxApplication LibManager MAct DawnLoad:V *:E
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
and `MAct` shows it in the status toggle and a Toast when you return to the
app. It also tolerates virtual game-process
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
installs the SDK/NDK, builds the signed release APK, builds the starter ZIP,
uploads both as the `xlox-apk` artifact and verifies the panel endpoints.
`workflow_dispatch` can additionally publish the starter ZIP to `/lib` when
`publish_loader_zip` is ticked (needs the `PANEL_ADMIN_PASSWORD` secret).

Signing uses `app/xlox.jks` (`key0` / `11111111`). CI regenerates a local
keystore if the bundled one is unusable, so replace it with your own keystore
for a real release — APKs signed with different keys cannot be updated in place.
