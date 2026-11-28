package com.ccore.core;


import android.os.Process;

import androidx.annotation.Keep;

import java.io.File;
import java.util.List;

import dalvik.system.DexFile;
import com.ccore.CCoreCore;
import com.ccore.app.BActivityThread;
import com.ccore.utils.compat.DexFileCompat;

import static com.ccore.core.env.BEnvironment.EMPTY_JAR;

/**
 * Created by Milk on 4/9/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public class NativeCore {
    public static final String TAG = "NativeCore";

    static {
        new File("");
        System.loadLibrary("CBOXCORE");
    }

    public static native void init(int apiLevel);

    public static native void enableIO();

    public static native void addIORule(String targetPath, String relocatePath);

    public static native void hideXposed();

    public static native boolean disableHiddenApi();

    public static native void init_seccomp();

    private static final long CALLER_UID_CACHE_MS = 5000;
    private static volatile int sLastCallingPid = -1;
    private static volatile int sLastCallingBUid = -1;
    private static volatile long sLastCallingBUidAt = 0;

    public static void dumpDex(ClassLoader classLoader, String packageName) {
        List<Long> cookies = DexFileCompat.getCookies(classLoader);
        for (Long cookie : cookies) {
            if (cookie == 0)
                continue;
        }
    }

    @Keep
    public static int getCallingUid(int origCallingUid) {
        if ((origCallingUid > 0 && origCallingUid < 10000) || origCallingUid > 19999 || origCallingUid != CCoreCore.getHostUid()) {
            return origCallingUid;
        }
        if (android.os.Binder.getCallingPid() == Process.myPid()) {
            return Process.myUid();
        }
        String appPackageName = BActivityThread.getAppPackageName();
        if (appPackageName != null && GmsCore.isGooglePackage(appPackageName)) {
            int bUid = BActivityThread.getBUid();
            return bUid > 0 ? bUid : Process.myUid();
        }
        int callingPid = android.os.Binder.getCallingPid();
        if (callingPid > 0 && callingPid != Process.myPid()) {
            long now = android.os.SystemClock.uptimeMillis();
            if (callingPid == sLastCallingPid && sLastCallingBUid > 0 && now - sLastCallingBUidAt <= CALLER_UID_CACHE_MS) {
                return sLastCallingBUid;
            }
        }
        return BActivityThread.getCallingBUid();
    }

    @Keep
    public static String redirectPath(String path) {
        return IOCore.get().redirectPath(path);
    }

    @Keep
    public static File redirectPath(File path) {
        return IOCore.get().redirectPath(path);
    }

    @Keep
    public static long[] loadEmptyDex() {
        try {
            DexFile dexFile = new DexFile(EMPTY_JAR);
            List<Long> cookies = DexFileCompat.getCookies(dexFile);
            long[] longs = new long[cookies.size()];
            for (int i = 0; i < cookies.size(); i++) {
                longs[i] = cookies.get(i);
            }
            return longs;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return new long[]{};
    }
}
