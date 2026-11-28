package com.ccore.utils.compat;

import android.content.Context;
import android.content.ContextWrapper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import black.android.app.BRContextImpl;
import black.android.app.BRContextImplKitkat;
import black.android.content.AttributionSourceStateContext;
import black.android.content.BRAttributionSource;
import black.android.content.BRAttributionSourceState;
import black.android.content.BRContentResolver;
import com.ccore.CCoreCore;
import com.ccore.app.BActivityThread;

/**
 * Created by Milk on 3/31/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public class ContextCompat {
    public static final String TAG = "ContextCompat";

    public static void fixAttributionSourceState(Object obj, int uid) {
        if (obj == null) return;
        // 1. Try BlackReflection
        try {
            if (BRAttributionSource.get(obj)._check_mAttributionSourceState() != null) {
                Object mAttributionSourceState = BRAttributionSource.get(obj).mAttributionSourceState();
                if (mAttributionSourceState != null) {
                    AttributionSourceStateContext attributionSourceStateContext = BRAttributionSourceState.get(mAttributionSourceState);
                    attributionSourceStateContext._set_packageName(CCoreCore.getHostPkg());
                    attributionSourceStateContext._set_uid(uid);
                }
                fixAttributionSourceState(BRAttributionSource.get(obj).getNext(), uid);
            }
        } catch (Throwable ignored) {
        }

        // 2. Direct deep reflection for Android 12+ AttributionSourceState
        try {
            Class<?> clazz = obj.getClass();
            Field stateField = null;
            while (clazz != null && clazz != Object.class) {
                try {
                    stateField = clazz.getDeclaredField("mAttributionSourceState");
                    stateField.setAccessible(true);
                    break;
                } catch (Throwable ignored) {
                }
                clazz = clazz.getSuperclass();
            }
            if (stateField != null) {
                Object state = stateField.get(obj);
                if (state != null) {
                    fixStateObj(state, uid);
                }
            }
            try {
                Method getNextMethod = obj.getClass().getMethod("getNext");
                Object next = getNextMethod.invoke(obj);
                if (next != null && next != obj) {
                    fixAttributionSourceState(next, uid);
                }
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
    }

    private static void fixStateObj(Object state, int uid) {
        if (state == null) return;
        try {
            Class<?> stateClass = state.getClass();
            try {
                Field pkgField = stateClass.getDeclaredField("packageName");
                pkgField.setAccessible(true);
                pkgField.set(state, CCoreCore.getHostPkg());
            } catch (Throwable ignored) {}

            try {
                Field uidField = stateClass.getDeclaredField("uid");
                uidField.setAccessible(true);
                uidField.setInt(state, uid);
            } catch (Throwable ignored) {}

            try {
                Field nextField = stateClass.getDeclaredField("next");
                nextField.setAccessible(true);
                Object nextArray = nextField.get(state);
                if (nextArray instanceof Object[]) {
                    for (Object childState : (Object[]) nextArray) {
                        if (childState != null) {
                            fixStateObj(childState, uid);
                        }
                    }
                }
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    public static void fix(Context context) {
        try {
            int deep = 0;
            while (context instanceof ContextWrapper) {
                context = ((ContextWrapper) context).getBaseContext();
                deep++;
                if (deep >= 10) {
                    return;
                }
            }
            BRContextImpl.get(context)._set_mPackageManager(null);
            try {
                context.getPackageManager();
            } catch (Throwable e) {
                e.printStackTrace();
            }

            BRContextImpl.get(context)._set_mBasePackageName(CCoreCore.getHostPkg());
            BRContextImplKitkat.get(context)._set_mOpPackageName(CCoreCore.getHostPkg());
            BRContentResolver.get(context.getContentResolver())._set_mPackageName(CCoreCore.getHostPkg());

            if (BuildCompat.isS()) {
                try {
                    Object attrSource = BRContextImpl.get(context).getAttributionSource();
                    if (attrSource != null) {
                        fixAttributionSourceState(attrSource, CCoreCore.getHostUid());
                    }
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
