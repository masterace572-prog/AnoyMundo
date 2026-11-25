package com.xlox.utils;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.Context;
import com.xlox.BuildConfig;
import java.io.File;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Real Android adapter entry points on host Android; no root, native game or account. */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = {21, 35})
public class AirCrashDiagnosticsTest {
    private Context context;
    private File dir;

    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        dir = new File(context.getFilesDir(), "air_crash_diagnostics");
        File[] markers = dir.listFiles();
        if (markers != null) for (File f : markers) assertTrue(f.delete());
    }

    @Test public void reportRequiresANewLaunchRatherThanExposingHistoricalCrashes() {
        String report = AirCrashDiagnostics.collect(context, false);
        if (!BuildConfig.AIR_NATIVE_ISOLATION) assertTrue(report.contains("disabled"));
        else {
            assertTrue(report.startsWith("Air crash report v1"));
            assertTrue(report.contains("No fresh Air launch marker"));
            assertFalse(report.contains("exitTimeMs="));
        }
    }

    @Test public void markerWriteIsBuildGatedAndIsolatedFromGameData() {
        AirCrashDiagnostics.recordLaunch(context);
        assertEquals(BuildConfig.AIR_NATIVE_ISOLATION, new File(dir, "launch.txt").exists());
        assertFalse(new File(context.getFilesDir(), "loader/launch.txt").exists());
    }

    @Test public void normalReportDoesNotReadSavedLicenceOrRequestRoot() {
        String secret = "ANOY-7D-PRIVATEKEY";
        context.getSharedPreferences("com.xlox.download", Context.MODE_PRIVATE).edit().putString("USER", secret).commit();
        AirCrashDiagnostics.recordLaunch(context);
        String report = AirCrashDiagnostics.collect(context, false);
        assertFalse(report.contains(secret));
        assertFalse(report.contains("Licence key:"));
        assertTrue(report.length() <= AirExitReport.MAX_REPORT);
        if (BuildConfig.AIR_NATIVE_ISOLATION) {
            assertTrue(report.contains("Root: not requested"));
            assertTrue(report.contains("Air callback marker: not seen"));
        } else assertTrue(report.contains("disabled"));
    }
}
