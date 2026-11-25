#!/usr/bin/env python3
"""Read-only isolation guard for the navigation-only Android project copy."""
from pathlib import Path
import hashlib
import json
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
REPO = ROOT.parent
BASE = json.loads((ROOT / 'BASELINE.json').read_text())
ORIGINAL = REPO / BASE['source_project']
CHANGED = {
    '.gitignore', 'README.md', 'settings.gradle', 'app/build.gradle',
    'app/src/main/java/com/xlox/ui/CapsuleNavigation.java',
    'app/src/main/java/com/xlox/ui/HomeSettingsNavigation.java',
    'app/src/main/res/layout/activity_main.xml',
    'app/src/main/res/drawable/bg_nav_capsule.xml',
    'app/src/main/res/drawable/bg_nav_indicator.xml',
    'app/src/main/res/values/colors.xml',
    'app/src/test/java/com/xlox/ui/HomeSettingsNavigationTest.java',
    'scripts/test_navigation.py',
}
NEW = {
    'BASELINE.json', 'AGENTS.md', 'scripts/check_liquid_scope.py',
    'app/src/main/java/com/xlox/ui/LiquidGlassBackdrop.java',
    'app/src/main/java/com/xlox/ui/LiquidGlassMotion.java',
    'app/src/main/java/com/xlox/ui/LiquidGlassRenderer.java',
    'app/src/test/java/com/xlox/ui/LiquidGlassMotionTest.java',
    'app/src/test/java/com/xlox/ui/LiquidGlassNavigationTest.java',
    'app/src/test/java/com/xlox/ui/LiquidGlassRenderTest.java',
}
OMITTED = set(BASE['omitted_private_signing_files'])
IGNORED = {'build', '.gradle', '.cxx', '.externalNativeBuild', '.idea', '__pycache__',
           'captures', 'qa-logs', 'node_modules', 'screenshots'}


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    assert BASE['source_commit'] == '141d5433a5f37c71764295d3e4025aeaec31a18f'
    for name, expected in BASE['sha256'].items():
        assert sha(ORIGINAL / name) == expected, 'Production project changed: ' + name
        copy = ROOT / name
        if name in OMITTED:
            assert not copy.exists(), 'Never duplicate the private signing file: ' + name
        elif name not in CHANGED:
            assert copy.is_file() and sha(copy) == expected, 'Out-of-scope copied file change: ' + name
    for file in ROOT.rglob('*'):
        relative = file.relative_to(ROOT)
        if not file.is_file() or any(p in IGNORED for p in relative.parts):
            continue
        if file.name in ('local.properties',) or file.suffix in ('.apk', '.aab', '.log', '.pyc'):
            continue
        name = relative.as_posix()
        assert name in BASE['sha256'] or name in NEW, 'Unexpected copied-project file: ' + name
        assert file.suffix not in ('.jks', '.keystore'), 'Private key copied into experiment'
    for name in NEW:
        assert (ROOT / name).is_file(), 'Missing Liquid implementation/verification file: ' + name
    original_gradle = (ORIGINAL / 'app/build.gradle').read_text()
    expected_gradle = original_gradle.replace(
        "        storeFile    : signingValue('XLOX_STORE_FILE', 'xlox.storeFile') ?: 'xlox.jks',",
        "        // Share the existing signer by reference; never duplicate its private keystore.\n"
        "        storeFile    : signingValue('XLOX_STORE_FILE', 'xlox.storeFile')\n"
        "                ?: rootProject.file('../xlox/app/xlox.jks').absolutePath,")
    assert (ROOT / 'app/build.gradle').read_text() == expected_gradle, 'Non-signing Gradle/application change'
    assert (ROOT / 'settings.gradle').read_text() == (ORIGINAL / 'settings.gradle').read_text().replace(
        'rootProject.name = "Anoy"', 'rootProject.name = "Liquid"')
    colors = lambda p: {e.get('name'): e.text for e in ET.parse(p).getroot().findall('color')}
    original_colors = colors(ORIGINAL / 'app/src/main/res/values/colors.xml')
    copied_colors = colors(ROOT / 'app/src/main/res/values/colors.xml')
    assert all(copied_colors.get(k) == v for k, v in original_colors.items()), 'Original palette changed'
    assert all(k.startswith('liquid_') for k in copied_colors.keys() - original_colors.keys())
    nav = (ROOT / 'app/src/main/java/com/xlox/ui/CapsuleNavigation.java').read_text()
    renderer = (ROOT / 'app/src/main/java/com/xlox/ui/LiquidGlassRenderer.java').read_text()
    sampler = (ROOT / 'app/src/main/java/com/xlox/ui/LiquidGlassBackdrop.java').read_text()
    assert 'indicator.setTranslationX(progress * segmentWidth)' in nav
    assert 'Ui.reducedMotion(getContext())' in nav and 'removeCallbacks(glassFrame)' in nav
    assert 'BitmapShader image = backdrop.shader()' in renderer
    assert 'source.draw(sampleCanvas)' in sampler and 'MAX_PIXELS = 12288' in sampler
    assert 'new Handler' not in sampler and 'postDelayed' not in sampler
    for forbidden in ('PixelCopy.request', 'MediaProjection', 'clearFlags', 'setRenderEffect', 'System.load', 'su -c'):
        assert forbidden not in nav + renderer + sampler, forbidden
    workflow = (REPO / '.github/workflows/liquid-nav.yml').read_text()
    assert 'contents: read' in workflow and 'working-directory: Liquid' in workflow
    for forbidden in ('contents: write', 'gh release ', 'publish-release.mjs', 'working-directory: xlox'):
        assert forbidden not in workflow, 'Unsafe experimental workflow: ' + forbidden
    print(f"PASS: {len(BASE['sha256'])} original project hashes unchanged; non-navigation copy inputs identical.")
    print('PASS: only navigation/overlay optics + verification/scaffolding differ; no private keystore duplicated.')
    print('PASS: original SDK/native/login/OBB/update/branding/version/permission contracts preserved.')
    print('PASS: Liquid-only, read-only, artifact-only build; no production publication or live mutations.')


if __name__ == '__main__':
    main()
