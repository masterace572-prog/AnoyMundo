package com.xlox.utils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.Collection;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Read-only Air extraction check. Never patches a library, game APK or user data. */
public final class AirNativeLibraries {
    // Both loader variants ship only arm64-v8a. Never accept 32-bit files instead.
    private static final String ARM64 = "lib/arm64-v8a/";
    private static final Pattern LIBRARY = Pattern.compile("lib[A-Za-z0-9_.+-]+\\.so");
    private static final int MAX_APKS = 128, MAX_LIBRARIES = 512;
    private AirNativeLibraries() {}

    /**
     * apks is Android's already-installed complete base/split set. Android owns
     * signature verification; ZIP CRCs here check copy completeness/integrity,
     * not authenticity. The pinned SDK discards copyNativeBinaries' result.
     *
     * Native files are streamed with 64 KiB memory, including libraries much
     * larger than the downloaded loader-payload limit. No persistent success
     * cache hides a subsequently truncated/corrupt copy. When Android permits
     * direct-from-APK loading, missing extracted files are legal; any extracted
     * copy that IS present must still match, since it can shadow the APK entry.
     * This check does not verify the direct-loaded APK entry's payload bytes.
     */
    public static boolean matches(Collection<String> apks, String nativeDirectory, boolean extractionRequired)
            throws IOException {
        interrupted();
        if (apks == null || apks.isEmpty() || apks.size() > MAX_APKS) return false;
        Map<String, Entry> expected = new LinkedHashMap<>();
        boolean otherAbi = false;
        for (String path : apks) {
            interrupted();
            if (path == null) return false;
            try (ZipFile zip = new ZipFile(path)) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    interrupted();
                    ZipEntry entry = entries.nextElement();
                    String full = entry.getName();
                    if (entry.isDirectory() || !full.startsWith("lib/")) continue;
                    int slash = full.lastIndexOf('/');
                    String name = full.substring(slash + 1);
                    // Match the ordinary native-library names Android extracts;
                    // unrelated assets, wrap scripts and debug files are not .so copies.
                    if (!LIBRARY.matcher(name).matches()) continue;
                    if (!full.startsWith(ARM64)) { otherAbi = true; continue; }
                    if (slash != ARM64.length() - 1 || entry.getSize() <= 0 || entry.getCrc() < 0)
                        return false;
                    Entry value = new Entry(entry.getSize(), entry.getCrc());
                    Entry prior = expected.put(name, value);
                    if (prior != null && (prior.size != value.size || prior.crc != value.crc)) return false;
                    if (expected.size() > MAX_LIBRARIES) return false;
                }
            }
        }
        // Resource/asset-only sets are legal, but a native 32-bit-only game is
        // NOT an empty native set compatible with this 64-bit container.
        if (expected.isEmpty()) return !otherAbi;
        if (nativeDirectory == null || nativeDirectory.isEmpty()) return !extractionRequired;
        File directory = new File(nativeDirectory).getCanonicalFile();
        if (!directory.exists()) return !extractionRequired;
        if (!directory.isDirectory() || !directory.canRead()) return false;
        byte[] buffer = new byte[64 * 1024];
        for (Map.Entry<String, Entry> item : expected.entrySet()) {
            interrupted();
            File file = new File(directory, item.getKey()).getCanonicalFile();
            if (!directory.equals(file.getParentFile())) return false; // No symlink/path escape reads.
            if (!file.exists() && !extractionRequired) continue;
            Entry value = item.getValue();
            if (!file.isFile() || !file.canRead() || file.length() != value.size) return false;
            long modified = file.lastModified(), size = 0;
            CRC32 crc = new CRC32();
            try (InputStream input = new FileInputStream(file)) {
                int count;
                while ((count = input.read(buffer)) != -1) {
                    interrupted();
                    if (count <= 0 || count > value.size - size) return false;
                    crc.update(buffer, 0, count);
                    size += count;
                }
            }
            if (size != value.size || crc.getValue() != value.crc || file.length() != value.size
                    || file.lastModified() != modified) return false;
        }
        return true;
    }

    private static void interrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Air preparation cancelled");
    }
    private static final class Entry {
        final long size, crc;
        Entry(long size, long crc) { this.size = size; this.crc = crc; }
    }
}
