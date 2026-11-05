package com.xlox.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Single source of truth for the downloaded loader library.
 *
 * The old code only checked {@code new File(filesDir, "loader/libbgmi.so").exists()},
 * which is why a broken / empty / nested release was treated as "loaded" while
 * {@link com.xlox.BoxApplication} logged "libbgmi.so NOT FOUND". This helper
 * validates the ELF header, resolves nested ZIP layouts and installs the
 * library at one canonical path shared by the app and the virtual process.
 */
public final class LibManager {

    public static final String TAG = "LibManager";
    public static final String PREF_NAME = "com.xlox.download";
    public static final String PREF_VERSION_KEY = "version";
    public static final String PREF_LAST_NOTIFIED = "last_notified_version";

    public static final String LOADER_DIR_NAME = "loader";
    public static final String STAGING_DIR_NAME = "loader_staging";
    public static final String LIB_NAME = "libbgmi.so";

    /** Also accept the legacy panel filename lib.so. */
    private static final String[] LIB_ALIASES = {LIB_NAME, "lib.so"};

    private static final long MIN_LIB_BYTES = 64L;
    private static final int MAX_SCAN_DEPTH = 6;

    private LibManager() {
    }

    public static File loaderDir(Context context) {
        return new File(context.getFilesDir(), LOADER_DIR_NAME);
    }

    public static File stagingDir(Context context) {
        return new File(context.getFilesDir(), STAGING_DIR_NAME);
    }

    public static File libFile(Context context) {
        return new File(loaderDir(context), LIB_NAME);
    }

    public static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public static String localVersion(Context context) {
        return prefs(context).getString(PREF_VERSION_KEY, "0.0");
    }

    /** True only when the library is present, readable and a real ELF binary. */
    public static boolean isUsableLib(Context context) {
        return isElf(libFile(context));
    }

    public static boolean isElf(File file) {
        if (file == null || !file.isFile() || !file.canRead() || file.length() < MIN_LIB_BYTES) {
            return false;
        }
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            byte[] magic = new byte[4];
            if (in.read(magic) != 4) return false;
            return (magic[0] & 0xFF) == 0x7F && magic[1] == 'E'
                    && magic[2] == 'L' && magic[3] == 'F';
        } catch (IOException e) {
            Log.w(TAG, "Cannot read " + file + ": " + e.getMessage());
            return false;
        } finally {
            closeQuietly(in);
        }
    }

    /** Recursively locate the loader library inside an extracted release ZIP. */
    public static File findLib(File root) {
        if (root == null || !root.exists()) return null;
        // 1. exact names at the ZIP root
        for (String alias : LIB_ALIASES) {
            File direct = new File(root, alias);
            if (isElf(direct)) return direct;
        }
        // 2. exact names inside a wrapping folder (e.g. Xlox/libbgmi.so)
        File named = scan(root, 0, true);
        if (named != null) return named;
        // 3. last resort: any ELF .so in the archive
        return scan(root, 0, false);
    }

    private static File scan(File dir, int depth, boolean namedOnly) {
        if (depth > MAX_SCAN_DEPTH || dir == null || !dir.isDirectory()) return null;
        File[] children = dir.listFiles();
        if (children == null) return null;
        File best = null;
        long bestSize = 0L;
        for (File child : children) {
            if (child.isDirectory()) {
                File nested = scan(child, depth + 1, namedOnly);
                if (nested != null && nested.length() > bestSize) {
                    best = nested;
                    bestSize = nested.length();
                }
            } else if (child.getName().endsWith(".so") && isElf(child)
                    && (!namedOnly || isAlias(child.getName()))
                    && child.length() > bestSize) {
                best = child;
                bestSize = child.length();
            }
        }
        return best;
    }

    private static boolean isAlias(String name) {
        for (String alias : LIB_ALIASES) if (alias.equalsIgnoreCase(name)) return true;
        return false;
    }

    /**
     * Install the extracted library (and any sibling .so dependencies) into
     * files/loader/ using a temp file + rename so a failed download can never
     * leave a half-written library behind.
     */
    public static boolean installLib(Context context, File found) {
        if (found == null || !isElf(found)) {
            Log.e(TAG, "Release ZIP did not contain a usable ELF library");
            return false;
        }
        File dir = loaderDir(context);
        if (!dir.exists() && !dir.mkdirs()) {
            Log.e(TAG, "Cannot create " + dir.getAbsolutePath());
            return false;
        }

        File parent = found.getParentFile();
        if (parent != null) {
            File[] siblings = parent.listFiles();
            if (siblings != null) {
                for (File sibling : siblings) {
                    if (!sibling.isFile() || !sibling.getName().endsWith(".so")) continue;
                    // libbgmi.so is installed below as the canonical target.
                    if (sibling.getName().equals(LIB_NAME)) continue;
                    if (!sibling.getAbsolutePath().equals(new File(dir, sibling.getName()).getAbsolutePath())) {
                        copy(sibling, new File(dir, sibling.getName()));
                    }
                }
            }
        }

        File target = libFile(context);
        if (!found.getAbsolutePath().equals(target.getAbsolutePath())) {
            if (!copy(found, target)) return false;
        }
        if (!isUsableLib(context)) {
            Log.e(TAG, "Installed library failed ELF validation at " + target);
            return false;
        }
        setPermissions(dir);
        Log.i(TAG, "Loader library ready: " + target.getAbsolutePath()
                + " (" + target.length() + " bytes)");
        return true;
    }

    private static boolean copy(File source, File target) {
        File temp = new File(target.getParentFile(), target.getName() + ".tmp");
        InputStream in = null;
        OutputStream out = null;
        try {
            in = new FileInputStream(source);
            out = new FileOutputStream(temp, false);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            out.flush();
            closeQuietly(out);
            out = null;
            if (target.exists() && !target.delete()) {
                Log.w(TAG, "Could not remove previous " + target.getName());
            }
            if (!temp.renameTo(target)) {
                Log.e(TAG, "Could not rename " + temp + " to " + target);
                return false;
            }
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Copy failed " + source + " -> " + target + ": " + e.getMessage());
            return false;
        } finally {
            closeQuietly(in);
            closeQuietly(out);
            if (temp.exists()) temp.delete();
        }
    }

    public static void setPermissions(File node) {
        if (node == null || !node.exists()) return;
        if (node.isDirectory()) {
            File[] files = node.listFiles();
            if (files != null) for (File file : files) setPermissions(file);
        } else {
            node.setReadable(true, false);
            node.setWritable(true, false);
            node.setExecutable(true, false);
        }
    }

    public static void deleteRecursive(File node) {
        if (node == null || !node.exists()) return;
        if (node.isDirectory()) {
            File[] files = node.listFiles();
            if (files != null) for (File file : files) deleteRecursive(file);
        }
        node.delete();
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (IOException ignored) {
        }
    }
}
