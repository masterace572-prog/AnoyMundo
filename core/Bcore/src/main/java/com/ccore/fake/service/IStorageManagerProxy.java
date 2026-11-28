package com.ccore.fake.service;

import android.os.IInterface;
import android.os.storage.StorageVolume;

import java.lang.reflect.Method;

import black.android.os.BRServiceManager;
import black.android.os.mount.BRIMountServiceStub;
import black.android.os.storage.BRIStorageManagerStub;
import com.ccore.CCoreCore;
import com.ccore.app.BActivityThread;
import com.ccore.fake.hook.BinderInvocationStub;
import com.ccore.fake.hook.MethodHook;
import com.ccore.fake.hook.ProxyMethod;
import com.ccore.utils.compat.BuildCompat;

/**
 * Created by Milk on 4/10/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public class IStorageManagerProxy extends BinderInvocationStub {

    public IStorageManagerProxy() {
        super(BRServiceManager.get().getService("mount"));
    }

    @Override
    protected Object getWho() {
        IInterface mount;
        if (BuildCompat.isOreo()) {
            mount = BRIStorageManagerStub.get().asInterface(BRServiceManager.get().getService("mount"));
        } else {
            mount = BRIMountServiceStub.get().asInterface(BRServiceManager.get().getService("mount"));
        }
        return mount;
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService("mount");
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @ProxyMethod("getVolumeList")
    public static class GetVolumeList extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if (args == null) {
                StorageVolume[] volumeList = CCoreCore.getBStorageManager().getVolumeList(BActivityThread.getBUid(), null, 0, BActivityThread.getUserId());
                if (volumeList == null) {
                    return method.invoke(who, args);
                }
                return volumeList;
            }
            try {
                int uid = args[0] instanceof Number ? ((Number) args[0]).intValue() : 0;
                String packageName = (String) args[1];
                int flags = args[2] instanceof Number ? ((Number) args[2]).intValue() : 0;
                StorageVolume[] volumeList = CCoreCore.getBStorageManager().getVolumeList(uid, packageName, flags, BActivityThread.getUserId());
                if (volumeList == null) {
                    return method.invoke(who, args);
                }
                return volumeList;
            } catch (Throwable t) {
                return method.invoke(who, args);
            }
        }
    }

    @ProxyMethod("mkdirs")
    public static class mkdirs extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return 0;
        }
    }
}
