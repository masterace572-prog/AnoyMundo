# One-time Anoy / Mundo test

Input: `mundo-release-lv10_cracked.aar`, uploaded by the project owner.
SHA-256: `404536acf645805c4843aefce5061b43d32c82e6e4bd6d32219bb43047c67473`.
This filename is not proof of origin, licensing status, functionality or safety.
The binary has only been statically inspected; use a spare test device.

## Isolation

- Normal `xlox/app/build.gradle` and `xlox/gradle.properties` stay unchanged in
  Git. Normal Anoy/Kenny builds still use `xlox/app/libs/mundo-release.aar`.
- The original AAR is never deleted, renamed, overwritten or moved. Its pinned
  SHA-256 is `368293d93076aea5db252d307614ba10a23bae84eb298830e6dab42aa3f90ba0`.
- Only an ephemeral CI checkout excludes that original file from the dependency
  list and links the uploaded test AAR. The unrelated permission AAR remains.
- Only Anoy is assembled: `com.xlox`, Anoy label/channel/JNI/contact, existing
  signing certificate, min SDK 21 and target SDK 28. Compilation uses SDK 37 to
  satisfy the new AAR metadata. No library/SDK activation logic is changed.
- The test version is `2.4.2-mundo-lv10-test` with code 20. Keeping the same code
  as stable 2.4.2 allows reinstalling that stable APK without a code downgrade.
  **Installing this test updates the Anoy app on that phone; it is NOT a
  side-by-side application.** Back up anything important before testing.
- Uploaded native libraries are retained byte-for-byte, not stripped, and their
  hashes are verified inside the actual APK. Old Mundo native libraries must
  be absent. All packaged arm64 native libraries must have 16 KiB alignment.

## Build / delivery

`.github/workflows/anoy-mundo-test.yml` is separate from the normal dual-brand
release workflow. Uploading an AAR alone does not trigger it. Its test-only
workflow/script changes trigger the initial build on the current Arena branch;
it also supports manual runs. The unsigned build job receives no signing
password. A separate job verifies, aligns and signs with Anoy's existing key,
then compares its signer to the verified stable 2.4.2 APK.

The output is `Anoy-Mundo-LV10-test.apk`, shared as a distinctly named
**prerelease**, explicitly **not latest**, with an `anoy-mundo-lv10-test-...`
tag. Existing releases are never overwritten or removed. The panel's latest
build selector ignores prereleases and also requires normal release tags and
normal APK filenames, so this test cannot be automatically selected there.
No database, invitation, credentials, live panel library or APK-update choice
is changed. Kenny is not built by this workflow.

Local static tests (no Java / Android SDK required):

```sh
python3 test-inputs/mundo/anoy_test.py inspect
python3 test-inputs/mundo/anoy_test.py self-test
```

`prepare` refuses to patch a normal local checkout. Compiled verification uses
`verify-apk <path>` with `aapt2` available under `ANDROID_SDK_ROOT`. A successful
build/signature check is not a physical-device, container, game, injection,
activation or malware-safety test.
