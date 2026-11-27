package com.xlox.utils;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

import net_62v.external.MetaActivityManager;
import net_62v.external.MetaApplicationInstaller;
import net_62v.external.MetaPackageManager;

public final class GoogleServicesInstaller {

    public static final String GMS_PKG = "com.google.android.gms";
    public static final String GSF_PKG = "com.google.android.gsf";
    public static final String PLAY_STORE_PKG = "com.android.vending";
    public static final String WEBVIEW_PKG = "com.google.android.webview";
    // BGMI's Google button signs in through Play Games on top of GMS.
    public static final String PLAY_GAMES_PKG = "com.google.android.play.games";

    private GoogleServicesInstaller() {
    }
    public static boolean isInstalled(Context ctx) {
        try {
            return MetaPackageManager.isInnerAppInstalled(GMS_PKG)
                    && MetaPackageManager.isInnerAppInstalled(GSF_PKG)
                    && MetaPackageManager.isInnerAppInstalled(PLAY_STORE_PKG);
        } catch (Throwable e) {
            return false;
        }
    }

    /** GMS + GSF inside the container: the pair a Google sign-in actually needs. */
    public static boolean hasCoreServices(Context ctx) {
        try {
            return MetaPackageManager.isInnerAppInstalled(GMS_PKG)
                    && MetaPackageManager.isInnerAppInstalled(GSF_PKG);
        } catch (Throwable e) {
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
        } catch (Throwable e) {
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
        boolean gms = installOne(ctx, GMS_PKG, true, out);
        boolean gsf = installOne(ctx, GSF_PKG, out);
        boolean store = installOneClone(ctx, PLAY_STORE_PKG, out);
        boolean games = installOneClone(ctx, PLAY_GAMES_PKG, out);
        boolean webview = installOneClone(ctx, WEBVIEW_PKG, out);
        if (gms && gsf) {
            reloadRegistry();
            String extra = "";
            if (store) extra += " Play Store installed.";
            if (games) extra += " Play Games installed.";
            if (webview) extra += " WebView installed.";
            String summary = installedSummary();
            return "Google Services installed" + (summary.isEmpty() ? "" : " (" + summary + ")") + "."
                    + extra + " Restart the game, then try Google login."
                    + " If the game still shows no account picker, open the Play Store inside Mundo once,"
                    + " sign in to Google there, and try again.";
        }
        return "Google Services install incomplete: " + out.toString().trim();
    }

    /** GMS must be an internal container app, or the game's sign-in intent
     *  finds no Google activity and silently dies. */
    private static void registerInternal(String pkg) {
        try {
            if (!MetaPackageManager.isAppInstalledAsInternal(pkg)) {
                MetaPackageManager.fixInternalAppAndReinstall(pkg);
            }
        } catch (Throwable ignored) {
        }
    }

    /** The container can drop GMS's internal registration across launches —
     *  working loaders re-assert it on every app start, before the game runs.
     *  Safe no-op when stock services are absent or already registered. */
    public static void repairInternalInstall(Context ctx) {
        try {
            if (!hasCoreServices(ctx)) return;
            registerInternal(GMS_PKG);
            reloadRegistry();
        } catch (Throwable ignored) {
        }
    }

    /** The container only wires GMS's activities/services after a registry reload;
     *  without it the game's Google button spins for a second and does nothing. */
    private static void reloadRegistry() {
        try {
            MetaPackageManager.reloadAllApps();
            MetaPackageManager.waitForBackgroundScanner();
        } catch (Throwable ignored) {
        }
    }

    private static boolean removePackage(String pkg) {
        try {
            if (!MetaPackageManager.isInnerAppInstalled(pkg)) return true;
        } catch (Throwable e) {
            return true;
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                MetaActivityManager.killAppByPkg(pkg);
            } catch (Throwable ignored) {
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {
            }
            try {
                MetaPackageManager.uninstallAppFully(pkg);
            } catch (Throwable ignored) {
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {
            }
            try {
                if (!MetaPackageManager.isInnerAppInstalled(pkg)) return true;
            } catch (Throwable e) {
                return true;
            }
        }
        try {
            return !MetaPackageManager.isInnerAppInstalled(pkg);
        } catch (Throwable e) {
            return true;
        }
    }

    public static String install(Context ctx) {
        StringBuilder out = new StringBuilder();
        boolean gms = installOne(ctx, GMS_PKG, true, out);
        boolean gsf = installOne(ctx, GSF_PKG, out);
        boolean store = installOneClone(ctx, PLAY_STORE_PKG, out);
        boolean games = installOneClone(ctx, PLAY_GAMES_PKG, out);
        boolean webview = installOneClone(ctx, WEBVIEW_PKG, out);
        if (gms && gsf) {
            // The microG->stock switch lands here: GMS must be registered as an
            // internal container app and the registry reloaded, or the game's
            // sign-in intent finds no Google activity and dies silently.
            reloadRegistry();
            String extra = "";
            if (store) extra += " Play Store installed.";
            if (games) extra += " Play Games installed.";
            if (webview) extra += " WebView installed.";
            String summary = installedSummary();
            return "Google Services installed" + (summary.isEmpty() ? "" : " (" + summary + ")") + "."
                    + extra + " Restart the game, then try Google login."
                    + " If the game still shows no account picker, open the Play Store inside Mundo once,"
                    + " sign in to Google there, and try again.";
        }
        return "Google Services install incomplete: " + out.toString().trim();
    }
    private static boolean installOneClone(Context ctx, String pkg, StringBuilder out) {
        try {
            if (MetaPackageManager.isInnerAppInstalled(pkg)) return true;
        } catch (Throwable ignored) {
        }
        try {
            int rc = MetaApplicationInstaller.cloneApp(pkg);
            boolean ok = false;
            try {
                ok = MetaPackageManager.isInnerAppInstalled(pkg);
            } catch (Throwable ignored) {
            }
            if (ok) return true;
            out.append(pkg).append(" clone failed (")
                    .append(MetaApplicationInstaller.convertResultToString(rc))
                    .append("), trying install. ");
        } catch (Throwable e) {
            out.append(pkg).append(" clone error, trying install. ");
        }
        return installOne(ctx, pkg, out);
    }

    private static boolean installOne(Context ctx, String pkg, StringBuilder out) {
        return installOne(ctx, pkg, false, out);
    }

    private static boolean installOne(Context ctx, String pkg, boolean registerInternal, StringBuilder out) {
        try {
            if (MetaPackageManager.isInnerAppInstalled(pkg)) {
                if (registerInternal) registerInternal(pkg);
                return true;
            }
        } catch (Throwable ignored) {
        }
        String apkPath;
        String[] splits;
        try {
            PackageInfo info = ctx.getPackageManager().getPackageInfo(pkg, 0);
            apkPath = info.applicationInfo.sourceDir;
            splits = info.applicationInfo.splitSourceDirs;
        } catch (PackageManager.NameNotFoundException e) {
            out.append(pkg).append(" not on this phone. ");
            return false;
        } catch (Throwable e) {
            out.append(pkg).append(" error. ");
            return false;
        }
        File dir = null;
        int rc = 0;
        try {
            if (splits != null && splits.length > 0) {
                // Modern GMS/Play Store ship as split APKs. Installing base.apk
                // alone leaves a broken package that signs in to nothing, so
                // copy base + splits into a scratch dir (the /data/app dir is
                // not listable) and hand the whole set to the container.
                dir = new File(ctx.getCacheDir(), "clone-" + pkg);
                if (!stageApkSet(dir, apkPath, splits)) {
                    out.append(pkg).append(" copy failed — free up storage and retry. ");
                    return false;
                }
                rc = MetaApplicationInstaller.installAppsByPath(dir.getAbsolutePath());
            } else {
                rc = MetaApplicationInstaller.installAppByPath(apkPath);
            }
            // Register while the staged files still exist: the fix-up reinstall
            // reads them again.
            if (registerInternal) registerInternal(pkg);
        } catch (Throwable e) {
            out.append(pkg).append(" install failed. ");
            return false;
        } finally {
            if (dir != null) deleteDir(dir);
        }
        boolean ok = false;
        try {
            ok = MetaPackageManager.isInnerAppInstalled(pkg);
        } catch (Throwable ignored) {
        }
        if (!ok) {
            out.append(pkg).append(" install failed");
            try {
                out.append(" (").append(MetaApplicationInstaller.convertResultToString(rc)).append(")");
            } catch (Throwable ignored) {
            }
            out.append(". ");
        }
        return ok;
    }

    /** Copies base.apk and every split into {@code dir}; false when any part fails. */
    private static boolean stageApkSet(File dir, String base, String[] splits) {
        deleteDir(dir);
        if (!dir.mkdirs()) return false;
        if (!copyFile(new File(base), new File(dir, new File(base).getName()))) return false;
        for (String split : splits) {
            if (!copyFile(new File(split), new File(dir, new File(split).getName()))) return false;
        }
        return true;
    }

    private static boolean copyFile(File from, File to) {
        InputStream in = null;
        OutputStream out = null;
        try {
            in = new FileInputStream(from);
            out = new FileOutputStream(to);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
            return true;
        } catch (Throwable e) {
            return false;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Throwable ignored) {
            }
            try {
                if (out != null) out.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void deleteDir(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) deleteDir(f); else f.delete();
            }
        }
        dir.delete();
    }

    /** "gms 25.41.31, gsf 10" — what the container actually ended up with, for support reports. */
    public static String installedSummary() {
        StringBuilder s = new StringBuilder();
        appendPkgVersion(s, "gms", GMS_PKG);
        appendPkgVersion(s, "gsf", GSF_PKG);
        appendPkgVersion(s, "store", PLAY_STORE_PKG);
        appendPkgVersion(s, "games", PLAY_GAMES_PKG);
        return s.toString();
    }

    private static void appendPkgVersion(StringBuilder s, String label, String pkg) {
        try {
            PackageInfo info = MetaPackageManager.getInnerAppPackageInfo(pkg, 0);
            if (info != null && info.versionName != null) {
                if (s.length() > 0) s.append(", ");
                s.append(label).append(' ').append(info.versionName);
                try {
                    s.append(MetaPackageManager.isAppInstalledAsInternal(pkg)
                            ? " [internal]" : " [not internal]");
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }
}
