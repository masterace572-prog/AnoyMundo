# Control library and APK updates from GitHub (with your own panel)

This guide is for someone who runs **their own panel** (the same login/licence
API as the Anoy panel) and wants the **library (.so / .zip) updates** and the
**app (APK) updates** to come from **GitHub files**, not from a panel admin page.

Once it is set up, pushing an update means **editing one small text file on
GitHub**:

- change the version in `api/lib/version` (or `api/lib/manifest`) → every phone downloads the new library
- change `version_code` in `api/app/update` → every phone is offered the new APK

Login and licence checks (`/connect`) still go to **your panel**. Only the two
kinds of update move to GitHub.

---

## 1. How it works (read this once)

The app has the addresses it uses written into the APK, in one config file:
`xlox/app/src/main/jni/panel_config.h`.

| Setting | What the app does with it |
|---|---|
| `ANOY_CONNECT_URL` | Login / licence check. **Your panel**, e.g. `https://your-panel.example.com/connect` |
| `ANOY_VERSION_URL` | Library version. The app also works out the other two update addresses from it (see below) |
| `ANOY_DOWNLOAD_URL` | The library ZIP that gets downloaded |

The app builds the other update addresses by taking `ANOY_VERSION_URL` and
swapping its ending `/api/lib/version`:

```
ANOY_VERSION_URL                      → .../api/lib/version     (plain-text version)
library manifest (optional, better)   → .../api/lib/manifest    (JSON: version + sha256)
app update check                      → .../api/app/update      (JSON: newest APK)
```

So if you set

```
ANOY_VERSION_URL = https://raw.githubusercontent.com/YOU/anoy-updates/main/api/lib/version
```

the app reads all of these GitHub files:

```
https://raw.githubusercontent.com/YOU/anoy-updates/main/api/lib/version
https://raw.githubusercontent.com/YOU/anoy-updates/main/api/lib/manifest
https://raw.githubusercontent.com/YOU/anoy-updates/main/api/app/update
```

> **The one rule that matters:** `ANOY_VERSION_URL` must start with `https://`
> and end **exactly** with `/api/lib/version`. The files must have **no
> extension** (`version`, not `version.txt`). If the ending is different, the app
> can't work out the manifest and app-update addresses and falls back to the
> original Anoy panel instead of your GitHub files.

The app adds `?code=13` to the app-update address. GitHub ignores that, which
is fine.

---

## 2. What you need

1. **A GitHub account.** It's free.
2. **A public repository for the update files**, for example `anoy-updates`.
   It must be public, because the phones read the files without logging in.
3. **Your copy of this project** (fork or private copy) to build your APK once.
   A **private** copy is recommended for the source, because the APK config
   (including your panel's static words) is in it.
4. **Your panel**, already working with the same `/connect` contract
   (see `INTEGRATION.md` / `API.md`). The panel's `STATIC_WORDS` and the app's
   `ANOY_STATIC_WORDS` must be the same.
5. **One signing key (keystore) that you keep forever.** Android only installs
   an update that is signed with the same key as the app already on the phone.

---

## 3. Create the update repository

On github.com: **New repository** → name `anoy-updates` → **Public** → tick
**Add a README** → **Create repository**.

Then create these files with **Add file → Create new file**. Type the path with
slashes, e.g. `api/lib/version`, and GitHub creates the folders for you.

```
anoy-updates/
└── api/
    ├── lib/
    │   ├── version      ← one line: the library version
    │   └── manifest     ← JSON: version + size + sha256 (recommended)
    └── app/
        └── update       ← JSON: the newest APK
```

### `api/lib/version`

One line only. Letters, numbers, `.` `_` `+` `-`, at most 32 characters.

```
1.15
```

### `api/lib/manifest` (recommended)

```json
{
  "ok": true,
  "version": "1.15",
  "available": true,
  "size": 1234567,
  "sha256": "PUT-THE-64-CHARACTER-SHA256-OF-THE-ZIP-HERE"
}
```

- `version` must be the **same** text as in `api/lib/version`.
- `sha256` lets the phone check that it downloaded the right file. With it, a
  phone that gets an old copy of the ZIP rejects it and tries again, instead of
  installing the old library under the new version number.
- `"available": false` pauses library updates.
- If this file does not exist (GitHub answers 404), the app uses
  `api/lib/version` on its own. That works too, but without the safety check.

### `api/app/update`

When there is **no** app update yet:

```json
{ "ok": true, "available": false }
```

When there **is** one (section 7 explains each field):

```json
{
  "ok": true,
  "available": true,
  "version_name": "2.3.5",
  "version_code": 14,
  "apk_url": "https://github.com/YOU/anoy-updates/releases/download/app-14/Anoy.apk",
  "changelog": "Faster start\nFixed the update screen",
  "mandatory": false
}
```

Check that the JSON is valid before you commit. One missing comma breaks it.
Any free "JSON validator" website will do.

---

## 4. Where to put the library ZIP

The ZIP must contain `libbgmi.so` (or `lib.so`). It can be inside a folder,
and a bare `.so` file is accepted too. The maximum is 64 MB.

`ANOY_DOWNLOAD_URL` is fixed inside the APK, so the ZIP must always be at the
**same address**. Pick one of these:

**Option A (recommended): a GitHub release with a fixed tag**

1. In `anoy-updates`: **Releases → Draft a new release** → tag `lib` → title `Library`.
2. Attach your ZIP and name it exactly `lib.zip` → **Publish release**.
3. Your fixed link is:
   ```
   https://github.com/YOU/anoy-updates/releases/download/lib/lib.zip
   ```
4. Next time: open the `lib` release → **Edit** → delete the old `lib.zip` →
   upload the new `lib.zip` (same name) → **Update release**. The link stays the same.

**Option B: a file in the repository**

Upload the ZIP as `api/lib/download` (no extension). The link is
`https://raw.githubusercontent.com/YOU/anoy-updates/main/api/lib/download`.
This is simpler, but GitHub keeps cached copies of raw files for about 5
minutes, so use the manifest with `sha256` (section 3).

---

## 5. Point your APK at GitHub (one time)

In **your copy of the project**, open
`xlox/app/src/main/jni/panel_config.h` and set the addresses.
This is the **only** file you edit. No Java changes are needed.

```c
#define ANOY_PANEL_BASE   "https://your-panel.example.com"
#define ANOY_VERSION_URL  "https://raw.githubusercontent.com/YOU/anoy-updates/main/api/lib/version"
#define ANOY_DOWNLOAD_URL "https://github.com/YOU/anoy-updates/releases/download/lib/lib.zip"
#define ANOY_CONNECT_URL  "https://your-panel.example.com/connect"
#define ANOY_TELEGRAM_URL "https://t.me/your_support"

/* Must be the same as STATIC_WORDS on YOUR panel. */
#define ANOY_STATIC_WORDS "the-same-words-as-your-panel"
```

- Replace `YOU` with your GitHub username and `main` with your branch name if it is different.
- Optional: the Java files `utils/Downtwo.java` and `utils/AppUpdater.java` contain a
  `FALLBACK_BASE` that is only used if the native library fails to load.
  You can leave it as it is.

> This edit has to be built into the APK **once**, because phones can only
> learn the GitHub address from the APK they have installed. After that, every
> library and app update is controlled from GitHub.

Also give your app its first version in `xlox/app/build.gradle`
(`versionCode` / `versionName`). Every later APK must have a **higher**
`versionCode`.

---

## 6. Build and sign the APK with GitHub Actions

The project already has the workflow `.github/workflows/build-xlox.yml`. It
builds the APK on every push that changes `xlox/**`.

1. **Keystore.** Put your keystore at `xlox/app/xlox.jks` with the alias `key0`.
   If you don't have one yet, create it **once** on your PC:
   ```
   keytool -genkeypair -v -keystore xlox.jks -alias key0 -keyalg RSA -keysize 2048 -validity 10000
   ```
   Keep a backup somewhere safe. **Never** create a new one later, or phones
   can't update any more.
2. **Secrets.** In your repository: **Settings → Secrets and variables → Actions →
   New repository secret**. Add:
   - `XLOX_STORE_PASSWORD`: the keystore password
   - `XLOX_KEY_PASSWORD`: the key password

   Never put these passwords in a file, a commit or a chat.
3. **Push** your change (for example the `panel_config.h` edit). Under **Actions →
   Build XLOX loader APK**, wait for the green tick (about 5–6 minutes).
4. The workflow publishes a GitHub release `anoy-v<versionName>-<versionCode>` with
   `Anoy-XLOX-loader.apk`. The direct APK link is:
   ```
   https://github.com/<you>/<your-source-repo>/releases/download/anoy-v2.3.5-14/Anoy-XLOX-loader.apk
   ```
   If your source repository is **private**, this link won't work on phones.
   Download the APK and attach it to a release in the **public** `anoy-updates`
   repository instead, e.g. tag `app-14`, file `Anoy.apk`.

   You can ignore the workflow's optional "publish to panel" inputs. They are
   for the original Anoy panel.

Install this first APK on the phones by hand. Every update after that comes
from GitHub.

---

## 7. Pushing updates (daily use)

### A) New library version

1. Upload the new ZIP to the **same address** first (section 4).
2. Get its sha256:
   - Windows (PowerShell): `Get-FileHash lib.zip -Algorithm SHA256`
   - Windows (cmd): `certutil -hashfile lib.zip SHA256`
   - Mac / Linux: `shasum -a 256 lib.zip`
3. On github.com, open `api/lib/manifest` → ✏️ **Edit** → set the new `version`,
   `size` (bytes) and `sha256` → **Commit changes**.
4. Open `api/lib/version` → ✏️ **Edit** → type the **same** version → **Commit changes**.
5. Done. Within about **5 minutes** (GitHub's cache), phones show the library update.

How the phone decides: if the published version is **different** from the
installed one (not only higher), it updates. If the sha256 differs, it also
updates. So to roll back, upload the old ZIP and publish a new version text,
e.g. `1.15-r1`.

### B) New app (APK) version

1. In your source copy, raise `versionCode` (and `versionName`) in
   `xlox/app/build.gradle` → push → wait for the green build (section 6).
2. Copy the **direct** APK link. It must end in `.apk`. A release *page* link
   gives an HTML page, and the app refuses it.
3. On github.com, open `api/app/update` → ✏️ **Edit**:

   | Field | Meaning |
   |---|---|
   | `ok` | always `true` |
   | `available` | `true` = offer this APK, `false` = offer nothing |
   | `version_name` | text shown to users, e.g. `2.3.5` |
   | `version_code` | whole number; phones update only if it is **higher** than theirs |
   | `apk_url` | direct `https://` link to the `.apk` |
   | `changelog` | short notes; use `\n` for a new line |
   | `mandatory` | `true` = users can't skip it; `false` = they can choose "Not now" |

4. **Commit changes**. Within about 5 minutes, opening the app shows the update.
   Phones that recently found "no update" check again after up to 10 minutes.

To withdraw an update, set `"available": false` and commit.

---

## 8. Test it

Open these in any browser (replace `YOU`):

```
https://raw.githubusercontent.com/YOU/anoy-updates/main/api/lib/version
https://raw.githubusercontent.com/YOU/anoy-updates/main/api/lib/manifest
https://raw.githubusercontent.com/YOU/anoy-updates/main/api/app/update
https://github.com/YOU/anoy-updates/releases/download/lib/lib.zip
```

- The first three should show your text or JSON, not "404: Not Found".
- The ZIP link should start a download, not show a web page.

Then on a phone, open the app. The library check runs on start, and app updates can be checked in **Settings → Check for app updates**.

---

## 9. Problems and fixes

| What you see | Usual cause | Fix |
|---|---|---|
| "The server returned an error (HTTP 404)…" | wrong path, wrong branch name, file has `.txt`, or repo is private | file paths must be exactly `api/lib/version`, `api/app/update`; repo public |
| Phone still talks to the old Anoy panel | `ANOY_VERSION_URL` doesn't end with `/api/lib/version` | fix `panel_config.h`, rebuild, reinstall once |
| Update not showing yet | GitHub raw cache (~5 min) + app's 10-min "up to date" memory | wait, or tap **Check for app updates** in Settings |
| "The downloaded file was damaged. Try again." | `sha256`/`size` in manifest doesn't match the ZIP, or the old ZIP is still cached | recompute sha256 of the exact uploaded file; wait 5 min |
| "The library package doesn't contain a usable library." | ZIP has no `libbgmi.so` / `lib.so` | re-zip with the `.so` inside |
| APK: "This update file doesn't match the app." | `apk_url` is the release page, not the file | use `.../releases/download/<tag>/<file>.apk` |
| APK downloads but Android refuses to install | APK signed with a different key, or different package name | always sign with the same keystore |
| App says up to date after you published | `version_code` not higher than the installed app | raise `versionCode` in `build.gradle`, rebuild, update JSON |
| "The update information from the server is incomplete…" or no effect | broken JSON (comma, quote) | paste into a JSON validator, fix, commit |

---

## 10. Safety notes

- The update repository is **public**: anyone with the links can download the
  library ZIP and the APK. Don't put anything secret in it.
- Keep the **source** copy private. It contains your panel address and static words.
- Never commit keystore passwords or panel passwords. Use GitHub **Secrets** only.
- Keep at least one backup of the keystore. Losing it means users must uninstall
  and reinstall to get new versions.
- Login, licences, key generation and everything else stay on **your panel**. This
  setup only moves the two kinds of update to GitHub.
