# Liquid native Android UI — verification

**Status: final signed build, both emulator walkthroughs and selected
rendered-screen review passed. Signed APKs are available in the workflow bundle.
GitHub denied release creation; no Liquid release was published.**

Verified Android source: `157b480e259c65c9d7d979b8704af211b57f0d0b`, in `liquid/`.
[Final build and emulator run](https://github.com/masterace572-prog/AnoyMundo/actions/runs/44923539867)
passed all three jobs, including the last rendered-review refinements:

| Android | Actual native captures | Passed checks | Failed checks |
| --- | ---: | ---: | ---: |
| 6 / API 23 | 57 | 311 | 0 |
| 13 / API 33 | 58 | 316 | 0 |
| **Total** | **115** | **627** | **0** |

Later publication-workflow/documentation-only commits do not change the tested
Android source. Publication refuses subsequent `liquid/` or build-workflow
changes, incomplete evidence, missing API results or a failed source run.

## APK delivery

[Download the signed Liquid-APKs bundle](https://github.com/masterace572-prog/AnoyMundo/actions/runs/44923539867/artifacts/14714912053)
(10.6 MB ZIP; GitHub sign-in may be required):

- `Anoy-Liquid.apk` — `com.xlox`.
- `KennyPvt-Liquid.apk` — `com.kennypvt`.
- `SHA256SUMS.txt` — verify extracted files with `sha256sum -c SHA256SUMS.txt`.

GitHub reports expiry **2026-12-05 16:18 UTC**. This is a retained build artifact,
not a permanent release URL. The sandbox's direct artifact download was blocked
by a network EOF, so no local APK download/hash verification is claimed; both
APK signatures and hashes were checked on the GitHub runner.

Release sharing is separately guarded. The manual dispatch API returned HTTP
403. The reviewed-request alternative passed all source/UI guards, downloaded
the actual APK/evidence artifacts, rechecked hashes and the original signing
certificate, and generated the screenshot archive/contact sheet. GitHub still
rejected release creation with **HTTP 403: Resource not accessible by
integration** in [sharing run 44925313389](https://github.com/masterace572-prog/AnoyMundo/actions/runs/44925313389).
No Liquid release/assets were created. The one-shot request and unused tag were
removed, leaving no pending publication. All five retained releases and their
14 original assets match the before-sharing inventory; latest remains 2.4.3.
The publication gate has **17** offline regression tests. Permanent release
links require GitHub release-write permissions to be repaired.

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

Twelve selected complete downscaled JPEGs from the final successful run were
independently reconstructed, JPEG-integrity-checked and visually reviewed:

- API 23: Kenny startup, Anoy sign-in, keyboard, home, Settings and tablet sign-in.
- API 33: library sheet, APK update, 200% home/sign-in/Settings and tablet home.

Earlier review identified a missing API-23 Back glyph and awkward large-type
home heading; code inspection also found nested tablet padding and an oversized
idle APK footer. The final screenshots confirm the compat-loaded explicit Back
glyph, stacked large-type heading, single login width cap, bounded actual
floating surfaces and compact idle update controls. The final harness also
passes visible-Back-glyph, tablet action-width and 200% APK/library-control
assertions. Conservative primary ripple-plus-sheen contrast is gated;
focus/hover use the darker fill and keyboard focus has an explicit outline.
These are reviewed native renders, not generated UI concepts or web mockups.

The walkthrough covers both brands and:

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
