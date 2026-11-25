package com.xlox.utils;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net_62v.external.MetaApplicationInstaller;
import net_62v.external.MetaPackageManager;

/**
 * Prepares an installed game as ONE package, whether it has one APK or splits.
 * Run on an I/O worker, not the UI thread. Never install just sourceDir: doing
 * so drops config/feature APKs and can cause missing resources, dex or JNI libs.
 * The original SDK's public package clone API preserves the complete installed
 * set. We do not merge/re-sign APKs, uninstall a game or clear container data.
 */
public final class GamePackagePreparer {
    public enum Status {
        READY, NOT_INSTALLED, INCOMPLETE_APKS, APK_UNREADABLE,
        PACKAGE_CHANGED, CONTAINER_UNAVAILABLE
    }

    public static final class Result {
        public final Status status;
        public final int splitCount;

        private Result(Status status, int splitCount) {
            this.status = status;
            this.splitCount = splitCount;
        }

        public boolean isReady() { return status == Status.READY; }
    }

    interface HostPackages {
        PackageInfo read(String packageName) throws PackageManager.NameNotFoundException;
    }

    interface Container {
        PackageInfo read(String packageName);
        void cloneInstalledPackage(String packageName);
    }

    private final HostPackages host;
    private final Container container;

    public GamePackagePreparer(Context context) {
        final PackageManager manager = context.getApplicationContext().getPackageManager();
        host = pkg -> manager.getPackageInfo(pkg, 0);
        container = new Container() {
            @Override public PackageInfo read(String pkg) {
                return MetaPackageManager.getPackageInfo(pkg);
            }

            @Override public void cloneInstalledPackage(String pkg) {
                // By PACKAGE, not base.apk, a split_config APK or an APK-path install.
                // Do not assume undocumented numeric result codes mean success:
                // the full package read-back below must pass before any launch.
                MetaApplicationInstaller.cloneApp(pkg);
            }
        };
    }

    // Host-Android test seam. Production always uses the original SDK above.
    GamePackagePreparer(HostPackages host, Container container) {
        this.host = host;
        this.container = container;
    }

    public Result prepare(GameTarget game) {
        if (game == null) return result(Status.NOT_INSTALLED);
        try {
            Snapshot installed = snapshot(host.read(game.packageName), game.packageName);
            Snapshot virtual = readContainer(game.packageName);
            if (!installed.matchesPackage(virtual)) {
                container.cloneInstalledPackage(game.packageName);
                virtual = readContainer(game.packageName);
            }
            // Store/installer updates can replace code paths during preparation.
            // Never combine the base from one install with splits from another.
            Snapshot latest;
            try {
                latest = snapshot(host.read(game.packageName), game.packageName);
            } catch (PackageManager.NameNotFoundException | InvalidApks e) {
                return result(Status.PACKAGE_CHANGED);
            }
            if (!installed.sameInstall(latest)) return result(Status.PACKAGE_CHANGED);
            if (!installed.matchesPackage(virtual)) return result(Status.CONTAINER_UNAVAILABLE);
            return new Result(Status.READY, installed.files.size() - 1);
        } catch (PackageManager.NameNotFoundException e) {
            return result(Status.NOT_INSTALLED);
        } catch (InvalidApks e) {
            return result(e.status);
        } catch (RuntimeException | LinkageError e) {
            // The prebuilt SDK can be unavailable/throw a linkage error. Surface a
            // controlled setup failure instead of starting a known partial game.
            return result(Status.CONTAINER_UNAVAILABLE);
        }
    }

    private static Result result(Status status) { return new Result(status, 0); }

    private Snapshot readContainer(String pkg) {
        try {
            return snapshot(container.read(pkg), pkg);
        } catch (InvalidApks | RuntimeException | LinkageError e) {
            return null;
        }
    }

    private static Snapshot snapshot(PackageInfo info, String pkg) throws InvalidApks {
        if (info == null || !pkg.equals(info.packageName) || info.applicationInfo == null) {
            throw new InvalidApks(Status.INCOMPLETE_APKS);
        }
        ApplicationInfo app = info.applicationInfo;
        if (app.packageName != null && !pkg.equals(app.packageName)) {
            throw new InvalidApks(Status.INCOMPLETE_APKS);
        }
        String[] names = info.splitNames;
        String[] paths = app.splitSourceDirs;
        int count = paths == null ? 0 : paths.length;
        if ((names == null ? 0 : names.length) != count) throw new InvalidApks(Status.INCOMPLETE_APKS);
        if (app.splitPublicSourceDirs != null && app.splitPublicSourceDirs.length != count) {
            throw new InvalidApks(Status.INCOMPLETE_APKS);
        }
        LinkedHashMap<String, FileStamp> files = new LinkedHashMap<>();
        files.put("", stamp(app.sourceDir));
        for (int i = 0; i < count; i++) {
            String name = names[i];
            if (name == null || name.isEmpty() || files.containsKey(name)) {
                throw new InvalidApks(Status.INCOMPLETE_APKS);
            }
            FileStamp split = stamp(paths[i]);
            for (FileStamp existing : files.values()) {
                if (split.path.equals(existing.path)) throw new InvalidApks(Status.INCOMPLETE_APKS);
            }
            files.put(name, split);
            if (app.splitPublicSourceDirs != null) stamp(app.splitPublicSourceDirs[i]);
        }
        long version = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode & 4294967295L;
        return new Snapshot(version, info.lastUpdateTime, files);
    }

    private static FileStamp stamp(String path) throws InvalidApks {
        if (path == null || path.isEmpty()) throw new InvalidApks(Status.INCOMPLETE_APKS);
        try {
            File file = new File(path).getCanonicalFile();
            if (!file.isFile() || !file.canRead() || file.length() <= 0) throw new InvalidApks(Status.APK_UNREADABLE);
            // PackageManager supplies the signed installed set. This lightweight
            // read is not an APK signature verifier; it catches missing, empty,
            // incomplete downloads and non-APK paths before handing them to SDK.
            try (ZipFile apk = new ZipFile(file)) {
                ZipEntry manifest = apk.getEntry("AndroidManifest.xml");
                if (manifest == null || manifest.isDirectory() || manifest.getSize() <= 0) {
                    throw new InvalidApks(Status.APK_UNREADABLE);
                }
            }
            return new FileStamp(file.getPath(), file.length(), file.lastModified());
        } catch (IOException | SecurityException e) {
            throw new InvalidApks(Status.APK_UNREADABLE);
        }
    }

    private static final class InvalidApks extends Exception {
        final Status status;
        InvalidApks(Status status) { this.status = status; }
    }

    private static final class FileStamp {
        final String path;
        final long size;
        final long modified;
        FileStamp(String path, long size, long modified) {
            this.path = path;
            this.size = size;
            this.modified = modified;
        }
    }

    private static final class Snapshot {
        final long version;
        final long updated;
        final LinkedHashMap<String, FileStamp> files;
        Snapshot(long version, long updated, LinkedHashMap<String, FileStamp> files) {
            this.version = version;
            this.updated = updated;
            this.files = files;
        }

        boolean matchesPackage(Snapshot other) {
            if (other == null || version != other.version || !files.keySet().equals(other.files.keySet())) return false;
            for (Map.Entry<String, FileStamp> file : files.entrySet()) {
                if (file.getValue().size != other.files.get(file.getKey()).size) return false;
            }
            return true;
        }

        boolean sameInstall(Snapshot other) {
            if (!matchesPackage(other) || updated != other.updated) return false;
            for (Map.Entry<String, FileStamp> file : files.entrySet()) {
                FileStamp a = file.getValue(), b = other.files.get(file.getKey());
                if (!a.path.equals(b.path) || a.modified != b.modified) return false;
            }
            return true;
        }
    }
}
