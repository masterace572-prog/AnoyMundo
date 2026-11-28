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
from zipfile import ZipFile

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


def manifest_resource_id(manifest, name):
    match = re.search(r'android:' + re.escape(name)
                      + r'\([^)]*\)\s*=\s*@(?:0x)?([0-9a-fA-F]+)(?=\s|$)', manifest)
    return int(match[1], 16) if match else None


def drawable_resource_id(resources, name):
    match = re.search(r'^\s*resource\s+(0x[0-9a-fA-F]+)\s+[^\n]*\bdrawable/'
                      + re.escape(name) + r'(?=\s|$)', resources, re.M)
    return int(match[1], 16) if match else None


gate(all(compiled_sdk_value(xml, 'minSdkVersion') == number for xml, number in (
    ('A: android:minSdkVersion(0x0101020c)=(type 0x10)0x15', 21),
    ('A: http://schemas.android.com/apk/res/android:minSdkVersion(0x0101020c)=21', 21),
    ('A: http://schemas.android.com/apk/res/android:minSdkVersion(0x0101020c)=23', 23),
    ('A: android:targetSdkVersion(0x01010270)=28', None),
)), 'compiled SDK parser accepts both aapt2 formats without masking changed SDK values')
gate(all(manifest_resource_id(xml, 'roundIcon') == 0x7f080123 for xml in (
    'A: android:roundIcon(0x0101052c)=@0x7f080123',
    'A: http://schemas.android.com/apk/res/android:roundIcon(0x0101052c)=@0x7f080123',
)) and all(drawable_resource_id(xml, 'ic_launcher_round') == 0x7f080123 for xml in (
    '    resource 0x7f080123 drawable/ic_launcher_round\n',
    '    resource 0x7f080123 com.kennypvt:drawable/ic_launcher_round\n',
)) and drawable_resource_id('resource 0x7f080123 drawable/ic_launcher_round_extra\n', 'ic_launcher_round') is None,
     'compiled icon reference parser accepts aapt2 formats without confusing similarly named resources')

main = strings(APP / 'src/main/res/values/strings.xml')
kenny = strings(APP / 'src/kenny/res/values/strings.xml')
brand_strings = {key: value for key, value in main.items() if re.search('anoy', value, re.I)}
gate(bool(brand_strings) and all(key in kenny and not re.search('anoy', kenny[key], re.I)
                              and ('KennyPvt' in kenny[key] or 'KENNYPVT' in kenny[key])
                              for key in brand_strings), 'KennyPvt overrides EVERY visible Anoy string')
gate(main['app_name'] == 'Anoy' and kenny['app_name'] == 'KennyPvt', 'distinct launcher/app names')
gate(main['telegram_url'] == 'https://t.me/libAkAudioVisiual'
     and kenny.get('telegram_url') == 'https://t.me/crimecell',
     'KennyPvt Telegram uses crimecell; Anoy contact is unchanged')
for file in (APP / 'src/kenny/res').rglob('*.xml'):
    ET.parse(file)
kenny_res = APP / 'src/kenny/res'
artwork_refs = {
    'ic_launcher': 'kenny_launcher_legacy',
    'ic_launcher_round': 'kenny_launcher_round',
    'ic_launcher_glyph': 'kenny_launcher_foreground',
    'ic_launcher_mark': 'kenny_launcher_legacy',
    'splash_mark': 'kenny_splash_badge',
}
android = '{http://schemas.android.com/apk/res/android}'
gate(all((kenny_res / f'drawable/{name}.xml').is_file()
         and ET.parse(kenny_res / f'drawable/{name}.xml').getroot().tag == 'bitmap'
         and ET.parse(kenny_res / f'drawable/{name}.xml').getroot().get(android + 'src') == f'@drawable/{artwork}'
         for name, artwork in artwork_refs.items()),
     'KennyPvt launcher/helper/startup drawables use only the supplied artwork, not inherited Anoy wordmarks')
gate(all((kenny_res / f'drawable-v26/{name}.xml').is_file()
         and ET.parse(kenny_res / f'drawable-v26/{name}.xml').getroot().tag == 'adaptive-icon'
         and ET.parse(kenny_res / f'drawable-v26/{name}.xml').getroot().find('foreground').get(android + 'drawable')
         == '@drawable/ic_launcher_glyph'
         for name in ('ic_launcher', 'ic_launcher_round')),
     'both Kenny launcher entries use native adaptive masks on Android 26+')
kenny_manifest = ET.parse(APP / 'src/kenny/AndroidManifest.xml').getroot().find('application')
gate(kenny_manifest.get(android + 'roundIcon') == '@drawable/ic_launcher_round'
     and '@drawable/ic_launcher' in (APP / 'src/main/AndroidManifest.xml').read_text(),
     'Kenny has a separate circular fallback; the shared/Anoy manifest is unchanged')
gate(all((kenny_res / f'drawable-{density}/{name}.png').is_file()
         for density in ('mdpi', 'hdpi', 'xhdpi', 'xxhdpi', 'xxxhdpi')
         for name in set(artwork_refs.values()))
     and not any(path.suffix.lower() in ('.jpg', '.jpeg') for path in (APP / 'src/kenny').rglob('*')),
     'Kenny artwork has all five density variants; no unscaled source JPEG is bundled')

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
    expected_contact = 'crimecell' if channel == 'kenny' else 'libAkAudioVisiual'
    gate(f'#define ANOY_TELEGRAM_URL "https://t.me/{expected_contact}"' in macros,
         f'{channel}: native Telegram contact agrees with the flavor resources')

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
game_targets = (java / 'utils/GameTarget.java').read_text()
lib_manager = (java / 'utils/LibManager.java').read_text()
gate('BGMI("com.pubg.imobile", "libbgmi.so", "BGMI")' in game_targets
     and 'BGMI_AIR("com.krafton.bgmilite", "libbgmilite.so", "BGMI Air")' in game_targets
     and 'public static final String LIB_NAME = GameTarget.BGMI.libraryName;' in lib_manager
     and 'return libFile(context, GameTarget.BGMI);' in lib_manager
     and 'new File(loaderDir(context), game.libraryName)' in lib_manager,
     'both brands retain separate BGMI and BGMI Air package/library identities')
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
        expected_name = re.search(r"(?m)^\s*versionName\s+'([^']+)'", gradle)[1]
        expected_code = re.search(r'(?m)^\s*versionCode\s+(\d+)', gradle)[1]
        gate(f"versionCode='{expected_code}'" in badging and f"versionName='{expected_name}'" in badging,
             f'{brand}: compiled version is {expected_name} ({expected_code})')
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
        dump = aapt('resources', apk)
        for attribute, resource in (('icon', 'ic_launcher'),
                                    ('roundIcon', 'ic_launcher_round' if brand == 'KennyPvt' else 'ic_launcher')):
            actual_id = manifest_resource_id(manifest, attribute)
            expected_id = drawable_resource_id(dump, resource)
            gate(expected_id is not None and actual_id == expected_id,
                 f'{brand}: compiled {attribute} selects drawable/{resource}')
        with ZipFile(apk) as package_zip:
            # AGP shortens packaged PNG paths (e.g. res/ab.png). Check the
            # authoritative resource identities, never the physical basename.
            artwork_resources = [name for name in set(artwork_refs.values())
                                 if drawable_resource_id(dump, name) is not None]
            has_provenance = 'assets/licenses/KENNY_ARTWORK.md' in package_zip.namelist()
            correct_artwork = (len(artwork_resources) == 4) if brand == 'KennyPvt' else not artwork_resources
            isolated = correct_artwork and has_provenance == (brand == 'KennyPvt')
            gate(isolated, f'{brand}: supplied artwork and its provenance are isolated to the Kenny flavor')
            if not isolated:
                annotation(f'{brand}: artwork resource identities={sorted(artwork_resources)}, provenance={has_provenance}')
        contact = 'crimecell' if brand == 'KennyPvt' else 'libAkAudioVisiual'
        contact_block = re.search(r'(?ms)^\s*resource [^\n]*\bstring/telegram_url[^\n]*\n(.*?)(?=^\s*resource |\Z)', dump)
        gate(bool(contact_block and f'https://t.me/{contact}' in contact_block[1]),
             f'{brand}: compiled Telegram resource points to the correct contact')
        if brand == 'KennyPvt':
            # Resource/style names may deliberately retain Anoy for compatibility;
            # only string resource VALUES are user-visible.
            current_string = False
            leaked = []
            for line in dump.splitlines():
                if re.match(r'\s*resource ', line):
                    current_string = bool(re.search(r'\bstring/', line))
                elif current_string and re.search(r'anoy|libAkAudioVisiual', line, re.I):
                    leaked.append(line.strip())
            gate(not leaked, 'KennyPvt: compiled string resources contain no Anoy branding or old Telegram contact')
            if leaked:
                print('\n'.join(leaked[:12]))
                annotation('\n'.join(leaked[:12]))

print(f'\n{checks - len(failures)}/{checks} branding/channel gates passed')
sys.exit(bool(failures))
