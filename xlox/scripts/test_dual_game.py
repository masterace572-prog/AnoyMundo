#!/usr/bin/env python3
"""Credential-free dual-game regressions. No Android SDK, device or services.

Runs production selection, install, reconciliation, restart-marker and warning
classes on a JVM. Minimal Android adapters below are test-only; they do NOT
establish Android inflation, linker, permissions or container compatibility.
Use a JDK, or JAVA + ECJ_JAR as with test_runtime_fixes.py.
"""
from __future__ import annotations

import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / 'app/src/main/java/com/xlox'
RES = ROOT / 'app/src/main/res'
A = '{http://schemas.android.com/apk/res/android}'


class DualGameWiring(unittest.TestCase):
    def test_home_has_distinct_actions_and_the_supplied_air_icon(self):
        layout = ET.parse(RES / 'layout/page_home.xml').getroot()
        ids = {e.get(A + 'id'): e for e in layout.iter() if e.get(A + 'id')}
        self.assertIn('@+id/primaryAction', ids)
        self.assertIn('@+id/airPrimaryAction', ids)
        self.assertEqual(ids['@+id/airPrimaryAction'].get(A + 'enabled'), 'false')
        self.assertEqual(ids['@+id/airGameTile'].get('{http://schemas.android.com/apk/res-auto}srcCompat'), '@drawable/ic_game_bgmi_air')
        self.assertTrue((RES / 'drawable-nodpi/ic_game_bgmi_air.png').is_file())

    def test_copy_launch_stop_store_and_load_are_package_scoped(self):
        home = (JAVA / 'MAct.java').read_text()
        for call in ('copyObbAsync(preparingGame.target.packageName,',
                     'obtainSplashLaunchIntent(launchTarget.target.packageName, this)',
                     'killAppByPkg(launchTarget.target.packageName)',
                     'isAppRunning(game.target.packageName)',
                     'market://details?id=" + launchTarget.target.packageName'):
            self.assertIn(call, home)
        app = (JAVA / 'BoxApplication.java').read_text()
        for call in ('GameTarget.forProcess(processName, processName2)',
                     'LibManager.libFile(ctx, game)', 'LibManager.findLib(loaderDir, game)',
                     'LibManager.installLib(ctx, nested, game)', 'System.load(lib.getAbsolutePath())'):
            self.assertIn(call, app)
        self.assertIn('if (virtualGameProcess) installGameWebRouting(false);', app)
        self.assertIn('virtualGameProcess = game == GameTarget.BGMI;', app)
        self.assertIn('if (game == GameTarget.BGMI && !LibManager.isElf(lib))', app)
        bridge = (JAVA / 'GameLoginCallbackActivity.java').read_text()
        self.assertIn('isAppRunning(GameTarget.BGMI_AIR.packageName, GameLoginRouter.USER)', bridge)
        router = (JAVA / 'utils/GameLoginRouter.java').read_text()
        self.assertIn('if (container.isOtherGameRunning()) return Result.AMBIGUOUS_GAME;', router)

    def test_air_availability_is_not_just_a_version_flag(self):
        home = (JAVA / 'MAct.java').read_text()
        self.assertIn('LibManager.isUsableLib(this, game.target)', home)
        self.assertIn('boolean comingSoon = game == air && !libraryAvailable(game) && !stop;', home)
        self.assertIn('game.action.setEnabled(!blocked && !comingSoon && !off)', home)
        self.assertIn('private final GameSession air = new GameSession(GameTarget.BGMI_AIR)', home)
        self.assertIn('final GameRunState runtime = new GameRunState()', home)
        client = (JAVA / 'utils/Downtwo.java').read_text()
        self.assertIn('LibManager.installRelease(app, staging)', client)
        self.assertIn('expectedSha.equalsIgnoreCase(actualSha)', client)
        self.assertIn('LibManager.saveInstalled(app, version, actualSha)', client)

    def test_consent_is_explicit_and_rechecked_after_copy(self):
        home = (JAVA / 'MAct.java').read_text()
        self.assertEqual(home.count('if (other != null && !concurrentLaunchApproved)'), 2)
        self.assertIn('!confirmation.confirm(SystemClock.elapsedRealtime())', home)
        tick = home.split('concurrentTick = new Runnable()', 1)[1].split('sheet.show();', 1)[0]
        self.assertNotIn('startLaunch();', tick)
        self.assertNotIn('launchGame();', tick)
        self.assertIn('if (concurrentSheet != null) concurrentSheet.dismiss();', home.split('protected void onStop()', 1)[1])
        layout = ET.parse(RES / 'layout/sheet_concurrent_games.xml').getroot()
        self.assertEqual(layout.tag, 'ScrollView')
        confirm = next(e for e in layout.iter() if e.get(A + 'id') == '@+id/concurrentGamesConfirm')
        cancel = next(e for e in layout.iter() if e.get(A + 'id') == '@+id/concurrentGamesCancel')
        self.assertEqual(confirm.get(A + 'enabled'), 'false')
        self.assertNotEqual(cancel.get(A + 'enabled'), 'false')

    def test_permission_recreation_and_restart_keep_the_selected_game(self):
        home = (JAVA / 'MAct.java').read_text()
        self.assertIn('outState.putString(STATE_LAUNCH_GAME, launchTarget.target.packageName)', home)
        self.assertIn('savedInstanceState.getString(STATE_LAUNCH_GAME)', home)
        self.assertIn('StorageAccess.markLaunchAfterRestart(this, launchTarget.target)', home)
        self.assertIn('StorageAccess.consumeGameAfterRestart(this)', home)
        self.assertIn('launchTarget != preparingGame', home)

    def test_library_updates_check_both_running_games(self):
        home = (JAVA / 'MAct.java').read_text()
        self.assertIn('if (preview || controlsBlocked() || anyGameRunning()) return;', home)
        for method in ('confirmRedownload()', 'startRedownload()'):
            body = home.split('private void ' + method, 1)[1].split('\n    }', 1)[0]
            self.assertIn('anyGameRunning()', body)
            self.assertIn('R.string.game_libraries_in_use', body)

    def test_per_game_server_status_disables_only_that_game(self):
        # The panel sets each game's status (available / unavailable /
        # maintenance) on the Server page; the library manifest carries it.
        status = (JAVA / 'utils/GameStatus.java').read_text()
        self.assertIn('public static boolean isPlayable(Context context, GameTarget game)', status)
        self.assertIn('static void remember(Context context, JSONObject games)', status)
        self.assertIn('"bgmi_air"', status)
        # Fail-open: a missing or unknown status must never block Launch.
        self.assertIn('return isKnown(value) ? value : AVAILABLE;', status)
        # The SAME verified manifest request feeds the cache — no extra HTTP.
        client = (JAVA / 'utils/Downtwo.java').read_text()
        self.assertIn('GameStatus.remember(app, json.optJSONObject("games"));', client)
        home = (JAVA / 'MAct.java').read_text()
        # Home shows the owner's message (or a short default) on that card…
        self.assertIn('!GameStatus.isPlayable(this, game.target)', home)
        self.assertIn('GameStatus.message(this, game.target)', home)
        self.assertIn('private String gameStatusLabel(GameSession game)', home)
        # …disables only that game's Launch button…
        self.assertIn('boolean off = !stop && game.installed && !GameStatus.isPlayable(this, game.target);', home)
        self.assertIn('game.action.setEnabled(!blocked && !comingSoon && !off)', home)
        # …and startLaunch re-checks so a stale card cannot slip through.
        launch = home.split('private void startLaunch()', 1)[1].split('\n    }', 1)[0]
        self.assertIn('!GameStatus.isPlayable(this, launchTarget.target)', launch)


# Tiny in-memory platform adapters, written only into a temporary compile dir.
# Real Android classes are never replaced in the application or packaged APK.
STUBS = {
    'android/annotation/SuppressLint.java': 'package android.annotation; public @interface SuppressLint { String[] value(); }',
    'androidx/annotation/Nullable.java': 'package androidx.annotation; public @interface Nullable {}',
    'android/content/SharedPreferences.java': '''package android.content;
import java.util.*;
public class SharedPreferences {
 private final Map<String,Object> values = new HashMap<>();
 public String getString(String k,String d){Object v=values.get(k);return v instanceof String?(String)v:d;}
 public long getLong(String k,long d){Object v=values.get(k);return v instanceof Long?(Long)v:d;}
 public boolean getBoolean(String k,boolean d){Object v=values.get(k);return v instanceof Boolean?(Boolean)v:d;}
 public Editor edit(){return new Editor();}
 public class Editor {
  private final Map<String,Object> changes=new HashMap<>();
  public Editor putString(String k,String v){changes.put(k,v);return this;}
  public Editor putLong(String k,long v){changes.put(k,v);return this;}
  public Editor putBoolean(String k,boolean v){changes.put(k,v);return this;}
  public Editor remove(String k){changes.put(k,null);return this;}
  public void apply(){commit();}
  public boolean commit(){for(Map.Entry<String,Object> e:changes.entrySet()){if(e.getValue()==null)values.remove(e.getKey());else values.put(e.getKey(),e.getValue());}return true;}
 }
}''',
    'android/content/Context.java': '''package android.content;
import java.io.*; import java.util.*; import android.content.pm.PackageManager;
public class Context {
 public static final int MODE_PRIVATE=0;
 private final File files; private final Map<String,SharedPreferences> prefs=new HashMap<>();
 public Context(){this(new File("."));} public Context(File f){files=f;f.mkdirs();}
 public File getFilesDir(){return files;}
 public SharedPreferences getSharedPreferences(String n,int m){return prefs.computeIfAbsent(n,k->new SharedPreferences());}
 public Context getApplicationContext(){return this;}
 public String getPackageName(){return "fixture.loader";}
 public PackageManager getPackageManager(){return new PackageManager();}
}''',
    'android/content/ActivityNotFoundException.java': 'package android.content; public class ActivityNotFoundException extends RuntimeException {}',
    'android/content/Intent.java': 'package android.content; public class Intent { private final String action; public Intent(String a){action=a;} public Intent(String a,android.net.Uri u){action=a;} public String getAction(){return action;} }',
    'android/content/pm/PackageManager.java': 'package android.content.pm; public class PackageManager { public static final int PERMISSION_GRANTED=0; public boolean canRequestPackageInstalls(){return true;} }',
    'android/app/Activity.java': 'package android.app; public class Activity extends android.content.Context { public void startActivityForResult(android.content.Intent i,int r){} }',
    'android/net/Uri.java': 'package android.net; public class Uri { public static Uri fromParts(String a,String b,String c){return new Uri();} }',
    'android/Manifest.java': '''package android; public class Manifest { public static class permission {
 public static final String READ_EXTERNAL_STORAGE="read", WRITE_EXTERNAL_STORAGE="write"; } }''',
    'android/os/Build.java': '''package android.os; public class Build {
 public static final String[] SUPPORTED_ABIS={"arm64-v8a"};
 public static class VERSION { public static final int SDK_INT=36; }
 public static class VERSION_CODES { public static final int M=23,O=26,R=30; }
}''',
    'android/os/Environment.java': 'package android.os; public class Environment { public static boolean isExternalStorageManager(){return true;} }',
    'android/provider/Settings.java': '''package android.provider; public class Settings {
 public static final String ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION="all-app",ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION="all",ACTION_MANAGE_UNKNOWN_APP_SOURCES="install",ACTION_APPLICATION_DETAILS_SETTINGS="details"; }''',
    'androidx/core/app/ActivityCompat.java': '''package androidx.core.app; public class ActivityCompat {
 public static int checkSelfPermission(android.content.Context c,String p){return 0;}
 public static boolean shouldShowRequestPermissionRationale(android.app.Activity a,String p){return false;}
 public static void requestPermissions(android.app.Activity a,String[] p,int r){}
}''',
    'android/system/Os.java': 'package android.system; public class Os { public static long sysconf(int n){return 4096L;} public static void chmod(String p,int m){} }',
    'android/system/OsConstants.java': 'package android.system; public class OsConstants { public static final int _SC_PAGESIZE=30; }',
    'android/util/Log.java': '''package android.util; public class Log {
 public static int i(String t,String m){return 0;} public static int d(String t,String m){return 0;} public static int e(String t,String m){return 0;}
 public static int w(String t,String m){return 0;} public static int w(String t,String m,Throwable e){return 0;}
}''',
}


def main():
    if not unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(DualGameWiring)).wasSuccessful():
        raise SystemExit(1)
    java = os.environ.get('JAVA') or shutil.which('java')
    javac = shutil.which('javac')
    ecj = os.environ.get('ECJ_JAR')
    if not java or not (javac or ecj):
        raise RuntimeError('A JDK is needed (or JAVA + ECJ_JAR) for production-class JVM regressions')
    with tempfile.TemporaryDirectory(prefix='loader-dual-game-') as tmp:
        output = Path(tmp)
        sources = []
        for name, code in STUBS.items():
            f = output / 'stubs' / name
            f.parent.mkdir(parents=True, exist_ok=True)
            f.write_text(code + '\n')
            sources.append(f)
        sources.extend(JAVA / 'utils' / name for name in (
            'GameTarget.java', 'GameLibraryFiles.java', 'GameLoginRouter.java', 'GameRunState.java',
            'ConcurrentLaunchGuard.java', 'LibManager.java', 'StorageAccess.java'))
        sources.append(ROOT / 'tests/runtime/DualGameTest.java')
        compiler = [javac, '-source', '8', '-target', '8'] if javac else [java, '-jar', ecj, '-8', '-warn:none']
        subprocess.run([*compiler, '-d', str(output / 'classes'), *map(str, sources)], check=True)
        subprocess.run([java, '-ea', '-cp', str(output / 'classes'), 'com.xlox.tests.DualGameTest'], check=True, timeout=30)


if __name__ == '__main__':
    main()
