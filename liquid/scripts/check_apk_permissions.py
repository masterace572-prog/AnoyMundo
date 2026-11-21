#!/usr/bin/env python3
"""Permissions in the built APK(s) - moved here from android-ui-qa.yml in 2.3.2
so every push checks them in the fast build.

    python3 scripts/check_apk_permissions.py app/build/outputs/apk/release/app-release.apk [more.apk ...]

Run from xlox/. Needs aapt2 (newest build-tools under $ANDROID_SDK_ROOT).
Prints ::error:: lines (GitHub annotations) and exits 1 on any problem.
"""
import glob, os, pathlib, re, subprocess, sys

P = 'android.permission.'
requested = ['MANAGE_EXTERNAL_STORAGE', 'READ_EXTERNAL_STORAGE', 'WRITE_EXTERNAL_STORAGE',
             'READ_MEDIA_IMAGES', 'READ_MEDIA_VIDEO', 'READ_MEDIA_AUDIO', 'REQUEST_INSTALL_PACKAGES']
declared = ['RECORD_AUDIO', 'BLUETOOTH', 'BLUETOOTH_CONNECT', 'BLUETOOTH_SCAN', 'NEARBY_WIFI_DEVICES',
            'SYSTEM_ALERT_WINDOW', 'ACCESS_COARSE_LOCATION', 'ACCESS_FINE_LOCATION', 'POST_NOTIFICATIONS',
            'INTERNET', 'ACCESS_NETWORK_STATE', 'WAKE_LOCK', 'FOREGROUND_SERVICE', 'QUERY_ALL_PACKAGES']
removed = ['READ_CALENDAR', 'WRITE_CALENDAR', 'READ_CALL_LOG', 'WRITE_CALL_LOG', 'PROCESS_OUTGOING_CALLS',
           'CAMERA', 'READ_CONTACTS', 'WRITE_CONTACTS', 'GET_ACCOUNTS', 'MANAGE_ACCOUNTS',
           'AUTHENTICATE_ACCOUNTS', 'USE_CREDENTIALS', 'READ_PROFILE', 'WRITE_PROFILE',
           'BODY_SENSORS', 'BODY_SENSORS_BACKGROUND', 'READ_PHONE_STATE', 'READ_PHONE_NUMBERS',
           'CALL_PHONE', 'ANSWER_PHONE_CALLS', 'USE_SIP', 'ACCEPT_HANDOVER', 'ADD_VOICEMAIL',
           'ACTIVITY_RECOGNITION', 'SEND_SMS', 'READ_SMS', 'RECEIVE_SMS', 'RECEIVE_MMS',
           'RECEIVE_WAP_PUSH', 'READ_CELL_BROADCASTS', 'ACCESS_SUPERUSER', 'ACTION_MANAGE_OVERLAY_PERMISSION']


def aapt2():
    sdk = os.environ.get('ANDROID_SDK_ROOT') or os.environ.get('ANDROID_HOME') or ''
    found = sorted(glob.glob(os.path.join(sdk, 'build-tools', '*', 'aapt2')))
    if not found:
        sys.exit('::error::aapt2 not found under $ANDROID_SDK_ROOT/build-tools')
    return found[-1]


def main(apks):
    if not apks:
        sys.exit('usage: check_apk_permissions.py APK [APK ...]')
    tool = aapt2()
    out_dir = pathlib.Path(os.environ.get('PERMISSIONS_LOG_DIR', '/tmp'))
    out_dir.mkdir(parents=True, exist_ok=True)
    errors = []
    for apk in apks:
        v = pathlib.Path(apk).stem
        text = subprocess.run([tool, 'dump', 'permissions', apk], check=True, capture_output=True, text=True).stdout
        xml = subprocess.run([tool, 'dump', 'xmltree', '--file', 'AndroidManifest.xml', apk],
                             check=True, capture_output=True, text=True).stdout
        (out_dir / ('permissions-%s.txt' % v)).write_text(text)
        (out_dir / ('manifest-%s.txt' % v)).write_text(xml)
        have = set(re.findall(r"name='([^']+)'", text))
        print(v, len(have), 'permissions')
        for n in requested + declared:
            if P + n not in have:
                errors.append('%s: %s missing' % (v, n))
        for n in removed:
            if P + n in have:
                errors.append('%s: %s still declared' % (v, n))
        for n in have:
            if 'GOOGLE_AUTH' in n or n.endswith('.READ_STEPS') or n == 'com.google.android.gms.permission.ACTIVITY_RECOGNITION':
                errors.append('%s: %s still declared' % (v, n))
        if 'supportsPictureInPicture' not in xml:
            errors.append('%s: picture-in-picture not declared' % v)
        if 'neverForLocation' not in xml and '0x10000' not in xml:
            print('note: neverForLocation flag not visible in the xmltree dump')
    # Anoy must not ask for the declared-only permissions.
    src = '\n'.join(p.read_text() for p in pathlib.Path('app/src/main/java').rglob('*.java'))
    for bad in ('RECORD_AUDIO', 'ACCESS_FINE_LOCATION', 'ACCESS_COARSE_LOCATION', 'POST_NOTIFICATIONS',
                'BLUETOOTH_CONNECT', 'BLUETOOTH_SCAN', 'NEARBY_WIFI_DEVICES',
                'ACTION_MANAGE_OVERLAY_PERMISSION', 'ACTION_PICTURE_IN_PICTURE_SETTINGS',
                'enterPictureInPictureMode'):
        if bad in src:
            errors.append('Java code references %s (must not be requested now)' % bad)
    for e in errors:
        print('::error::' + e)
    if not errors:
        print('permissions check passed')
    return 1 if errors else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
