# design/ci: compile check for the splash changes (not active)

`compile-check.yml.draft` is a draft GitHub Actions workflow. It is **not
active**, because GitHub only runs files in `.github/workflows/`. It builds the
splash hand-off changes B1–B4 (`design/splash/patches/xlox-b1-b4.patch`)
without publishing anything:

- **Trigger:** a push to `splash-compile-check` only.
- **Permissions:** `contents: read`.
- **No secrets, releases, tags or panel uploads.**
- **What it runs:** the UI gate, `assembleDebug` and `lintRelease`.
- **Output:** the debug APK and the lint report, as workflow artifacts kept
  for 3 days.

## WARNINGS: read before using it

1. **Never merge `splash-compile-check` into `arena/2e95185a-anoymundo`.** The
   merge would bring the `xlox/` changes onto the arena branch. That fires
   `build-xlox.yml`, which rebuilds 2.3.8 (17) and **overwrites the live
   anoy-v2.3.8-17 release** (`gh release upload --clobber`), then deletes other
   releases.
2. **Never start `build-xlox.yml` manually (Run workflow) on
   `splash-compile-check`.** Its publish steps only check
   `env.XLOX_SIGNED == 'true'`, not the branch. Today it can't be started
   manually, because `main` (the default branch) has no workflow files. That
   would change as soon as `main` gets them.
3. **Delete `splash-compile-check` as soon as the run has finished** (see
   step 2d). Its artifacts expire by themselves after 3 days.
4. **The debug APK cannot be installed over the real Anoy 2.3.8.** It has the
   same package (`com.xlox`) but is signed with the runner's throwaway debug
   key, so the phone refuses the update.
   - Use a phone without the real app, or uninstall the real app first. That
     deletes its data and sign-in.
   - The APK is debuggable, so its frame times are not representative.
   - The repository is public: anyone signed in to GitHub can download the
     artifact while it exists.

## Step 2: commands (run from your own clone, not from the Arena session)

These are not run by the Arena session. It may only push to its own branch.

```sh
# a) start from the arena branch, which holds this folder and the patch
git fetch origin arena/2e95185a-anoymundo
git switch -c splash-compile-check origin/arena/2e95185a-anoymundo

# b) apply B1-B4 and activate the draft ON THIS BRANCH ONLY
git apply --check design/splash/patches/xlox-b1-b4.patch
git apply design/splash/patches/xlox-b1-b4.patch
mkdir -p .github/workflows
cp design/ci/compile-check.yml.draft .github/workflows/splash-compile-check.yml
git add xlox/app/src/main/java/com/xlox/SplashActivity.java \
        xlox/app/src/main/res/anim/splash_handoff_enter.xml \
        xlox/app/src/main/res/anim/splash_handoff_exit.xml \
        .github/workflows/splash-compile-check.yml
git commit -m "Compile check: splash B1-B4 (not for release)"
git push -u origin splash-compile-check
# Fires only "Splash compile check (no publish)". The five existing workflows
# filter on branches: [arena/2e95185a-anoymundo], so none of them run.

# c) read the result
gh run list --branch splash-compile-check
gh run view <run-id>          # compile / lint errors appear as annotations

# d) clean up
git push origin --delete splash-compile-check
git switch arena/2e95185a-anoymundo && git branch -D splash-compile-check
```
