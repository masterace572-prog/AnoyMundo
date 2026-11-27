package com.xlox.utils;

import android.app.Application;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Android 16+ launch-crash guard. ConfigurationController.handleConfigurationChanged
 * reads ActivityThread.currentApplication() and passes it to a @NonNull
 * parameter of ClientTransactionListenerController; the virtual container
 * leaves mInitialApplication null across a vprocess handoff, so the
 * configuration change that follows a split-APK launch (Play Store, BGMI Air)
 * NPEs inside the framework and the system shows "app has a bug". Before every
 * client transaction this guard puts the live Application back when it is
 * missing. Reflection targets framework internals only, with every step
 * guarded — on any failure the app behaves exactly as before.
 */
public final class ConfigGuard {

    private static final String TAG = "ConfigGuard";
    /** ActivityThread$H.EXECUTE_TRANSACTION (stable since Android 9). */
    private static final int EXECUTE_TRANSACTION = 159;

    private static Method currentActivityThread;
    private static Method currentApplication;
    private static Field initialApplication;
    private static boolean reflectionBroken;

    private ConfigGuard() {
    }

    /**
     * Wraps the main handler's callback, chaining whatever the container (or
     * AndroidX) installed there. Idempotent: a second call keeps the first
     * guard and its chain.
     */
    public static void install(Application app) {
        if (app == null) return;
        try {
            Class<?> threadClass = Class.forName("android.app.ActivityThread");
            Object thread = threadClass.getDeclaredMethod("currentActivityThread").invoke(null);
            if (thread == null) return;
            Field hField = threadClass.getDeclaredField("mH");
            hField.setAccessible(true);
            Handler handler = (Handler) hField.get(thread);
            if (handler == null) return;
            Field callbackField = Handler.class.getDeclaredField("mCallback");
            callbackField.setAccessible(true);
            Object previous = callbackField.get(handler);
            if (previous instanceof GuardCallback) return; // already guarding, chain preserved
            callbackField.set(handler, new GuardCallback(app, (Handler.Callback) previous));
            Log.i(TAG, "Config guard installed" + (previous != null ? " (chained)" : ""));
        } catch (Throwable t) {
            Log.w(TAG, "Config guard unavailable", t);
        }
    }

    /** Immediate repair, right before a container launch. */
    public static void repairNow(Application app) {
        ensureApplication(app);
    }

    private static void ensureApplication(Application app) {
        if (app == null || reflectionBroken) return;
        try {
            if (currentActivityThread == null) {
                Class<?> threadClass = Class.forName("android.app.ActivityThread");
                currentActivityThread = threadClass.getDeclaredMethod("currentActivityThread");
                currentApplication = threadClass.getDeclaredMethod("currentApplication");
                initialApplication = threadClass.getDeclaredField("mInitialApplication");
                initialApplication.setAccessible(true);
            }
            if (currentApplication.invoke(null) != null) return; // healthy: nothing to do
            Object thread = currentActivityThread.invoke(null);
            if (thread != null && initialApplication.get(thread) == null) {
                initialApplication.set(thread, app);
                Log.i(TAG, "Restored the application instance before a transaction");
            }
        } catch (Throwable t) {
            reflectionBroken = true;
            Log.w(TAG, "Could not guard the application instance", t);
        }
    }

    private static final class GuardCallback implements Handler.Callback {
        private final Application app;
        private final Handler.Callback previous;

        GuardCallback(Application app, Handler.Callback previous) {
            this.app = app;
            this.previous = previous;
        }

        @Override
        public boolean handleMessage(Message msg) {
            if (msg.what == EXECUTE_TRANSACTION) {
                ensureApplication(app);
            }
            return previous != null && previous.handleMessage(msg);
        }
    }
}
