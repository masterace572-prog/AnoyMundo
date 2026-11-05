# XLOX loader — Anoy panel integration

Android (arm64-v8a) loader app from `XLOX-Loader-Source.zip`, now wired to the
**Anoy** panel and buildable in CI. The source was extracted from the archive
into this directory (the original archive stays at the repo root; the local
`_extract/` copy is git-ignored).

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

## Building

Local (requires JDK 17, Android SDK platform 34, build-tools 34.0.0 and NDK
24.0.8215888):

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
