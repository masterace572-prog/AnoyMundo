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
        if (!GAME_PROCESS.equals(processName) && !GAME_PROCESS.equals(processName2)) {
            Log.d(LOAD_TAG, "Ignoring non-game process: " + processName);
            return;
        }

        ENABLE_IMGUI = false;
        try {
            Context ctx = gApp != null ? gApp : this;
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

            if (!LibManager.isElf(lib)) {
                Log.e(LOAD_TAG, "libbgmi.so NOT FOUND or invalid at " + lib.getAbsolutePath()
                        + " — open the app once so it can download the panel release");
                return;
            }
            LibManager.setPermissions(lib);
            System.load(lib.getAbsolutePath());
            Log.i(LOAD_TAG, "libbgmi.so LOADED from " + lib.getAbsolutePath());
        } catch (UnsatisfiedLinkError e) {
            Log.e(LOAD_TAG, "libbgmi.so could not be dlopen'd", e);
        } catch (Exception e) {
            Log.e(LOAD_TAG, "Library load error", e);
        }
    }
}
