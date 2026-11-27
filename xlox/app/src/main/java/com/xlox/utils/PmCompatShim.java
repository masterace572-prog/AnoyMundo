package com.xlox.utils;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.IBinder;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

/**
 * Android 16+ changed several IPackageManager methods (getInstalledPackages,
 * getInstalledApplications, the query* family) to return new ParceledListSlice
 * subclasses — PackageInfoList, ApplicationInfoList and friends. The
 * container's hooked package manager still produces plain ParceledListSlice
 * results, and the proxy's return-type adaptation kills the caller:
 * ClassCastException "Couldn't convert result of type ParceledListSlice to
 * PackageInfoList" — the Play Store dies on its first package scan inside
 * Mundo. This shim wraps the hooked IPackageManager singleton, bypasses the
 * broken adaptation by calling the container's handler directly, and re-wraps
 * slice results into the subclass the current Android expects (the new list
 * classes are ParceledListSlice subclasses with a public List constructor).
 * When the container's own conversion already crashed, the call is retried
 * against the real package manager over Binder — its wire layout matches what
 * the new list classes read. ParceledListSlice itself is a non-SDK class, so
 * every reference to it is by name/reflection; on older Android, where the
 * types already match, the shim is a pass-through.
 */
public final class PmCompatShim {

    private static final String TAG = "PmCompatShim";
    private static final String SLICE = "android.content.pm.ParceledListSlice";
    private static Object systemPm;

    private PmCompatShim() {
    }

    /**
     * Wraps ActivityThread.sPackageManager, once per process. If the container
     * has not hooked it yet this is a no-op; the later install points (process
     * callback, onInitialized, launch guard) catch it once hooked.
     */
    public static void install() {
        try {
            Class<?> threadClass = Class.forName("android.app.ActivityThread");
            Field field = threadClass.getDeclaredField("sPackageManager");
            field.setAccessible(true);
            Object current = field.get(null);
            if (current == null) return;
            if (Proxy.isProxyClass(current.getClass())
                    && Proxy.getInvocationHandler(current) instanceof ShimHandler) {
                return; // already ours
            }
            Class<?> ipm = Class.forName("android.content.pm.IPackageManager");
            Object shim = Proxy.newProxyInstance(PmCompatShim.class.getClassLoader(),
                    new Class<?>[]{ipm}, new ShimHandler(current));
            field.set(null, shim);
            Log.i(TAG, "Package manager shim installed over " + current.getClass().getName());
        } catch (Throwable t) {
            Log.w(TAG, "Package manager shim unavailable", t);
        }
    }

    private static final class ShimHandler implements InvocationHandler {
        private final Object delegate; // whatever IPackageManager was installed before us

        ShimHandler(Object delegate) {
            this.delegate = delegate;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            Object result;
            try {
                if (Proxy.isProxyClass(delegate.getClass())) {
                    // Skip the JDK proxy's return adaptation: the container's
                    // handler hands us the raw slice, which we re-wrap below.
                    result = Proxy.getInvocationHandler(delegate).invoke(proxy, method, args);
                } else {
                    result = invokeRaw(delegate, method, args);
                }
            } catch (ClassCastException e) {
                if (!isSlice(method.getReturnType())) throw e;
                Log.w(TAG, method.getName() + ": the container could not convert its result;"
                        + " asking the real package manager", e);
                return invokeSystem(method, args);
            }
            Class<?> returns = method.getReturnType();
            if (result != null && isSlice(result.getClass()) && !returns.isInstance(result)
                    && isSlice(returns)) {
                try {
                    return wrapSlice(returns, sliceList(result));
                } catch (Throwable t) {
                    Log.w(TAG, "Could not re-wrap " + method.getName() + " as "
                            + returns.getSimpleName() + "; asking the real package manager", t);
                    return invokeSystem(method, args);
                }
            }
            // Second layer of the Android 16+ split fix: infos the framework
            // pulls through the PM also get a repaired dependency table.
            try {
                ApplicationInfo info = pmResultInfo(result);
                if (info != null) SplitDepsFix.repair(info);
            } catch (Throwable ignored) {
            }
            return result;
        }
    }

    /** The ApplicationInfo a PM result carries, or null. */
    private static ApplicationInfo pmResultInfo(Object result) {
        if (result instanceof ApplicationInfo) return (ApplicationInfo) result;
        if (result instanceof PackageInfo) return ((PackageInfo) result).applicationInfo;
        return null;
    }

    /** True when clazz is android.content.pm.ParceledListSlice or a subclass of it. */
    private static boolean isSlice(Class<?> clazz) {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            if (SLICE.equals(c.getName())) return true;
        }
        return false;
    }

    /** ParceledListSlice.getList() via reflection (the class is not in the SDK jar). */
    private static List<?> sliceList(Object slice) throws Exception {
        Method getList = slice.getClass().getMethod("getList");
        getList.setAccessible(true);
        return (List<?>) getList.invoke(slice);
    }

    /** The new list types are ParceledListSlice subclasses with a List constructor. */
    private static Object wrapSlice(Class<?> listClass, List<?> items) throws Exception {
        java.lang.reflect.Constructor<?> ctor = listClass.getDeclaredConstructor(List.class);
        ctor.setAccessible(true);
        return ctor.newInstance(items);
    }

    /** Last resort: the device's real package manager, over the real Binder interface. */
    private static Object invokeSystem(Method method, Object[] args) throws Throwable {
        Object pm = systemPm;
        if (pm == null) {
            Class<?> serviceManager = Class.forName("android.os.ServiceManager");
            Object binder = serviceManager.getMethod("getService", String.class).invoke(null, "package");
            Class<?> stub = Class.forName("android.content.pm.IPackageManager$Stub");
            pm = stub.getMethod("asInterface", IBinder.class).invoke(null, binder);
            systemPm = pm;
        }
        return invokeRaw(pm, method, args);
    }

    private static Object invokeRaw(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getTargetException();
        }
    }
}
