package com.ccore.core.system;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import com.ccore.CCoreCore;
import com.ccore.core.env.AppSystemEnv;
import com.ccore.core.env.BEnvironment;
import com.ccore.core.system.accounts.BAccountManagerService;
import com.ccore.core.system.am.BActivityManagerService;
import com.ccore.core.system.am.BJobManagerService;
import com.ccore.core.system.location.BLocationManagerService;
import com.ccore.core.system.notification.BNotificationManagerService;
import com.ccore.core.system.os.BStorageManagerService;
import com.ccore.core.system.pm.BPackageInstallerService;
import com.ccore.core.system.pm.BPackageManagerService;
import com.ccore.core.system.pm.BXposedManagerService;
import com.ccore.core.system.user.BUserHandle;
import com.ccore.core.system.user.BUserManagerService;
import com.ccore.entity.pm.InstallOption;
import com.ccore.utils.FileUtils;

import static com.ccore.core.env.BEnvironment.EMPTY_JAR;
import static com.ccore.core.env.BEnvironment.JUNIT_JAR;

/**
 * Created by Milk on 4/22/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public class BlackBoxSystem {
    private static BlackBoxSystem sBlackBoxSystem;
    private final List<ISystemService> mServices = new ArrayList<>();
    private final static AtomicBoolean isStartup = new AtomicBoolean(false);

    public static BlackBoxSystem getSystem() {
        if (sBlackBoxSystem == null) {
            synchronized (BlackBoxSystem.class) {
                if (sBlackBoxSystem == null) {
                    sBlackBoxSystem = new BlackBoxSystem();
                }
            }
        }
        return sBlackBoxSystem;
    }

    public static ClassLoader getFixedClassLoader(String str, ClassLoader classLoader) {
        return ("com.android.vending".equals(str) || "com.google.android.gms".equals(str) || "com.google.android.gsf".equals(str)) ? CCoreCore.getContext().getClassLoader() : classLoader;
    }

    public void startup() {
        if (isStartup.getAndSet(true))
            return;
        BEnvironment.load();

        mServices.add(BPackageManagerService.get());
        mServices.add(BUserManagerService.get());
        mServices.add(BActivityManagerService.get());
        mServices.add(BJobManagerService.get());
        mServices.add(BStorageManagerService.get());
        mServices.add(BPackageInstallerService.get());
        mServices.add(BXposedManagerService.get());
        mServices.add(BProcessManagerService.get());
        mServices.add(BAccountManagerService.get());
        mServices.add(BLocationManagerService.get());
        mServices.add(BNotificationManagerService.get());

        for (ISystemService service : mServices) {
            service.systemReady();
        }

        List<String> preInstallPackages = AppSystemEnv.getPreInstallPackages();
        for (String preInstallPackage : preInstallPackages) {
            try {
                if (!BPackageManagerService.get().isInstalled(preInstallPackage, BUserHandle.USER_ALL)) {
                    PackageInfo packageInfo = CCoreCore.getPackageManager().getPackageInfo(preInstallPackage, 0);
                    BPackageManagerService.get().installPackageAsUser(packageInfo.applicationInfo.sourceDir, InstallOption.installBySystem(), BUserHandle.USER_ALL);
                }
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        initJarEnv();
    }

    private void initJarEnv() {
        try {
            InputStream junit = CCoreCore.getContext().getAssets().open("junit.jar");
            FileUtils.copyFile(junit, JUNIT_JAR);

            InputStream empty = CCoreCore.getContext().getAssets().open("empty.jar");
            FileUtils.copyFile(empty, EMPTY_JAR);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
