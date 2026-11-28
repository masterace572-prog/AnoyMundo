package com.ccore.spoof;

import android.os.Build;
import android.util.Log;

import java.lang.reflect.Field;

/**
 * Installs the flagship identity inside a virtual process. Two layers:
 *
 * <ul>
 *   <li>Reflection rewrite of the already-initialized android.os.Build fields
 *       (they are static finals assigned at class load, so a property hook
 *       alone cannot change them).</li>
 *   <li>A native hook on SystemProperties.native_get (see DeviceSpoofHook.cpp)
 *       that answers every later property read through {@link #hookGet}.</li>
 * </ul>
 *
 * Runs only inside virtual processes; the host never calls this.
 */
public final class DeviceSpoof {

    private static final String TAG = "DeviceSpoof";
    private static boolean sInstalled;

    private DeviceSpoof() {
    }

    public static synchronized void install() {
        if (sInstalled) return;
        sInstalled = true;
        int applied = 0;
        applied += setBuild("BRAND", DeviceProfiles.get("ro.product.brand")) ? 1 : 0;
        applied += setBuild("MANUFACTURER", DeviceProfiles.get("ro.product.manufacturer")) ? 1 : 0;
        applied += setBuild("MODEL", DeviceProfiles.get("ro.product.model")) ? 1 : 0;
        applied += setBuild("DEVICE", DeviceProfiles.get("ro.product.device")) ? 1 : 0;
        applied += setBuild("PRODUCT", DeviceProfiles.get("ro.product.name")) ? 1 : 0;
        applied += setBuild("BOARD", DeviceProfiles.get("ro.product.board")) ? 1 : 0;
        applied += setBuild("HARDWARE", DeviceProfiles.get("ro.hardware")) ? 1 : 0;
        applied += setBuild("FINGERPRINT", DeviceProfiles.get("ro.build.fingerprint")) ? 1 : 0;
        applied += setBuild("DISPLAY", DeviceProfiles.get("ro.build.display.id")) ? 1 : 0;
        applied += setBuild("ID", DeviceProfiles.get("ro.build.id")) ? 1 : 0;
        applied += setBuildVersionIncremental(DeviceProfiles.get("ro.build.version.incremental")) ? 1 : 0;
        Log.i(TAG, "Flagship identity active: " + DeviceProfiles.get("ro.product.marketname")
                + " (" + applied + " Build fields)");
    }

    private static boolean setBuild(String name, String value) {
        if (value == null) return false;
        try {
            Field field = Build.class.getField(name);
            field.setAccessible(true);
            field.set(null, value);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "Build." + name + " not spoofed", t);
            return false;
        }
    }

    private static boolean setBuildVersionIncremental(String value) {
        if (value == null) return false;
        try {
            Field field = Build.VERSION.class.getField("INCREMENTAL");
            field.setAccessible(true);
            field.set(null, value);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "Build.VERSION.INCREMENTAL not spoofed", t);
            return false;
        }
    }

    /** Native SystemProperties.native_get hook entry: null means "pass through". */
    public static String hookGet(String key) {
        return DeviceProfiles.get(key);
    }
}
