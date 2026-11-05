package com.xlox;

import android.app.Application;
import android.content.Context;
import android.util.Log;

import com.xlox.utils.LibManager;

import java.io.File;

import net_62v.external.IMundoProcessCallback;
import net_62v.external.MetaActivationManager;
import net_62v.external.MetaCore;

public class BoxApplication extends Application
        implements IMundoProcessCallback {

    private static final String TAG = "BoxApplication";
    private static final String LOAD_TAG = "Load";
    private static final String GAME_PROCESS = "com.pubg.imobile";

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
    }

    /**
     * Called by the virtual container when the game process starts. This is
     * where the downloaded loader library must be injected into the game.
     */
    @Override
    public void onCreate(String processName, String processName2) {
        Log.i(LOAD_TAG, "Virtual process started: " + processName + " / " + processName2
                + " — " + LibManager.diagnostics(this));
        if (!isGameProcess(processName) && !isGameProcess(processName2)) {
            Log.d(LOAD_TAG, "Ignoring non-game process: " + processName);
            return;
        }

        ENABLE_IMGUI = false;
        Context ctx = gApp != null ? gApp : this;
        try {
            File loaderDir = LibManager.loaderDir(ctx);
            File lib = LibManager.libFile(ctx);
            if (!LibManager.isElf(lib)) {
                // Handles releases wrapped in a folder or uploaded as lib.so.
                File nested = LibManager.findLib(loaderDir);
                if (nested != null && !nested.getAbsolutePath().equals(lib.getAbsolutePath())) {
                    LibManager.installLib(ctx, nested);
                    lib = LibManager.libFile(ctx);
                }
            }

            Log.i(LOAD_TAG, "Loader diagnostics: " + LibManager.diagnostics(ctx));

            if (!LibManager.isElf(lib)) {
                String message = "libbgmi.so NOT FOUND or invalid at " + lib.getAbsolutePath()
                        + " — open the app once so it can download the panel release";
                Log.e(LOAD_TAG, message);
                LibManager.recordLoadError(ctx, message);
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
            Log.i(LOAD_TAG, "libbgmi.so LOADED from " + lib.getAbsolutePath());
            LibManager.recordLoadSuccess(ctx, lib.getAbsolutePath());
        } catch (UnsatisfiedLinkError e) {
            String message = describeLoadFailure(ctx, e);
            Log.e(LOAD_TAG, message, e);
            LibManager.recordLoadError(ctx, message);
        } catch (Throwable e) {
            String message = "libbgmi.so load error: " + e;
            Log.e(LOAD_TAG, message, e);
            LibManager.recordLoadError(ctx, message);
        }
    }

    /**
     * Some newer Android builds report the virtualised game process with a
     * suffix or a rewritten name. Accept those variants so the library is
     * still injected; unrelated processes are still ignored.
     */
    private static boolean isGameProcess(String name) {
        if (name == null) return false;
        return name.equals(GAME_PROCESS)
                || name.startsWith(GAME_PROCESS + ":")
                || name.contains("pubg.imobile");
    }

    /** Turn the raw linker error into an actionable, user-facing reason. */
    private String describeLoadFailure(Context ctx, UnsatisfiedLinkError error) {
        String raw = error == null ? "" : String.valueOf(error.getMessage());
        File lib = LibManager.libFile(ctx);
        String alignment = LibManager.alignmentProblem(lib);
        if (alignment != null) return alignment + " (linker: " + raw + ")";
        if (raw.contains("writable")) {
            return "Android 17 refused a writable native library. Update to the "
                    + "latest loader build (it marks libbgmi.so read-only). Linker: " + raw;
        }
        return "libbgmi.so could not be dlopen'd: " + raw;
    }
}
