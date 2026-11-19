#!/usr/bin/env python3
"""Two-brand/channel gates. Without args: static checks, no Android SDK.
With Anoy.apk KennyPvt.apk: also inspect the compiled packages/resources.
"""
from __future__ import annotations
import glob
import os
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / 'app'
failures = []
checks = 0


def annotation(message):
    if os.environ.get('GITHUB_ACTIONS'):
        print('::error title=Brand/channel APK check::' + str(message).replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A'))


def gate(condition, message):
    global checks
    checks += 1
    print(('PASS  ' if condition else 'FAIL  ') + message)
    if not condition:
        failures.append(message)
        annotation(message)


def strings(path):
    return {e.attrib['name']: ''.join(e.itertext()) for e in ET.parse(path).getroot().findall('string')}


def compiled_sdk_value(manifest, name):
    # Old aapt2: android:minSdkVersion(...)= (type 0x10)0x15
    # New aapt2: http://schemas.android.com/apk/res/android:minSdkVersion(...)=21
    match = re.search(r'android:' + re.escape(name)
                      + r'\([^)]*\)\s*=\s*(?:\(type 0x1[01]\)\s*)?(0x[0-9a-fA-F]+|[0-9]+)(?=\s|$)', manifest)
    return int(match[1], 0) if match else None


gate(all(compiled_sdk_value(xml, 'minSdkVersion') == number for xml, number in (
    ('A: android:minSdkVersion(0x0101020c)=(type 0x10)0x15', 21),
    ('A: http://schemas.android.com/apk/res/android:minSdkVersion(0x0101020c)=21', 21),
    ('A: http://schemas.android.com/apk/res/android:minSdkVersion(0x0101020c)=23', 23),
    ('A: android:targetSdkVersion(0x01010270)=28', None),
)), 'compiled SDK parser accepts both aapt2 formats without masking changed SDK values')

main = strings(APP / 'src/main/res/values/strings.xml')
kenny = strings(APP / 'src/kenny/res/values/strings.xml')
brand_strings = {key: value for key, value in main.items() if re.search('anoy', value, re.I)}
gate(bool(brand_strings) and all(key in kenny and not re.search('anoy', kenny[key], re.I)
                              and ('KennyPvt' in kenny[key] or 'KENNYPVT' in kenny[key])
                              for key in brand_strings), 'KennyPvt overrides EVERY visible Anoy string')
gate(main['app_name'] == 'Anoy' and kenny['app_name'] == 'KennyPvt', 'distinct launcher/app names')
for file in (APP / 'src/kenny/res').rglob('*.xml'):
    ET.parse(file)
gate(all((APP / f'src/kenny/res/drawable/{name}.xml').is_file()
         and (APP / f'src/kenny/res/drawable/{name}.xml').read_bytes()
         != (APP / f'src/main/res/drawable/{name}.xml').read_bytes()
         for name in ('ic_launcher_glyph', 'ic_launcher_mark', 'splash_mark')),
     'KennyPvt has its own adaptive, legacy and system-splash wordmarks')

gradle = (APP / 'build.gradle').read_text()
gate("namespace 'com.xlox'" in gradle and "applicationId 'com.xlox'" in gradle
     and "applicationId 'com.kennypvt'" in gradle, 'Anoy package/JNI preserved; KennyPvt separately installable')
gate(all(f"arguments 'RELEASE_CHANNEL={channel}'" in gradle for channel in ('anoy', 'kenny'))
     and '-DLOADER_KENNY=1' in (APP / 'src/main/jni/Android.mk').read_text(), 'native build is flavor-specific')
header = APP / 'src/main/jni/panel_config.h'
for channel in ('anoy', 'kenny'):
    flags = ['-DLOADER_KENNY=1'] if channel == 'kenny' else []
    macros = subprocess.run(['cpp', '-dM', *flags, str(header)], check=True, capture_output=True, text=True).stdout
    gate(f'/api/lib/version?channel={channel}"' in macros
         and f'/api/lib/download?channel={channel}"' in macros,
         f'{channel}: native version/download endpoints select only its own channel')

java = APP / 'src/main/java/com/xlox'
lib_client = (java / 'utils/Downtwo.java').read_text()
app_client = (java / 'utils/AppUpdater.java').read_text()
gate('appendQueryParameter("channel", BuildConfig.RELEASE_CHANNEL)' in lib_client
     and 'BuildConfig.PANEL_BASE_URL' in lib_client
     and '&channel=" + BuildConfig.RELEASE_CHANNEL' in app_client,
     'Java manifest/version/download/APK checks use the compiled channel (including fallbacks)')
gate('app.getPackageName().equals(info.packageName)' in app_client
     and 'BuildConfig.APPLICATION_ID + ".APP_INSTALL_STATUS"' in app_client,
     'APK package validation and installer callbacks are isolated by application ID')
gate('BuildConfig.DISPLAY_BRAND' in (java / 'MAct.java').read_text(), 'support info uses the correct display brand')
gate('public static final String LIB_NAME = "libbgmi.so"' in (java / 'utils/LibManager.java').read_text(),
     'installed main library is always libbgmi.so')
workflow = (ROOT.parent / '.github/workflows/build-xlox.yml').read_text()
gate(all(task in workflow for task in (':app:assembleAnoyRelease', ':app:assembleKennyRelease',
                                      'dist/Anoy-XLOX-loader.apk', 'dist/KennyPvt-loader.apk'))
     and not re.search(r'gh\s+release\s+delete|git/refs/tags|publish-release\.mjs', workflow),
     'one workflow uploads both APKs without deleting releases or changing live libraries')

if len(sys.argv) > 1:
    if len(sys.argv) != 3:
        sys.exit('usage: check_branding.py [Anoy.apk KennyPvt.apk]')
    sdk = os.environ.get('ANDROID_SDK_ROOT') or os.environ.get('ANDROID_HOME') or ''
    tools = sorted(glob.glob(str(Path(sdk) / 'build-tools/*/aapt2')))
    if not tools:
        sys.exit('aapt2 not found under ANDROID_SDK_ROOT/build-tools')
    def aapt(*args):
        result = subprocess.run([tools[-1], 'dump', *args], capture_output=True, text=True)
        if result.returncode:
            annotation(f'aapt2 {args[0]}: {result.stderr[:2000]}')
            sys.exit(result.returncode)
        return result.stdout

    for apk, package, brand in zip(sys.argv[1:], ('com.xlox', 'com.kennypvt'), ('Anoy', 'KennyPvt')):
        badging = aapt('badging', apk)
        summary = '\n'.join(line for line in badging.splitlines() if re.match(r'package:|application-label:|sdkVersion:|targetSdkVersion:', line))
        print(summary)
        gate(f"package: name='{package}'" in badging and f"application-label:'{brand}'" in badging,
             f'{brand}: compiled APK has the correct package and app label')
        if f"package: name='{package}'" not in badging or f"application-label:'{brand}'" not in badging:
            annotation(summary)
        # Check the authoritative compiled uses-sdk attributes. aapt2's
        # badging labels vary across build-tools; resource XML integer values
        # are stable and must be EXACTLY 21 / 28 for container compatibility.
        manifest = aapt('xmltree', '--file', 'AndroidManifest.xml', apk)
        minimum = compiled_sdk_value(manifest, 'minSdkVersion')
        target = compiled_sdk_value(manifest, 'targetSdkVersion')
        sdk_lines = '\n'.join(line.strip() for line in manifest.splitlines() if re.search('minSdkVersion|targetSdkVersion', line))
        if os.environ.get('GITHUB_ACTIONS'):
            metadata = f'{brand}: {summary}\n{sdk_lines}'
            print('::notice title=Compiled APK metadata::' + metadata.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A'))
        gate(minimum == 21 and target == 28,
             f'{brand}: compiled minSdk={minimum}, targetSdk={target} (required 21 / 28)')
        if minimum != 21 or target != 28:
            annotation(sdk_lines or 'Compiled uses-sdk attributes were not found in the aapt2 XML dump.')
        if brand == 'KennyPvt':
            dump = aapt('resources', apk)
            # Resource/style names may deliberately retain Anoy for compatibility;
            # only string resource VALUES are user-visible.
            current_string = False
            leaked = []
            for line in dump.splitlines():
                if re.match(r'\s*resource ', line):
                    current_string = bool(re.search(r'\bstring/', line))
                elif current_string and re.search(r'anoy', line, re.I):
                    leaked.append(line.strip())
            gate(not leaked, 'KennyPvt: compiled string resources contain no Anoy branding')
            if leaked:
                print('\n'.join(leaked[:12]))
                annotation('\n'.join(leaked[:12]))

print(f'\n{checks - len(failures)}/{checks} branding/channel gates passed')
sys.exit(bool(failures))
