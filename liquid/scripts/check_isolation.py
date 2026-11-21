#!/usr/bin/env python3
"""Prove that the original project and the copied non-UI implementation survive."""
from pathlib import Path
import hashlib
import json
import sys

ROOT = Path(__file__).resolve().parents[1]
REPO = ROOT.parent
baseline = json.loads((ROOT / 'qa/xlox-baseline.json').read_text())
errors = []
original_count = 0
core_count = 0

for relative, expected in baseline['files'].items():
    original = REPO / relative
    original_count += 1
    if not original.is_file() or hashlib.sha256(original.read_bytes()).hexdigest() != expected:
        errors.append('Original changed: ' + relative)
    inside = Path(relative).relative_to('xlox')
    # Everything in these areas is an exact copy, not a UI rewrite.
    protected = (
        inside.as_posix().startswith(('app/libs/', 'app/src/main/jni/',
                                     'app/src/main/java/com/xlox/utils/',
                                     'app/src/main/res/font/', 'app/src/kenny/'))
        or inside.as_posix() in (
            'app/src/main/AndroidManifest.xml', 'app/proguard-rules.pro',
            'app/src/main/java/com/xlox/BoxApplication.java',
            'app/src/main/java/com/xlox/GameLoginCallbackActivity.java',
        )
    )
    # Shortened explanatory copy is the only approved flavour-level delta.
    if inside.as_posix() == 'app/src/kenny/res/values/strings.xml':
        protected = False
    if protected:
        core_count += 1
        copy = ROOT / inside
        if not copy.is_file() or hashlib.sha256(copy.read_bytes()).hexdigest() != expected:
            errors.append('Non-UI copy changed: ' + inside.as_posix())

extras = {p.relative_to(REPO).as_posix() for p in (REPO / 'xlox').rglob('*') if p.is_file()
          and not any(part in {'.gradle', '.cxx', '.externalNativeBuild', 'build', '__pycache__'}
                      for part in p.relative_to(REPO / 'xlox').parts)} - set(baseline['files'])
for extra in sorted(extras):
    if not extra.endswith('local.properties'):
        errors.append('Unexpected original-project file: ' + extra)

if (ROOT / 'app/xlox.jks').exists():
    errors.append('Do not duplicate or regenerate the existing release signing key')

gradle = (ROOT / 'app/build.gradle').read_text()
for required in ("namespace 'com.xlox'", "applicationId 'com.xlox'", "applicationId 'com.kennypvt'",
                 'minSdk 21', 'targetSdk 28', 'compileSdk 36', "versionCode 21",
                 "versionName '2.5.0-liquid'", "'../../xlox/app/xlox.jks'"):
    if required not in gradle:
        errors.append('Compatibility requirement missing: ' + required)

# Only one styling call may differ in the existing web-login activity.
web = 'app/src/main/java/com/xlox/GameWebLoginActivity.java'
copy = (ROOT / web).read_text().replace('        com.xlox.ui.Liquid.attach(this);\n', '')
if copy != (REPO / 'xlox' / web).read_text():
    errors.append('Cancelled web-login behaviour changed beyond screen styling')

for error in errors:
    print('FAIL', error)
if errors:
    sys.exit(1)
print(f'PASS {original_count} original xlox files unchanged from {baseline["commit"][:7]}')
print(f'PASS {core_count} protected copied files identical (JNI, SDK, transport, manifests, fonts and branding)')
print('PASS package, SDK, signing and rollback identities preserved; cancelled login code untouched')
