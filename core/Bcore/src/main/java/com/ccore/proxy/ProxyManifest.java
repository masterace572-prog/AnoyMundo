package com.ccore.proxy;

import java.util.Locale;

import com.ccore.CCoreCore;

/**
 * Created by Milk on 4/1/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public class ProxyManifest {
    public static final int FREE_COUNT = 50;

    public static boolean isProxy(String msg) {
        return getBindProvider().equals(msg) || msg.contains("proxy_content_provider_");
    }

    public static String getBindProvider() {
        return CCoreCore.getHostPkg() + ".blackbox.SystemCallProvider";
    }

    public static String getProxyAuthorities(int index) {
        return String.format(Locale.CHINA, "%s.proxy_content_provider_%d", CCoreCore.getHostPkg(), index);
    }

    public static String getProxyPendingActivity(int index) {
        return String.format(Locale.CHINA, "com.ccore.proxy.ProxyPendingActivity$P%d", index);
    }

    public static String getProxyActivity(int index) {
        return String.format(Locale.CHINA, "com.ccore.proxy.ProxyActivity$P%d", index);
    }

    public static String TransparentProxyActivity(int index) {
        return String.format(Locale.CHINA, "com.ccore.proxy.TransparentProxyActivity$P%d", index);
    }

    public static String getProxyService(int index) {
        return String.format(Locale.CHINA, "com.ccore.proxy.ProxyService$P%d", index);
    }

    public static String getProxyJobService(int index) {
        return String.format(Locale.CHINA, "com.ccore.proxy.ProxyJobService$P%d", index);
    }

    public static String getProxyFileProvider() {
        return CCoreCore.getHostPkg() + ".blackbox.FileProvider";
    }

    public static String getProxyReceiver() {
        return CCoreCore.getHostPkg() + ".stub_receiver";
    }

    public static String getProcessName(int bPid) {
        return CCoreCore.getHostPkg() + ":p" + bPid;
    }
}
