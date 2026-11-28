package com.ccore.fake.service;

import android.os.IBinder;
import black.android.os.BRServiceManager;
import com.ccore.fake.hook.BinderInvocationStub;
import com.ccore.fake.hook.MethodHook;
import com.ccore.fake.hook.ProxyMethod;
import com.ccore.utils.compat.ParceledListSliceCompat;
import java.lang.reflect.Method;
import java.util.Collections;

public class IAppIntegrityManagerProxy extends BinderInvocationStub {
    public static final String SERVICE_NAME = "app_integrity";

    @ProxyMethod("getCurrentRuleSetProvider")
    public static class getCurrentRuleSetProvider extends MethodHook {
        @Override
        public Object hook(Object obj, Method method, Object[] objArr) {
            return "";
        }
    }

    @ProxyMethod("getCurrentRuleSetVersion")
    public static class getCurrentRuleSetVersion extends MethodHook {
        @Override
        public Object hook(Object obj, Method method, Object[] objArr) {
            return "";
        }
    }

    @ProxyMethod("getCurrentRules")
    public static class getCurrentRules extends MethodHook {
        @Override
        public Object hook(Object obj, Method method, Object[] objArr) {
            return ParceledListSliceCompat.create(Collections.emptyList());
        }
    }

    @ProxyMethod("getWhitelistedRuleProviders")
    public static class getWhitelistedRuleProviders extends MethodHook {
        @Override
        public Object hook(Object obj, Method method, Object[] objArr) {
            return Collections.emptyList();
        }
    }

    @ProxyMethod("updateRuleSet")
    public static class updateRuleSet extends MethodHook {
        @Override
        public Object hook(Object obj, Method method, Object[] objArr) {
            return null;
        }
    }

    public static boolean isServiceAvailable() {
        try {
            return BRServiceManager.get().getService(SERVICE_NAME) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    public IAppIntegrityManagerProxy() {
        super(BRServiceManager.get().getService(SERVICE_NAME));
    }

    @Override
    protected Object getWho() {
        try {
            Class<?> stub = Class.forName("android.content.integrity.IAppIntegrityManager$Stub");
            Method asInterface = stub.getMethod("asInterface", IBinder.class);
            return asInterface.invoke(null, BRServiceManager.get().getService(SERVICE_NAME));
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public void inject(Object obj, Object obj2) {
        replaceSystemService(SERVICE_NAME);
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }
}
