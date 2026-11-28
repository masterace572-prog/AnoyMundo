package com.ccore.fake.service;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;

import androidx.annotation.NonNull;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import black.android.app.ActivityThreadActivityClientRecordContext;
import black.android.app.BRActivityClient;
import black.android.app.BRActivityClientActivityClientControllerSingleton;
import black.android.app.BRActivityManagerNative;
import black.android.app.BRActivityThread;
import black.android.app.BRActivityThreadActivityClientRecord;
import black.android.app.BRActivityThreadCreateServiceData;
import black.android.app.BRActivityThreadH;
import black.android.app.BRIActivityManager;
import black.android.app.servertransaction.BRClientTransaction;
import black.android.app.servertransaction.BRLaunchActivityItem;
import black.android.app.servertransaction.LaunchActivityItemContext;
import black.android.os.BRHandler;
import com.ccore.CCoreCore;
import com.ccore.app.BActivityThread;
import com.ccore.fake.hook.IInjectHook;
import com.ccore.proxy.ProxyManifest;
import com.ccore.proxy.record.ProxyActivityRecord;
import com.ccore.utils.Slog;
import com.ccore.utils.compat.BuildCompat;


/**
 * Created by Milk on 3/31/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public class HCallbackProxy implements IInjectHook, Handler.Callback {
    public static final String TAG = "HCallbackStub";
    private Handler.Callback mOtherCallback;
    private AtomicBoolean mBeing = new AtomicBoolean(false);

    private Handler.Callback getHCallback() {
        return BRHandler.get(getH()).mCallback();
    }

    private Handler getH() {
        Object currentActivityThread = CCoreCore.mainThread();
        return BRActivityThread.get(currentActivityThread).mH();
    }

    @Override
    public void injectHook() {
        mOtherCallback = getHCallback();
        if (mOtherCallback != null && (mOtherCallback == this || mOtherCallback.getClass().getName().equals(this.getClass().getName()))) {
            mOtherCallback = null;
        }
        BRHandler.get(getH())._set_mCallback(this);
    }

    @Override
    public boolean isBadEnv() {
        Handler.Callback hCallback = getHCallback();
        return hCallback != null && hCallback != this;
    }

    @Override
    public boolean handleMessage(@NonNull Message msg) {
        if (!mBeing.getAndSet(true)) {
            try {
                if (BuildCompat.isPie()) {
                    Integer executeTransaction = null;
                    try {
                        executeTransaction = BRActivityThreadH.get().EXECUTE_TRANSACTION();
                    } catch (Throwable ignored) {}
                    int targetWhat = executeTransaction != null ? executeTransaction : 159;
                    if (msg.what == targetWhat) {
                        if (handleLaunchActivity(msg.obj)) {
                            getH().sendMessageAtFrontOfQueue(Message.obtain(msg));
                            return true;
                        }
                    }
                } else {
                    Integer launchActivity = null;
                    try {
                        launchActivity = BRActivityThreadH.get().LAUNCH_ACTIVITY();
                    } catch (Throwable ignored) {}
                    int targetWhat = launchActivity != null ? launchActivity : 100;
                    if (msg.what == targetWhat) {
                        if (handleLaunchActivity(msg.obj)) {
                            getH().sendMessageAtFrontOfQueue(Message.obtain(msg));
                            return true;
                        }
                    }
                }
                Integer createService = null;
                try {
                    createService = BRActivityThreadH.get().CREATE_SERVICE();
                } catch (Throwable ignored) {}
                int createServiceWhat = createService != null ? createService : 114;
                if (msg.what == createServiceWhat) {
                    return handleCreateService(msg.obj);
                }
                if (mOtherCallback != null) {
                    return mOtherCallback.handleMessage(msg);
                }
                return false;
            } finally {
                mBeing.set(false);
            }
        }
        return false;
    }

    private Object getLaunchActivityItem(Object clientTransaction) {
        List<Object> mActivityCallbacks = BRClientTransaction.get(clientTransaction).mActivityCallbacks();

        for (Object obj : mActivityCallbacks) {
            if (BRLaunchActivityItem.getRealClass().getName().equals(obj.getClass().getCanonicalName())) {
                return obj;
            }
        }
        return null;
    }

    private boolean handleLaunchActivity(Object client) {
        Object r;
        if (BuildCompat.isPie()) {
            // ClientTransaction
            r = getLaunchActivityItem(client);
        } else {
            // ActivityClientRecord
            r = client;
        }
        if (r == null)
            return false;

        Intent intent;
        IBinder token;
        if (BuildCompat.isPie()) {
            intent = BRLaunchActivityItem.get(r).mIntent();
            token = BRClientTransaction.get(client).mActivityToken();
        } else {
            ActivityThreadActivityClientRecordContext clientRecordContext = BRActivityThreadActivityClientRecord.get(r);
            intent = clientRecordContext.intent();
            token = clientRecordContext.token();
        }

        if (intent == null)
            return false;

        ProxyActivityRecord stubRecord = ProxyActivityRecord.create(intent);
        ActivityInfo activityInfo = stubRecord.mActivityInfo;
        if (activityInfo != null) {
            if (BActivityThread.getAppConfig() == null) {
                CCoreCore.getBActivityManager().restartProcess(activityInfo.packageName, activityInfo.processName, stubRecord.mUserId);

                Intent launchIntentForPackage = CCoreCore.getBPackageManager().getLaunchIntentForPackage(activityInfo.packageName, stubRecord.mUserId);
                intent.setExtrasClassLoader(this.getClass().getClassLoader());
                ProxyActivityRecord.saveStub(intent, launchIntentForPackage, stubRecord.mActivityInfo, stubRecord.mActivityRecord, stubRecord.mUserId);
                if (BuildCompat.isPie()) {
                    LaunchActivityItemContext launchActivityItemContext = BRLaunchActivityItem.get(r);
                    launchActivityItemContext._set_mIntent(intent);
                    launchActivityItemContext._set_mInfo(activityInfo);
                } else {
                    ActivityThreadActivityClientRecordContext clientRecordContext = BRActivityThreadActivityClientRecord.get(r);
                    clientRecordContext._set_intent(intent);
                    clientRecordContext._set_activityInfo(activityInfo);
                }
                return true;
            }
            // bind
            if (!BActivityThread.currentActivityThread().isInit()) {
                BActivityThread.currentActivityThread().bindApplication(activityInfo.packageName,
                        activityInfo.processName);
                return true;
            }

            int taskId = BRIActivityManager.get(BRActivityManagerNative.get().getDefault()).getTaskForActivity(token, false);
            CCoreCore.getBActivityManager().onActivityCreated(taskId, token, stubRecord.mActivityRecord);

            if (BuildCompat.isPie()) {
                try {
                    LaunchActivityItemContext launchActivityItemContext = BRLaunchActivityItem.get(r);
                    launchActivityItemContext._set_mIntent(stubRecord.mTarget);
                    launchActivityItemContext._set_mInfo(activityInfo);
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            } else {
                ActivityThreadActivityClientRecordContext clientRecordContext = BRActivityThreadActivityClientRecord.get(r);
                clientRecordContext._set_intent(stubRecord.mTarget);
                clientRecordContext._set_activityInfo(activityInfo);
            }

            if (BuildCompat.isS()) {
                try {
                    Object record = null;
                    try {
                        record = BRActivityThread.get(CCoreCore.mainThread()).getActivityClient(token);
                    } catch (Throwable ignored) {}
                    if (record == null) {
                        try {
                            record = BRActivityThread.get(CCoreCore.mainThread()).getLaunchingActivity(token);
                        } catch (Throwable ignored) {}
                    }
                    if (record != null) {
                        ActivityThreadActivityClientRecordContext clientRecordContext = BRActivityThreadActivityClientRecord.get(record);
                        clientRecordContext._set_intent(stubRecord.mTarget);
                        clientRecordContext._set_activityInfo(activityInfo);
                        clientRecordContext._set_packageInfo(BActivityThread.currentActivityThread().getPackageInfo());
                    }
                } catch (Throwable ignored) {}

                checkActivityClient();
            }
        }
        return false;
    }

    private boolean handleCreateService(Object data) {
        if (BActivityThread.getAppConfig() != null) {
            String appPackageName = BActivityThread.getAppPackageName();
            assert appPackageName != null;

            ServiceInfo serviceInfo = BRActivityThreadCreateServiceData.get(data).info();
            if (!serviceInfo.name.equals(ProxyManifest.getProxyService(BActivityThread.getAppPid()))
                    && !serviceInfo.name.equals(ProxyManifest.getProxyJobService(BActivityThread.getAppPid()))) {
                Slog.d(TAG, "handleCreateService: " + data);
                Intent intent = new Intent();
                intent.setComponent(new ComponentName(appPackageName, serviceInfo.name));
                CCoreCore.getBActivityManager().startService(intent, null, false, BActivityThread.getUserId());
                return true;
            }
        }
        return false;
    }

    private void checkActivityClient() {
        try {
            Object activityClientController = BRActivityClient.get().getActivityClientController();
            if (activityClientController != null && !(activityClientController instanceof Proxy)) {
                IActivityClientProxy iActivityClientProxy = new IActivityClientProxy(activityClientController);
                iActivityClientProxy.onlyProxy(true);
                iActivityClientProxy.injectHook();
                Object instance = BRActivityClient.get().getInstance();
                Object o = BRActivityClient.get(instance).INTERFACE_SINGLETON();
                BRActivityClientActivityClientControllerSingleton.get(o)._set_mKnownInstance(iActivityClientProxy.getProxyInvocation());
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }
}
