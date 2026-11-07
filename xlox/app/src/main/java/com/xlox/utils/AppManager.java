package com.xlox.utils;

import android.content.Context;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.util.Log;

import com.xlox.R;
import com.xlox.ui.Ui;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

import net_62v.external.MetaStorageManager;

/**
 * Copies the game's main OBB into the virtual container's storage.
 * Runs on one background thread; callbacks are delivered on the main thread.
 */
public final class AppManager {

    private static final String TAG = "AppManager";
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

    private static File containerObbDir(String pkg) throws Exception {
        return new File(MetaStorageManager.obtainAppExternalStorageDir(pkg) + "/Android/obb", pkg);
    }

    private static String obbPattern(String pkg) {
        return "^main\\.[0-9]+\\." + Pattern.quote(pkg) + "\\.obb$";
    }

    /** True when the container already holds a main OBB for {@code pkg}. */
    public boolean checkObbContainer(String pkg) {
        try {
            File dir = containerObbDir(pkg);
            File[] files = dir.listFiles();
            if (files == null) return false;
            String pattern = obbPattern(pkg);
            for (File file : files) {
                if (file.isFile() && file.length() > 0 && file.getName().matches(pattern)) return true;
            }
        } catch (Throwable t) {
            Log.w(TAG, "Container OBB check failed", t);
        }
        return false;
    }

    /**
     * True when the container already has the same main OBB the device has
     * (same name and size), so a game update triggers a fresh copy.
     */
    public boolean isObbCurrent(String pkg) {
        try {
            File sourceDir = new File(Environment.getExternalStorageDirectory(), "Android/obb/" + pkg);
            File source = findMainObb(sourceDir, pkg);
            if (source == null) return checkObbContainer(pkg);
            File target = new File(containerObbDir(pkg), source.getName());
            return target.isFile() && target.length() == source.length();
        } catch (Throwable t) {
            Log.w(TAG, "OBB freshness check failed", t);
            return false;
        }
    }

    public void copyObbAsync(String pkg, CopyCallback callback) {
        EXECUTOR.execute(() -> {
            String[] error = {null};
            boolean ok;
            try {
                ok = isObbCurrent(pkg) || copyMainObb(pkg, callback, error);
            } catch (Throwable t) {
                Log.e(TAG, "OBB copy crashed", t);
                ok = false;
                if (error[0] == null) error[0] = String.valueOf(t.getMessage());
            }
            final boolean success = ok;
            final String message = error[0];
            main.post(() -> {
                if (callback != null) callback.onCopyCompleted(success, message);
            });
        });
    }

    private boolean copyMainObb(String pkg, CopyCallback callback, String[] error) throws Exception {
        File sourceDir = new File(Environment.getExternalStorageDirectory(), "Android/obb/" + pkg);
        if (!sourceDir.isDirectory()) {
            error[0] = ctx.getString(R.string.obb_folder_not_found);
            return false;
        }
        File source = findMainObb(sourceDir, pkg);
        if (source == null) {
            error[0] = ctx.getString(R.string.main_obb_not_found);
            return false;
        }
        File destDir = containerObbDir(pkg);
        if (sourceDir.getAbsolutePath().equals(destDir.getAbsolutePath())) return true;
        if (!destDir.isDirectory() && !destDir.mkdirs()) {
            error[0] = ctx.getString(R.string.update_install_failed);
            return false;
        }
        long total = source.length();
        try {
            long free = new StatFs(destDir.getAbsolutePath()).getAvailableBytes();
            if (free < total + 32L * 1024L * 1024L) {
                error[0] = ctx.getString(R.string.update_no_space, Ui.bytes(total));
                return false;
            }
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "StatFs unavailable for " + destDir, e);
        }

        File target = new File(destDir, source.getName());
        File temp = new File(destDir, source.getName() + ".part");
        long copied = 0L;
        int lastPercent = -1;
        try (InputStream in = new FileInputStream(source);
             FileOutputStream out = new FileOutputStream(temp, false)) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                copied += read;
                int percent = total > 0 ? (int) ((copied * 100L) / total) : 100;
                if (percent != lastPercent && callback != null) {
                    lastPercent = percent;
                    final int p = percent;
                    main.post(() -> callback.onProgressUpdate(p));
                }
            }
            out.flush();
            try {
                out.getFD().sync();
            } catch (IOException ignored) {
                // Best effort.
            }
        } catch (IOException e) {
            if (temp.exists() && !temp.delete()) Log.w(TAG, "Could not delete " + temp);
            error[0] = Net.isNoSpace(e) ? ctx.getString(R.string.update_no_space, Ui.bytes(total))
                    : String.valueOf(e.getMessage());
            Log.e(TAG, "OBB copy failed", e);
            return false;
        }
        if (copied != total) {
            if (!temp.delete()) Log.w(TAG, "Could not delete " + temp);
            error[0] = ctx.getString(R.string.net_interrupted);
            return false;
        }
        if (target.exists() && !target.delete()) Log.w(TAG, "Could not replace " + target);
        if (!temp.renameTo(target)) {
            if (!temp.delete()) Log.w(TAG, "Could not delete " + temp);
            error[0] = ctx.getString(R.string.update_install_failed);
            return false;
        }
        return true;
    }

    private static File findMainObb(File sourceDir, String pkg) {
        File[] files = sourceDir.listFiles();
        if (files == null) return null;
        String pattern = obbPattern(pkg);
        File selected = null;
        long selectedVersion = -1L;
        for (File file : files) {
            if (!file.isFile() || !file.getName().matches(pattern)) continue;
            long version = -1L;
            String[] parts = file.getName().split("\\.");
            if (parts.length > 1) {
                try {
                    version = Long.parseLong(parts[1]);
                } catch (NumberFormatException ignored) {
                    // Keep -1 and fall through to the comparison.
                }
            }
            if (selected == null || version > selectedVersion) {
                selected = file;
                selectedVersion = version;
            }
        }
        return selected;
    }
}
