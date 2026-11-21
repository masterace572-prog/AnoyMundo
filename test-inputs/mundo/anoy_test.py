#!/usr/bin/env python3
"""Pinned one-time Anoy/Mundo build. Normal Gradle files stay unchanged in Git.

inspect / self-test: safe local static checks; no Android SDK or Java needed.
prepare: patches only an ephemeral GitHub Actions checkout; never deletes AARs.
verify-apk: checks the actual SDKs, Anoy identity and exact uploaded native code.
"""
from __future__ import annotations

import argparse
from io import BytesIO
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import unittest
from unittest.mock import patch
from zipfile import ZipFile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
ORIGINAL = ROOT / 'xlox/app/libs/mundo-release.aar'
UPLOADED = ROOT / 'test-inputs/mundo/mundo-release-lv10_cracked.aar'
ORIGINAL_SHA256 = '368293d93076aea5db252d307614ba10a23bae84eb298830e6dab42aa3f90ba0'
UPLOADED_SHA256 = '404536acf645805c4843aefce5061b43d32c82e6e4bd6d32219bb43047c67473'
TEST_VERSION = '2.4.2-mundo-lv10-test'
TEST_CODE = 20
ANDROID = '{http://schemas.android.com/apk/res/android}'

PACKAGING = '''    // ONE-TIME TEST ONLY: retain the supplied native binaries byte-for-byte.
    packagingOptions {
        jniLibs {
            keepDebugSymbols += ['**/libixord1fyrx8dy.so', '**/libmundoproxy.so']
        }
    }
'''
OLD_DEPENDENCY = "    implementation fileTree(dir: 'libs', includes: ['*.aar'])"
TEST_DEPENDENCY = '''    // ONE-TIME TEST ONLY: the original AAR stays on disk but is NOT linked.
    implementation fileTree(dir: 'libs', includes: ['*.aar'], excludes: ['mundo-release.aar'])
    implementation files(rootProject.file('../test-inputs/mundo/mundo-release-lv10_cracked.aar'))'''


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def inputs() -> dict:
    require(sha256(ORIGINAL.read_bytes()) == ORIGINAL_SHA256, 'Original Mundo AAR changed; refusing test build')
    require(sha256(UPLOADED.read_bytes()) == UPLOADED_SHA256, 'Uploaded AAR does not match the reviewed file')
    with ZipFile(UPLOADED) as archive:
        require(archive.testzip() is None, 'Uploaded AAR CRC failure')
        manifest = ET.fromstring(archive.read('AndroidManifest.xml'))
        require(manifest.find('uses-sdk').get(ANDROID + 'minSdkVersion') == '21', 'Unexpected AAR minimum SDK')
        metadata = archive.read('META-INF/com/android/build/gradle/aar-metadata.properties').decode()
        require(re.search(r'(?m)^minCompileSdk=37$', metadata) is not None, 'Unexpected AAR compile SDK')
        native = {info.filename: sha256(archive.read(info)) for info in archive.infolist() if info.filename.endswith('.so')}
        require(set(native) == {'jni/arm64-v8a/libixord1fyrx8dy.so', 'jni/arm64-v8a/libmundoproxy.so'}, 'Unexpected uploaded native libraries')
        # No build-time include/file-I/O directives from this third-party AAR.
        rules = archive.read('proguard.txt').decode()
        allowed_rules = {'-dontwarn', '-keep', '-keepclasseswithmembernames', '-keepclassmembers', '-keepclasseswithmembers'}
        for line in rules.splitlines():
            line = line.strip()
            if line.startswith('-'):
                require(line.split()[0] in allowed_rules, 'Unreviewed consumer ProGuard directive')
            require(not line.startswith('@'), 'External consumer ProGuard include is not permitted')
        with ZipFile(BytesIO(archive.read('classes.jar'))) as jar:
            require(not any(name.startswith('META-INF/services/') for name in jar.namelist()), 'Unreviewed build-time service provider')
    return {'original_sha256': ORIGINAL_SHA256, 'uploaded_sha256': UPLOADED_SHA256,
            'native_sha256': native, 'package': 'com.xlox', 'version_name': TEST_VERSION,
            'version_code': TEST_CODE, 'compile_sdk': 37, 'min_sdk': 21, 'target_sdk': 28}


def replace_once(text: str, old: str, new: str) -> str:
    require(text.count(old) == 1, 'Expected exactly one build-setting anchor: ' + old.splitlines()[0])
    return text.replace(old, new, 1)


def test_gradle(text: str) -> str:
    text = replace_once(text, '    compileSdk 36\n', '    compileSdk 37\n    buildToolsVersion \'36.0.0\'\n' + PACKAGING)
    text = replace_once(text, "        versionName '2.4.2'", f"        versionName '{TEST_VERSION}'")
    text = replace_once(text, OLD_DEPENDENCY, TEST_DEPENDENCY)
    require('        minSdk 21\n' in text and '        targetSdk 28\n' in text, 'Runtime SDK contract changed')
    require(f'        versionCode {TEST_CODE}\n' in text, 'Unexpected Anoy version code')
    require("applicationId 'com.xlox'" in text and "namespace 'com.xlox'" in text, 'Anoy installation/JNI identity changed')
    return text


def prepare() -> None:
    require(os.environ.get('GITHUB_ACTIONS') == 'true', 'prepare is restricted to an ephemeral GitHub Actions checkout')
    evidence = inputs()
    gradle = ROOT / 'xlox/app/build.gradle'
    properties = ROOT / 'xlox/gradle.properties'
    # Validate everything before writing either temporary build file.
    updated_gradle = test_gradle(gradle.read_text())
    updated_properties = replace_once(properties.read_text(), 'android.suppressUnsupportedCompileSdk=36', 'android.suppressUnsupportedCompileSdk=37')
    gradle.write_text(updated_gradle)
    properties.write_text(updated_properties)
    evidence_dir = ROOT / 'dist'
    evidence_dir.mkdir(exist_ok=True)
    (evidence_dir / 'MUNDO-TEST-INPUTS.json').write_text(json.dumps(evidence, indent=2) + '\n')
    require(sha256(ORIGINAL.read_bytes()) == ORIGINAL_SHA256, 'Original AAR must remain intact')
    print('PASS: ephemeral Anoy-only test settings prepared; original AAR retained, SDKs 37 / 21 / 28')


def sdk_value(manifest: str, name: str) -> int | None:
    match = re.search(r'android:' + re.escape(name) + r'\([^)]*\)\s*=\s*(?:\(type 0x1[01]\)\s*)?(0x[0-9a-fA-F]+|[0-9]+)(?=\s|$)', manifest)
    return int(match[1], 0) if match else None


def verify_native(archive: ZipFile, expected: dict[str, str]) -> None:
    names = set(archive.namelist())
    for name, digest in expected.items():
        apk_name = name.replace('jni/', 'lib/', 1)
        require(apk_name in names, 'Missing test native library: ' + apk_name)
        require(sha256(archive.read(apk_name)) == digest, 'Test native library was altered or stripped: ' + apk_name)
    for old_name in ('libLizard.so', 'libmmkv.so', 'libmsaoaidsec.so'):
        require('lib/arm64-v8a/' + old_name not in names, 'Original Mundo was accidentally linked: ' + old_name)
    require('lib/arm64-v8a/libXloX.so' in names, 'Anoy native bridge missing')
    require(not any(name.startswith(('lib/armeabi', 'lib/x86')) for name in names), 'Unexpected non-arm64 native code')


def verify_apk(path: Path) -> None:
    evidence = inputs()
    sdk = os.environ.get('ANDROID_SDK_ROOT') or os.environ.get('ANDROID_HOME')
    tools = sorted(Path(sdk).glob('build-tools/*/aapt2')) if sdk else []
    require(bool(tools), 'aapt2 is required for compiled APK verification')
    def aapt(*args):
        result = subprocess.run([str(tools[-1]), 'dump', *args, str(path)], capture_output=True, text=True)
        require(result.returncode == 0, 'aapt2 failed: ' + result.stderr[:1500])
        return result.stdout
    badging = aapt('badging')
    require("package: name='com.xlox'" in badging and "application-label:'Anoy'" in badging, 'Test APK is not Anoy/com.xlox')
    require(f"versionName='{TEST_VERSION}'" in badging and f"versionCode='{TEST_CODE}'" in badging, 'Test APK version marker/code incorrect')
    require("compileSdkVersion='37'" in badging, 'Test APK was not compiled against SDK 37')
    manifest = aapt('xmltree', '--file', 'AndroidManifest.xml')
    require(sdk_value(manifest, 'minSdkVersion') == 21 and sdk_value(manifest, 'targetSdkVersion') == 28, 'Test APK must retain min SDK 21 / target SDK 28')
    resources = aapt('resources')
    contact = re.search(r'(?ms)^\s*resource [^\n]*\bstring/telegram_url[^\n]*\n(.*?)(?=^\s*resource |\Z)', resources)
    require(bool(contact and 'https://t.me/libAkAudioVisiual' in contact[1]), 'Anoy contact changed')
    with ZipFile(path) as archive:
        require(archive.testzip() is None, 'Test APK CRC failure')
        verify_native(archive, evidence['native_sha256'])
        require('assets/licenses/KENNY_ARTWORK.md' not in archive.namelist(), 'Kenny flavor assets leaked into Anoy test')
        require('AndroidManifest.xml' in archive.namelist() and 'classes.dex' in archive.namelist(), 'APK structure incomplete')
        elf_dir = path.parent / 'elf-check'
        elf_dir.mkdir(exist_ok=True)
        for name in archive.namelist():
            if name.startswith('lib/arm64-v8a/') and name.endswith('.so'):
                extracted = elf_dir / Path(name).name
                extracted.write_bytes(archive.read(name))
                subprocess.run([sys.executable, str(ROOT / 'xlox/scripts/check_elf_alignment.py'), '--fail-on-unaligned', str(extracted)], check=True)
    print('PASS: Anoy identity/contact/version, SDK 37/21/28, original AAR exclusion, exact uploaded native bytes and all native alignments')


class StaticTests(unittest.TestCase):
    def test_pinned_inputs(self):
        self.assertEqual(inputs()['min_sdk'], 21)
    def test_ephemeral_gradle_only_changes_requested_settings(self):
        original = (ROOT / 'xlox/app/build.gradle').read_text()
        updated = test_gradle(original)
        restored = updated.replace('    compileSdk 37\n    buildToolsVersion \'36.0.0\'\n' + PACKAGING, '    compileSdk 36\n')
        restored = restored.replace(f"versionName '{TEST_VERSION}'", "versionName '2.4.2'")
        restored = restored.replace(TEST_DEPENDENCY, OLD_DEPENDENCY)
        self.assertEqual(restored, original)
    def test_double_preparation_rejected(self):
        original = (ROOT / 'xlox/app/build.gradle').read_text()
        with self.assertRaises(ValueError):
            test_gradle(test_gradle(original))
    def test_missing_anchor_rejected(self):
        with self.assertRaises(ValueError): replace_once('different source', 'expected', 'updated')
    def test_duplicate_anchor_rejected(self):
        with self.assertRaises(ValueError): replace_once('same same', 'same', 'updated')
    def test_cannot_patch_normal_local_checkout(self):
        with patch.dict(os.environ, {'GITHUB_ACTIONS': 'false'}):
            with self.assertRaisesRegex(ValueError, 'ephemeral'): prepare()
    def test_sdk_parser_supports_both_aapt2_formats(self):
        for text in ('A: android:minSdkVersion(0x0101020c)=(type 0x10)0x15', 'A: http://schemas.android.com/apk/res/android:minSdkVersion(0x0101020c)=21'):
            self.assertEqual(sdk_value(text, 'minSdkVersion'), 21)
        self.assertIsNone(sdk_value('A: android:targetSdkVersion(0x01010270)=28', 'minSdkVersion'))
    def native_fixture(self, old=False, changed=False):
        data = BytesIO()
        with ZipFile(data, 'w') as apk:
            apk.writestr('lib/arm64-v8a/libXloX.so', b'native bridge fixture')
            apk.writestr('lib/arm64-v8a/libnew.so', b'changed' if changed else b'new native fixture')
            if old: apk.writestr('lib/arm64-v8a/libLizard.so', b'old fixture')
        data.seek(0)
        return ZipFile(data)
    def test_correct_native_selection_passes(self):
        with self.native_fixture() as apk:
            verify_native(apk, {'jni/arm64-v8a/libnew.so': sha256(b'new native fixture')})
    def test_linking_old_and_new_libraries_fails(self):
        with self.native_fixture(old=True) as apk:
            with self.assertRaisesRegex(ValueError, 'Original Mundo'): verify_native(apk, {'jni/arm64-v8a/libnew.so': sha256(b'new native fixture')})
    def test_modified_native_bytes_fail(self):
        with self.native_fixture(changed=True) as apk:
            with self.assertRaisesRegex(ValueError, 'altered or stripped'): verify_native(apk, {'jni/arm64-v8a/libnew.so': sha256(b'new native fixture')})


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('inspect', 'self-test', 'prepare', 'verify-apk'))
    parser.add_argument('apk', nargs='?', type=Path)
    args = parser.parse_args()
    if args.action == 'inspect': print(json.dumps(inputs(), indent=2))
    elif args.action == 'self-test':
        result = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(StaticTests))
        sys.exit(not result.wasSuccessful())
    elif args.action == 'prepare': prepare()
    else:
        require(args.apk is not None, 'verify-apk requires an APK path')
        verify_apk(args.apk)


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        print('FAIL: ' + str(error), file=sys.stderr)
        if os.environ.get('GITHUB_ACTIONS'):
            print('::error title=One-time Mundo test::' + str(error).replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A'))
        sys.exit(1)
