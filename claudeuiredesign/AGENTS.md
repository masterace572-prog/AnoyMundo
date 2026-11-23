# claudeuiredesign workspace

- This is a fresh, isolated copy of the current `xlox/` Android working tree.
- Make future UI redesign changes and local test outputs inside this folder.
- Do not edit the original `xlox/`, unrelated workflows, website, backend or live
  data without separate explicit authorization.
- Preserve both brands, package/JNI contracts, licensing, verified downloads,
  installation, game/OBB data, SDK compatibility and the original signing identity.
- The original signing key was intentionally not copied. The default signing
  path in `app/build.gradle` references `../../xlox/app/xlox.jks`; existing
  environment/property overrides are preserved. Never copy or print credentials.
- Put generated test outputs in `qa-output/` (ignored), not in source folders.
- Do not publish releases or change live APK/library selections as part of UI
  testing. Do not run inherited publication scripts without explicit approval.
- No UI redesign changes have been applied to this fresh copy.
