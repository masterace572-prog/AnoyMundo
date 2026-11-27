#!/usr/bin/env python3
"""Run credential-free JVM regressions and Android wiring checks.

CI uses its JDK's javac. For a local JRE-only environment, JAVA and ECJ_JAR may
point at an Eclipse Java compiler; no tools/dependencies are downloaded here.
Optional APK arguments additionally check the actual merged callback manifest.
"""
from __future__ import annotations

import hashlib
from io import BytesIO
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

    def test_outbound_web_routing_is_installed_only_for_the_virtual_game(self):
        app = (JAVA / 'BoxApplication.java').read_text()
        android_create = app.split('public void onCreate() {', 1)[1].split('public void onCreate(String', 1)[0]
        self.assertNotIn('GameWebRouting.install', android_create)
        guest_create = app.split('public void onCreate(String', 1)[1].split('public void onInitialized()', 1)[0]
        self.assertIn('virtualGameProcess = game == GameTarget.BGMI;', guest_create)
        self.assertIn('if (virtualGameProcess) installGameWebRouting(false);', guest_create)
        self.assertIn('GameTarget.forProcess(processName, processName2)', guest_create)
        self.assertIn('if (virtualGameProcess) installGameWebRouting(true);', app)
        routing = (JAVA / 'utils/GameWebRouting.java').read_text()
        self.assertIn('MetaCore.addServiceInterpreter(service, method, handler)', routing)
        self.assertIn('BuildConfig.APPLICATION_ID', routing)
        self.assertIn('Intent.ACTION_CHOOSER', routing)
        self.assertIn('GameWebPolicy.contain', routing)
        self.assertIn('MetaPackageManager.resolveActivityInfo(component)', routing)
        self.assertIn('inGame.setComponent(new ComponentName(info.packageName, info.name))', routing)
        self.assertNotIn('setAccessible', routing)

    def test_original_sdk_supports_the_exact_outbound_hook_api_and_method_hashes(self):
        with ZipFile(ROOT / 'app/libs/mundo-release.aar') as aar:
            with ZipFile(BytesIO(aar.read('classes.jar'))) as jar:
                core = jar.read('net_62v/external/MetaCore.class')
                self.assertIn(b'addServiceInterpreter', core)
                self.assertIn(b'(Ljava/lang/String;Ljava/lang/String;Ljava/lang/reflect/InvocationHandler;)Ljava/lang/reflect/InvocationHandler;', core)
                start = jar.read('top/bienvenido/date_24323/a4.class')
                for name in ('startActivity', 'startActivityAsUser', 'startActivityAndWait', 'startActivityWithConfig',
                             'startActivityAsCaller', 'startActivityWithFeature', 'startActivityAsUserWithFeature'):
                    value = 0
                    for char in name:
                        value = (31 * value + ord(char)) & 0xffffffff
                    self.assertIn(value.to_bytes(4, 'big'), start, name)

    def test_contained_browser_is_private_and_not_an_os_browser_handler(self):
        app = ET.parse(MANIFEST).getroot().find('application')
        activity = next(e for e in app.findall('activity') if e.get(ANDROID + 'name') == '.GameWebLoginActivity')
        self.assertEqual(activity.get(ANDROID + 'exported'), 'false')
        self.assertEqual(activity.findall('intent-filter'), [])
        self.assertIsNone(activity.get(ANDROID + 'process'))
        self.assertNotEqual(activity.get(ANDROID + 'noHistory'), 'true')
        layout = ET.parse(ROOT / 'app/src/main/res/layout/activity_game_web_login.xml').getroot()
        origin = next(e for e in layout.iter() if e.get(ANDROID + 'id') == '@+id/gameWebOrigin')
        self.assertEqual(origin.get(ANDROID + 'singleLine'), 'true')
        self.assertIsNone(origin.get(ANDROID + 'maxLines'))

    def test_webview_cannot_launch_an_outside_app_or_expose_a_native_js_bridge(self):
        browser = (JAVA / 'GameWebLoginActivity.java').read_text()
        self.assertNotRegex(browser, r'\bstartActivity(?:ForResult)?\s*\(')
        for forbidden in ('addJavascriptInterface(', 'evaluateJavascript(', 'handler.proceed(', 'setUserAgentString(',
                          'URI_ALLOW_UNSAFE', 'FLAG_GRANT_', 'launchApp(', 'killApp'):
            self.assertNotIn(forbidden, browser)
        self.assertIn('settings.setAllowFileAccess(false)', browser)
        self.assertIn('settings.setAllowContentAccess(false)', browser)
        self.assertIn('WebSettings.MIXED_CONTENT_NEVER_ALLOW', browser)
        self.assertIn('handler.cancel();', browser)
        self.assertIn('WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)', browser)
        self.assertIn('MetaActivityManager.launchIntent(scoped, GameLoginRouter.USER)', browser)
        self.assertIn('!target.exported', browser)
        self.assertIn('GameWebNavigator.navigate', browser)
        self.assertIn('getOnBackPressedDispatcher().addCallback', browser)
        self.assertNotIn('public void onBackPressed()', browser)
        self.assertNotIn('setResult(RESULT_OK', browser)
        self.assertIn('if (returned && !isFinishing()) finish();', browser)
        self.assertLess(browser.index('prepareWebView();'), browser.index('setContentView('))
        self.assertIn('WebView.setDataDirectorySuffix("loader_game_web")', browser)
        for line in browser.splitlines():
            if 'Log.' in line:
                self.assertNotRegex(line, r'\b(url|value|candidate|data|parsed|ignored|fallback)\b')

    def test_compiled_browser_parser_refuses_exported_or_globally_registered_webview(self):
        private = '    E: activity (line=8)\n      A: android:name(0x01010003)="com.xlox.GameWebLoginActivity"\n      A: android:exported(0x01010010)=(type 0x12)0x0\n'
        verify_contained_browser(private)
        verify_contained_browser(private.replace('0x0\n', 'false\n'))
        for invalid in ('', private + private, private.replace('0x0\n', '0xffffffff\n'),
                        private + '      E: intent-filter (line=9)\n',
                        private + '      A: android:process(0x01)=":other"\n'):
            with self.assertRaises(AssertionError):
                verify_contained_browser(invalid)

    def test_disposed_gates_cancel_network_and_queued_work(self):
        gate = (JAVA / 'ui/UpdateGate.java').read_text()
        self.assertIn('request.cancel();', gate)
        self.assertIn('task.cancel(true);', gate)
        self.assertIn('gate.task = BACKGROUND.submit(', gate)
        self.assertIn('task = EXECUTOR.submit(', gate)
        self.assertNotIn('Downtwo.check(activity);', gate)
        self.assertNotIn('percent == lastPercent', gate)
        self.assertIn('if (optional) runRedownload();\n            else runCheck();', gate)

    def test_google_login_installers_are_pinned_and_verified(self):
        microg = (JAVA / 'utils/MicroGInstaller.java').read_text()
        services = (JAVA / 'utils/GoogleServicesInstaller.java').read_text()
        # Pinned microG release URLs, sizes and SHA-256 digests (provided by the owner).
        for needle in (
                'https://github.com/microg/GmsCore/releases/download/v0.3.18.252432/com.google.android.gms-252432036.apk',
                'https://github.com/microg/GsfProxy/releases/download/v0.1.0/GsfProxy.apk',
                'https://github.com/microg/GmsCore/releases/download/v0.3.18.252432/com.android.vending-84022636.apk',
                '"108520063"', '"21872"', '"4212484"',
                '1094fa60ace2367ac52bd08d1bd4f33fa8144ba930e355121b28234b8ebbf382',
                '86891b174301f06a1c84187b545a0a2a57044c6b768f3e84e865908743349692',
                '3f1bde2f72bca746fbb53b61a32c2a85fcb026f886ee96d6fe9a05de9a6c6d5b'):
            self.assertIn(needle, microg)
        # Downloads are verified before install; HTTP only by redirect from the pinned https URL.
        self.assertIn('expectedSha256.equalsIgnoreCase(sha256Of(outFile))', microg)
        self.assertIn('outFile.length() != expectedSize', microg)
        self.assertIn('HTTP_OK', microg)
        self.assertNotIn('http://', microg)
        # The stock path only clones/installs apps already on the phone, and a
        # split APK (modern GMS) is staged as base + splits, never base-only.
        self.assertIn('info.applicationInfo.sourceDir', services)
        self.assertIn('MetaApplicationInstaller.cloneApp(pkg)', services)
        self.assertIn('info.applicationInfo.splitSourceDirs', services)
        self.assertIn('MetaApplicationInstaller.installAppsByPath(dir.getAbsolutePath())', services)
        self.assertNotIn('http://', services)
        # BOTH stock paths (fresh install and the microG->stock switch) must
        # register GMS as internal and reload the container registry, or the
        # game's Google button spins and dies with no account picker.
        self.assertEqual(services.count('installOne(ctx, GMS_PKG, true, out)'), 2)
        self.assertIn('MetaPackageManager.fixInternalAppAndReinstall(pkg)', services)
        self.assertIn('reloadRegistry()', services)
        # The container drops the internal flag across launches: a public repair
        # exists and MAct re-asserts it on every start (working-loader pattern).
        self.assertIn('public static void repairInternalInstall(Context ctx)', services)
        mact = (JAVA / 'MAct.java').read_text()
        self.assertIn('GoogleServicesInstaller.repairInternalInstall(app)', mact)
        self.assertIn('MicroGInstaller.repairInternalInstall(app)', mact)
        # Crashes are recorded and offered for copying on the next home open.
        box = (JAVA / 'BoxApplication.java').read_text()
        self.assertIn('CrashLogger.install(this)', box)
        self.assertIn('CrashLogger.read(this)', mact)
        self.assertTrue((JAVA / 'utils' / 'CrashLogger.java').is_file())
        # A native container death leaves no Java trace: the breadcrumb written
        # before a risky action is reported on the next start.
        crash = (JAVA / 'utils' / 'CrashLogger.java').read_text()
        self.assertIn('noteAction', crash)
        self.assertIn('CrashLogger.pendingAction(this)', mact)
        # The Android 16+ broadcast double-finish race is benign and swallowed,
        # including the Play-Services-executor-wrapped variant.
        self.assertIn('Broadcast already finished', crash)
        self.assertIn('t.getCause()', crash)
        # Android 16+ split-APK launch NPE: the config guard is installed at
        # process start and re-armed right before every container launch.
        self.assertTrue((JAVA / 'utils' / 'ConfigGuard.java').is_file())
        self.assertIn('ConfigGuard.install(this)', box)
        self.assertIn('guardContainerLaunch()', mact)
        # Android 16+ PM slice types: the shim re-wraps ParceledListSlice into
        # the PackageInfoList-style subclass the current Android expects.
        self.assertTrue((JAVA / 'utils' / 'PmCompatShim.java').is_file())
        self.assertIn('PmCompatShim.install()', box)
        self.assertIn('PmCompatShim.install()', mact)
        # Android 16+ isolated-split launches: the container never computes the
        # split dependency table, so every virtual-app activity launch NPEs in
        # SplitDependencyLoader.collectConfigSplitIndices. The fix repairs the
        # ApplicationInfo on incoming launches and on PM-returned infos.
        self.assertTrue((JAVA / 'utils' / 'SplitDepsFix.java').is_file())
        self.assertTrue((JAVA / 'utils' / 'SplitDepsTable.java').is_file())
        self.assertEqual(box.count('SplitDepsFix.install()'), 3)
        self.assertIn('SplitDepsFix.install()', mact)
        self.assertIn('SplitDepsFix.repair(info)', (JAVA / 'utils' / 'PmCompatShim.java').read_text())
        # Silent in-container sign-in failures are traced into support info.
        self.assertTrue((JAVA / 'utils' / 'AccountTrace.java').is_file())
        self.assertIn('AccountTrace.install(this)', box)
        self.assertIn('AccountTrace.read(this)', mact)
        # BGMI's Google button rides on Play Games; it is cloned too (optional).
        self.assertEqual(services.count('installOneClone(ctx, PLAY_GAMES_PKG, out)'), 2)
        for src in (microg, services):
            self.assertNotIn('getExtras(', src)
            self.assertNotIn('FLAG_GRANT_', src)

    def test_google_login_setup_is_wired_into_settings(self):
        home = (JAVA / 'MAct.java').read_text()
        self.assertIn('GoogleLoginSetup.resolveStatus', home)
        self.assertIn('R.layout.sheet_google_login', home)
        self.assertIn('R.id.googleSetupRow', home)
        self.assertIn('GoogleServicesInstaller.installFresh(app)', home)
        self.assertIn('MicroGInstaller.installStock(app)', home)
        self.assertIn('MicroGInstaller.repairInternalInstall(app)', home)
        self.assertIn('anyGameRunning()', home)
        self.assertIn('googleSheet.dismiss()', home)
        settings = (ROOT / 'app/src/main/res/layout/page_settings.xml').read_text()
        self.assertIn('@+id/googleSetupRow', settings)
        self.assertIn('@+id/googleStatusValue', settings)
        sheet = (ROOT / 'app/src/main/res/layout/sheet_google_login.xml').read_text()
        for view_id in ('googleEnableSwitch', 'googleSwitchCaption', 'googleAddAccount',
                        'googleProgress', 'googleProgressText', 'googleResultText', 'googleCancel'):
            self.assertIn('@+id/' + view_id, sheet)
        # microG and the in-container Play Store button are gone: the phone's
        # Google apps are the only provider, driven by one latching switch.
        self.assertNotIn('googleUseMicrog', sheet)
        self.assertNotIn('googleOpenProvider', sheet)
        # The account the game picks comes from GMS's own add-account flow
        # (MinuteMaidActivity, verified on a real phone), opened inside Mundo.
        # NB: the SDK's launchApp/obtainSplashLaunchIntent String overloads take
        # a container USER name, not an activity — launchIntent(Intent) is the
        # arbitrary-component entry point and the only correct call here.
        self.assertIn('openGoogleAddAccountInMundo', home)
        self.assertIn('GoogleServicesInstaller.MINUTEMAID_ACTIVITY', home)
        self.assertIn('MetaActivityManager.launchIntent(', home)
        self.assertNotIn('obtainSplashLaunchIntent(GoogleServicesInstaller.GMS_PKG', home)
        self.assertNotIn('MetaActivityManager.launchApp(GoogleServicesInstaller.GMS_PKG', home)
        services = (JAVA / 'utils/GoogleServicesInstaller.java').read_text()
        self.assertIn('com.google.android.gms.auth.uiflows.minutemaid.MinuteMaidActivity', services)
        self.assertIn('public static boolean resolvableInContainer(ComponentName component)', services)
        strings = (ROOT / 'app/src/main/res/values/strings.xml').read_text()
        for name in ('google_setup_row', 'google_status_none', 'google_status_stock',
                     'google_status_microg', 'google_status_repair', 'google_stop_game_first',
                     'google_switch_label', 'google_switch_caption_on', 'google_switch_caption_off',
                     'google_add_account', 'google_add_account_settling', 'google_add_account_failed',
                     'google_add_account_opening'):
            self.assertIn('name="' + name + '"', strings)
        # The switch latches on and is only reset by clearing the app's data.
        self.assertIn('google_login_enabled', home)
        # Switches and CheckBoxes have Anoy-themed widget styles.
        styles = (ROOT / 'app/src/main/res/values/styles.xml').read_text()
        self.assertIn('Widget.Anoy.Switch', styles)
        self.assertIn('Widget.Anoy.CheckBox', styles)

    def test_bgmi_air_launches_without_game_files(self):
        target = (JAVA / 'utils/GameTarget.java').read_text()
        mact = (JAVA / 'MAct.java').read_text()
        # Air carries no OBB: the launch flow skips the copy and the inspection.
        self.assertIn('BGMI_AIR("com.krafton.bgmilite", "libbgmilite.so", "BGMI Air", false)', target)
        self.assertIn('public final boolean needsObb', target)
        self.assertIn('if (!launchTarget.target.needsObb)', mact)
        self.assertIn('if (!game.target.needsObb)', mact)
        # Air still needs the game inside the container, installed through the
        # staged base+splits path (the SDK's implicit first-launch install is
        # where Air died natively on Android 17).
        self.assertIn('ensureGameInContainerThenLaunch()', mact)
        self.assertIn('MetaPackageManager.isInnerAppInstalled(launching.target.packageName)', mact)
        self.assertIn('GoogleServicesInstaller.installFromPhone(app, launching.target.packageName)', mact)
        services = (JAVA / 'utils/GoogleServicesInstaller.java').read_text()
        self.assertIn('public static boolean installFromPhone(Context ctx, String pkg)', services)
        # cloneApp goes first (the SDK's native clone carries the split set);
        # the staged fallback drops foreign-ABI/density config splits.
        self.assertIn('ok = installOneClone(ctx, pkg, out);', services)
        self.assertIn('filterSplits(ctx, splits)', services)
        self.assertIn('Build.SUPPORTED_ABIS', services)
        # A native death during install or launch is attributed by a breadcrumb.
        self.assertIn('"container install: "', mact)
        self.assertIn('"launch: "', mact)

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


def compiled_components(text: str) -> list:
    components = []
    for match in re.finditer(r'(?m)^([ \t]*)E: (activity-alias|activity)\b[^\n]*\n', text):
        indent = len(match[1])
        lines = []
        for line in text[match.end():].splitlines():
            if line.strip() and len(line) - len(line.lstrip()) <= indent:
                break
            lines.append(line)
        components.append((match[2], '\n'.join(lines)))
    return components


def verify_compiled_manifest(text: str) -> None:
    components = compiled_components(text)
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


def verify_contained_browser(text: str) -> None:
    browsers = [body for kind, body in compiled_components(text)
                if kind == 'activity' and '"com.xlox.GameWebLoginActivity"' in body]
    assert len(browsers) == 1, 'Missing/duplicate contained web login'
    browser = browsers[0]
    assert re.search(r'android:exported[^\n]*(?:\b0x0\b|false)', browser), 'Web login must not be exported'
    assert 'E: intent-filter' not in browser, 'Not a global browser/deep-link handler'
    assert 'android:process' not in browser, 'Use the initialized host core and a separate WebView profile'


def check_apk(path: Path) -> None:
    sdk = os.environ.get('ANDROID_SDK_ROOT') or os.environ.get('ANDROID_HOME')
    tools = sorted(Path(sdk).glob('build-tools/*/aapt2')) if sdk else []
    if not tools:
        raise RuntimeError('aapt2 is required for compiled callback checks')
    text = subprocess.check_output([str(tools[-1]), 'dump', 'xmltree', '--file',
                                    'AndroidManifest.xml', str(path)], text=True)
    verify_compiled_manifest(text)
    verify_contained_browser(text)
    print('PASS compiled private WebView and legacy callback manifest:', path.name)


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
    sources = [JAVA / 'utils' / name for name in ('GameLoginRouter.java', 'UpdateRequest.java', 'LibraryHttp.java', 'GameWebPolicy.java', 'GameWebCallRouter.java', 'GameWebNavigator.java', 'GoogleLoginSetup.java', 'SplitDepsTable.java')]
    sources.extend(ROOT / 'tests/runtime' / name for name in ('RuntimeFixesTest.java', 'GameWebFlowTest.java', 'GoogleLoginSetupTest.java', 'SplitDepsTableTest.java'))
    with tempfile.TemporaryDirectory(prefix='loader-runtime-test-') as output:
        compiler = [javac, '-source', '8', '-target', '8'] if javac else [java, '-jar', ecj, '-8', '-warn:none']
        subprocess.run([*compiler, '-d', output, *map(str, sources)], check=True)
        for test in ('RuntimeFixesTest', 'GameWebFlowTest', 'GoogleLoginSetupTest', 'SplitDepsTableTest'):
            subprocess.run([java, '-ea', '-cp', output, 'com.xlox.tests.' + test], check=True, timeout=30)
    # SDK-free structural checks (host Android UI behavior runs separately in Gradle).
    subprocess.run([sys.executable, '-B', str(ROOT / 'scripts/test_navigation.py')], check=True)
    # Package/library isolation, warning countdown and restart-target regressions.
    subprocess.run([sys.executable, '-B', str(ROOT / 'scripts/test_dual_game.py')], check=True)


if __name__ == '__main__':
    main()
