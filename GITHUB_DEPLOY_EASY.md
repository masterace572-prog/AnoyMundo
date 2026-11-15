# Easy guide: library and app updates from GitHub

Your panel does the login. GitHub does the updates.

In this guide, replace **YOU** with your GitHub username.

---

## Step 1: Make an update repo

1. On GitHub, click **New repository**.
2. Name it `anoy-updates`, choose **Public**, then click **Create**.

## Step 2: Add 2 files

Click **Add file → Create new file**. Type the name exactly as shown, with **no `.txt`**.

**File name:** `api/lib/version`
```
1.0
```

**File name:** `api/app/update`
```json
{ "ok": true, "available": false }
```

## Step 3: Upload the library ZIP

1. Open **Releases → Create a new release**.
2. Type `lib` as the tag.
3. Upload your ZIP and name it **`lib.zip`**. The ZIP must have `libbgmi.so` inside.
4. Click **Publish**.

## Step 4: Put the links in the app (one time only)

1. Open `xlox/app/src/main/jni/panel_config.h` in your copy of the project.
2. Change these lines:

```c
#define ANOY_VERSION_URL  "https://raw.githubusercontent.com/YOU/anoy-updates/main/api/lib/version"
#define ANOY_DOWNLOAD_URL "https://github.com/YOU/anoy-updates/releases/download/lib/lib.zip"
#define ANOY_CONNECT_URL  "https://YOUR-PANEL/connect"
```

3. Build the APK. GitHub Actions does it when you push.
4. Install it on the phones.

---

## Push a library update

1. Open the `lib` release, click **Edit**, delete the old `lib.zip` and upload the new `lib.zip`.
2. Open `api/lib/version`, click ✏️, change the number (for example `1.0` to `1.1`), then click **Commit**.

Done. Phones update within 5 minutes.

## Push an app update

1. Raise `versionCode` in `xlox/app/build.gradle` and push. GitHub builds the new APK.
2. Copy the APK link. It must end with **`.apk`**.
3. Open `api/app/update`, click ✏️, paste this, then click **Commit**:

```json
{
  "ok": true,
  "available": true,
  "version_name": "2.3.5",
  "version_code": 14,
  "apk_url": "PASTE-APK-LINK-HERE",
  "changelog": "What is new",
  "mandatory": false
}
```

Done. Phones are offered the update within 5 minutes.

---

## Remember

- The update repo must be **Public**.
- File names have **no `.txt`**.
- For an app update, `version_code` must be **bigger** than the last one.
- Always sign the APK with the **same key**.
- More details are in `GITHUB_DEPLOY.md`.
