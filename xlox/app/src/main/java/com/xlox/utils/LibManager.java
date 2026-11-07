package com.xlox.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;

/**
 * Single source of truth for the downloaded loader library.
 *
 * The old code only checked {@code new File(filesDir, "loader/libbgmi.so").exists()},
 * which is why a broken / empty / nested release was treated as "loaded" while
 * {@link com.xlox.BoxApplication} logged "libbgmi.so NOT FOUND". This helper
 * validates the ELF header, resolves nested ZIP layouts and installs the
 * library at one canonical path shared by the app and the virtual process.
 *
 * Android 15+ can boot with 16 KB memory pages. Every ELF file that is
 * dlopen()'d on such a device needs its PT_LOAD segments aligned to at least
 * the runtime page size. A 4 KB-aligned library is rejected by the linker with
 * "program alignment (4096) cannot be smaller than system page size (16384)".
 * This helper reads the real page size and the real ELF alignment so failures
 * are reported instead of silently looking like "the menu did not show up".
 */
public final class LibManager {

    public static final String TAG = "LibManager";
    public static final String LOAD_TAG = "Load";
    public static final String PREF_NAME = "com.xlox.download";
    public static final String PREF_VERSION_KEY = "version";
    public static final String PREF_LAST_NOTIFIED = "last_notified_version";
    public static final String PREF_SHA_KEY = "sha256";

    public static final String LOADER_DIR_NAME = "loader";
    public static final String STAGING_DIR_NAME = "loader_staging";
    public static final String LIB_NAME = "libbgmi.so";

    // A plain file, not SharedPreferences: the virtual game process writes the
    // failure and the main UI process reads it, and SharedPreferences caches
    // are per-process. A file has no cache and is visible immediately.
    private static final String ERROR_FILE = "loader_error.txt";

    public static final long PAGE_4K = 4096L;
    public static final long PAGE_16K = 16384L;

    /** Also accept the legacy panel filename lib.so. */
    private static final String[] LIB_ALIASES = {LIB_NAME, "lib.so"};

    private static final long MIN_LIB_BYTES = 64L;
    private static final int MAX_SCAN_DEPTH = 6;
    private static final int PT_LOAD = 1;

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

    public static String localSha(Context context) {
        return prefs(context).getString(PREF_SHA_KEY, "");
    }

    /** Version label for the UI, or null when no usable library is installed. */
    public static String installedVersion(Context context) {
        return isUsableLib(context) ? localVersion(context) : null;
    }

    /** Persist the installed release only after the library is verified usable. */
    @android.annotation.SuppressLint("ApplySharedPref") // called off the main thread
    public static void saveInstalled(Context context, String version, String sha256) {
        prefs(context).edit()
                .putString(PREF_VERSION_KEY, version)
                .putString(PREF_SHA_KEY, sha256 == null ? "" : sha256)
                .putString(PREF_LAST_NOTIFIED, version)
                .commit();
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
        String problem = alignmentProblem(target);
        if (problem != null) Log.w(TAG, problem);
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
            try {
                ((FileOutputStream) out).getFD().sync();
            } catch (IOException ignored) {
                // Best effort durability before the rename.
            }
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

    // ------------------------------------------------------------------
    // Android 15+ / 16 KB page-size support
    // ------------------------------------------------------------------

    /** Real kernel page size (4096 on older phones, 16384 on newer ones). */
    public static long pageSize() {
        try {
            long value = Os.sysconf(OsConstants._SC_PAGESIZE);
            return value > 0 ? value : PAGE_4K;
        } catch (Throwable t) {
            Log.w(TAG, "sysconf(_SC_PAGESIZE) unavailable: " + t);
            return PAGE_4K;
        }
    }

    /**
     * Smallest PT_LOAD p_align in an ELF file, or -1 when it cannot be read.
     * Works for both ELF32 and ELF64 little-endian binaries (all Android ABIs).
     */
    public static long minLoadAlignment(File file) {
        if (!isElf(file)) return -1L;
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(file, "r");
            byte[] header = new byte[64];
            raf.readFully(header);
            boolean is64 = (header[4] & 0xFF) == 2;
            boolean little = (header[5] & 0xFF) == 1;
            if (!little) return -1L; // Android ABIs are little-endian

            long phoff;
            int phentsize;
            int phnum;
            if (is64) {
                phoff = le64(header, 32);
                phentsize = le16(header, 54);
                phnum = le16(header, 56);
            } else {
                phoff = le32(header, 28);
                phentsize = le16(header, 42);
                phnum = le16(header, 44);
            }
            if (phoff <= 0 || phentsize < (is64 ? 56 : 32) || phnum <= 0 || phnum > 4096
                    || (long) phentsize * phnum > (1L << 20)) {
                return -1L;
            }
            byte[] table = new byte[phentsize * phnum];
            raf.seek(phoff);
            raf.readFully(table);

            long min = Long.MAX_VALUE;
            for (int i = 0; i < phnum; i++) {
                int at = i * phentsize;
                if (le32(table, at) != PT_LOAD) continue;
                long align = is64 ? le64(table, at + 48) : le32(table, at + 28);
                if (align > 0 && align < min) min = align;
            }
            return min == Long.MAX_VALUE ? -1L : min;
        } catch (Exception e) {
            Log.w(TAG, "Cannot read ELF program headers from " + file + ": " + e);
            return -1L;
        } finally {
            closeQuietly(raf);
        }
    }

    /**
     * A human-readable reason why this library cannot be dlopen()'d on the
     * current device, or null when the ELF alignment is compatible.
     */
    public static String alignmentProblem(File file) {
        if (file == null || !file.isFile()) return "loader library is missing";
        long page = pageSize();
        long align = minLoadAlignment(file);
        if (align < 0) return "loader library is not a valid ELF file";
        if (page > PAGE_4K && align < page) {
            return "This device uses " + page + "-byte memory pages but " + file.getName()
                    + " is only " + align + "-byte aligned. Rebuild the library with "
                    + "-Wl,-z,max-page-size=16384 (NDK r27+), or enable "
                    + "\"Run app with page size compat mode\" in App info > Advanced.";
        }
        return null;
    }

    /**
     * Android 17 (API 37) extends Safer Dynamic Code Loading to native files:
     * anything passed to System.load() must be read-only. The old code made the
     * downloaded library writable, which is exactly the failing case.
     */
    public static boolean makeReadOnly(File file) {
        if (file == null || !file.isFile()) return false;
        try {
            Os.chmod(file.getAbsolutePath(), 0444);
        } catch (Throwable t) {
            // Older platforms without Os.chmod still honour File.setReadOnly().
            file.setReadOnly();
        }
        boolean readOnly = !file.canWrite();
        if (!readOnly) Log.w(TAG, "Could not mark " + file.getName() + " read-only");
        return readOnly;
    }

    /** One-line dump for logcat / support tickets. */
    public static String diagnostics(Context context) {
        File lib = libFile(context);
        String abi = Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0
                ? Build.SUPPORTED_ABIS[0] : "unknown";
        return "sdk=" + Build.VERSION.SDK_INT
                + " abi=" + abi
                + " page=" + pageSize()
                + " lib=" + (lib.isFile() ? lib.length() + "B" : "missing")
                + " align=" + minLoadAlignment(lib)
                + " writable=" + (lib.isFile() && lib.canWrite())
                + " lastError=" + lastLoadError(context);
    }

    private static File errorFile(Context context) {
        return new File(context.getFilesDir(), ERROR_FILE);
    }

    public static void recordLoadSuccess(Context context, String path) {
        Log.i(TAG, "Loader load succeeded: " + path);
        clearLoadError(context);
    }

    public static void recordLoadError(Context context, String message) {
        if (message == null) message = "unknown error";
        OutputStream out = null;
        try {
            out = new FileOutputStream(errorFile(context), false);
            out.write(message.getBytes("UTF-8"));
            out.flush();
        } catch (Exception e) {
            Log.w(TAG, "Cannot persist load error: " + e);
        } finally {
            closeQuietly(out);
        }
    }

    public static String lastLoadError(Context context) {
        File file = errorFile(context);
        if (!file.isFile() || file.length() > 8192) return "";
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            byte[] buffer = new byte[(int) file.length()];
            int read = in.read(buffer);
            return read > 0 ? new String(buffer, 0, read, "UTF-8").trim() : "";
        } catch (Exception e) {
            return "";
        } finally {
            closeQuietly(in);
        }
    }

    public static void clearLoadError(Context context) {
        File file = errorFile(context);
        if (file.exists() && !file.delete()) {
            Log.w(TAG, "Could not clear " + ERROR_FILE);
        }
    }

    private static int le16(byte[] b, int at) {
        return (b[at] & 0xFF) | ((b[at + 1] & 0xFF) << 8);
    }

    private static long le32(byte[] b, int at) {
        return (b[at] & 0xFFL) | ((b[at + 1] & 0xFFL) << 8)
                | ((b[at + 2] & 0xFFL) << 16) | ((b[at + 3] & 0xFFL) << 24);
    }

    private static long le64(byte[] b, int at) {
        return le32(b, at) | (le32(b, at + 4) << 32);
    }

    // ------------------------------------------------------------------

    public static void setPermissions(File node) {
        if (node == null || !node.exists()) return;
        if (node.isDirectory()) {
            try {
                Os.chmod(node.getAbsolutePath(), 0755);
            } catch (Throwable ignored) {
            }
            File[] files = node.listFiles();
            if (files != null) for (File file : files) setPermissions(file);
        } else if (node.getName().endsWith(".so")) {
            // Native code must be read-only before System.load() on API 37.
            makeReadOnly(node);
        } else {
            node.setReadable(true, false);
            node.setWritable(true, false);
            node.setExecutable(false, false);
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
