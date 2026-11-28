package com.xlox.utils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Filesystem-only release selection, also exercised by credential-free JVM tests. */
public final class GameLibraryFiles {
    private static final long MIN_LIB_BYTES = 64L;
    private static final int MAX_SCAN_DEPTH = 6;

    private GameLibraryFiles() { }

    /** Same basic readable ELF-magic check as the original loader; not ABI/linker proof. */
    public static boolean isElf(File file) {
        if (file == null || !file.isFile() || !file.canRead() || file.length() < MIN_LIB_BYTES) return false;
        try (InputStream in = new FileInputStream(file)) {
            byte[] magic = new byte[4];
            return in.read(magic) == 4 && (magic[0] & 0xff) == 0x7f
                    && magic[1] == 'E' && magic[2] == 'L' && magic[3] == 'F';
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Air is exact-name only. Legacy lib.so / arbitrary-name fallback belongs
     * exclusively to BGMI and can NEVER promote libbgmilite.so to libbgmi.so.
     */
    public static File find(File root, GameTarget game) {
        if (root == null || game == null || !root.isDirectory()) return null;
        File direct = new File(root, game.libraryName);
        if (isElf(direct)) return direct;
        File named = scan(root, game, 0, game.libraryName);
        if (named != null || game != GameTarget.BGMI) return named;
        File legacy = new File(root, "lib.so");
        if (isElf(legacy)) return legacy;
        named = scan(root, game, 0, "lib.so");
        if (named != null) return named;
        // A multi-game bundle must identify its BGMI payload; a dependency
        // alongside an Air-only payload is not an anonymous BGMI replacement.
        if (scan(root, GameTarget.BGMI_AIR, 0, GameTarget.BGMI_AIR.libraryName) != null) return null;
        return scan(root, game, 0, null);
    }

    private static File scan(File dir, GameTarget game, int depth, String exactName) {
        if (depth > MAX_SCAN_DEPTH || !dir.isDirectory()) return null;
        File[] children = dir.listFiles();
        if (children == null) return null;
        File best = null;
        for (File child : children) {
            File candidate = null;
            if (child.isDirectory()) {
                candidate = scan(child, game, depth + 1, exactName);
            } else if (child.getName().endsWith(".so") && isElf(child)) {
                String name = child.getName();
                if (exactName != null ? exactName.equalsIgnoreCase(name)
                        : !GameTarget.isGameLibrary(name)) candidate = child;
            }
            if (candidate != null && (best == null || candidate.length() > best.length())) best = candidate;
        }
        return best;
    }
}
