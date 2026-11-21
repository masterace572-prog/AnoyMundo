# One-time Anoy / Mundo test — reported broken

## Runtime report / quarantine

**Do not use the SDK36 test APK for normal login.** The tester reports a Login
crash and the unexpected text `cracked by : @Anticheat02`. The crash has not
been reproduced on a device here and no crash stack has been provided, so its
exact cause is unknown. The text is absent from Anoy's application source;
static scanning found no plaintext ASCII/UTF16 or simple base64 match in either
AAR. Obfuscated/native runtime strings cannot be attributed to a function from
that negative search alone. There is no proof here of malware or stolen keys.

Return to [stable Anoy 2.4.2](https://github.com/masterace572-prog/AnoyMundo/releases/download/loaders-v2.4.2-20/Anoy-XLOX-loader.apk).
Back up important data before any uninstall or data clear: those actions remove
Anoy's app/container data. Do not send licence keys, tokens or passwords when
sharing a crash trace. Android version, crash stage and a redacted fatal stack
are needed before attempting a runtime fix; removing an attribution alone is
not a crash fix.

Automatic triggers for this one-time workflow have been removed. Manual
unsigned diagnostics require explicit acknowledgement of the reported crash;
further signing/publication of this exact input is disabled. The original AAR,
uploaded AAR and existing APK assets are retained, not deleted or patched.
Normal builds continue using the original library; no live panel setting was
changed. The published test prerelease is marked as reported broken.

## Input

Input: `mundo-release-lv10_cracked.aar`, uploaded by the project owner.
SHA-256: `404536acf645805c4843aefce5061b43d32c82e6e4bd6d32219bb43047c67473`.
This filename is not proof of origin, licensing status, functionality or safety.
Agent-side inspection has been static. The tester's runtime failure is recorded
above; this input should not be used for ordinary builds or login.

## Isolation

- Normal `xlox/app/build.gradle` and `xlox/gradle.properties` stay unchanged in
  Git. Normal Anoy/Kenny builds still use `xlox/app/libs/mundo-release.aar`.
- The original AAR is never deleted, renamed, overwritten or moved. Its pinned
  SHA-256 is `368293d93076aea5db252d307614ba10a23bae84eb298830e6dab42aa3f90ba0`.
- Only an ephemeral CI checkout excludes that original file from the dependency
  list and links the uploaded test AAR. The unrelated permission AAR remains.
- Only Anoy is assembled: `com.xlox`, Anoy label/channel/JNI/contact, existing
  signing certificate, min SDK 21 and target SDK 28. No library/SDK activation
  logic is changed.
- **SDK compatibility exception:** the input declares minimum compile SDK 37,
  but neither the stable nor preview SDK catalogs offered `platforms;android-37`
  in the checked CI runs (44894340660 / 44894562838). This experimental build
  compiles with the available **SDK 36**, excluding only the
  `:app:checkAnoyReleaseAarMetadata` task in the two test build/lint commands.
  **The AAR itself, including its metadata, remains unchanged.** This does not
  satisfy or validate the library's declared SDK-37 requirement. The exception
  does not apply to the normal workflow and is recorded in the test evidence.
- The test version is `2.4.2-mundo-lv10-sdk36-test` with code 20. Keeping the same code
  as stable 2.4.2 allows reinstalling that stable APK without a code downgrade.
  **Installing this test updates the Anoy app on that phone; it is NOT a
  side-by-side application.** Back up anything important before testing.
- Uploaded native libraries are retained byte-for-byte, not stripped, and their
  hashes are verified inside the actual APK. Old Mundo native libraries must
  be absent. All packaged arm64 native libraries must have 16 KiB alignment.

## Build / delivery

`.github/workflows/anoy-mundo-test.yml` is separate from the normal dual-brand
release workflow. Originally its test-only workflow/script changes triggered
the initial build. Those automatic triggers are now removed. Only an explicitly
acknowledged manual unsigned diagnostic build is possible. The unsigned job
receives no signing password. The historical separate signing/verification job
is retained for reference but disabled after the runtime failure report.

The historical output is `Anoy-Mundo-LV10-SDK36-test.apk`, now marked reported
broken. It was shared as a distinctly named **prerelease**, explicitly **not latest**, with an
`anoy-mundo-lv10-sdk36-test-...` tag. Existing releases are never overwritten or removed. The panel's latest
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

## Historical build/signature checks — not runtime approval

- [Isolated CI run 44894751118](https://github.com/masterace572-prog/AnoyMundo/actions/runs/44894751118)
  passed from source `f1132b2d46234c25f367b9d3d6cbe8f1e7165d9c`.
- Anoy compiled and linted. Both unsigned and signed APKs passed package,
  version/contact, SDK 36 / minimum 21 / target 28, permissions, exact uploaded
  native-byte, original-native exclusion and 16 KiB native alignment checks.
  Zip alignment and signatures passed; the test certificate matches stable
  Anoy 2.4.2. The declared SDK-37 metadata requirement was intentionally NOT
  validated, as explained above, and remains unchanged inside the source AAR.
- Local checks passed: 11 test-build regressions, 14 UI / 20 branding gates,
  original Anoy icon hashes, and 213 smoke / 20 HTTP / 69 PostgreSQL checks.
- [Test-only prerelease](https://github.com/masterace572-prog/AnoyMundo/releases/tag/anoy-mundo-lv10-sdk36-test-f1132b2d)
  contains `Anoy-Mundo-LV10-SDK36-test.apk`, input evidence and checksums.
  APK size: **10,993,772 bytes**. SHA-256:
  `b35fa1c647b627a62d388d928af0cfc16e90a8a76d85142dd16e86f48188c338`.
  GitHub's uploaded-asset digest matches the signed build checksum.
- Confirmed after sharing: GitHub's stable latest remains
  `loaders-v2.4.2-20`, with all stable asset metadata unchanged. Normal Gradle
  files, original/test AAR files, app source, backend and normal workflow are
  unchanged by this test task. No live panel publication was performed.
- This sandbox cannot fetch GitHub's release-asset CDN; the signed APK is
  delivered through the verified GitHub download link, not a local placeholder.
