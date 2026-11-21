# Liquid native Android UI — verification

**Status: first complete two-API run passed; final rendered-review refinements
need their own successful run. No Liquid release has been published yet.**

Verified source: `71f8bba349051c5402dcbe975e027838479a96ff`, in `liquid/`.
[Build and emulator run](https://github.com/masterace572-prog/AnoyMundo/actions/runs/44921824876)
passed the signed build, API 23 (56 captures / 299 checks) and API 33
(57 captures / 304 checks). The refinements below are newer than that source;
this historical result does not certify them.

## Preserved boundaries

- All **282 original `xlox/` files** match the SHA-256 baseline at `393e759`.
- **160 protected copied files** are byte-identical: original Mundo/JNI,
  transport/install utilities, manifests, fonts, brand artwork and contacts.
- Both existing packages and the original signing certificate are retained.
  Anoy: `com.xlox`; KennyPvt: `com.kennypvt`.
- arm64-v8a; minSdk **21**, targetSdk **28**, compileSdk **36**.
- Original licensing, canonical `libbgmi.so`, verified downloads, atomic
  installation, game/container/OBB paths and cancelled login implementation are
  not rewritten for this visual fork.
- No site, production database, account, key, billing, APK or library selection
  is changed. [Obsolete-test cleanup](OBSOLETE_TEST_CLEANUP.md) is complete;
  stable rollback releases remain available.

## Executed checks

- Both brands' release and debug variants compile; minified release builds and
  Android lint pass without adding UI-lint suppressions.
- **10/10** Liquid static gates: XML/resources, scalable type, styles, palette,
  contrast, controls/targets, vectors/licences, blur boundaries and fallbacks.
- **20/20** source branding/channel gates; compiled APK branding/SDK/manifests,
  packaged Kenny pixels and permissions are checked separately in the build.
- **12** Kenny artwork regressions and **6** compact-SVG parser regressions pass.
- **100** actual Java blur/oracle/edge/radius/dimension regressions pass in CI.
- The unchanged Java runtime/download/callback regression suites pass in CI.
- Actual release APK signatures match the original key's certificate;
  `libXloX.so` passes the native ELF alignment check for both brands.
- Conservative compositing calculation: even over an all-white backdrop plus
  the maximum glass glint, the weakest normal text token is **4.60:1** on the
  floating glass and **4.65:1** on the sheet. This is a colour calculation, not
  a rendered accessibility certification.

The vector check found compact SVG arc flags and leading-dot fractions that
older Android parsers cannot safely consume. Paths are now explicitly separated
and zero-prefixed without changing their geometry; regression tests cover it.

## Rendered emulator evidence

Final source run pending. The walkthrough covers both brands and:

- splash plus four real phase-mapped motion frames;
- licence sign-in, key visibility, keyboard and error/loading/success states;
- home, licence expiry, notices and preparing/launching/error states;
- Settings, reveal/copy, glass/opaque appearance, Help/support, re-download and
  sign-out sheets;
- eight library-update and four APK-update states;
- 360dp, tablet and 200% system-font layouts; high-contrast/opaque mode;
- API-33 permission explanation and the actual minified release's entry screen.

The full-resolution PNGs, view trees, results and logs are workflow artifacts.
Selected complete native frames are reviewed independently of the assertions.

## Installation and limitations

Preview version **2.5.0-liquid / code 21** is an optional **in-place update**, not
an additional side-by-side package. Do not uninstall or clear app/game data.
Code 21 permits a manual return to retained **2.4.3/code 21**; Android normally
blocks an ordinary downgrade to panel-linked **2.4.2/code 20**.

The x86 emulators use temporary ABI-neutral, re-signed copies and debug-only,
fictitious UI data. A manifest-only game-presence fixture is **not BGMI**.
Distributed release APKs keep their real arm64 binaries and original signer;
QA copies are never distributed. UI checks do **not** establish real-phone
container/game launch, provider login, OEM permission handling, API-21 runtime,
frame rate, battery use or thermal performance. The cancelled login work remains
cancelled. No real licence, provider account or password is used for this QA.
