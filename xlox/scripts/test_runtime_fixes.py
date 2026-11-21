#!/usr/bin/env python3
"""Run credential-free JVM regressions and Android wiring checks.

CI uses its JDK's javac. For a local JRE-only environment, JAVA and ECJ_JAR may
point at an Eclipse Java compiler; no tools/dependencies are downloaded here.
Optional APK arguments additionally check the actual merged callback manifest.
"""
from __future__ import annotations

import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from zipfile import ZipFile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / 'app/src/main/java/com/xlox'
ANDROID = '{http://schemas.android.com/apk/res/android}'
TOOLS = '{http://schemas.android.com/tools}'
MANIFEST = ROOT / 'app/src/main/AndroidManifest.xml'


class AndroidWiring(unittest.TestCase):
    def test_original_mundo_is_still_the_normal_dependency(self):
        original = ROOT / 'app/libs/mundo-release.aar'
        self.assertEqual(hashlib.sha256(original.read_bytes()).hexdigest(),
                         '368293d93076aea5db252d307614ba10a23bae84eb298830e6dab42aa3f90ba0')
        gradle = (ROOT / 'app/build.gradle').read_text()
        self.assertIn("implementation fileTree(dir: 'libs', includes: ['*.aar'])", gradle)
        self.assertNotIn('test-inputs', gradle)
        self.assertNotIn('checkAnoyReleaseAarMetadata', gradle)
        for value in ('minSdk 21', 'targetSdk 28', 'compileSdk 36'):
            self.assertIn(value, gradle)

    def test_alias_preserves_original_filters_but_targets_the_return_bridge(self):
        app = ET.parse(MANIFEST).getroot().find('application')
        alias = next(e for e in app.findall('activity-alias')
                     if e.get(ANDROID + 'name') == 'top.bienvenido.mundo.FBLoginAbility')
        self.assertEqual(alias.get(ANDROID + 'targetActivity'), 'com.xlox.GameLoginCallbackActivity')
        self.assertEqual(alias.get(TOOLS + 'replace'), 'android:targetActivity')
        self.assertIsNone(alias.get(TOOLS + 'node'))  # Do not replace/drop the inherited filters.
        with ZipFile(ROOT / 'app/libs/mundo-release.aar') as aar:
            old = ET.fromstring(aar.read('AndroidManifest.xml')).find('application')
        old_alias = next(e for e in old.findall('activity-alias')
                         if e.get(ANDROID + 'name') == 'top.bienvenido.mundo.FBLoginAbility')
        schemes = {e.get(ANDROID + 'scheme') for e in old_alias.findall('.//data')}
        self.assertTrue({'fbconnect', 'imobile1450', 'tencent1106545419'} <= schemes)
        names = [e.get(ANDROID + 'name') for e in app]
        self.assertLess(names.index('.GameLoginCallbackActivity'), names.index('top.bienvenido.mundo.FBLoginAbility'))

    def test_new_callback_filter_is_narrow(self):
        app = ET.parse(MANIFEST).getroot().find('application')
        activity = next(e for e in app.findall('activity')
                        if e.get(ANDROID + 'name') == '.GameLoginCallbackActivity')
        self.assertEqual(activity.get(ANDROID + 'exported'), 'true')
        self.assertEqual(activity.get(ANDROID + 'noHistory'), 'true')
        filters = activity.findall('intent-filter')
        self.assertEqual(len(filters), 1)
        data = filters[0].findall('data')
        self.assertEqual(len(data), 1)
        self.assertEqual(data[0].get(ANDROID + 'scheme'), 'twittersdk')
        self.assertEqual(data[0].get(ANDROID + 'host'), 'callback')

    def test_callback_never_starts_the_outside_game_or_logs_return_data(self):
        bridge = (JAVA / 'GameLoginCallbackActivity.java').read_text()
        self.assertIn('MetaActivityManager.launchIntent(intent, GameLoginRouter.USER)', bridge)
        self.assertIn('.setPackage(GameLoginRouter.GAME)', bridge)
        self.assertNotIn('launchApp(', bridge)
        self.assertNotIn('killApp', bridge)
        self.assertNotIn('getExtras(', bridge)
        self.assertNotIn('putExtra(', bridge)
        self.assertNotIn('FLAG_GRANT_', bridge)
        for line in bridge.splitlines():
            if 'Log.' in line:
                self.assertNotRegex(line, r'\b(value|uri|incoming|intent|ignored)\b')

    def test_compiled_manifest_parser_rejects_missing_or_ambiguous_routes(self):
        activity = '    E: activity (line=10)\n      A: android:name(0x01010003)="com.xlox.GameLoginCallbackActivity"\n      A: android:exported(0x01010010)=(type 0x12)0xffffffff\n      E: intent-filter (line=11)\n        E: data (line=12)\n          A: android:scheme(0x01010027)="twittersdk"\n          A: android:host(0x01010028)="callback"\n'
        alias = '    E: activity-alias (line=20)\n      A: android:name(0x01010003)="top.bienvenido.mundo.FBLoginAbility"\n      A: android:targetActivity(0x01010202)="com.xlox.GameLoginCallbackActivity"\n      E: intent-filter (line=21)\n        E: data (line=22)\n          A: android:scheme(0x01010027)="fbconnect"\n        E: data (line=23)\n          A: android:scheme(0x01010027)="imobile1450"\n        E: data (line=24)\n          A: android:scheme(0x01010027)="tencent1106545419"\n'
        verify_compiled_manifest(activity + alias)
        verify_compiled_manifest((activity + alias).replace('0xffffffff', 'true'))
        for invalid in (alias + activity, activity + activity + alias,
                        (activity + alias).replace('"twittersdk"', '"https"'),
                        (activity + alias).replace('"imobile1450"', '"missing"'),
                        (activity + alias).replace('0xffffffff', '0x0'),
                        (activity + alias).replace('android:targetActivity', 'android:wrong')):
            with self.assertRaises(AssertionError):
                verify_compiled_manifest(invalid)

    def test_disposed_gates_cancel_network_and_queued_work(self):
        gate = (JAVA / 'ui/UpdateGate.java').read_text()
        self.assertIn('request.cancel();', gate)
        self.assertIn('task.cancel(true);', gate)
        self.assertIn('gate.task = BACKGROUND.submit(', gate)
        self.assertIn('task = EXECUTOR.submit(', gate)
        self.assertNotIn('Downtwo.check(activity);', gate)
        self.assertNotIn('percent == lastPercent', gate)
        self.assertIn('if (optional) runRedownload();\n            else runCheck();', gate)

    def test_one_fresh_manifest_also_updates_the_game_support_notice(self):
        client = (JAVA / 'utils/Downtwo.java').read_text()
        support = (JAVA / 'utils/GameSupport.java').read_text()
        home = (JAVA / 'MAct.java').read_text()
        self.assertIn('GameSupport.remember(app, json.optString("game_version", ""))', client)
        self.assertNotIn('HttpURLConnection', support)
        self.assertNotIn('GameSupport.refresh', home)
        self.assertIn('showGameSupport(installedVersionName(PKG_BGMI));', home)
        self.assertIn('CHECK_BUDGET_MS = 20_000L', client)
        self.assertIn('DOWNLOAD_BUDGET_MS = 90_000L', client)
        self.assertIn('expectedSha.equalsIgnoreCase(actualSha)', client)
        self.assertIn('USER_AGENT, release.sha256.isEmpty(), request)', client)
        self.assertNotIn('lastReported', client)
        self.assertIn('LibManager.saveInstalled', client)
        self.assertIn('MAX_ATTEMPTS = 2', client)


def verify_compiled_manifest(text: str) -> None:
    components = []
    for match in re.finditer(r'(?m)^([ \t]*)E: (activity-alias|activity)\b[^\n]*\n', text):
        indent = len(match[1])
        lines = []
        for line in text[match.end():].splitlines():
            if line.strip() and len(line) - len(line.lstrip()) <= indent:
                break
            lines.append(line)
        components.append((match[2], '\n'.join(lines)))
    callbacks = [(i, body) for i, (kind, body) in enumerate(components)
                 if kind == 'activity' and '"com.xlox.GameLoginCallbackActivity"' in body]
    aliases = [(i, body) for i, (kind, body) in enumerate(components)
               if kind == 'activity-alias' and '"top.bienvenido.mundo.FBLoginAbility"' in body]
    assert len(callbacks) == len(aliases) == 1, 'Missing/duplicate compiled login bridge'
    index, callback = callbacks[0]
    alias_index, alias = aliases[0]
    assert index < alias_index, 'Alias must follow its declared target activity'
    assert re.search(r'android:targetActivity[^\n]*"com.xlox.GameLoginCallbackActivity"', alias)
    for scheme in ('fbconnect', 'imobile1450', 'tencent1106545419'):
        assert f'"{scheme}"' in alias, 'Inherited login filter lost: ' + scheme
    assert '"twittersdk"' in callback and '"callback"' in callback
    assert '"http"' not in callback and '"https"' not in callback
    assert re.search(r'android:exported[^\n]*(?:0xffffffff|true)', callback)


def check_apk(path: Path) -> None:
    sdk = os.environ.get('ANDROID_SDK_ROOT') or os.environ.get('ANDROID_HOME')
    tools = sorted(Path(sdk).glob('build-tools/*/aapt2')) if sdk else []
    if not tools:
        raise RuntimeError('aapt2 is required for compiled callback checks')
    text = subprocess.check_output([str(tools[-1]), 'dump', 'xmltree', '--file',
                                    'AndroidManifest.xml', str(path)], text=True)
    verify_compiled_manifest(text)
    print('PASS compiled callback bridge, inherited provider filters and declaration order:', path.name)


def main() -> None:
    suite = unittest.defaultTestLoader.loadTestsFromTestCase(AndroidWiring)
    if not unittest.TextTestRunner(verbosity=2).run(suite).wasSuccessful():
        raise SystemExit(1)
    if len(sys.argv) > 1:
        for arg in sys.argv[1:]:
            check_apk(Path(arg))
        return
    java = os.environ.get('JAVA') or shutil.which('java')
    javac = shutil.which('javac')
    ecj = os.environ.get('ECJ_JAR')
    if not java or not (javac or ecj):
        raise RuntimeError('A JDK is needed (or JAVA + ECJ_JAR for a JRE-only local environment)')
    sources = [JAVA / 'utils' / name for name in ('GameLoginRouter.java', 'UpdateRequest.java', 'LibraryHttp.java')]
    sources.append(ROOT / 'tests/runtime/RuntimeFixesTest.java')
    with tempfile.TemporaryDirectory(prefix='loader-runtime-test-') as output:
        compiler = [javac, '-source', '8', '-target', '8'] if javac else [java, '-jar', ecj, '-8', '-warn:none']
        subprocess.run([*compiler, '-d', output, *map(str, sources)], check=True)
        subprocess.run([java, '-ea', '-cp', output, 'com.xlox.tests.RuntimeFixesTest'], check=True, timeout=30)


if __name__ == '__main__':
    main()
