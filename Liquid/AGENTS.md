# Isolated Liquid navigation workspace

- Work only in `Liquid/` for this visual experiment. Do not edit `xlox/`,
  `claudeuiredesign/`, web/database/live channels or public releases.
- Scope: bottom Home/Settings navigation glass and its floating-overlay clearance.
  Do not redesign Home/Login/Splash, change gameplay, permissions, licences, SDKs,
  native payloads, versions, package IDs, server routes or update rules.
- `BASELINE.json` records the original project. Run `scripts/check_liquid_scope.py`.
- Glass must sample only the sibling pager, stay transparent on fallback, keep
  text sharp, and stop sampling/animation when idle/detached. No screen exports,
  PixelCopy, root access, FLAG_SECURE changes, perpetual animation or game hooks.
- Reduced motion and 48dp keyboard/TalkBack-accessible targets remain mandatory.
- Never copy a private keystore here. Signing references the existing key in
  `../xlox/app/` or an explicitly configured external key path.
- Use only the artifact-only `liquid-nav.yml` workflow. Never run copied publishing
  or emulator-install helpers against a user's app/data or a public release.
