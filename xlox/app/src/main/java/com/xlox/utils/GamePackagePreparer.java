package com.xlox.utils;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net_62v.external.MetaApplicationInstaller;
import net_62v.external.MetaPackageManager;

/**
 * Prepares ONE installed package, whether it has one APK or many splits.
 * Run on an I/O worker, not the UI thread. The original SDK's non-moving
 * multi-APK installer parses the whole directory, imports every split and
 * extracts their native libraries. Its cloneApp API does NOT import that set.
 * Never merge/re-sign APKs, uninstall a game or clear container/user data.
 */
public final class GamePackagePreparer {
    public enum Status {
        READY, NOT_INSTALLED, INCOMPLETE_APKS, APK_UNREADABLE,
        PACKAGE_CHANGED, INSUFFICIENT_SPACE, CONTAINER_UNAVAILABLE
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
        void installApkSet(String directory);
        boolean hasDefaultUser(String packageName);
        void addDefaultUser(String packageName);
        boolean hasLaunchActivity(String packageName);
    }

    interface Pause { void sleep(long millis) throws InterruptedException; }

    // Copying is synchronous in the pinned SDK; component parsing and adding a
    // user are not. A PackageInfo/file match alone is NOT a launch-ready signal.
    private static final int READY_WAITS = 100;
    private static final long READY_POLL_MS = 100L;
    private final HostPackages host;
    private final Container container;
    private final File workRoot;
    private final Pause pause;

    public GamePackagePreparer(Context context) {
        final Context app = context.getApplicationContext();
        final PackageManager manager = app.getPackageManager();
        host = pkg -> manager.getPackageInfo(pkg, 0);
        workRoot = app.getCacheDir();
        pause = Thread::sleep;
        container = new Container() {
            private boolean scanned;
            @Override public PackageInfo read(String pkg) {
                // The SDK exposes a bounded (10 s) initial-scan barrier. Do not
                // race its startup scan when retaining an existing user's data.
                if (!scanned) { MetaPackageManager.waitForBackgroundScanner(); scanned = true; }
                return MetaPackageManager.getPackageInfo(pkg);
            }

            @Override public void installApkSet(String directory) {
                // Original public API, mode 1: directory containing base + ALL
                // splits. Not mode 0 (base only), mode 2 (clone/external mode),
                // nor the "AndMove" APIs which must never move installed APKs.
                // Require full read-back, not an undocumented numeric result.
                MetaApplicationInstaller.installAppsByPath(directory);
            }

            @Override public boolean hasDefaultUser(String pkg) {
                String[] users = MetaPackageManager.getInstalledUserName(pkg);
                if (users != null) for (String user : users) if ("0".equals(user)) return true;
                return false;
            }

            @Override public void addDefaultUser(String pkg) {
                // This public API ADDS a missing user asynchronously. Never
                // reset/clean an existing user or create a second clone ID.
                MetaPackageManager.createEmptyUser(pkg, "0");
            }

            @Override public boolean hasLaunchActivity(String pkg) {
                // Resolution has no launch side effect and uses the internal
                // component index filled by the SDK's asynchronous parser.
                Intent launch = MetaPackageManager.getLaunchIntent(pkg);
                return launch != null && launch.getComponent() != null
                        && pkg.equals(launch.getComponent().getPackageName());
            }
        };
    }

    // Host-Android test seams. Production always uses the original SDK above.
    GamePackagePreparer(HostPackages host, Container container, File workRoot) {
        this(host, container, workRoot, Thread::sleep);
    }

    GamePackagePreparer(HostPackages host, Container container, File workRoot, Pause pause) {
        this.host = host;
        this.container = container;
        this.workRoot = workRoot;
        this.pause = pause;
    }

    public Result prepare(GameTarget game) {
        if (game == null) return result(Status.NOT_INSTALLED);
        try {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            Snapshot installed = snapshot(host.read(game.packageName), game.packageName);
            Snapshot virtual = readContainer(game.packageName);
            boolean launchReady;
            // Use Android's installed directory only if its .apk files are
            // EXACTLY the PackageManager set. Otherwise stream a private set;
            // never send unrelated/unlisted APKs to the SDK. Keep staged files
            // until read-back/readiness finishes, not merely until API return.
            try (ApkDirectory source = installed.matchesPackage(virtual) ? null : apkDirectory(installed)) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                if (source != null) {
                    container.installApkSet(source.directory.getPath());
                    virtual = readContainer(game.packageName);
                }
                // A rejected import must not fall back to creating an external
                // clone/user for a package that was never fully imported.
                launchReady = installed.matchesPackage(virtual) && awaitLaunchReady(game.packageName);
                if (launchReady) virtual = readContainer(game.packageName);
            }
            // Store updates can replace code paths during preparation. Never
            // combine the base from one installation with splits from another.
            Snapshot latest;
            try {
                latest = snapshot(host.read(game.packageName), game.packageName);
            } catch (PackageManager.NameNotFoundException | InvalidApks e) {
                return result(Status.PACKAGE_CHANGED);
            }
            if (!installed.sameInstall(latest)) return result(Status.PACKAGE_CHANGED);
            if (!launchReady || !installed.matchesPackage(virtual)) return result(Status.CONTAINER_UNAVAILABLE);
            return new Result(Status.READY, installed.files.size() - 1);
        } catch (PackageManager.NameNotFoundException e) {
            return result(Status.NOT_INSTALLED);
        } catch (InvalidApks e) {
            return result(e.status);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return result(Status.CONTAINER_UNAVAILABLE);
        } catch (IOException | RuntimeException | LinkageError e) {
            return result(Status.CONTAINER_UNAVAILABLE);
        }
    }

    private boolean awaitLaunchReady(String pkg) throws InterruptedException {
        boolean userRequested = false;
        for (int waits = 0; waits <= READY_WAITS; waits++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            boolean userPresent = container.hasDefaultUser(pkg);
            if (!userPresent && !userRequested) {
                container.addDefaultUser(pkg);
                userRequested = true;
            }
            // Never silently launch another user's save, and never consider an
            // asynchronous create request or a numeric install result success.
            if (userPresent && container.hasLaunchActivity(pkg)) return true;
            if (waits < READY_WAITS) pause.sleep(READY_POLL_MS);
        }
        return false;
    }

    private static Result result(Status status) { return new Result(status, 0); }

    private Snapshot readContainer(String pkg) {
        try {
            return snapshot(container.read(pkg), pkg);
        } catch (InvalidApks | RuntimeException | LinkageError e) {
            return null;
        }
    }

    private ApkDirectory apkDirectory(Snapshot installed) throws InvalidApks, IOException {
        File directory = new File(installed.files.get("").path).getParentFile();
        Set<String> expected = new HashSet<>();
        boolean together = directory != null;
        for (FileStamp file : installed.files.values()) {
            expected.add(file.path);
            together &= directory != null && directory.equals(new File(file.path).getParentFile());
        }
        File[] children = together ? directory.listFiles() : null;
        Set<String> found = new HashSet<>();
        if (children != null) {
            for (File child : children) {
                if (child.getName().endsWith(".apk")) found.add(child.getCanonicalPath());
            }
            if (expected.equals(found)) return new ApkDirectory(directory, false);
        }
        if (workRoot == null || (!workRoot.isDirectory() && !workRoot.mkdirs())) throw new IOException("No staging directory");
        long total = 1024L * 1024L; // Keep a little room for directory/ZIP metadata.
        for (FileStamp file : installed.files.values()) total += file.size;
        if (workRoot.getUsableSpace() < total) throw new InvalidApks(Status.INSUFFICIENT_SPACE);
        File stage = File.createTempFile("game-apks-", "", workRoot);
        if (!stage.delete() || !stage.mkdir()) throw new IOException("Could not stage APK set");
        ApkDirectory source = new ApkDirectory(stage, true);
        try {
            byte[] buffer = new byte[64 * 1024]; // Never load a multi-GB asset APK into memory.
            int i = 0;
            for (Map.Entry<String, FileStamp> entry : installed.files.entrySet()) {
                FileStamp file = entry.getValue();
                File target = new File(stage, entry.getKey().isEmpty() ? "base.apk" : "split_" + i++ + ".apk");
                long copied = 0;
                try (InputStream in = new FileInputStream(file.path); FileOutputStream out = new FileOutputStream(target)) {
                    int count;
                    while ((count = in.read(buffer)) != -1) {
                        if (Thread.currentThread().isInterrupted() || copied + count > file.size) {
                            throw new InvalidApks(Status.PACKAGE_CHANGED);
                        }
                        out.write(buffer, 0, count);
                        copied += count;
                    }
                }
                if (copied != file.size || !file.sameContents(stamp(target.getPath()))
                        || !file.sameFile(stamp(file.path))) throw new InvalidApks(Status.PACKAGE_CHANGED);
            }
            return source;
        } catch (IOException | InvalidApks | RuntimeException | LinkageError e) {
            source.close();
            throw e;
        }
    }

    private static Snapshot snapshot(PackageInfo info, String pkg) throws InvalidApks {
        if (info == null || !pkg.equals(info.packageName) || info.applicationInfo == null) {
            throw new InvalidApks(Status.INCOMPLETE_APKS);
        }
        ApplicationInfo app = info.applicationInfo;
        if (app.packageName != null && !pkg.equals(app.packageName)) throw new InvalidApks(Status.INCOMPLETE_APKS);
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
            if (name == null || name.isEmpty() || files.containsKey(name)) throw new InvalidApks(Status.INCOMPLETE_APKS);
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
            MessageDigest directory;
            try { directory = MessageDigest.getInstance("SHA-256"); }
            catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
            // Compare entry CRCs/sizes/names, not file size alone. Reading the
            // central directory avoids hashing GBs of asset data every launch.
            // This is a completeness descriptor, NOT a new signature verifier.
            try (ZipFile apk = new ZipFile(file)) {
                ZipEntry manifest = apk.getEntry("AndroidManifest.xml");
                if (manifest == null || manifest.isDirectory() || manifest.getSize() <= 0) throw new InvalidApks(Status.APK_UNREADABLE);
                try (InputStream in = apk.getInputStream(manifest)) {
                    if (in.read() == -1) throw new InvalidApks(Status.APK_UNREADABLE);
                }
                ByteBuffer numbers = ByteBuffer.allocate(24);
                Enumeration<? extends ZipEntry> entries = apk.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    byte[] name = entry.getName().getBytes(StandardCharsets.UTF_8);
                    numbers.clear();
                    numbers.putLong(name.length).putLong(entry.getSize()).putLong(entry.getCrc());
                    directory.update(numbers.array());
                    directory.update(name);
                }
            }
            return new FileStamp(file.getPath(), file.length(), file.lastModified(), directory.digest());
        } catch (IOException | SecurityException e) {
            throw new InvalidApks(Status.APK_UNREADABLE);
        }
    }

    private static final class ApkDirectory implements Closeable {
        final File directory;
        final boolean temporary;
        ApkDirectory(File directory, boolean temporary) { this.directory = directory; this.temporary = temporary; }
        @Override public void close() {
            if (!temporary) return; // NEVER remove anything in Android's installed source directory.
            File[] children = directory.listFiles();
            if (children != null) for (File file : children) file.delete();
            directory.delete();
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
        final byte[] directoryDigest;
        FileStamp(String path, long size, long modified, byte[] directoryDigest) {
            this.path = path; this.size = size; this.modified = modified; this.directoryDigest = directoryDigest;
        }
        boolean sameContents(FileStamp other) { return size == other.size && Arrays.equals(directoryDigest, other.directoryDigest); }
        boolean sameFile(FileStamp other) { return sameContents(other) && path.equals(other.path) && modified == other.modified; }
    }

    private static final class Snapshot {
        final long version;
        final long updated;
        final LinkedHashMap<String, FileStamp> files;
        Snapshot(long version, long updated, LinkedHashMap<String, FileStamp> files) {
            this.version = version; this.updated = updated; this.files = files;
        }
        boolean matchesPackage(Snapshot other) {
            if (other == null || version != other.version || !files.keySet().equals(other.files.keySet())) return false;
            for (Map.Entry<String, FileStamp> file : files.entrySet()) {
                if (!file.getValue().sameContents(other.files.get(file.getKey()))) return false;
            }
            return true;
        }
        boolean sameInstall(Snapshot other) {
            if (!matchesPackage(other) || updated != other.updated) return false;
            for (Map.Entry<String, FileStamp> file : files.entrySet()) {
                if (!file.getValue().sameFile(other.files.get(file.getKey()))) return false;
            }
            return true;
        }
    }
}
