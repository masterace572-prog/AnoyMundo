#!/usr/bin/env python3
"""Air native-copy integrity regressions, without accounts or a phone.

Runs the actual read-only verifier on real ZIP/native-file fixtures in a 32 MiB
JVM. Integration with Android PackageInfo/readiness/SDK adapters runs separately
in GamePackagePreparerTest under Robolectric for BOTH brands. Neither fixture
suite starts the SDK or a game; neither proves the user's SIGBUS is fixed.

Use JAVA + ECJ_JAR on a JRE-only host. --compiled-apks APK... checks the R8 output
instead of executing fixtures. No dependencies or game files are downloaded.
"""
from __future__ import annotations

import argparse
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest
from zipfile import ZipFile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / 'app/src/main/java/com/xlox'
MARKERS = (b'Air preparation cancelled', b'Air native preparation: VERIFIED_AFTER_SDK_REIMPORT',
           b'Air native preparation: VERIFIED\x00')


def verify_dex(data: list[bytes]) -> None:
    assert data, 'No compiled DEX'
    for marker in MARKERS:
        assert any(marker in dex for dex in data), 'Missing compiled Air preparation marker: ' + repr(marker)


class PreparationWiring(unittest.TestCase):
    def test_air_check_is_after_readiness_and_uses_one_original_sdk_import_at_most(self):
        source = (JAVA / 'utils/GamePackagePreparer.java').read_text()
        self.assertIn('nativeReady = game != GameTarget.BGMI_AIR || nativeLibrariesMatch(installed, virtual);', source)
        self.assertLess(source.index('awaitLaunchReady(game.packageName)'), source.index('nativeLibrariesMatch(installed, virtual)'))
        self.assertEqual(source.count('container.installApkSet(source.directory.getPath())'), 1)
        self.assertIn('attempt < 2', source)
        self.assertIn('if (nativeReady || imported) break;', source)
        self.assertIn('if (!nativeReady) return result(Status.NATIVE_LIBRARIES_UNAVAILABLE);', source)
        self.assertIn('MetaApplicationInstaller.installAppsByPath(directory)', source)
        for forbidden in ('cloneApp(', 'AndMove(', 'uninstall', 'cleanPackageData', 'setAccessible(', 'System.load('):
            self.assertNotIn(forbidden, re.sub(r'/\*.*?\*/|//[^\n]*', '', source, flags=re.S))

    def test_verifier_only_reads_packaged_arm64_libraries_with_bounded_memory(self):
        source = (JAVA / 'utils/AirNativeLibraries.java').read_text()
        self.assertIn('lib/arm64-v8a/', source)
        self.assertIn('new byte[64 * 1024]', source)
        self.assertIn('new CRC32()', source)
        self.assertIn('crc.getValue() != value.crc', source)
        self.assertIn('directory.equals(file.getParentFile())', source)
        for forbidden in ('FileOutputStream', 'write(', '.delete(', '.renameTo(', 'ProcessBuilder', 'Runtime.getRuntime',
                          'System.load', 'setAccessible', 'readAllBytes', 'libbgmi.so', 'libbgmilite.so', '/data/tombstones'):
            self.assertNotIn(forbidden, source)
        # Packaging remains arm64-only; a 32-bit game must not be accepted as an empty native set.
        self.assertIn("abiFilters 'arm64-v8a'", (ROOT / 'app/build.gradle').read_text())
        self.assertIn('if (expected.isEmpty()) return !otherAbi;', source)

    def test_no_running_game_is_killed_or_replaced_to_repair_air(self):
        source = (JAVA / 'utils/GamePackagePreparer.java').read_text()
        self.assertIn('game == GameTarget.BGMI_AIR && container.isRunning(game.packageName)', source)
        self.assertEqual(source.count('container.isRunning(game.packageName)'), 2)
        self.assertIn('if (!hostUnchanged(installed, game.packageName))', source)
        self.assertEqual(source.count('MetaActivityManager.'), 1)
        self.assertIn('return MetaActivityManager.isAppRunning(pkg);', source)
        self.assertNotIn('killApp', source)
        self.assertIn('InterruptedException | InterruptedIOException', source)

    def test_legacy_and_direct_from_apk_extraction_policy_is_preserved(self):
        source = (JAVA / 'utils/GamePackagePreparer.java').read_text()
        self.assertIn('Build.VERSION.SDK_INT < 23', source)
        self.assertIn('(installed.flags | virtual.flags) & ApplicationInfo.FLAG_EXTRACT_NATIVE_LIBS', source)
        verifier = (JAVA / 'utils/AirNativeLibraries.java').read_text()
        self.assertIn('if (!file.exists() && !extractionRequired) continue;', verifier)
        self.assertNotRegex(source, r'\bapp\.flags\s*[|&^]?=')  # storing a snapshot's final int is allowed
        original = ROOT / 'app/libs/mundo-release.aar'
        self.assertEqual(hashlib.sha256(original.read_bytes()).hexdigest(),
                         '368293d93076aea5db252d307614ba10a23bae84eb298830e6dab42aa3f90ba0')

    def test_failed_native_readiness_has_an_explicit_non_destructive_ui_error(self):
        home = (JAVA / 'MAct.java').read_text()
        self.assertIn('case NATIVE_LIBRARIES_UNAVAILABLE: message = R.string.game_error_native_setup;', home)
        self.assertIn('case GAME_RUNNING: message = R.string.game_error_apk_running;', home)
        strings = {e.get('name'): ''.join(e.itertext()) for e in ET.parse(
            ROOT / 'app/src/main/res/values/strings.xml').getroot().findall('string')}
        self.assertIn('Launch was stopped', strings['game_error_native_setup'])
        for key in ('game_error_native_setup', 'game_error_apk_running'):
            self.assertIn('%1$s', strings[key])
            self.assertIn('data has not been cleared', strings[key])

    def test_integration_cases_and_compiled_markers_are_required_in_ci(self):
        workflow = (ROOT.parent / '.github/workflows/build-xlox.yml').read_text()
        fixture = (ROOT / 'app/src/test/java/com/xlox/utils/GamePackagePreparerTest.java').read_text()
        cases = len(re.findall(r'@Test\s+public void ', fixture))
        self.assertGreaterEqual(cases, 70)
        self.assertIn('sdk = {21, 28, 35}', fixture)
        self.assertIn("'com.xlox.utils.GamePackagePreparerTest': " + str(cases * 3), workflow)
        self.assertIn('python3 -B scripts/test_air_native_preparation.py\n', workflow)
        self.assertIn('test_air_native_preparation.py --compiled-apks ../dist/Anoy-XLOX-loader.apk ../dist/KennyPvt-loader.apk', workflow)

    def test_compiled_marker_gate_rejects_partial_or_old_apks(self):
        verify_dex([b'\x00'.join(MARKERS)])
        for invalid in ([], [b'old APK'], [MARKERS[0]], [MARKERS[1] + MARKERS[2]]):
            with self.assertRaises(AssertionError):
                verify_dex(invalid)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--compiled-apks', nargs='+', type=Path)
    args = parser.parse_args()
    if args.compiled_apks:
        for path in args.compiled_apks:
            with ZipFile(path) as apk:
                data = [apk.read(name) for name in apk.namelist() if re.fullmatch(r'classes(?:[0-9]+)?\.dex', name)]
            verify_dex(data)
            print('PASS compiled Air native preparation:', path.name)
        return
    suite = unittest.defaultTestLoader.loadTestsFromTestCase(PreparationWiring)
    if not unittest.TextTestRunner(verbosity=2).run(suite).wasSuccessful():
        raise SystemExit(1)
    java = os.environ.get('JAVA') or shutil.which('java')
    javac = shutil.which('javac')
    ecj = os.environ.get('ECJ_JAR')
    if not java or not (javac or ecj):
        raise RuntimeError('A JDK is needed (or JAVA + ECJ_JAR for a JRE-only host)')
    sources = [JAVA / 'utils/AirNativeLibraries.java', ROOT / 'tests/runtime/AirNativeLibrariesTest.java']
    with tempfile.TemporaryDirectory(prefix='air-native-integrity-test-') as output:
        compiler = [javac, '-source', '8', '-target', '8'] if javac else [java, '-jar', ecj, '-8', '-warn:none']
        subprocess.run([*compiler, '-d', output, *map(str, sources)], check=True)
        subprocess.run([java, '-Xmx32m', '-ea', '-cp', output, 'com.xlox.tests.AirNativeLibrariesTest'], check=True, timeout=60)


if __name__ == '__main__':
    main()
