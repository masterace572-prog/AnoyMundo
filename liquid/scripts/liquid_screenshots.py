#!/usr/bin/env python3
"""Offline Android UI checks and actual emulator captures, never production sign-in.

Distributed release APKs are NOT modified. Only temporary emulator copies have
arm64 native libraries removed and are re-signed with a disposable QA-only key.
Every screen fixture is already guarded by BuildConfig.DEBUG in the app.
"""
from __future__ import annotations

import argparse
from io import BytesIO
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import tempfile
import time
from zipfile import ZipFile, ZIP_DEFLATED
import xml.etree.ElementTree as ET

from PIL import Image, ImageStat

parser = argparse.ArgumentParser()
parser.add_argument('--apks', required=True, type=Path)
parser.add_argument('--out', required=True, type=Path)
parser.add_argument('--api', required=True, type=int)
args = parser.parse_args()
args.out.mkdir(parents=True, exist_ok=True)
sdk = Path(os.environ.get('ANDROID_SDK_ROOT') or os.environ['ANDROID_HOME'])
build_tools = sorted((sdk / 'build-tools').iterdir())[-1]
adb_path = sdk / 'platform-tools/adb'
evidence = {'api': args.api, 'scope': 'ABI-neutral emulator UI only; no native game/authentication claims',
            'fixtures': 'offline debug-only sample data, never live credentials', 'screens': [], 'checks': []}
pkg = 'com.xlox'
brand = 'anoy'


def run(*cmd, check=True, binary=False, timeout=90):
    result = subprocess.run([str(c) for c in cmd], check=False, capture_output=True,
                            text=not binary, timeout=timeout)
    if check and result.returncode:
        raise RuntimeError(f'{cmd[0]} failed: {result.stderr!s}\n{result.stdout!s}')
    return result.stdout


def adb(*cmd, **kwargs):
    return run(adb_path, *cmd, **kwargs)


def shell(*cmd, **kwargs):
    return adb('shell', shlex.join(str(c) for c in cmd), **kwargs)


def check(condition, message):
    evidence['checks'].append({'pass': bool(condition), 'message': message})
    print(('PASS ' if condition else 'FAIL ') + message, flush=True)
    if not condition:
        raise AssertionError(message)


def tree():
    for attempt in range(3):
        shell('rm', '-f', '/sdcard/liquid-ui.xml', check=False)
        shell('uiautomator', 'dump', '--compressed', '/sdcard/liquid-ui.xml', check=False, timeout=40)
        data = shell('cat', '/sdcard/liquid-ui.xml', check=False)
        try:
            return ET.fromstring(data), data
        except ET.ParseError:
            time.sleep(.5)
    raise AssertionError('Could not obtain an idle Android view hierarchy')


def find(view_id, doc=None):
    if doc is None: doc, _ = tree()
    return next((n for n in doc.iter('node') if n.get('resource-id', '').endswith(':id/' + view_id)), None)


def bounds(node):
    return [int(x) for x in re.findall(r'\d+', node.get('bounds', ''))]


def tap(view_id):
    node = find(view_id)
    check(node is not None, f'{brand}: {view_id} is available to interact with')
    x1, y1, x2, y2 = bounds(node)
    check(x2 > x1 and y2 > y1, f'{brand}: {view_id} has a visible hit target')
    shell('input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))
    time.sleep(.6)


def swipe_up():
    size = shell('wm', 'size')
    w, h = map(int, re.findall(r'(\d+)x(\d+)', size)[-1])
    shell('input', 'swipe', str(w // 2), str(int(h * .78)), str(w // 2), str(int(h * .3)), '260')
    time.sleep(.35)


def scroll_to(view_id, limit=6):
    for _ in range(limit):
        n = find(view_id)
        if n is not None and len(bounds(n)) == 4 and bounds(n)[3] > bounds(n)[1]: return
        swipe_up()
    raise AssertionError('Could not scroll to ' + view_id)


def capture(name, required=(), hierarchy=True):
    time.sleep(.45)
    full = brand + '-' + name
    focused = ''
    # Window focus can briefly be null during a cold, minified release start.
    # Wait for the actual app window; never mistake the launcher for a capture.
    for _ in range(24):
        focus = shell('dumpsys', 'window')
        focused = next((line for line in focus.splitlines() if 'mCurrentFocus=' in line), '')
        if pkg in focused:
            break
        time.sleep(.25)
    check(pkg in focused, f'{full}: loader is the foreground app ({focused.strip()})')
    visible_text = []
    if hierarchy:
        doc, data = tree()
        (args.out / (full + '.xml')).write_text(data)
        visible_text = [n.get('text') for n in doc.iter('node') if n.get('text')]
        for view_id in required:
            n = find(view_id, doc)
            check(n is not None, f'{full}: {view_id} is rendered')
            if n is not None and view_id in {'login', 'primaryAction', 'settingsBack', 'settingsButton',
                                             'helpButton', 'gateAction', 'appUpdatePrimary', 'toggleKey', 'pasteKey'}:
                x1, y1, x2, y2 = bounds(n)
                density = int(re.findall(r'\d+', shell('wm', 'density'))[-1]) / 160
                check((y2 - y1) / density >= 47 and (x2 - x1) / density >= 47,
                      f'{full}: {view_id} meets the 48dp target (rounding tolerance 1dp)')
    image = adb('exec-out', 'screencap', '-p', binary=True)
    frame = Image.open(BytesIO(image)).convert('RGB')
    check(min(frame.size) >= 480 and max(ImageStat.Stat(frame).stddev) > 8,
          full + ': non-blank emulator frame')
    (args.out / (full + '.png')).write_bytes(image)
    evidence['screens'].append({'name': full, 'size': frame.size, 'text': visible_text})
    print('CAPTURE ' + full, flush=True)


def start(activity, extras=(), wait=1):
    shell('am', 'force-stop', pkg)
    component = f'{pkg}/com.xlox.{activity}'
    result = shell('am', 'start', '-W', '-n', component, *extras)
    check('Error:' not in result and 'Exception' not in result, f'{brand}: {activity} starts')
    time.sleep(wait)


def home(*extras):
    start('MAct', ('--ez', 'qa_preview', 'true', '--ez', 'qa_freeze_clock', 'true',
                   '--es', 'qa_note', 'Preview announcement. Your panel message appears here.', *extras))


def hide_ime():
    # ESC is Back when no keyboard is showing on some Android versions. Do
    # not accidentally finish LogAct just to prepare a keyboard-free capture.
    state = shell('dumpsys', 'input_method')
    if re.search(r'\b(?:mInputShown|mIsInputViewShown|isInputViewShown)\s*=\s*true\b', state):
        shell('input', 'keyevent', '111')
        time.sleep(.5)


def login(*extras):
    start('LogAct', ('--es', 'qa_gate', 'ready', *extras))
    hide_ime()
    time.sleep(.4)


def settings(*extras):
    home('--ez', 'qa_settings', 'true', *extras)


def profile(width=1080, height=2400, density=420, font='1.0'):
    shell('wm', 'size', f'{width}x{height}')
    shell('wm', 'density', str(density))
    shell('settings', 'put', 'system', 'font_scale', font)
    time.sleep(1)


def install(source, directory):
    unsigned = directory / 'emulator-unsigned.apk'
    signed = directory / 'emulator-signed.apk'
    with ZipFile(source) as original, ZipFile(unsigned, 'w', ZIP_DEFLATED) as rewritten:
        for item in original.infolist():
            if not item.filename.startswith(('lib/', 'META-INF/')):
                rewritten.writestr(item, original.read(item.filename))
    run(build_tools / 'apksigner', 'sign', '--ks', directory / 'qa.jks',
        '--ks-pass', 'pass:android', '--key-pass', 'pass:android', '--out', signed, unsigned)
    adb('uninstall', pkg, check=False)
    result = adb('install', '-r', '-g', signed)
    check('Success' in result, brand + ': temporary ABI-neutral UI copy installs')
    for permission in ('READ_EXTERNAL_STORAGE', 'WRITE_EXTERNAL_STORAGE'):
        shell('pm', 'grant', pkg, 'android.permission.' + permission, check=False)
    shell('appops', 'set', pkg, 'MANAGE_EXTERNAL_STORAGE', 'allow', check=False)
    shell('appops', 'set', pkg, 'REQUEST_INSTALL_PACKAGES', 'allow', check=False)


def game_fixture(directory):
    # Manifest-only installed package for the launcher's game-present UI. This
    # is NOT BGMI and never launches, handles a login or enters a release artifact.
    fixture = directory / 'game-fixture'
    fixture.mkdir()
    manifest = fixture / 'AndroidManifest.xml'
    manifest.write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android"
        package="com.pubg.imobile" android:versionCode="1" android:versionName="4.0.0">
        <uses-sdk android:minSdkVersion="21" android:targetSdkVersion="28"/>
        <application android:hasCode="false" android:label="Offline UI fixture"/>
        </manifest>''')
    unsigned = fixture / 'unsigned.apk'
    signed = fixture / 'fixture.apk'
    platform = sorted((sdk / 'platforms').glob('*/android.jar'))[-1]
    run(build_tools / 'aapt', 'package', '-f', '-M', manifest, '-I', platform, '-F', unsigned)
    run(build_tools / 'apksigner', 'sign', '--ks', directory / 'qa.jks', '--ks-pass',
        'pass:android', '--key-pass', 'pass:android', '--out', signed, unsigned)
    adb('install', '-r', signed)


try:
    adb('wait-for-device')
    adb('logcat', '-c')
    for name in ('window_animation_scale', 'transition_animation_scale', 'animator_duration_scale'):
        shell('settings', 'put', 'global', name, '0')
    shell('settings', 'put', 'secure', 'show_ime_with_hard_keyboard', '1')
    shell('settings', 'put', 'global', 'hide_error_dialogs', '1')
    shell('input', 'keyevent', '82', check=False)
    with tempfile.TemporaryDirectory(prefix='liquid-emulator-') as folder:
        folder = Path(folder)
        run('keytool', '-genkeypair', '-keystore', folder / 'qa.jks', '-alias', 'qa',
            '-keyalg', 'RSA', '-keysize', '2048', '-validity', '2', '-storepass', 'android',
            '-keypass', 'android', '-dname', 'CN=Disposable UI QA')
        game_fixture(folder)
        for brand, pkg, apk in [('anoy', 'com.xlox', 'Anoy-Liquid-debug.apk'),
                                ('kenny', 'com.kennypvt', 'KennyPvt-Liquid-debug.apk')]:
            profile()
            install(args.apks / apk, folder)
            start('SplashActivity', ('--ef', 'qa_frame', '1.1', '--es', 'qa_app_update', 'up_to_date'), wait=1.5)
            capture('splash', ('splashName', 'splashStatus'))
            login()
            capture('login', ('userkey', 'toggleKey', 'pasteKey', 'login'))
            home()
            capture('home', ('primaryAction', 'helpButton', 'settingsButton', 'licenceCard'))
            if brand == 'kenny':
                settings()
                capture('settings', ('settingsBack', 'licenceKeyValue'))
                continue

            tap('helpButton')
            capture('help', ('helpTitle',))
            scroll_to('helpCopySupport')
            tap('helpCopySupport')
            capture('support', ('supportInfoCopy', 'supportInfoText'))
            tap('supportInfoCopy')
            capture('support-copied', ('supportInfoCopy',))
            home('--es', 'qa_action', 'signout')
            capture('signout', ('signOutConfirm', 'signOutForget'))
            settings('--es', 'qa_app_check', 'current')
            capture('settings', ('settingsBack', 'licenceKeyValue'))
            tap('licenceKeyEye')
            capture('settings-key-revealed', ('licenceKeyValue',))
            tap('licenceKeyRow')
            capture('settings-key-copied', ('licenceKeyValue',))
            scroll_to('redownloadRow')
            tap('redownloadRow')
            capture('redownload', ('redownloadConfirm', 'redownloadCancel'))
            settings()
            for _ in range(3): swipe_up()
            capture('settings-lower', ('signOut',))
            settings()
            scroll_to('glassEffects')
            tap('glassEffects')
            home()
            capture('home-solid', ('primaryAction',))
            settings()
            scroll_to('glassEffects')
            tap('glassEffects')

            for state in ('loading', 'wrong', 'offline', 'server', 'success'):
                login('--es', 'qa_result', state)
                capture('login-' + state, ('userkey', 'login'))
            login()
            tap('userkey')
            shell('input', 'text', 'DEMO-7D-EXAMPLE')
            capture('login-keyboard', ('userkey', 'login'))
            hide_ime()
            tap('toggleKey')
            capture('login-key-visible', ('userkey',))

            for state in ('checking', 'required', 'downloading', 'downloading_unknown',
                          'installing', 'done', 'check_failed', 'download_failed'):
                start('LogAct', ('--es', 'qa_gate', state))
                capture('library-' + state, ('gateTitle',))
            for state in ('normal', 'downloading', 'installing', 'failed'):
                start('AppUpdateActivity', ('--es', 'app_update_name', '2.5.1', '--ei', 'app_update_code', '99',
                      '--es', 'app_update_url', 'https://example.com/offline-ui-fixture.apk',
                      '--es', 'app_update_changelog', 'Refined controls\nClearer update messages\nSmoother startup',
                      '--ez', 'qa_update_preview', 'true', '--es', 'qa_update_state', state))
                capture('apk-update-' + state, ('appUpdatePrimary',))
            for state in ('preparing', 'launching', 'error'):
                home('--es', 'qa_action', state)
                capture('home-' + state, ('primaryAction',))
            home('--es', 'qa_expiry', 'past')
            capture('home-expired', ('primaryAction',))
            home('--es', 'qa_action', 'storage_launch')
            # On a pre-30 phone the runtime Files prompt is a system-owned UI;
            # preview the explanation on 33 by temporarily revoking All files.
            if args.api >= 30:
                shell('am', 'force-stop', pkg)
                shell('appops', 'set', pkg, 'MANAGE_EXTERNAL_STORAGE', 'deny')
                home('--es', 'qa_action', 'storage_launch')
                capture('permission', ('permExplainTitle', 'permExplainContinue'))
                shell('am', 'force-stop', pkg)
                shell('appops', 'set', pkg, 'MANAGE_EXTERNAL_STORAGE', 'allow')

            profile(720, 1600, 320)
            login()
            capture('login-360', ('userkey', 'login'))
            home()
            capture('home-360', ('primaryAction',))
            profile(720, 1600, 320, '2.0')
            login()
            capture('login-font200-top', ('loginTitle',))
            swipe_up()
            capture('login-font200-bottom')
            home()
            capture('home-font200', ('primaryAction',))
            settings()
            capture('settings-font200', ('settingsBack',))
            start('LogAct', ('--es', 'qa_gate', 'required'))
            capture('library-font200', ('gateTitle',))
            for _ in range(2): swipe_up()
            capture('library-font200-actions')
            profile(1600, 2560, 320)
            login()
            capture('login-tablet', ('userkey', 'login'))
            home()
            capture('home-tablet', ('primaryAction',))
            profile()
            for name in ('window_animation_scale', 'transition_animation_scale', 'animator_duration_scale'):
                shell('settings', 'put', 'global', name, '1')
            for frame in ('0.0', '0.3', '0.7', '1.1'):
                start('SplashActivity', ('--ef', 'qa_frame', frame), wait=.6)
                capture('motion-frame-' + frame.replace('.', ''), hierarchy=False)
            for name in ('window_animation_scale', 'transition_animation_scale', 'animator_duration_scale'):
                shell('settings', 'put', 'global', name, '0')
            # High contrast is enforced even when the appearance preference is on.
            shell('settings', 'put', 'secure', 'high_text_contrast_enabled', '1')
            home()
            capture('home-high-contrast', ('primaryAction',))
            shell('settings', 'put', 'secure', 'high_text_contrast_enabled', '0')

        # Check real minified release inflation via its only exported entry.
        brand, pkg = 'anoy', 'com.xlox'
        install(args.apks / 'Anoy-Liquid.apk', folder)
        start('SplashActivity', wait=1.5)
        capture('minified-release-entry', hierarchy=False)
        shell('am', 'force-stop', pkg)

    log = adb('logcat', '-d', '-v', 'threadtime')
    (args.out / 'logcat.txt').write_text(log)
    crashes = []
    lines = log.splitlines()
    for i, line in enumerate(lines):
        if 'FATAL EXCEPTION' in line and any(app in '\n'.join(lines[i:i + 5]) for app in ('com.xlox', 'com.kennypvt')):
            crashes.append('\n'.join(lines[i:i + 20]))
    check(not crashes, 'No app-process fatal exceptions during rendered UI walkthrough')
    check(len(evidence['screens']) >= 50, 'Complete native screen/state/accessibility capture set')
finally:
    # Keep the failing rendered state too; assertions must not discard it.
    try:
        (args.out / 'last-screen.png').write_bytes(adb('exec-out', 'screencap', '-p', binary=True))
    except (RuntimeError, subprocess.TimeoutExpired):
        pass
    (args.out / 'results.json').write_text(json.dumps(evidence, indent=2) + '\n')
    (args.out / 'last-logcat.txt').write_text(adb('logcat', '-d', '-v', 'tag', check=False))
    shell('wm', 'size', 'reset', check=False)
    shell('wm', 'density', 'reset', check=False)
    shell('settings', 'put', 'system', 'font_scale', '1.0', check=False)
    shell('settings', 'put', 'secure', 'high_text_contrast_enabled', '0', check=False)
