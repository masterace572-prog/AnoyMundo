# Restore the released 2.4.3 APK + OBB loader

## Owner decision and exact baseline

The owner ended the Air split-APK/crash investigation and requested the 2.4.3
update with the original Air **Coming soon** behavior. They report that a normal
game APK + OBB works, while the split install does not. This is an owner-reported
outcome, not an independent phone test of either flavor by the agent.

The loader application and build inputs are restored byte-for-byte to public
source **`f8f58847a8e9eebac008913700cd0ff343122016`**. The immediate pre-experiment
maintenance snapshot **`909b8a14f77ab935d02a2eeabb5a530544a88ecd`** supplies the
already-correct public-release backup pin and post-publication documentation;
it has the same application/build inputs as the public source.

This is a forward rollback commit on `arena/dda4cc57-anoymundo`, not a branch
switch, reset to an old remote tip, history rewrite, or force-push. The failed
experiments remain available in history, not in the active application.

## Restored behavior

- Both Anoy and KennyPvt use the original package-scoped normal APK + OBB flow:
  inspect and verify/copy the selected game's OBB, then obtain the original SDK's
  splash launch intent. No custom split staging/import or native-copy repair runs.
- Air displays **Coming soon** and is disabled when its own usable
  `libbgmilite.so` is unavailable. This is the original library gate, **not a
  permanent Air-disable flag**. An already-installed usable Air library can
  enable the original Launch/Install controls; it is not deleted by this rollback.
- BGMI keeps `com.pubg.imobile` / `libbgmi.so`; Air keeps
  `com.krafton.bgmilite` / `libbgmilite.so`. The selected game's OBB names and
  destination remain separate. Split/asset-pack installs are not certified as
  working and are outside this restored APK + OBB path.
- The released Home/Settings sliding pages, capsule dragging, Login/Splash,
  licence visibility controls, announcement styling and game cards are retained.

## Removed from the active tree

- `GamePackagePreparer`, its all-split importer/readiness checks and tests.
- `AirNativeLibraries` and the later SDK-owned extraction-repair gate/tests.
- Air injection-isolation Gradle/workflow switches and conditional loading.
- Air crash-report controls/dialog, exit/session collection, optional root reader,
  native-record parsing, tests and diagnostic instructions.

No new diagnostic is requested and no replacement SDK/game library is introduced.
This restoration does not claim to fix the failed split-APK compatibility case.

## Intentionally preserved outside the rollback

- **2.4.3 / versionCode 21**, original signer and keystore, original Mundo AAR,
  separate application/channel identities, branding and contacts.
- Saves, licence keys and app/container data. No uninstall, data clear, migration,
  SDK swap, game patch, signal/protection change or library withdrawal is added.
- The separate **20 MiB** input/combined-expanded upload rollout
  (**20,971,520 bytes**), with **21 MiB** normalized Storage transport allowance.
  Web/Supabase sources and live panel/library/APK choices are not rolled back.
  The standalone publication CLI's stale 10 MiB local check is aligned with that
  retained 20 MiB contract; it is not an Android application/build input.
- All existing public release assets and tag targets, including 2.4.3 and older
  releases. Build verification must use `[verify-2.4.3]` or explicit
  `publish_release=false`; do not replace the already-correct public APKs.

## Verification and limits

Local rollback verification compared **284 application/build files** against the
public source, with **zero content differences or extra application files**.
It also checked that the original SDK and keystore hashes are unchanged, the
normal APK + OBB call and Coming soon gate are restored, and web/Supabase sources
are unchanged. Local UI/branding/artwork/navigation and Android wiring checks,
Java/Python/XML/YAML/shell syntax, offline panel build/tests, and five mocked
publication-CLI size boundaries passed. The CLI fixtures make no real network
requests or publications.

There is no local JDK/Android runtime. JVM execution, both Android builds/lints,
compiled-APK gates and signature checks belong to the fresh build-only CI run;
no pending CI result is represented as a pass here. The restored host suite
requires at least **180 tests** across the two brands and Android 21/28, with
zero failures/errors/skips. This is intentionally the released baseline's suite,
not the retired 676-test experimental suite. Host results are not game/device QA.

The original public APKs remain at
[the 2.4.3 release](https://github.com/masterace572-prog/AnoyMundo/releases/tag/loaders-v2.4.3-21).
Their published SHA-256 values are:

```text
7342e7fb67c0f127b51bafdbfde077a0af80101c5d47fc036c250b056b44489e  Anoy-XLOX-loader.apk
0c1c11737776a49c66b7ffe7607b504fc7c71df9a30f1fa39b187d5a28a7c438  KennyPvt-loader.apk
```

Install the matching brand over an experimental code-21 APK if returning to
this baseline; **never uninstall or clear app/container data**. The updater's
strictly-greater-versionCode rule remains unchanged, so it will not automatically
offer a different code-21 APK to a code-21 installation. Existing code-20 users
still have the normal matching-brand 2.4.2-to-2.4.3 in-place upgrade path.

Ignored local verification receipts are under `qa-logs/rollback-2.4.3/`.
