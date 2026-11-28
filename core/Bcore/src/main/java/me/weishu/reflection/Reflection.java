package me.weishu.reflection;

import android.content.Context;
import java.lang.reflect.Method;

/**
 * Native Java reflection bypass unsealing hidden Android APIs.
 * Embeds FreeReflection directly into Bcore to eliminate deprecated JCenter dependency.
 */
public class Reflection {
    private static final String TAG = "Reflection";

    public static int unseal(Context context) {
        if (android.os.Build.VERSION.SDK_INT < 28) {
            return 0;
        }
        try {
            Method forNameMethod = Class.class.getDeclaredMethod("forName", String.class);
            Method getDeclaredMethodMethod = Class.class.getDeclaredMethod("getDeclaredMethod", String.class, Class[].class);

            Class<?> vmRuntimeClass = (Class<?>) forNameMethod.invoke(null, "dalvik.system.VMRuntime");
            Method getRuntimeMethod = (Method) getDeclaredMethodMethod.invoke(vmRuntimeClass, "getRuntime", new Class[0]);
            Method setHiddenApiExemptionsMethod = (Method) getDeclaredMethodMethod.invoke(vmRuntimeClass, "setHiddenApiExemptions", new Class[]{String[].class});

            Object vmRuntime = getRuntimeMethod.invoke(null);
            setHiddenApiExemptionsMethod.invoke(vmRuntime, new Object[]{new String[]{"L"}});
            android.util.Log.i(TAG, "Hidden API restrictions unsealed successfully");
            return 0;
        } catch (Throwable e) {
            android.util.Log.w(TAG, "Failed to unseal hidden API restrictions: " + e.getMessage());
            return -1;
        }
    }
}
