# Easy setup: updates from GitHub

## 1. Make the version file

On GitHub, make a **Public** repo. Click **Add file → Create new file**.

- Name: `api/lib/version`
- Text inside: `1.0`

⚠️ Don't name it `version.txt`. It must be exactly `api/lib/version`.

## 2. Upload the lib ZIP

In the same repo, go to **Releases → New release**.

- Tag: `lib`
- Upload your ZIP and name it `lib.zip`
- Click **Publish**

## 3. Copy the 2 links

Version link:
```
https://raw.githubusercontent.com/YOU/REPO/main/api/lib/version
```

ZIP link:
```
https://github.com/YOU/REPO/releases/download/lib/lib.zip
```

Replace `YOU` with your GitHub username and `REPO` with the repo name.

## 4. Paste them in the app

In `xlox/app/src/main/jni/panel_config.h`:

```c
#define ANOY_VERSION_URL  "PASTE VERSION LINK"
#define ANOY_DOWNLOAD_URL "PASTE ZIP LINK"
```

Build the APK once and install it.

---

## To push a lib update

1. Replace `lib.zip` in the `lib` release with the new ZIP.
2. Change the version text, for example `1.0` → `1.1`.

Phones update within 5 minutes.

---

## For APK updates (optional)

Make one more file named `api/app/update`:

```json
{
  "ok": true,
  "available": true,
  "version_name": "2.3.5",
  "version_code": 14,
  "apk_url": "PASTE APK LINK",
  "changelog": "What is new",
  "mandatory": false
}
```

To push a new APK, paste its link and make `version_code` bigger.
