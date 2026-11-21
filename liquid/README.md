# Liquid

An isolated native Android UI redesign of Anoy + KennyPvt.

- Source: `liquid/`; the original `xlox/` project is unchanged.
- Design and safety plan: [`../docs/LIQUID_UI_PLAN.md`](../docs/LIQUID_UI_PLAN.md).
- Java/XML + Material 3, bundled Inter/JetBrains Mono and Lucide vectors.
- Glass is a cropped, downsampled backdrop blur; text/controls are never blurred.
- Settings → Appearance → Glass effects provides an opaque alternative.
  High contrast and battery saver also disable decorative transparency.
- System Remove animations is respected; the splash is a short, one-shot reveal.
- Original Mundo, native code, licensing, transport, data, contacts, package IDs,
  minSdk 21 / targetSdk 28 and the signing identity are preserved.

## Build

Use **Liquid Android — build and visual QA**, or run with JDK 17, SDK 36 and
NDK 24.0.8215888:

```sh
python3 scripts/check_isolation.py
python3 scripts/ui_quality_gate.py
python3 scripts/test_liquid_blur.py
python3 scripts/test_runtime_fixes.py
./gradlew :app:assembleAnoyRelease :app:assembleKennyRelease
```

Signing uses the existing key in `../xlox/app/xlox.jks` and the existing environment
configuration. Do not copy passwords into local scripts, documentation or Git.
No new signing key or private credentials are generated for distribution.

## Installation and rollback

Preview version: **2.5.0-liquid (21)**. These are optional in-place UI updates,
not side-by-side packages. Android should offer **Update**, not a fresh app.
Do not uninstall or clear app/game data. Anoy remains `com.xlox`; Kenny remains
`com.kennypvt`. Code 21 intentionally permits an in-place return to retained
2.4.3/code 21. Android will not normally allow a downgrade to 2.4.2/code 20.

The dedicated workflow uploads build/QA artifacts only. Publishing a non-latest
preview release never changes the live panel APK or library selections.

## Verification scope

`qa/xlox-baseline.json` proves the original source was not changed. The build
also checks protected copied files, runtime regressions, WCAG token contrast,
resources, lint, artwork, packages, permissions, alignment and signatures.

Emulator UI captures use **debug-only fixtures and ABI-neutral, re-signed copies**
on x86 emulators. Native arm64 binaries in the distributed release APKs are not
stripped or replaced. Emulator UI checks do not prove real-phone container,
game launch, provider authentication, frame rate or OEM permission behaviour.
The cancelled login investigation is not resumed by this visual fork.
