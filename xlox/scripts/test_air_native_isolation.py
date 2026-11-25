#!/usr/bin/env python3
"""Build-only Air isolation guards. No account, SDK activation or real game.

The JVM fixture compiles the ACTUAL BoxApplication + GameTarget twice, with the
compile-time flag false and true. A deliberately absent .so makes attempted
System.load calls observable as handled linker errors; no native library runs.
These tests establish only the scope of the diagnostic, NOT game compatibility.

--static-only is for workspaces without a JDK. CI must run the default full suite.
--expected-isolation true|false APK... also checks generated release BuildConfig
and the mode-specific marker in each compiled APK's DEX, after R8.
"""
from __future__ import annotations

import argparse
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
APP = ROOT / 'app'
JAVA = APP / 'src/main/java/com/xlox'
WORKFLOW = ROOT.parent / '.github/workflows/build-xlox.yml'
MARKER = 'AIR-NATIVE-ISOLATION: libbgmilite.so injection skipped'
NATIVE_MARKERS = (b'Air native crash report v2', b'NATIVE_LOOKUP_V2', b'NATIVE_CAPTURE_V2')
A = '{http://schemas.android.com/apk/res/android}'


def workflow_step(name: str) -> str:
    text = WORKFLOW.read_text()
    body = text.split('      - name: ' + name + '\n', 1)[1]
    return re.split(r'\n      - (?:name:|uses:)', body, maxsplit=1)[0]


def verify_apk_marker(path: Path, diagnostic: bool) -> None:
    with ZipFile(path) as apk:
        dex = [apk.read(name) for name in apk.namelist()
               if re.fullmatch(r'classes(?:[2-9][0-9]*|1[0-9]+)?\.dex', name)]
    assert dex, 'No compiled DEX in ' + path.name
    present = any(MARKER.encode() in data for data in dex)
    assert present == diagnostic, path.name + ': wrong compiled Air isolation mode'
    for marker in NATIVE_MARKERS:
        assert any(marker in data for data in dex) == diagnostic, path.name + ': wrong compiled native reporter mode'
    if diagnostic:
        assert any(b'Air crash report v1' in data for data in dex), path.name + ': exit reporter missing'


def verify_generated_release_flags(diagnostic: bool) -> None:
    root = APP / 'build/generated/source/buildConfig'
    for brand, package in (('anoy', 'com.xlox'), ('kenny', 'com.kennypvt')):
        files = list(root.glob(brand + '/release/**/BuildConfig.java'))
        assert len(files) == 1, (brand, 'Missing/ambiguous generated release config', files)
        text = files[0].read_text()
        value = re.search(r'public static final boolean AIR_NATIVE_ISOLATION = (true|false);', text)
        assert value and (value[1] == 'true') == diagnostic, (brand, 'Wrong generated flag')
        assert 'APPLICATION_ID = "' + package + '";' in text, brand + ': identity changed'
        assert 'VERSION_CODE = 21;' in text and 'VERSION_NAME = "2.4.3";' in text, brand + ': version changed'
        print('PASS generated release mode:', brand, 'diagnostic' if diagnostic else 'normal')


class IsolationWiringTest(unittest.TestCase):
    def test_gradle_flag_is_strict_compile_time_and_off_by_default(self):
        text = (APP / 'build.gradle').read_text()
        self.assertIn("providers.gradleProperty('airNativeIsolation').getOrElse('false')", text)
        self.assertIn("if (!(airNativeIsolation in ['true', 'false']))", text)
        self.assertIn("buildConfigField 'boolean', 'AIR_NATIVE_ISOLATION', airNativeIsolation", text)
        self.assertIn('versionCode 21', text)
        self.assertIn("versionName '2.4.3'", text)

    def test_skip_is_only_air_and_does_not_claim_success(self):
        text = (JAVA / 'BoxApplication.java').read_text()
        guard = 'if (BuildConfig.AIR_NATIVE_ISOLATION && game == GameTarget.BGMI_AIR) {'
        self.assertEqual(text.count(guard), 1)
        skip = text.split(guard, 1)[1].split('}', 1)[0].strip()
        self.assertEqual(skip, 'AirCrashDiagnostics.recordGuest(ctx);\n                Log.w(LOAD_TAG, "' + MARKER + '");\n                return;')
        self.assertLess(text.index('LibManager.makeReadOnly(lib)'), text.index(guard))
        self.assertLess(text.index(guard), text.index('System.load(lib.getAbsolutePath())'))
        self.assertLess(text.index('System.load(lib.getAbsolutePath())'), text.index('LibManager.recordLoadSuccess'))
        self.assertNotIn('getBooleanExtra(', text)
        self.assertNotIn('Thread.sleep(', text)

    def test_sdk_activation_and_bgmi_web_routing_remain_present(self):
        text = (JAVA / 'BoxApplication.java').read_text()
        for statement in ('System.loadLibrary("XloX")', 'MetaCore.setProcessLifecycleCallback(this)',
                          'MetaActivationManager.activateSdk(getSdkKey())',
                          'virtualGameProcess = game == GameTarget.BGMI;',
                          'if (virtualGameProcess) installGameWebRouting(false);',
                          'if (virtualGameProcess) installGameWebRouting(true);'):
            self.assertIn(statement, text)

    def test_notice_is_diagnostic_only_and_independent_of_errors(self):
        layout = ET.parse(APP / 'src/main/res/layout/page_home.xml').getroot()
        notice = next(e for e in layout.iter() if e.get(A + 'id') == '@+id/airDiagnosticNotice')
        self.assertEqual(notice.get(A + 'visibility'), 'gone')
        self.assertEqual(notice.get(A + 'text'), '@string/air_native_isolation_notice')
        home = (JAVA / 'MAct.java').read_text()
        self.assertIn('findViewById(R.id.airDiagnosticNotice).setVisibility(\n'
                      '                BuildConfig.AIR_NATIVE_ISOLATION ? View.VISIBLE : View.GONE);', home)
        self.assertIn('BuildConfig.AIR_NATIVE_ISOLATION ? R.string.settings_version_value_air_diagnostic', home)
        strings = {e.get('name'): ''.join(e.itertext())
                   for e in ET.parse(APP / 'src/main/res/values/strings.xml').getroot().findall('string')}
        self.assertIn('not a fix', strings['air_native_isolation_notice'])

    def test_report_is_private_scoped_and_root_is_only_explicit(self):
        adapter = (JAVA / 'utils/AirCrashDiagnostics.java').read_text()
        dialog = (JAVA / 'ui/AirCrashReportDialog.java').read_text()
        home = (JAVA / 'MAct.java').read_text()
        self.assertIn('if (!BuildConfig.AIR_NATIVE_ISOLATION) return', adapter)
        self.assertIn('getHistoricalProcessExitReasons(host, 0, 32)', adapter)
        self.assertIn('if (includeRoot)', adapter)
        self.assertEqual(adapter.count('new ProcessBuilder('), 1)
        self.assertIn('new ProcessBuilder("su", "-c", "/system/bin/dumpsys activity exit-info " + host)', adapter)
        self.assertIn('if (!AirExitReport.ownPackage(host))', adapter)
        self.assertIn('reader.get(20, TimeUnit.SECONDS)', adapter)
        self.assertIn('process.destroyForcibly()', adapter)
        startup = adapter.split('public static String collect', 1)[0]
        self.assertNotIn('ProcessBuilder', startup)
        self.assertNotIn('getHistoricalProcessExitReasons', startup)
        self.assertIn('read(false);', dialog)
        self.assertIn('.setPositiveButton(R.string.air_crash_root_allow, (d, which) -> read(true))', dialog)
        self.assertIn('task.cancel(true)', dialog)
        self.assertIn('worker.shutdownNow()', dialog)
        self.assertIn('airCrashReportDialog.dispose()', home)
        self.assertNotIn('getDescription()', adapter)  # Never export ApplicationExitInfo descriptions.
        for forbidden in ('supportInfo(', 'licenceKey(', 'getAndroidId(', 'http://', 'https://'):
            self.assertNotIn(forbidden, adapter + dialog)
        self.assertIn('BuildConfig.AIR_NATIVE_ISOLATION && !preview && launchTarget == air', home)
        layout = ET.parse(APP / 'src/main/res/layout/page_home.xml').getroot()
        button = next(e for e in layout.iter() if e.get(A + 'id') == '@+id/airCrashReportOpen')
        self.assertEqual(button.get(A + 'visibility'), 'gone')

    def test_native_report_reads_only_allowlisted_fields_and_bounded_streams(self):
        report = (JAVA / 'utils/AirExitReport.java').read_text()
        self.assertIn('MAX_REPORT = 32 * 1024', report)
        self.assertIn('MAX_TRACE = 8 * 1024 * 1024', report)
        self.assertIn('MAX_ROOT = 128 * 1024', report)
        self.assertIn('frames.size() >= 32', report)
        self.assertIn('pid != expectedPid || uid != expectedUid', report)
        self.assertNotIn('getSharedPreferences', report)
        self.assertNotIn('Runtime.getRuntime()', report)
        self.assertNotIn('new FileOutputStream', report)

    def test_native_root_is_a_distinct_fresh_consent_not_open_copy_or_exit_history(self):
        adapter = (JAVA / 'utils/AirCrashDiagnostics.java').read_text()
        dialog = (JAVA / 'ui/AirCrashReportDialog.java').read_text()
        native = (JAVA / 'utils/RootNativeCrashReader.java').read_text()
        old_collect = adapter.split('public static String collect(Context', 1)[1].split('public static String collectNative', 1)[0]
        self.assertNotIn('RootNativeCrashReader', old_collect)
        self.assertNotIn('NativeTraceProbe', old_collect)
        self.assertIn('public static String collectNative(Context context)', adapter)
        self.assertIn('if (guests.isEmpty()) return', adapter)
        self.assertIn('RootNativeCrashReader.read(host, android.os.Process.myUid(), session, guests, exits, now)', adapter)
        self.assertIn('.setPositiveButton(R.string.air_crash_native_allow, (d, which) -> readNative())', dialog)
        self.assertIn('private void read(boolean includeRoot) { startRead(includeRoot, false); }', dialog)
        self.assertIn('private void readNative() { startRead(false, true); }', dialog)
        self.assertIn('includeNative ? reader.nativeTrace(app) : reader.exits(app, includeRoot)', dialog)
        copy = dialog.split('copy.setOnClickListener', 1)[1].split('root.setOnClickListener', 1)[0]
        self.assertNotIn('nativeTrace', copy)
        self.assertNotIn('startRead', copy)
        self.assertEqual(native.count('new ProcessBuilder('), 1)
        self.assertIn('!BuildConfig.AIR_NATIVE_ISOLATION || !AirExitReport.ownPackage(host)', native)
        self.assertIn('System.nanoTime() + TimeUnit.SECONDS.toNanos(20)', native)
        self.assertIn('reader.get(remaining, TimeUnit.NANOSECONDS)', native)
        self.assertIn('Thread.currentThread().isInterrupted()', native)
        self.assertIn('process.destroyForcibly()', native)
        self.assertIn('reader.cancel(true)', native)
        for source in JAVA.rglob('*.java'):
            if source.name not in ('AirCrashDiagnostics.java', 'AirCrashReportDialog.java'):
                self.assertNotIn('collectNative(', source.read_text(), str(source))
        sheet = ET.parse(APP / 'src/main/res/layout/sheet_air_crash_report.xml').getroot()
        buttons = {e.get(A + 'id'): e for e in sheet.iter() if e.tag.endswith('MaterialButton')}
        for id in ('airCrashReportRoot', 'airCrashReportNative', 'airCrashReportCopy'):
            self.assertEqual(buttons['@+id/' + id].get(A + 'enabled'), 'false')
        strings = {e.get('name'): ''.join(e.itertext())
                   for e in ET.parse(APP / 'src/main/res/values/strings.xml').getroot().findall('string')}
        self.assertNotEqual(strings['air_crash_root_message'], strings['air_crash_native_message'])
        self.assertIn('Separate from exit history', strings['air_crash_native_message'])
        self.assertIn('may not exist', strings['air_crash_native_message'])

    def test_native_commands_are_fixed_bounded_read_only_and_fail_closed(self):
        probe = (JAVA / 'utils/NativeTraceProbe.java').read_text()
        native = (JAVA / 'utils/RootNativeCrashReader.java').read_text()
        self.assertIn('/data/tombstones/tombstone_[0-9][0-9]', probe)
        self.assertIn('/system/bin/head -c 4096', probe)
        self.assertIn('/system/bin/head -c 65536', probe)
        self.assertIn('[ ! -L', probe)
        self.assertIn('MAX_EXIT_LAG_MS = 30_000L', probe)
        self.assertIn('MAX_TIMESTAMP_AHEAD_MS = 2000L', probe)
        self.assertIn('Math.min(32, exits.size())', probe)
        self.assertIn('guests.isEmpty()', probe)
        self.assertIn('!nativeLike(latest)', probe)
        self.assertEqual(probe.count('AirExitReport.nativeIdentityMatches('), 2)
        self.assertIn('!Arrays.equals(before, after)', probe)
        self.assertIn('String facts = renderCapture(raw, h);', probe)
        self.assertNotRegex(probe + native, r'getSharedPreferences|FileOutputStream|Log\.[deiw]\(|HttpURLConnection|setenforce|logcat|ptrace|sigaction')
        self.assertNotIn('new ProcessBuilder', probe)
        self.assertEqual((probe + native).count('new ProcessBuilder('), 1)
        self.assertNotIn('getMessage()', probe + native)
        self.assertTrue(all(ord(c) >= 32 or ord(c) in (9, 10, 13) for c in probe + native))

    def test_actual_workflow_selector_rejects_publishing_or_unmarked_diagnostics(self):
        body = workflow_step('Select normal or strictly build-only Air diagnostic')
        script = body.split('        run: |\n', 1)[1]
        script = '\n'.join(line[10:] if line.startswith('          ') else line
                           for line in script.splitlines())
        for event in ('push', 'workflow_dispatch'):
            for diagnostic in (False, True):
                for verify in (False, True):
                    for publish in (False, True):
                        with self.subTest(event=event, diagnostic=diagnostic, verify=verify, publish=publish):
                            with tempfile.TemporaryDirectory(prefix='air-workflow-') as tmp:
                                env_file, summary = Path(tmp) / 'env', Path(tmp) / 'summary'
                                env = dict(os.environ, GITHUB_EVENT_NAME=event, GITHUB_ENV=str(env_file),
                                           GITHUB_STEP_SUMMARY=str(summary),
                                           AIR_NATIVE_ISOLATION_REQUESTED=str(diagnostic).lower(),
                                           VERIFY_ONLY_PUSH=str(verify).lower(),
                                           PUBLISH_REQUESTED=str(publish).lower())
                                result = subprocess.run(['bash', '-c', script], env=env,
                                                        capture_output=True, text=True, timeout=10)
                                allowed = not diagnostic or (not publish and (event != 'push' or verify))
                                self.assertEqual(result.returncode == 0, allowed, result.stdout + result.stderr)
                                if allowed:
                                    self.assertEqual(env_file.read_text().strip(),
                                                     'AIR_NATIVE_ISOLATION=' + str(diagnostic).lower())
                                else:
                                    self.assertFalse(env_file.exists())

    def test_publication_has_both_expression_and_shell_guard(self):
        step = workflow_step('Publish both APKs in ONE GitHub release (no pruning)')
        condition = next(line for line in step.splitlines() if line.strip().startswith('if:'))
        self.assertIn("if: env.AIR_NATIVE_ISOLATION != 'true' &&", condition)
        self.assertIn("!contains(github.event.head_commit.message, '[verify-2.4.3]')", condition)
        shell_guard = '[ "$AIR_NATIVE_ISOLATION" = false ] ||'
        self.assertIn(shell_guard, step)
        self.assertLess(step.index(shell_guard), step.index('gh release '))

    def test_all_gradle_invocations_and_artifacts_have_unambiguous_mode(self):
        workflow = WORKFLOW.read_text()
        invocations = [line for line in workflow.splitlines() if './gradlew ' in line]
        self.assertEqual(len(invocations), 3)
        self.assertTrue(all('"-PairNativeIsolation=$AIR_NATIVE_ISOLATION"' in line for line in invocations))
        self.assertIn('python3 -B scripts/test_air_native_isolation.py\n', workflow)
        self.assertIn('--expected-isolation "$AIR_NATIVE_ISOLATION"', workflow)
        for brand in ('Anoy', 'KennyPvt'):
            self.assertIn('dist/air-native-isolation/' + brand + '-Air-diagnostic.apk', workflow)
        self.assertIn("&& '-Air-diagnostic' || ''", workflow)

    def test_dex_marker_parser_refuses_wrong_mode_missing_dex_or_asset_only_marker(self):
        with tempfile.TemporaryDirectory(prefix='air-dex-marker-') as tmp:
            path = Path(tmp) / 'fixture.apk'
            compiled = b'dex\n035\x00' + MARKER.encode() + b'Air crash report v1' + b''.join(NATIVE_MARKERS)
            for name, data, diagnostic in (('classes.dex', compiled, True),
                                            ('classes2.dex', compiled, True),
                                            ('classes10.dex', b'dex\n035\x00', False)):
                with ZipFile(path, 'w') as apk:
                    apk.writestr(name, data)
                verify_apk_marker(path, diagnostic)
                with self.assertRaises(AssertionError):
                    verify_apk_marker(path, not diagnostic)
            with ZipFile(path, 'w') as apk:
                apk.writestr('assets/readme.txt', MARKER)
            with self.assertRaises(AssertionError):
                verify_apk_marker(path, True)
            with ZipFile(path, 'w') as apk:
                apk.writestr('classes.dex', b'dex\n035\x00')
                apk.writestr('assets/readme.txt', MARKER)
            with self.assertRaises(AssertionError):
                verify_apk_marker(path, True)


# JVM-only adapters. These files never enter the Android application source set.
STUBS = {
    'android/content/Context.java': 'package android.content; public class Context {}',
    'android/app/Application.java': '''package android.app;
public class Application extends android.content.Context {
 protected void attachBaseContext(android.content.Context base) {} public void onCreate() {}
}''',
    'android/util/Log.java': '''package android.util;
import java.util.*;
public final class Log {
 public static final List<String> messages = new ArrayList<>();
 public static boolean has(String part) { for (String m : messages) if(m.contains(part)) return true; return false; }
 public static int i(String t,String m){messages.add(m);return 0;}
 public static int d(String t,String m){messages.add(m);return 0;}
 public static int w(String t,String m){messages.add(m);return 0;}
 public static int e(String t,String m){messages.add(m);return 0;}
 public static int e(String t,String m,Throwable x){messages.add(m);return 0;}
}''',
    'net_62v/external/IMundoProcessCallback.java': '''package net_62v.external;
public interface IMundoProcessCallback { void onCreate(String a,String b); void onInitialized(); }''',
    'net_62v/external/MetaCore.java': '''package net_62v.external;
public class MetaCore { public static void setProcessLifecycleCallback(IMundoProcessCallback c) {} }''',
    'net_62v/external/MetaActivationManager.java': '''package net_62v.external;
public class MetaActivationManager { public static void activateSdk(String key) {} }''',
    'com/xlox/utils/AirCrashDiagnostics.java': '''package com.xlox.utils;
public class AirCrashDiagnostics { public static int guests;
 public static void recordGuest(android.content.Context c){guests++;} }''',
    'com/xlox/utils/GameWebRouting.java': '''package com.xlox.utils;
public class GameWebRouting {
 public static int installs,refreshes;
 public static void install(){installs++;} public static void refreshAfterInitialization(){refreshes++;}
}''',
    'com/xlox/utils/LibManager.java': '''package com.xlox.utils;
import android.content.Context; import java.io.File;
public class LibManager {
 public static int errors,successes,validations,readOnly,files; public static boolean valid=true;
 public static String lastError;
 public static String diagnostics(Context c){return "fixture";}
 public static String diagnostics(Context c,GameTarget g){return "fixture";}
 public static File loaderDir(Context c){return new File(System.getProperty("air.test.missingLibraryDir"));}
 public static File libFile(Context c,GameTarget g){files++;return new File(loaderDir(c),g.libraryName);}
 public static boolean isElf(File f){validations++;return valid;}
 public static File findLib(File f,GameTarget g){return null;}
 public static void installLib(Context c,File f,GameTarget g){throw new AssertionError("Unexpected replacement");}
 public static String alignmentProblem(File f){return null;}
 public static boolean makeReadOnly(File f){readOnly++;return true;}
 public static void recordLoadError(Context c,GameTarget g,String m){errors++;lastError=m;}
 public static void recordLoadSuccess(Context c,GameTarget g,String p){successes++;}
}''',
}


def run_callback_harness() -> None:
    java, javac = os.environ.get('JAVA') or shutil.which('java'), shutil.which('javac')
    ecj = os.environ.get('ECJ_JAR')
    if not java or not (javac or ecj):
        raise RuntimeError('A JDK is required (or JAVA + ECJ_JAR); --static-only does not run the callback fixture')
    with tempfile.TemporaryDirectory(prefix='air-callback-') as tmp:
        base = Path(tmp)
        sources = []
        for relative, text in STUBS.items():
            path = base / 'src' / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text)
            sources.append(path)
        sources += [JAVA / 'BoxApplication.java', JAVA / 'utils/GameTarget.java',
                    JAVA / 'utils/AirExitReport.java', JAVA / 'utils/AirCrashSession.java',
                    JAVA / 'utils/NativeTraceProbe.java',
                    ROOT / 'tests/runtime/AirNativeIsolationTest.java',
                    ROOT / 'tests/runtime/AirCrashReportTest.java',
                    ROOT / 'tests/runtime/AirNativeRecordTest.java']
        config = base / 'src/com/xlox/BuildConfig.java'
        for diagnostic in (False, True):
            value = str(diagnostic).lower()
            config.write_text('package com.xlox; public final class BuildConfig { '
                              'public static final boolean AIR_NATIVE_ISOLATION = ' + value + '; }')
            output = base / value
            output.mkdir()
            compiler = [javac, '-encoding', 'UTF-8', '-source', '8', '-target', '8'] if javac else [java, '-jar', ecj, '-8', '-encoding', 'UTF-8', '-warn:none']
            subprocess.run([*compiler, '-d', str(output), *map(str, sources), str(config)],
                           check=True, timeout=60)
            subprocess.run([java, '-ea', '-Dair.test.missingLibraryDir=' + str(base / 'absent'),
                            '-cp', str(output), 'com.xlox.tests.AirNativeIsolationTest'],
                           check=True, timeout=30)
        subprocess.run([java, '-ea', '-cp', str(output), 'com.xlox.tests.AirCrashReportTest'],
                       check=True, timeout=30)
        subprocess.run([java, '-ea', '-cp', str(output), 'com.xlox.tests.AirNativeRecordTest'],
                       check=True, timeout=60)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--static-only', action='store_true')
    parser.add_argument('--expected-isolation', choices=('true', 'false'))
    parser.add_argument('apks', nargs='*', type=Path)
    args = parser.parse_args()
    if bool(args.apks) != bool(args.expected_isolation):
        parser.error('--expected-isolation and APK paths must be provided together')
    if args.static_only and args.apks:
        parser.error('--static-only cannot be combined with compiled APK verification')
    suite = unittest.defaultTestLoader.loadTestsFromTestCase(IsolationWiringTest)
    if not unittest.TextTestRunner(verbosity=2).run(suite).wasSuccessful():
        raise SystemExit(1)
    if args.apks:
        diagnostic = args.expected_isolation == 'true'
        verify_generated_release_flags(diagnostic)
        for path in args.apks:
            verify_apk_marker(path, diagnostic)
            print('PASS compiled DEX mode:', path.name, 'diagnostic' if diagnostic else 'normal')
    elif not args.static_only:
        run_callback_harness()


if __name__ == '__main__':
    main()
