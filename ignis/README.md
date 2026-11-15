# Ignis

## 1. Set your links (one time)

Open `app/src/main/jni/panel_config.h` and change these 3 lines:

```
IGNIS_CONNECT_URL   https://YOUR-PANEL/connect
IGNIS_VERSION_URL   https://github.com/YOUR-NAME/YOUR-REPO/releases/download/update/version.txt
IGNIS_DOWNLOAD_URL  https://github.com/YOUR-NAME/YOUR-REPO/releases/download/update/lib.zip
```

Commit. GitHub builds the APK by itself (Actions → **Build Ignis APK**).
The APK is in the release **ignis-v…** in this repo.

> Use a **separate repo** (for example `ignis-updates`) for the `update`
> release. The Anoy build in this repo deletes other releases.

## 2. Make one release

In your updates repo: **Releases → New release → tag `update`**.
Upload these 4 files:

| File | What it is |
|---|---|
| `version.txt` | lib version |
| `lib.zip` | the lib (zip with `libbgmi.so` inside) |
| `apk.txt` | app update info |
| `ignis.apk` | the app |

**version.txt**

```
1.0
```

**apk.txt**

```
version_code=15
version_name=1.0.1
apk_url=https://github.com/YOUR-NAME/YOUR-REPO/releases/download/update/ignis.apk
mandatory=false
changelog=Bug fixes
```

## 3. Push an update

- **New lib:** upload the new `lib.zip`, then change the number in `version.txt` (e.g. `1.0` → `1.1`).
- **New app:** upload the new `ignis.apk`, then raise `version_code` in `apk.txt`.

To replace a file: open the release → **Edit** → delete the old file → upload the new one (same name).

## Notes

- The app's own version code is **14**. `version_code` in apk.txt must be **higher** to show an update.
- `mandatory=true` = users can't skip the update.
- More lines of changes: add more `changelog=` lines.
- No `apk.txt` in the release = no app update shown.
- Mod status always shows **Safe**.
