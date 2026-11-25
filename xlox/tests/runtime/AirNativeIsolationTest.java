package com.xlox.tests;

import android.util.Log;
import com.xlox.BoxApplication;
import com.xlox.BuildConfig;
import com.xlox.utils.GameWebRouting;
import com.xlox.utils.LibManager;

/** Actual callback + game selector, JVM adapters only; NOT a game/runtime test. */
public final class AirNativeIsolationTest {
    private static final String SKIP = "AIR-NATIVE-ISOLATION: libbgmilite.so injection skipped";
    private static int checks;

    private static void expect(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static BoxApplication fresh() {
        // Native XloX is deliberately absent on the host; the production class
        // handles its static linker error. Clear it before observing callbacks.
        BoxApplication app = new BoxApplication();
        Log.messages.clear();
        LibManager.errors = LibManager.successes = LibManager.validations = 0;
        LibManager.readOnly = LibManager.files = 0;
        LibManager.valid = true;
        LibManager.lastError = null;
        GameWebRouting.installs = GameWebRouting.refreshes = 0;
        return app;
    }

    public static void main(String[] args) {
        String[][] air = {
                {"com.krafton.bgmilite", "com.krafton.bgmilite"},
                {"com.krafton.bgmilite:service", "com.krafton.bgmilite"},
                {"stub1", "com.krafton.bgmilite"},
                {null, "com.krafton.bgmilite"}
        };
        for (String[] names : air) {
            BoxApplication app = fresh();
            app.onCreate(names[0], names[1]);
            expect(LibManager.validations > 0, "Air file validation must be preserved");
            expect(LibManager.readOnly == 1, "Air read-only handling must be preserved");
            expect(LibManager.successes == 0, "Never claim an unexecuted library loaded");
            expect(!Log.has("libbgmilite.so LOADED"), "No fabricated Air load log");
            expect(Log.has(SKIP) == BuildConfig.AIR_NATIVE_ISOLATION, "Exact skip marker only in diagnostic mode");
            // The file is absent: only the normal mode should reach System.load
            // and record its handled UnsatisfiedLinkError.
            expect(LibManager.errors == (BuildConfig.AIR_NATIVE_ISOLATION ? 0 : 1),
                    "Only diagnostic Air skips the actual System.load handoff");
            if (!BuildConfig.AIR_NATIVE_ISOLATION) {
                expect(LibManager.lastError.contains("could not be dlopen'd"), "Normal Air still attempts injection");
            }
            app.onInitialized();
            expect(GameWebRouting.installs == 0 && GameWebRouting.refreshes == 0,
                    "BGMI OAuth routing must not leak into Air");
        }

        String[][] bgmi = {
                {"com.pubg.imobile", "com.pubg.imobile"},
                {"stub2", "com.pubg.imobile:service"},
                {"com.pubg.imobile", null}
        };
        for (String[] names : bgmi) {
            BoxApplication app = fresh();
            app.onCreate(names[0], names[1]);
            expect(LibManager.errors == 1, "BGMI must still attempt System.load in BOTH modes");
            expect(LibManager.lastError.contains("could not be dlopen'd"), "BGMI linker diagnostics preserved");
            expect(LibManager.successes == 0, "Absent test BGMI library did not load");
            expect(LibManager.readOnly == 1, "BGMI read-only handling preserved");
            expect(!Log.has(SKIP), "Diagnostic must never skip BGMI");
            expect(GameWebRouting.installs == 1, "BGMI early web routing preserved");
            app.onInitialized();
            expect(GameWebRouting.refreshes == 1, "BGMI final web routing preserved");
        }

        String[][] ignored = {
                {null, null}, {"unknown", "stub1"},
                {"com.pubg.imobile", "com.krafton.bgmilite"},
                {"com.krafton.bgmilite.fake", null}
        };
        for (String[] names : ignored) {
            BoxApplication app = fresh();
            app.onCreate(names[0], names[1]);
            expect(LibManager.files == 0 && LibManager.validations == 0,
                    "Unknown/ambiguous callbacks cannot select a library");
            expect(LibManager.errors == 0 && LibManager.successes == 0, "No load result for ignored callback");
            expect(!Log.has(SKIP), "Ignored callbacks cannot masquerade as the Air diagnostic");
        }

        BoxApplication invalid = fresh();
        LibManager.valid = false;
        invalid.onCreate("com.krafton.bgmilite", "com.krafton.bgmilite");
        expect(LibManager.errors == 1, "Diagnostic must not bypass the installed-library check");
        expect(LibManager.lastError.contains("NOT FOUND or invalid"), "Existing validation error preserved");
        expect(!Log.has(SKIP), "An invalid file is not a valid diagnostic run");
        expect(LibManager.successes == 0, "Invalid file is never reported loaded");
        System.out.println("PASS actual BoxApplication callback (AIR_NATIVE_ISOLATION="
                + BuildConfig.AIR_NATIVE_ISOLATION + "): " + checks + " assertions");
    }
}
