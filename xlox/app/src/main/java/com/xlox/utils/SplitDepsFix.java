package com.xlox.utils;

import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.util.SparseArray;

import java.io.File;
import java.lang.ref.Reference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Android 16+ crashes EVERY virtual-app activity launch when the app's
 * ApplicationInfo requests isolated split loading but carries no split
 * dependency table:
 *
 *   NullPointerException: SparseArray.get(int) on a null object
 *     at SplitDependencyLoader.collectConfigSplitIndices
 *     at LoadedApk$SplitDependencyLoaderImpl.ensureSplitLoaded
 *     at ContextImpl.createActivityContext
 *     at ActivityThread.performLaunchActivity
 *
 * LoadedApk builds its SplitDependencyLoader from aInfo.splitDependencies.
 * On a real phone PMS computes that table at install time; the container's
 * own APK parser never does, so the field is null and the first activity
 * context (base or split) dies. BGMI Air and the in-container Google sign-in
 * both hit this on Android 17.
 *
 * The fix wraps ActivityThread's H callback and repairs the ApplicationInfo
 * of every incoming launch/bind before the framework builds the LoadedApk.
 * Delegation runs FIRST: the container swaps the stub launch info for the
 * virtual app's real one inside its own callback (in place), so the repair
 * only makes sense after the inner callback returns. If the container
 * already created a LoadedApk for the package through its own reflective
 * bind, the cached instance holds a split loader with a null table — it is
 * rebuilt in place (same thread, right before the framework uses it).
 *
 * All framework members are reached reflectively: they are greylist entries,
 * which targetSdk 28 may still access.
 */
public final class SplitDepsFix {

    private static final String TAG = "SplitDepsFix";
    /** ActivityThread.H.EXECUTE_TRANSACTION / BIND_APPLICATION (stable for years). */
    private static final int EXECUTE_TRANSACTION_FALLBACK = 159;
    private static final int BIND_APPLICATION_FALLBACK = 110;
    private static final String LAUNCH_ITEM = "android.app.servertransaction.LaunchActivityItem";
    private static final String SPLIT_LOADER_IMPL = "android.app.LoadedApk$SplitDependencyLoaderImpl";

    private static boolean installed;
    private static File stateDir;

    private SplitDepsFix() {
    }

    /** Records the files dir for repair bookkeeping, then installs the hook. */
    public static void install(android.content.Context ctx) {
        try {
            if (ctx != null) stateDir = ctx.getApplicationContext().getFilesDir();
        } catch (Throwable ignored) {
        }
        install();
    }

    /** Packages the repair fired for, newest last; "" when none or unreadable. */
    public static String readState(android.content.Context ctx) {
        File dir = stateDir;
        try {
            if (ctx != null) dir = ctx.getFilesDir();
        } catch (Throwable ignored) {
        }
        if (dir == null) return "";
        File file = new File(dir, "splitfix_state.txt");
        if (!file.isFile()) return "";
        try {
            byte[] data = new byte[(int) Math.min(file.length(), 4096)];
            java.io.FileInputStream in = new java.io.FileInputStream(file);
            try {
                int n = in.read(data);
                return n > 0 ? new String(data, 0, n, java.nio.charset.StandardCharsets.UTF_8).trim() : "";
            } finally {
                in.close();
            }
        } catch (Throwable t) {
            return "";
        }
    }

    private static void noteRepair(String packageName) {
        File dir = stateDir;
        if (dir == null) return;
        java.io.FileOutputStream out = null;
        try {
            out = new java.io.FileOutputStream(new File(dir, "splitfix_state.txt"), true);
            out.write((packageName + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
        } finally {
            try {
                if (out != null) out.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** Installs the repair hook once per process; safe to call repeatedly. */
    public static synchronized void install() {
        if (installed) return;
        installed = true;
        try {
            Class<?> threadClass = Class.forName("android.app.ActivityThread");
            Method current = threadClass.getMethod("currentActivityThread");
            Object thread = current.invoke(null);
            if (thread == null) return;
            Field hField = threadClass.getDeclaredField("mH");
            hField.setAccessible(true);
            Object handler = hField.get(thread);
            if (!(handler instanceof Handler)) return;
            Field callbackField = Handler.class.getDeclaredField("mCallback");
            callbackField.setAccessible(true);
            final Handler.Callback previous = (Handler.Callback) callbackField.get(handler);
            if (previous instanceof RepairCallback) return; // already ours
            callbackField.set(handler, new RepairCallback(thread, previous,
                    hConstant("EXECUTE_TRANSACTION", EXECUTE_TRANSACTION_FALLBACK),
                    hConstant("BIND_APPLICATION", BIND_APPLICATION_FALLBACK)));
            Log.i(TAG, "Split dependency repair installed over "
                    + (previous != null ? previous.getClass().getName() : "no callback"));
        } catch (Throwable t) {
            Log.w(TAG, "Launch repair hook unavailable", t);
        }
    }

    private static int hConstant(String name, int fallback) {
        try {
            Class<?> hClass = Class.forName("android.app.ActivityThread$H");
            return hClass.getField(name).getInt(null);
        } catch (Throwable t) {
            return fallback;
        }
    }

    /** Chains behind the container's callback and repairs after it swaps info. */
    private static final class RepairCallback implements Handler.Callback {
        private final Object thread;
        private final Handler.Callback previous;
        private final int executeWhat;
        private final int bindWhat;

        RepairCallback(Object thread, Handler.Callback previous, int executeWhat, int bindWhat) {
            this.thread = thread;
            this.previous = previous;
            this.executeWhat = executeWhat;
            this.bindWhat = bindWhat;
        }

        @Override
        public boolean handleMessage(Message msg) {
            boolean handled = previous != null && previous.handleMessage(msg);
            if (!handled) {
                if (msg.what == executeWhat) {
                    repairTransaction(thread, msg.obj);
                } else if (msg.what == bindWhat) {
                    repairBindData(msg.obj);
                }
            }
            return handled;
        }
    }

    private static void repairTransaction(Object thread, Object transaction) {
        if (transaction == null) return;
        try {
            Method getCallbacks = transaction.getClass().getMethod("getCallbacks");
            List<?> callbacks = (List<?>) getCallbacks.invoke(transaction);
            if (callbacks == null) return;
            for (Object item : callbacks) {
                if (item == null || !LAUNCH_ITEM.equals(item.getClass().getName())) continue;
                try {
                    Field infoField = item.getClass().getDeclaredField("mInfo");
                    infoField.setAccessible(true);
                    ActivityInfo info = (ActivityInfo) infoField.get(item);
                    ApplicationInfo app = info != null ? info.applicationInfo : null;
                    if (repair(app)) repairLoadedApkCache(thread, app.packageName);
                } catch (Throwable t) {
                    Log.w(TAG, "Could not inspect a launch item", t);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Could not inspect a launch transaction", t);
        }
    }

    /** A virtual-app bind carries the same container-built ApplicationInfo. */
    private static void repairBindData(Object bindData) {
        if (bindData == null) return;
        try {
            Field appInfo = bindData.getClass().getDeclaredField("appInfo");
            appInfo.setAccessible(true);
            repair((ApplicationInfo) appInfo.get(bindData));
        } catch (Throwable ignored) {
        }
    }

    /**
     * The container can create the game's LoadedApk through its own reflective
     * bind before any framework message runs. That instance caches a split
     * loader whose dependency table is null; rebuild it from the repaired
     * ApplicationInfo. Runs on the activity thread, right before the framework
     * itself would use the loader — no race.
     */
    private static void repairLoadedApkCache(Object thread, String packageName) {
        try {
            Field packagesField = thread.getClass().getDeclaredField("mPackages");
            packagesField.setAccessible(true);
            Object packages = packagesField.get(thread);
            Method get = packages.getClass().getMethod("get", Object.class);
            Object ref = get.invoke(packages, packageName);
            Object loadedApk = ref instanceof Reference ? ((Reference<?>) ref).get() : null;
            if (loadedApk == null) return;
            Field loaderField = loadedApk.getClass().getDeclaredField("mSplitLoader");
            loaderField.setAccessible(true);
            Object loader = loaderField.get(loadedApk);
            if (loader == null) return; // no isolated split loading: healthy
            Field depsField = loader.getClass().getSuperclass().getDeclaredField("mDependencies");
            depsField.setAccessible(true);
            if (depsField.get(loader) != null) return; // healthy table
            ApplicationInfo info = (ApplicationInfo) loadedApk.getClass()
                    .getMethod("getApplicationInfo").invoke(loadedApk);
            if (!repair(info)) return;
            Object deps = splitDependenciesField().get(info);
            if (deps == null) return;
            Class<?> implClass = Class.forName(SPLIT_LOADER_IMPL);
            Constructor<?> ctor = implClass.getDeclaredConstructor(loadedApk.getClass(), SparseArray.class);
            ctor.setAccessible(true);
            loaderField.set(loadedApk, ctor.newInstance(loadedApk, deps));
            Log.i(TAG, "Rebuilt the split loader for " + packageName);
        } catch (Throwable t) {
            Log.w(TAG, "Could not rebuild the split loader for " + packageName, t);
        }
    }

    /**
     * Fills a missing split dependency table on an isolated-split app; true
     * when the info was repaired. Shared with PmCompatShim, which repairs
     * ApplicationInfos the framework pulls through the hooked package manager.
     * No-op for unsplit or healthy apps. splitNames is read reflectively: it
     * only exists from API 26 up, and the repair is pointless below it anyway
     * (the framework path that needs it is Android 16+).
     */
    public static boolean repair(ApplicationInfo info) {
        if (info == null) return false;
        try {
            String[] splitNames = splitNames(info);
            if (splitNames == null || splitNames.length == 0) return false;
            if (!requestsIsolatedSplitLoading(info)) return false;
            Field deps = splitDependenciesField();
            if (deps.get(info) != null) return false;
            deps.set(info, toSparseArray(SplitDepsTable.synthesize(splitNames)));
            ensureClassLoaderNames(info, splitNames.length);
            noteRepair(info.packageName);
            Log.i(TAG, "Repaired split dependencies for " + info.packageName);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "Could not repair " + info.packageName, t);
            return false;
        }
    }

    private static String[] splitNames(ApplicationInfo info) throws Exception {
        Field field = ApplicationInfo.class.getField("splitNames");
        field.setAccessible(true);
        return (String[]) field.get(info);
    }

    /** Null rows (pure config splits) carry no entry, matching the AOSP layout. */
    private static SparseArray<int[]> toSparseArray(int[][] table) {
        SparseArray<int[]> deps = new SparseArray<>(table.length);
        for (int i = 0; i < table.length; i++) {
            if (table[i] != null) deps.put(i, table[i]);
        }
        return deps;
    }

    private static boolean requestsIsolatedSplitLoading(ApplicationInfo info) throws Exception {
        Method method = ApplicationInfo.class.getMethod("requestsIsolatedSplitLoading");
        return Boolean.TRUE.equals(method.invoke(info));
    }

    private static Field splitDependenciesField() throws Exception {
        Field field = ApplicationInfo.class.getField("splitDependencies");
        field.setAccessible(true);
        return field;
    }

    /** constructSplit indexes this array for non-base splits; a null array NPEs. */
    private static void ensureClassLoaderNames(ApplicationInfo info, int splitCount) {
        try {
            Field names = ApplicationInfo.class.getField("splitClassLoaderNames");
            names.setAccessible(true);
            if (names.get(info) == null) names.set(info, new String[splitCount]);
        } catch (Throwable ignored) {
        }
    }
}
