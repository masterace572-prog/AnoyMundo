package com.xlox.utils;

import android.content.Context;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

import com.xlox.R;
import com.xlox.ui.Ui;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net_62v.external.MetaStorageManager;

/**
 * Copies the game's OBB files (newest main, plus the newest patch if there is
 * one) from the device into the virtual container's own OBB folder before a
 * launch.
 *
 * <p>Rules (a first install must always copy):
 * <ul>
 *   <li>The source main OBB must be found on the device, otherwise fail -
 *       "something is already in the container" is never enough.</li>
 *   <li>The container folder must be a different place from the device folder.
 *       If both resolve to the same path the container storage is not ready:
 *       retry a few times, then fail. Never "succeed" with zero bytes.</li>
 *   <li>A file is skipped only when the container copy is a different file
 *       with the same name and size; anything else is copied and verified.</li>
 * </ul>
 * Runs on one background thread; callbacks are delivered on the main thread.
 */
public final class AppManager {

    private static final String TAG = "AppManager";
    /** Container storage may not be set up on the very first call: retry this often. */
    private static final int CONTAINER_DIR_ATTEMPTS = 5;
    private static final long CONTAINER_DIR_RETRY_MS = 600L;
    private static final long SPACE_MARGIN = 32L * 1024L * 1024L;

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "anoy-obb");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    public interface CopyCallback {
        void onCopyCompleted(boolean success, String error);

        default void onProgressUpdate(int percent) {
        }
    }

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());

    public AppManager(Context context) {
        this.ctx = context.getApplicationContext();
    }

    // ------------------------------------------------------------ public API

    /**
     * Makes sure the container has a verified copy of the game's OBB files,
     * copying with progress where needed. {@code success} is true only after a
     * real, verified copy or a correct "already current" check.
     */
    public void copyObbAsync(String pkg, CopyCallback callback) {
        EXECUTOR.execute(() -> {
            String[] error = {null};
            boolean ok;
            try {
                ok = prepareObb(pkg, callback, error);
            } catch (Throwable t) {
                Log.e(TAG, "OBB copy crashed", t);
                ok = false;
            }
            if (!ok && TextUtils.isEmpty(error[0])) error[0] = ctx.getString(R.string.obb_copy_failed);
            final boolean success = ok;
            final String message = error[0];
            main.post(() -> {
                if (callback != null) callback.onCopyCompleted(success, message);
            });
        });
    }

    /**
     * True only when the device's newest main OBB was found and the container
     * holds a different file with the same name and size (plus the patch, if
     * the device has one). Never true when the source is missing or the
     * container folder is the device folder. Blocking; call off the main thread.
     */
    public boolean isObbCurrent(String pkg) {
        try {
            Source source = findSource(pkg, new String[1]);
            if (source == null) return false;
            File destDir = containerObbDir(pkg);
            if (destDir == null || isHostFolder(destDir, pkg)) return false;
            for (File file : source.files()) {
                if (!isCurrent(file, new File(destDir, file.getName()), destDir)) return false;
            }
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "OBB freshness check failed", t);
            return false;
        }
    }

    // ------------------------------------------------------------- the work

    private boolean prepareObb(String pkg, CopyCallback callback, String[] error) throws IOException {
        // 1. Source on the device.
        Source source = findSource(pkg, error);
        if (source == null) {
            Log.d(TAG, "No source OBB for " + pkg + ": " + error[0]);
            return false;
        }
        Log.d(TAG, "Source " + source.dir + " main=" + describe(source.mainObb)
                + (source.patchObb != null ? " patch=" + describe(source.patchObb) : " patch=none"));

        // 2. The container's own OBB folder - never the device folder.
        File destDir = readyContainerObbDir(pkg);
        if (destDir == null) {
            error[0] = ctx.getString(R.string.obb_container_not_ready);
            return false;
        }
        Log.d(TAG, "Destination " + destDir);
        if (!destDir.isDirectory() && !destDir.mkdirs() && !destDir.isDirectory()) {
            Log.w(TAG, "Could not create " + destDir);
            error[0] = ctx.getString(R.string.obb_copy_write_failed);
            return false;
        }

        // 3. Decide per file: skip only what is truly current.
        List<File> toCopy = new ArrayList<>();
        long total = 0L;
        for (File file : source.files()) {
            File dest = new File(destDir, file.getName());
            if (isCurrent(file, dest, destDir)) {
                Log.d(TAG, "Skip (current) " + file.getName() + " " + file.length() + " B -> " + dest);
            } else {
                Log.d(TAG, "Copy " + file + " (" + file.length() + " B) -> " + dest
                        + (dest.exists() ? " (replacing " + dest.length() + " B)" : ""));
                toCopy.add(file);
                total += file.length();
            }
        }
        if (toCopy.isEmpty()) {
            Log.d(TAG, "OBB already current in the container; no copy needed");
            return true;
        }

        // 4. Space for everything that has to be copied.
        try {
            long free = new StatFs(destDir.getAbsolutePath()).getAvailableBytes();
            if (free < total + SPACE_MARGIN) {
                error[0] = ctx.getString(R.string.update_no_space, Ui.bytes(total));
                return false;
            }
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "StatFs unavailable for " + destDir, e);
        }

        // 5. Copy with live percent across all files, then verify each one.
        Progress progress = new Progress(total, callback);
        progress.publish(0);
        for (File file : toCopy) {
            File dest = new File(destDir, file.getName());
            if (!copyFile(file, dest, progress, error)) return false;
            if (!isCurrent(file, dest, destDir)) {
                Log.e(TAG, "Verification failed: " + describe(file) + " vs " + describe(dest));
                error[0] = ctx.getString(R.string.obb_copy_verify_failed);
                return false;
            }
            Log.d(TAG, "Copied and verified " + dest + " (" + dest.length() + " B)");
        }
        return true;
    }

    /** Temp file, then rename. Progress is reported through {@code progress}. */
    private boolean copyFile(File source, File target, Progress progress, String[] error) {
        long expected = source.length();
        File temp = new File(target.getParentFile(), target.getName() + ".part");
        long copied = 0L;
        try (InputStream in = new FileInputStream(source);
             FileOutputStream out = new FileOutputStream(temp, false)) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                copied += read;
                progress.add(read);
            }
            out.flush();
            try {
                out.getFD().sync();
            } catch (IOException ignored) {
                // Best effort.
            }
        } catch (IOException e) {
            deleteQuietly(temp);
            error[0] = Net.isNoSpace(e) ? ctx.getString(R.string.update_no_space, Ui.bytes(expected))
                    : ctx.getString(R.string.obb_copy_write_failed);
            Log.e(TAG, "OBB copy failed: " + source + " -> " + target, e);
            return false;
        }
        if (copied != expected) {
            Log.e(TAG, "OBB copy incomplete: " + copied + " of " + expected + " B");
            deleteQuietly(temp);
            error[0] = ctx.getString(R.string.obb_copy_incomplete);
            return false;
        }
        if (target.exists() && !target.delete()) Log.w(TAG, "Could not replace " + target);
        if (!temp.renameTo(target)) {
            Log.e(TAG, "Could not rename " + temp + " -> " + target);
            deleteQuietly(temp);
            error[0] = ctx.getString(R.string.obb_copy_write_failed);
            return false;
        }
        return true;
    }

    // ---------------------------------------------------------- source side

    /** The device folders that can hold the game's OBB, in lookup order. */
    private static List<File> sourceDirs(String pkg) {
        List<File> dirs = new ArrayList<>();
        addUnique(dirs, new File("/storage/emulated/0/Android/obb", pkg));
        addUnique(dirs, new File(Environment.getExternalStorageDirectory(), "Android/obb/" + pkg));
        return dirs;
    }

    /**
     * Finds the device folder with the newest main OBB (and its newest patch).
     * On failure sets {@code error[0]} to obb_folder_not_found or
     * main_obb_not_found and returns null.
     */
    private Source findSource(String pkg, String[] error) {
        boolean anyFolder = false;
        for (File dir : sourceDirs(pkg)) {
            if (!dir.isDirectory()) continue;
            anyFolder = true;
            File mainObb = newest(dir, "main", pkg);
            if (mainObb != null) return new Source(dir, mainObb, newest(dir, "patch", pkg));
        }
        error[0] = ctx.getString(anyFolder ? R.string.main_obb_not_found : R.string.obb_folder_not_found);
        return null;
    }

    /** Newest {@code <kind>.<version>.<pkg>.obb} in {@code dir}, or null. */
    private static File newest(File dir, String kind, String pkg) {
        File[] files = dir.listFiles();
        if (files == null) return null;
        Pattern pattern = Pattern.compile("^" + kind + "\\.([0-9]+)\\." + Pattern.quote(pkg) + "\\.obb$");
        File selected = null;
        long selectedVersion = -1L;
        for (File file : files) {
            if (!file.isFile() || file.length() <= 0) continue;
            Matcher m = pattern.matcher(file.getName());
            if (!m.matches()) continue;
            long version;
            try {
                version = Long.parseLong(m.group(1));
            } catch (NumberFormatException e) {
                version = -1L;
            }
            if (selected == null || version > selectedVersion) {
                selected = file;
                selectedVersion = version;
            }
        }
        return selected;
    }

    // ------------------------------------------------------- container side

    private static File containerObbDir(String pkg) {
        String root;
        try {
            root = MetaStorageManager.obtainAppExternalStorageDir(pkg);
        } catch (Throwable t) {
            Log.w(TAG, "Container storage unavailable", t);
            return null;
        }
        if (TextUtils.isEmpty(root)) return null;
        return new File(root + "/Android/obb", pkg);
    }

    /**
     * The container's OBB folder, retried while the container storage is not
     * set up yet (no path, or a path that is really the device folder).
     */
    private static File readyContainerObbDir(String pkg) {
        for (int attempt = 1; attempt <= CONTAINER_DIR_ATTEMPTS; attempt++) {
            File dir = containerObbDir(pkg);
            if (dir != null && !isHostFolder(dir, pkg)) return dir;
            Log.w(TAG, "Container storage not ready (attempt " + attempt + "): " + dir);
            if (attempt < CONTAINER_DIR_ATTEMPTS) SystemClock.sleep(CONTAINER_DIR_RETRY_MS);
        }
        return null;
    }

    /** True when {@code dir} resolves to one of the device OBB folders. */
    private static boolean isHostFolder(File dir, String pkg) {
        File canonical = canonical(dir);
        for (File host : sourceDirs(pkg)) {
            if (canonical.equals(canonical(host))) return true;
        }
        return false;
    }

    /**
     * True when {@code dest} is a separate, complete copy of {@code source}:
     * inside the container folder, a different file, same name, same size.
     */
    private static boolean isCurrent(File source, File dest, File destDir) {
        if (source == null || !source.isFile()) return false;
        File src = canonical(source);
        File dst = canonical(dest);
        if (src.equals(dst)) return false;
        File parent = dst.getParentFile();
        if (parent == null || !parent.equals(canonical(destDir))) return false;
        return dest.isFile()
                && dest.getName().equals(source.getName())
                && dest.length() == source.length();
    }

    // ------------------------------------------------------------- helpers

    private static File canonical(File file) {
        try {
            return file.getCanonicalFile();
        } catch (IOException e) {
            return file.getAbsoluteFile();
        }
    }

    private static void addUnique(List<File> dirs, File dir) {
        File c = canonical(dir);
        for (File d : dirs) {
            if (canonical(d).equals(c)) return;
        }
        dirs.add(dir);
    }

    private static void deleteQuietly(File file) {
        if (file.exists() && !file.delete()) Log.w(TAG, "Could not delete " + file);
    }

    private static String describe(File file) {
        return file == null ? "none" : file.getAbsolutePath() + " (" + file.length() + " B)";
    }

    /** The game's OBB files found in one device folder. */
    private static final class Source {
        final File dir;
        final File mainObb;
        final File patchObb;

        Source(File dir, File mainObb, File patchObb) {
            this.dir = dir;
            this.mainObb = mainObb;
            this.patchObb = patchObb;
        }

        List<File> files() {
            List<File> list = new ArrayList<>(2);
            list.add(mainObb);
            if (patchObb != null) list.add(patchObb);
            return list;
        }
    }

    /** Whole-job percent across every file being copied, posted on change only. */
    private final class Progress {
        private final long total;
        private final CopyCallback callback;
        private long done;
        private int last = -1;

        Progress(long total, CopyCallback callback) {
            this.total = total;
            this.callback = callback;
        }

        void add(long bytes) {
            done += bytes;
            publish(total > 0 ? (int) Math.min(100L, (done * 100L) / total) : 100);
        }

        void publish(int percent) {
            if (percent == last || callback == null) return;
            last = percent;
            if (percent % 25 == 0) Log.d(TAG, "Copy progress " + percent + "%");
            main.post(() -> callback.onProgressUpdate(percent));
        }
    }
}
