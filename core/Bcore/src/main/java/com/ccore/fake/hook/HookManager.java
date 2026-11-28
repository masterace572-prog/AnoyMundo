package com.ccore.fake.hook;

import android.util.Log;

import java.util.HashMap;
import java.util.Map;

import com.ccore.CCoreCore;
import com.ccore.fake.delegate.AppInstrumentation;
import com.ccore.fake.service.HCallbackProxy;
import com.ccore.fake.service.IAccessibilityManagerProxy;
import com.ccore.fake.service.IAccountManagerProxy;
import com.ccore.fake.service.IActivityClientProxy;
import com.ccore.fake.service.IActivityManagerProxy;
import com.ccore.fake.service.IActivityTaskManagerProxy;
import com.ccore.fake.service.IAlarmManagerProxy;
import com.ccore.fake.service.IAppOpsManagerProxy;
import com.ccore.fake.service.IAppWidgetManagerProxy;
import com.ccore.fake.service.IAutofillManagerProxy;
import com.ccore.fake.service.IConnectivityManagerProxy;
import com.ccore.fake.service.IContextHubServiceProxy;
import com.ccore.fake.service.IDeviceIdentifiersPolicyProxy;
import com.ccore.fake.service.IDevicePolicyManagerProxy;
import com.ccore.fake.service.IDisplayManagerProxy;
import com.ccore.fake.service.IFingerprintManagerProxy;
import com.ccore.fake.service.IGraphicsStatsProxy;
import com.ccore.fake.service.IJobServiceProxy;
import com.ccore.fake.service.ILauncherAppsProxy;
import com.ccore.fake.service.ILocationManagerProxy;
import com.ccore.fake.service.IMediaRouterServiceProxy;
import com.ccore.fake.service.IMediaSessionManagerProxy;
import com.ccore.fake.service.INetworkManagementServiceProxy;
import com.ccore.fake.service.INotificationManagerProxy;
import com.ccore.fake.service.IPackageManagerProxy;
import com.ccore.fake.service.IPermissionManagerProxy;
import com.ccore.fake.service.IPersistentDataBlockServiceProxy;
import com.ccore.fake.service.IPhoneSubInfoProxy;
import com.ccore.fake.service.IPowerManagerProxy;
import com.ccore.fake.service.IShortcutManagerProxy;
import com.ccore.fake.service.IStorageManagerProxy;
import com.ccore.fake.service.IStorageStatsManagerProxy;
import com.ccore.fake.service.ISystemUpdateProxy;
import com.ccore.fake.service.ITelephonyManagerProxy;
import com.ccore.fake.service.ITelephonyRegistryProxy;
import com.ccore.fake.service.IUserManagerProxy;
import com.ccore.fake.service.IVibratorServiceProxy;
import com.ccore.fake.service.IVpnManagerProxy;
import com.ccore.fake.service.IWifiManagerProxy;
import com.ccore.fake.service.IWifiScannerProxy;
import com.ccore.fake.service.IWindowManagerProxy;
import com.ccore.fake.service.context.ContentServiceStub;
import com.ccore.fake.service.context.RestrictionsManagerStub;
import com.ccore.fake.service.libcore.OsStub;
import com.ccore.utils.Slog;
import com.ccore.utils.compat.BuildCompat;

/**
 * Created by Milk on 3/30/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public class HookManager {
    public static final String TAG = "HookManager";

    private static final HookManager sHookManager = new HookManager();

    private final Map<Class<?>, IInjectHook> mInjectors = new HashMap<>();

    public static HookManager get() {
        return sHookManager;
    }

    public void init() {
        if (CCoreCore.get().isBlackProcess() || CCoreCore.get().isServerProcess()) {
            addInjector(new IDisplayManagerProxy());
            addInjector(new OsStub());
            addInjector(new IActivityManagerProxy());
            addInjector(new IPackageManagerProxy());
            addInjector(new ITelephonyManagerProxy());
            addInjector(new HCallbackProxy());
            addInjector(new IAppOpsManagerProxy());
            addInjector(new INotificationManagerProxy());
            addInjector(new IAlarmManagerProxy());
            addInjector(new IAppWidgetManagerProxy());
            addInjector(new ContentServiceStub());
            addInjector(new IWindowManagerProxy());
            addInjector(new IUserManagerProxy());
            addInjector(new RestrictionsManagerStub());
            addInjector(new IMediaSessionManagerProxy());
            addInjector(new ILocationManagerProxy());
            addInjector(new IStorageManagerProxy());
            addInjector(new ILauncherAppsProxy());
            addInjector(new IJobServiceProxy());
            addInjector(new IAccessibilityManagerProxy());
            addInjector(new ITelephonyRegistryProxy());
            addInjector(new IDevicePolicyManagerProxy());
            addInjector(new IAccountManagerProxy());
            addInjector(new IConnectivityManagerProxy());
            addInjector(new IPhoneSubInfoProxy());
            addInjector(new IMediaRouterServiceProxy());
            addInjector(new IPowerManagerProxy());
            addInjector(new IContextHubServiceProxy());
            addInjector(new IVibratorServiceProxy());
            addInjector(new IPersistentDataBlockServiceProxy());
            addInjector(AppInstrumentation.get());
            /*
            * It takes time to test and enhance the compatibility of WifiManager
            * (only tested in Android 10).
            * commented by BlackBoxing at 2022/03/08
            * */
            addInjector(new IWifiManagerProxy());
            addInjector(new IWifiScannerProxy());
            // 12.0
            if (BuildCompat.isS()) {
                addInjector(new IActivityClientProxy(null));
                addInjector(new IVpnManagerProxy());
            }
            // 11.0
            if (BuildCompat.isR()) {
                addInjector(new IPermissionManagerProxy());
            }
            // 10.0
            if (BuildCompat.isQ()) {
                addInjector(new IActivityTaskManagerProxy());
            }
            // 9.0
            if (BuildCompat.isPie()) {
                addInjector(new ISystemUpdateProxy());
            }
            // 8.0
            if (BuildCompat.isOreo()) {
                addInjector(new IAutofillManagerProxy());
                addInjector(new IDeviceIdentifiersPolicyProxy());
                addInjector(new IStorageStatsManagerProxy());
            }
            // 7.1
            if (BuildCompat.isN_MR1()) {
                addInjector(new IShortcutManagerProxy());
            }
            // 7.0
            if (BuildCompat.isN()) {
                addInjector(new INetworkManagementServiceProxy());
            }
            // 6.0
            if (BuildCompat.isM()) {
                addInjector(new IFingerprintManagerProxy());
                addInjector(new IGraphicsStatsProxy());
            }
            // 5.0
            if (BuildCompat.isL()) {
                addInjector(new IJobServiceProxy());
            }
        }
        injectAll();
    }

    public void checkEnv(Class<?> clazz) {
        IInjectHook iInjectHook = mInjectors.get(clazz);
        if (iInjectHook != null && iInjectHook.isBadEnv()) {
            Log.d(TAG, "checkEnv: " + clazz.getSimpleName() + " is bad env");
            iInjectHook.injectHook();
        }
    }

    public void checkAll() {
        for (Class<?> aClass : mInjectors.keySet()) {
            IInjectHook iInjectHook = mInjectors.get(aClass);
            if (iInjectHook != null && iInjectHook.isBadEnv()) {
                Log.d(TAG, "checkEnv: " + aClass.getSimpleName() + " is bad env");
                iInjectHook.injectHook();
            }
        }
    }

    void addInjector(IInjectHook injectHook) {
        mInjectors.put(injectHook.getClass(), injectHook);
    }

    void injectAll() {
        for (IInjectHook value : mInjectors.values()) {
            try {
                Slog.d(TAG, "hook: " + value);
                value.injectHook();
            } catch (Exception e) {
                Slog.d(TAG, "hook error: " + value);
            }
        }
    }
}
