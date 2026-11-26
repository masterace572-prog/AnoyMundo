package com.xlox.utils;

import android.content.Context;
import android.content.pm.PackageInfo;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import net_62v.external.MetaActivityManager;
import net_62v.external.MetaApplicationInstaller;
import net_62v.external.MetaPackageManager;

public final class MicroGInstaller {

    private MicroGInstaller() {
    }
    private static final String[][] MICROG_APPS = {
            {"https://github.com/microg/GmsCore/releases/download/v0.3.18.252432/com.google.android.gms-252432036.apk", GoogleServicesInstaller.GMS_PKG, "108520063", "1094fa60ace2367ac52bd08d1bd4f33fa8144ba930e355121b28234b8ebbf382", "gmscore.apk"},
            {"https://github.com/microg/GsfProxy/releases/download/v0.1.0/GsfProxy.apk", GoogleServicesInstaller.GSF_PKG, "21872", "86891b174301f06a1c84187b545a0a2a57044c6b768f3e84e865908743349692", "gsfproxy.apk"},
            {"https://github.com/microg/GmsCore/releases/download/v0.3.18.252432/com.android.vending-84022636.apk", GoogleServicesInstaller.PLAY_STORE_PKG, "4212484", "3f1bde2f72bca746fbb53b61a32c2a85fcb026f886ee96d6fe9a05de9a6c6d5b", "fakestore.apk"},
    };

    private static final String[] GOOGLE_PKGS = {
            GoogleServicesInstaller.GMS_PKG,
            GoogleServicesInstaller.GSF_PKG,
            GoogleServicesInstaller.PLAY_STORE_PKG,
    };
    public static boolean isMicroGInstalled(Context ctx) {
        try {
            PackageInfo info = MetaPackageManager.getInnerAppPackageInfo(
                    GoogleServicesInstaller.GMS_PKG, 0);
            return info != null && info.versionName != null
                    && info.versionName.startsWith("0.");
        } catch (Exception e) {
            return false;
        }
    }
    public static boolean repairInternalInstall(Context ctx) {
        try {
            if (!isMicroGInstalled(ctx)) return false;
            boolean repaired = false;
            if (!MetaPackageManager.isAppInstalledAsInternal(GoogleServicesInstaller.GMS_PKG)) {
                repaired = MetaPackageManager.fixInternalAppAndReinstall(GoogleServicesInstaller.GMS_PKG);
            }
            if (repaired) {
                MetaPackageManager.reloadAllApps();
                MetaPackageManager.waitForBackgroundScanner();
            }
            return repaired;
        } catch (Exception e) {
            return false;
        }
    }
    public interface DownloadProgress {
        void onProgress(String file, long downloaded, long total);
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



    public static String installMicroG(Context ctx, DownloadProgress progress) {
        StringBuilder out = new StringBuilder();
        for (String pkg : GOOGLE_PKGS) {
            if (!removePackage(pkg)) {
                out.append(pkg).append(" could not be removed — uninstall it from Mundo first. ");
                return "Google Software install failed: " + out.toString().trim();
            }
        }
        boolean allOk = true;
        String[] labels = {"Google Software", "Google Software", "Google Software"};
        for (int i = 0; i < MICROG_APPS.length; i++) {
            String[] app = MICROG_APPS[i];
            if (!installOneApk(ctx, app[0], app[1], Long.parseLong(app[2]), app[3], app[4], labels[i], progress, out)) allOk = false;
        }
        if (allOk) {
            repairInternalInstall(ctx);
            return "Google Software installed in Mundo. Open Google Software Settings, sign in to Google, enable device registration, then restart the game and try Google login.";
        }
        return "Google Software install incomplete: " + out.toString().trim();
    }

    public static String installStock(Context ctx) {
        for (String pkg : GOOGLE_PKGS) {
            try {
                if (MetaPackageManager.isInnerAppInstalled(pkg)) {
                    MetaPackageManager.uninstallAppFully(pkg);
                }
            } catch (Exception ignored) {
            }
        }
        return GoogleServicesInstaller.install(ctx);
    }

    public static boolean isInternalInstall(Context ctx) {
        try {
            return MetaPackageManager.isAppInstalledAsInternal(GoogleServicesInstaller.GMS_PKG);
        } catch (Exception e) {
            return false;
        }
    }
    private static boolean installOneApk(Context ctx, String urlStr,
                                         String pkg, long expectedSize, String expectedSha256,
                                         String assetName,
                                         String label, DownloadProgress progress,
                                         StringBuilder out) {
        File outFile = new File(ctx.getCacheDir(), "microg-" + pkg + ".apk");
        boolean haveGoodFile = outFile.exists() && outFile.length() == expectedSize
                && expectedSha256.equalsIgnoreCase(sha256Of(outFile));
        if (!haveGoodFile) {
            boolean assetOk = copyAssetApk(ctx, assetName, outFile, expectedSize, expectedSha256, label, progress);
            if (!assetOk) {
                if (!downloadFile(urlStr, outFile, label, progress)) {
                    out.append(pkg).append(" download failed — check internet. ");
                    return false;
                }
                if (outFile.length() != expectedSize
                        || !expectedSha256.equalsIgnoreCase(sha256Of(outFile))) {
                    out.append(pkg).append(" download corrupt — try again. ");
                    return false;
                }
            }
        }
        try {
            MetaApplicationInstaller.installAppByPath(outFile.getAbsolutePath());
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

    private static boolean copyAssetApk(Context ctx, String assetName, File outFile,
                                        long expectedSize, String expectedSha256,
                                        String label, DownloadProgress progress) {
        InputStream in = null;
        OutputStream out = null;
        try {
            in = ctx.getAssets().open("microg/" + assetName);
            out = new FileOutputStream(outFile);
            byte[] buf = new byte[65536];
            int n;
            long copied = 0;
            long lastReport = 0;
            if (progress != null) progress.onProgress(label, 0, expectedSize);
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                copied += n;
                long now = System.currentTimeMillis();
                if (progress != null && now - lastReport > 250) {
                    progress.onProgress(label, copied, expectedSize);
                    lastReport = now;
                }
            }
            out.flush();
            if (progress != null) progress.onProgress(label, copied, expectedSize);
        } catch (Exception e) {
            return false;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Exception ignored) {
            }
            try {
                if (out != null) out.close();
            } catch (Exception ignored) {
            }
        }
        return outFile.length() == expectedSize
                && expectedSha256.equalsIgnoreCase(sha256Of(outFile));
    }
    private static String sha256Of(File f) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            try (InputStream in = new FileInputStream(f)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : md.digest()) hex.append(String.format(java.util.Locale.US, "%02x", b));
            return hex.toString();
        } catch (Exception e) {
            return null;
        }
    }
    private static boolean downloadFile(String urlStr, File outFile,
                                        String label, DownloadProgress progress) {
        HttpURLConnection conn = null;
        InputStream in = null;
        OutputStream out = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(120000);
            conn.setInstanceFollowRedirects(true);
            conn.connect();
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) return false;
            long total = conn.getContentLength();
            in = conn.getInputStream();
            out = new FileOutputStream(outFile);
            byte[] buf = new byte[8192];
            int n;
            long downloaded = 0;
            long lastReport = 0;
            if (progress != null) progress.onProgress(label, 0, total);
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                downloaded += n;
                long now = System.currentTimeMillis();
                if (progress != null && now - lastReport >= 250) {
                    lastReport = now;
                    progress.onProgress(label, downloaded, total);
                }
            }
            if (progress != null) progress.onProgress(label, downloaded, total);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Exception ignored) {
            }
            try {
                if (out != null) out.close();
            } catch (Exception ignored) {
            }
            if (conn != null) conn.disconnect();
        }
    }
}
