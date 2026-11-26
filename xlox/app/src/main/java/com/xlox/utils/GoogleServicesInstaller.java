package com.xlox.utils;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import net_62v.external.MetaActivityManager;
import net_62v.external.MetaApplicationInstaller;
import net_62v.external.MetaPackageManager;

public final class GoogleServicesInstaller {

    public static final String GMS_PKG = "com.google.android.gms";
    public static final String GSF_PKG = "com.google.android.gsf";
    public static final String PLAY_STORE_PKG = "com.android.vending";
    public static final String WEBVIEW_PKG = "com.google.android.webview";

    private GoogleServicesInstaller() {
    }
    public static boolean isInstalled(Context ctx) {
        try {
            return MetaPackageManager.isInnerAppInstalled(GMS_PKG)
                    && MetaPackageManager.isInnerAppInstalled(GSF_PKG)
                    && MetaPackageManager.isInnerAppInstalled(PLAY_STORE_PKG);
        } catch (Exception e) {
            return false;
        }
    }

    /** GMS + GSF inside the container: the pair a Google sign-in actually needs. */
    public static boolean hasCoreServices(Context ctx) {
        try {
            return MetaPackageManager.isInnerAppInstalled(GMS_PKG)
                    && MetaPackageManager.isInnerAppInstalled(GSF_PKG);
        } catch (Exception e) {
            return false;
        }
    }

    /** The phone itself carries the Google apps to clone (Play Store / WebView stay optional). */
    public static boolean availableOnPhone(Context ctx) {
        try {
            ctx.getPackageManager().getPackageInfo(GMS_PKG, 0);
            ctx.getPackageManager().getPackageInfo(GSF_PKG, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        } catch (RuntimeException e) {
            return false;
        }
    }
    public static boolean isPlayStoreInstalled(Context ctx) {
        try {
            return MetaPackageManager.isInnerAppInstalled(PLAY_STORE_PKG);
        } catch (Exception e) {
            return false;
        }
    }
    public static String installFresh(Context ctx) {
        StringBuilder out = new StringBuilder();
        String[] required = {GMS_PKG, GSF_PKG};
        for (String pkg : required) {
            if (!removePackage(pkg)) {
                out.append(pkg).append(" could not be removed. ");
                return "Google Services install failed: " + out.toString().trim();
            }
        }
        boolean gms = installOne(ctx, GMS_PKG, out);
        boolean gsf = installOne(ctx, GSF_PKG, out);
        boolean store = installOneClone(ctx, PLAY_STORE_PKG, out);
        boolean webview = installOneClone(ctx, WEBVIEW_PKG, out);
        if (gms && gsf) {
            try {
                if (!MetaPackageManager.isAppInstalledAsInternal(GMS_PKG)) {
                    MetaPackageManager.fixInternalAppAndReinstall(GMS_PKG);
                }
            } catch (Exception ignored) {
            }
            try {
                MetaPackageManager.reloadAllApps();
                MetaPackageManager.waitForBackgroundScanner();
            } catch (Exception ignored) {
            }
            String extra = "";
            if (store) extra += " Play Store installed.";
            if (webview) extra += " WebView installed.";
            return "Google Services installed." + extra + " Restart the game, then try Google login.";
        }
        return "Google Services install incomplete: " + out.toString().trim();
    }

    private static boolean removePackage(String pkg) {
        try {
            if (!MetaPackageManager.isInnerAppInstalled(pkg)) return true;
        } catch (Exception e) {
            return true;
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                MetaActivityManager.killAppByPkg(pkg);
            } catch (Exception ignored) {
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {
            }
            try {
                MetaPackageManager.uninstallAppFully(pkg);
            } catch (Exception ignored) {
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {
            }
            try {
                if (!MetaPackageManager.isInnerAppInstalled(pkg)) return true;
            } catch (Exception e) {
                return true;
            }
        }
        try {
            return !MetaPackageManager.isInnerAppInstalled(pkg);
        } catch (Exception e) {
            return true;
        }
    }

    public static String install(Context ctx) {
        StringBuilder out = new StringBuilder();
        boolean gms = installOne(ctx, GMS_PKG, out);
        boolean gsf = installOne(ctx, GSF_PKG, out);
        boolean store = installOneClone(ctx, PLAY_STORE_PKG, out);
        boolean webview = installOneClone(ctx, WEBVIEW_PKG, out);
        if (gms && gsf) {
            String extra = "";
            if (store) extra += " Play Store installed.";
            if (webview) extra += " WebView installed.";
            return "Google Services installed." + extra + " Restart the game, then try Google login.";
        }
        return "Google Services install incomplete: " + out.toString().trim();
    }
    private static boolean installOneClone(Context ctx, String pkg, StringBuilder out) {
        try {
            if (MetaPackageManager.isInnerAppInstalled(pkg)) return true;
        } catch (Exception ignored) {
        }
        try {
            int rc = MetaApplicationInstaller.cloneApp(pkg);
            boolean ok = false;
            try {
                ok = MetaPackageManager.isInnerAppInstalled(pkg);
            } catch (Exception ignored) {
            }
            if (ok) return true;
            out.append(pkg).append(" clone failed (")
                    .append(MetaApplicationInstaller.convertResultToString(rc))
                    .append("), trying install. ");
        } catch (Exception e) {
            out.append(pkg).append(" clone error, trying install. ");
        }
        return installOne(ctx, pkg, out);
    }

    private static boolean installOne(Context ctx, String pkg, StringBuilder out) {
        try {
            if (MetaPackageManager.isInnerAppInstalled(pkg)) return true;
        } catch (Exception ignored) {
        }
        String apkPath;
        try {
            PackageInfo info = ctx.getPackageManager().getPackageInfo(pkg, 0);
            apkPath = info.applicationInfo.sourceDir;
        } catch (PackageManager.NameNotFoundException e) {
            out.append(pkg).append(" not on this phone. ");
            return false;
        } catch (Exception e) {
            out.append(pkg).append(" error. ");
            return false;
        }
        try {
            MetaApplicationInstaller.installAppByPath(apkPath);
        } catch (Exception e) {
            out.append(pkg).append(" install failed. ");
            return false;
        }
        boolean ok = false;
        try {
            ok = MetaPackageManager.isInnerAppInstalled(pkg);
        } catch (Exception ignored) {
        }
        if (!ok) out.append(pkg).append(" install failed. ");
        return ok;
    }
}
