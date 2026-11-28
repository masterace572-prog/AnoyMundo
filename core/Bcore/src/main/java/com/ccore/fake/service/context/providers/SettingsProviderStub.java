package com.ccore.fake.service.context.providers;

import android.os.Bundle;
import android.os.IInterface;

import java.lang.reflect.Method;

import black.android.content.BRAttributionSource;
import com.ccore.CCoreCore;
import com.ccore.app.BActivityThread;
import com.ccore.fake.hook.ClassInvocationStub;
import android.util.Log;
import com.ccore.utils.compat.ContextCompat;

/**
 * Created by Milk on 4/8/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public class SettingsProviderStub extends ClassInvocationStub implements BContentProvider {
    private static final String TAG = "SettingsProviderStub";
    private IInterface mBase;

    @Override
    public IInterface wrapper(IInterface contentProviderProxy, String appPkg) {
        mBase = contentProviderProxy;
        injectHook();
        return (IInterface) getProxyInvocation();
    }

    @Override
    protected Object getWho() {
        return mBase;
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {

    }

    @Override
    protected void onBindMethod() {

    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if ("asBinder".equals(method.getName())) {
            return method.invoke(mBase, args);
        }
        if (args != null && args.length > 0) {
            for (int i = 0; i < args.length; i++) {
                Object arg = args[i];
                if (arg == null) continue;
                if (arg instanceof String) {
                    if (arg.equals(BActivityThread.getAppPackageName()) || arg.equals(CCoreCore.getHostPkg())) {
                        args[i] = CCoreCore.getHostPkg();
                    }
                } else if ("android.content.AttributionSource".equals(arg.getClass().getName())) {
                    ContextCompat.fixAttributionSourceState(arg, CCoreCore.getHostUid());
                }
            }
        }
        try {
            return method.invoke(mBase, args);
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            if (cause instanceof SecurityException) {
                Log.e(TAG, "Suppressed SecurityException in SettingsProvider." + method.getName() + ": " + cause.getMessage());
                if (Bundle.class.equals(method.getReturnType())) {
                    return new Bundle();
                }
                return null;
            }
            throw cause;
        }
    }
}
