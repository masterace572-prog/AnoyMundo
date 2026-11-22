# gptsuggestion workspace

- This is an isolated copy of the existing `xlox/` Android working tree.
- Make all redesign changes and local build/test outputs inside this folder only.
- Exception explicitly authorized by the user: add only
  `.github/workflows/build-gptsuggestion.yml` for isolated builds, UI checks and
  artifact uploads. It must never publish releases or modify production.
- Never edit the original `xlox/`, existing root workflows, website, backend or production data.
- The approved UI prompt covers Home, Settings and Login, in that order.
- Do not reuse the rejected Liquid version or turn the whole app into glass.
- Preserve both brands, their contacts, package/JNI contracts, licensing, verified
  downloads, installation, game/OBB data, SDK compatibility and original signing identity.
- The default signing path reads the existing original keystore; do not copy it
  or put passwords/tokens into source, logs or documentation.
- Deliver future test APKs by download/file links only. Never create a GitHub
  release/prerelease or change live APK/library selections.
- Do not run inherited publication scripts as part of UI testing.
- Put generated test outputs in `qa-output/` (ignored), not elsewhere in the repo.
- The source-copy inventory is `qa/source-baseline.json`.
