package com.xlox;

import android.app.Application;
import android.content.Context;
import android.util.Log;

import com.xlox.utils.ConfigGuard;
import com.xlox.utils.CrashLogger;
import com.xlox.utils.LibManager;
import com.xlox.utils.GameTarget;
import com.xlox.utils.GameWebRouting;
import com.xlox.utils.AccountTrace;
import com.xlox.utils.PmCompatShim;
import com.xlox.utils.SplitDepsFix;

import java.io.File;

import net_62v.external.IMundoProcessCallback;
import net_62v.external.MetaActivationManager;
import net_62v.external.MetaCore;

public class BoxApplication extends Application
        implements IMundoProcessCallback {

    private static final String TAG = "BoxApplication";
    private static final String LOAD_TAG = "Load";
    private boolean virtualGameProcess;

    static {
        try {
            System.loadLibrary("XloX");
        } catch (UnsatisfiedLinkError e) {
            // Do not swallow this: every JNI call below (Version/Link/Check)
            // depends on libXloX.so. A silent failure used to look like a
            // "lib not loading" bug with no useful log.
            Log.e(TAG, "libXloX.so failed to load", e);
        }
    }

    public static native String getSdkKey();

    public static BoxApplication gApp;

    public static boolean ENABLE_IMGUI = false;

    public static BoxApplication get() {
        return gApp;
    }

    @Override
    protected void attachBaseContext(Context base) {
        // Earliest point of the process: record any later crash for support.
        CrashLogger.install(this);
        gApp = this;
        // Android 16+: the container's process handoff can leave the framework
        // without an Application; the next config change then NPEs (see ConfigGuard).
        ConfigGuard.install(this);
        super.attachBaseContext(base);
        MetaCore.setProcessLifecycleCallback(this);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        gApp = this;
        Log.i(TAG, "Loader diagnostics: " + LibManager.diagnostics(this));
        try {
            MetaActivationManager.activateSdk(getSdkKey());
        } catch (Throwable e) {
            // getSdkKey() is native; an UnsatisfiedLinkError is an Error, not an
            // Exception, and would otherwise kill the process at startup.
            Log.e(TAG, "Activation Error", e);
        }
        // Activation may hook the main handler after attachBaseContext: re-chain
        // the config guard so it survives whatever the container installed.
        ConfigGuard.install(this);
        // Same for the package manager hook: wrap it with the Android 16+
        // slice-type shim once the container's proxy is in place.
        PmCompatShim.install();
        // Android 16+: virtual apps with isolated splits get no dependency
        // table from the container; repair it on every incoming launch.
        SplitDepsFix.install(this);
        AccountTrace.install(this);
    }

    /**
     * Called by the virtual container when the game process starts. This is
     * where the downloaded loader library must be injected into the game.
     */
    @Override
    public void onCreate(String processName, String processName2) {
        Log.i(LOAD_TAG, "Virtual process started: " + processName + " / " + processName2
                + " — " + LibManager.diagnostics(this));
        // The vprocess is fully hooked when this callback runs: wrap the hooked
        // package manager before the virtual app's threads first query it
        // (Android 16+ slice types; the Play Store dies on this otherwise).
        PmCompatShim.install();
        SplitDepsFix.install(this);
        AccountTrace.install(this);
        // Android 17 NPEs on a null split table in the LoadedApk constructor during
        // the bind — before any H callback fires. Repair the freshly-bound app's
        // ApplicationInfo now, and once more shortly after (the container can finish
        // the bind a beat after this callback returns).
        SplitDepsFix.repairBoundApp();
        new android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed(SplitDepsFix::repairBoundApp, 120);
        final GameTarget game = GameTarget.forProcess(processName, processName2);
        if (game == null) {
            Log.d(LOAD_TAG, "Ignoring non-game process: " + processName);
            return;
        }

        // The existing OAuth/callback router is BGMI-specific. Do not route
        // Air authentication into com.pubg.imobile when both games are open.
        virtualGameProcess = game == GameTarget.BGMI;
        if (virtualGameProcess) installGameWebRouting(false);
        ENABLE_IMGUI = false;
        Context ctx = gApp != null ? gApp : this;
        try {
            File loaderDir = LibManager.loaderDir(ctx);
            File lib = LibManager.libFile(ctx, game);
            if (game == GameTarget.BGMI && !LibManager.isElf(lib)) {
                // Legacy BGMI recovery only. Air must be installed canonically
                // by the bundle installer; never revive a withdrawn nested Air file.
                // Handles releases wrapped in a folder or uploaded as lib.so.
                File nested = LibManager.findLib(loaderDir, game);
                if (nested != null && !nested.getAbsolutePath().equals(lib.getAbsolutePath())) {
                    LibManager.installLib(ctx, nested, game);
                    lib = LibManager.libFile(ctx, game);
                }
            }

            Log.i(LOAD_TAG, "Loader diagnostics: " + LibManager.diagnostics(ctx, game));

            if (!LibManager.isElf(lib)) {
                String message = game.libraryName + " NOT FOUND or invalid at " + lib.getAbsolutePath()
                        + " — open the app once so it can download the panel release";
                Log.e(LOAD_TAG, message);
                LibManager.recordLoadError(ctx, game, message);
                return;
            }

            // Android 15+ 16 KB-page devices reject 4 KB-aligned ELF files.
            // Report it explicitly rather than letting the menu silently vanish.
            String alignment = LibManager.alignmentProblem(lib);
            if (alignment != null) {
                Log.e(LOAD_TAG, "ELF alignment: " + alignment);
            }

            // Android 17 (API 37) refuses System.load() on a writable file.
            // The old code deliberately made the library writable here.
            if (!LibManager.makeReadOnly(lib)) {
                Log.w(LOAD_TAG, "Could not mark " + lib.getName() + " read-only; "
                        + "Android 17 may reject System.load()");
            }

            System.load(lib.getAbsolutePath());
            Log.i(LOAD_TAG, game.libraryName + " LOADED from " + lib.getAbsolutePath());
            LibManager.recordLoadSuccess(ctx, game, lib.getAbsolutePath());
            // Milestone AFTER the load: the next thing to die is the game's own
            // startup or its own .so exec under the Android 16/17 virtual runtime.
            // Seeing "loaded-ok" with no following milestone pins the crash there.
            LibManager.recordMilestone(ctx, game, "loaded-ok");
        } catch (UnsatisfiedLinkError e) {
            String message = describeLoadFailure(ctx, game, e);
            Log.e(LOAD_TAG, message, e);
            LibManager.recordLoadError(ctx, game, message);
        } catch (Throwable e) {
            String message = game.libraryName + " load error: " + e;
            Log.e(LOAD_TAG, message, e);
            LibManager.recordLoadError(ctx, game, message);
        }
    }

    @Override
    public void onInitialized() {
        PmCompatShim.install();
        SplitDepsFix.install(this);
        // The game process reached full container init — the game's own code/.so
        // runs next. If this milestone is present but the process still died, the
        // fault is inside the game's own native startup under Android 16/17.
        try {
            LibManager.recordMilestone(this, GameTarget.BGMI_AIR, "container-init-ok");
        } catch (Throwable ignored) {
        }
        if (virtualGameProcess) installGameWebRouting(true);
    }

    private void installGameWebRouting(boolean finalPass) {
        try {
            if (finalPass) GameWebRouting.refreshAfterInitialization();
            else GameWebRouting.install();
        } catch (Throwable ignored) {
            // Keep activation/native loading independent; never log Intent data.
            Log.w(TAG, "In-container game web routing is not available yet");
        }
    }

    /** Turn the raw linker error into an actionable, user-facing reason. */
    private String describeLoadFailure(Context ctx, GameTarget game, UnsatisfiedLinkError error) {
        String raw = error == null ? "" : String.valueOf(error.getMessage());
        File lib = LibManager.libFile(ctx, game);
        String alignment = LibManager.alignmentProblem(lib);
        if (alignment != null) return alignment + " (linker: " + raw + ")";
        if (raw.contains("writable")) {
            return "Android 17 refused a writable native library. Update to the "
                    + "latest loader build (it marks " + game.libraryName + " read-only). Linker: " + raw;
        }
        return game.libraryName + " could not be dlopen'd: " + raw;
    }
}
