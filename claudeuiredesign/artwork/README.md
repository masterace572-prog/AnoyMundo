# KennyPvt supplied icon

`kenny-logo.jpg` is the unmodified 640 × 640 image supplied by the project owner
for KennyPvt. SHA-256:
`d83c2810a2279fd429640294659a47d1548835ed3d727c1b666a777dec0b182c`.
It contains no EXIF. The original and the 512 px rounded-square preview stay
outside Android's source sets; neither is bundled in either APK.

The underlying illustration remains the property of its respective rights
holders. This repository does not claim authorship or grant an additional
licence to that artwork. The Kenny APK includes the separate provenance file
`src/kenny/assets/licenses/KENNY_ARTWORK.md`.

## Android presentation

Only `app/src/kenny` overrides the icons. The shared/Anoy manifest, launcher
vectors, system-splash vectors and existing signing key are unchanged.

| Use | Canvas / artwork at mdpi | Behavior |
|---|---|---|
| Legacy icon | 48 × 48 px, 22.5% corner radius | Rounded-square fallback on Android 21–25 |
| Round icon | 48 × 48 px, circular alpha | Separate round fallback when requested by the launcher |
| Adaptive foreground | 108 × 108 px; centered 72 px artwork | Android 26+ owns the final circle, squircle or other mask |
| Startup badge | 288 × 288 px; centered 184 px circular artwork | Fits inside Android 12+'s 192 dp mask-safe circle |

The generator supplies mdpi, hdpi, xhdpi, xxhdpi and xxxhdpi PNGs. It only
resizes the original square and applies antialiased outer masks: no redraw,
recolouring or stretching. Circular launchers naturally clip the original's
outer corners, not its central character. The adaptive image is placed in the
72 dp viewport, not scaled from a full-bleed 108 dp photograph. The startup
badge is shared by the system splash and the first app frame; the existing
fade/typography film, update checks and timings are unchanged.

## Reproduce and verify

From `xlox/`, with Python 3.10+:

```sh
python3 -m pip install -r scripts/icon-requirements.txt
python3 scripts/kenny_icons.py
python3 scripts/kenny_icons.py --check
python3 scripts/test_kenny_icons.py
python3 scripts/ui_quality_gate.py
python3 scripts/check_branding.py
# Also check all 20 packaged PNGs after assembly (ANDROID_SDK_ROOT + aapt2):
python3 scripts/kenny_icons.py --check --apk ../dist/KennyPvt-loader.apk
```

The historical `scripts/brand_wordmark.py` command now delegates to this
pipeline, so it cannot overwrite the new image with old Kenny text vectors.
The source checksum is intentional: replacing the artwork requires reviewing
and updating that checksum. Generation and `--check` also verify the five
original Anoy icon files against their unchanged hashes.

CI verifies source pixels, density/alpha/mask geometry, the exact flavor-only
bitmap allowlist, compiled `icon` / `roundIcon` references, package/contact/SDK
isolation and decoded pixels from the real Kenny APK. APK checks follow the
compiled resource table, including AGP's shortened `res/a.png` paths; they do
not assume source basenames survive optimization. Regression tests reject
changed source art, Anoy-art changes, missing/ambiguous/reassigned packaged
densities and modified packaged pixels. The Anoy APK must contain neither Kenny's images nor their
flavor-only provenance file. A physical launcher/device test is separate from
these reproducible checks.

Android 2.4.2 (20) uses a new dual-brand release tag; old releases are retained.
Building/publishing APK downloads never changes either Owner's live panel
library or APK-update selection.
