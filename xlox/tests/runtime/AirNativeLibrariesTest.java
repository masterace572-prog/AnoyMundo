package com.xlox.tests;

import com.xlox.utils.AirNativeLibraries;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Real ZIP/file fixtures; no Android, ELF loading, original SDK or game is started. */
public final class AirNativeLibrariesTest {
    private static final String ABI = "lib/arm64-v8a/";
    private static final byte[] BASE = bytes("base-native-fixture");
    private static final byte[] SPLIT = bytes("split-UE4-native-fixture");
    private static int checks;
    private static File root, natives;

    public static void main(String[] args) throws Exception {
        root = Files.createTempDirectory("air-native-integrity-").toFile();
        natives = new File(root, "native"); require(natives.mkdir(), "fixture directory");
        try {
            ordinaryCopies();
            legalDirectMode();
            abiAndMetadata();
            pathAndFileSafety();
            boundsAndCancellation();
            largeGameLibrary();
            System.out.println("PASS Air native integrity: " + checks
                    + " assertions; ZIP/file fixtures only, 32 MiB JVM, no SDK/game runtime proof");
        } finally { remove(root.toPath()); }
    }

    private static void ordinaryCopies() throws Exception {
        List<String> apks = completeSet();
        nativeFile("libbase.so", BASE); nativeFile("libUE4.so", SPLIT);
        byte[] before = Files.readAllBytes(new File(apks.get(0)).toPath());
        long modified = new File(natives, "libUE4.so").lastModified();
        require(match(apks, true), "whole base + ABI/language/asset set matches");
        require(Arrays.equals(before, Files.readAllBytes(new File(apks.get(0)).toPath())), "source APK unchanged");
        require(new File(natives, "libUE4.so").lastModified() == modified, "native timestamp unchanged");
        require(natives.listFiles().length == 2, "verifier creates no files");

        File file = new File(natives, "libUE4.so"); byte[] wrong = SPLIT.clone(); wrong[1] ^= 1;
        nativeFile(file.getName(), wrong); require(file.setLastModified(modified), "same mtime fixture");
        require(file.length() == SPLIT.length, "same size fixture");
        require(!match(apks, true), "same-size same-mtime corruption detected by CRC");
        require(Arrays.equals(wrong, Files.readAllBytes(file.toPath())), "verification never repairs bytes itself");
        nativeFile(file.getName(), SPLIT);
        require(match(apks, true), "SDK-equivalent replacement can be verified");
        nativeFile(file.getName(), new byte[]{1});
        require(!match(apks, true), "truncation rejected");
        require(file.delete(), "remove fixture");
        require(!match(apks, true), "missing ABI split library rejected");
        require(file.mkdir(), "directory masquerading as library");
        require(!match(apks, true), "directory is not a native file");
        require(file.delete(), "remove fake directory");
        nativeFile(file.getName(), SPLIT);
        require(!AirNativeLibraries.matches(apks, null, true), "missing required directory metadata");
        require(!AirNativeLibraries.matches(apks, new File(root, "absent").getPath(), true), "missing required directory");
        require(!AirNativeLibraries.matches(apks, file.getPath(), true), "file is not a native directory");
        require(AirNativeLibraries.matches(Collections.singletonList(apks.get(0)), natives.getPath(), true),
                "single APK uses the same byte check");
    }

    private static void legalDirectMode() throws Exception {
        List<String> apks = completeSet();
        require(AirNativeLibraries.matches(apks, null, false), "direct mode needs no invented directory");
        require(AirNativeLibraries.matches(apks, new File(root, "not-extracted").getPath(), false),
                "direct mode accepts missing extraction directory");
        File file = new File(natives, "libUE4.so"); require(file.delete(), "remove optional copy");
        require(match(apks, false), "direct mode may mix present matching and absent copies");
        nativeFile(file.getName(), new byte[]{1});
        require(!match(apks, false), "bad extracted copy cannot shadow direct-loaded entry");
        nativeFile(file.getName(), SPLIT);
        require(match(apks, false), "present intact direct-mode copy is accepted");
    }

    private static void abiAndMetadata() throws Exception {
        File resources = zip("resources.apk", new String[]{"assets/libUE4.so", "res/raw/asset"},
                new byte[][]{bytes("not a packaged native library"), BASE});
        require(AirNativeLibraries.matches(Collections.singletonList(resources.getPath()), null, true),
                "resource/asset-only APK needs no native directory");
        File arm32 = zip("arm32.apk", new String[]{"lib/armeabi-v7a/libUE4.so"}, new byte[][]{SPLIT});
        require(!AirNativeLibraries.matches(Collections.singletonList(arm32.getPath()), null, true),
                "32-bit-only native game is not considered an empty arm64 set");
        require(!AirNativeLibraries.matches(Collections.singletonList(arm32.getPath()), null, false),
                "direct mode cannot make a 32-bit game compatible with arm64 host");
        List<String> fat = completeSet(); fat.add(arm32.getPath());
        require(match(fat, true), "multi-ABI APK set selects arm64 files only");
        File duplicate = zip("duplicate.apk", new String[]{ABI + "libUE4.so"}, new byte[][]{SPLIT});
        List<String> same = completeSet(); same.add(duplicate.getPath());
        require(match(same, true), "identical duplicate across APKs is unambiguous");
        zip("duplicate.apk", new String[]{ABI + "libUE4.so"}, new byte[][]{BASE});
        require(!match(same, true), "conflicting duplicate native basename rejected");
        File empty = zip("empty-native.apk", new String[]{ABI + "libempty.so"}, new byte[][]{new byte[0]});
        require(!match(Collections.singletonList(empty.getPath()), false), "empty native file rejected even in direct mode");
        File debug = zip("debug-assets.apk", new String[]{"lib/arm64-v8a/wrap.sh", "lib/arm64-v8a/gdbserver"},
                new byte[][]{BASE, BASE});
        require(AirNativeLibraries.matches(Collections.singletonList(debug.getPath()), null, true),
                "debug scripts are not ordinary lib*.so copies");
    }

    private static void pathAndFileSafety() throws Exception {
        List<String> apks = completeSet(); File link = new File(natives, "libUE4.so");
        require(link.delete(), "remove copy for symlink fixture");
        File outside = new File(root, "outside-library"); Files.write(outside.toPath(), SPLIT);
        Files.createSymbolicLink(link.toPath(), outside.toPath());
        require(!match(apks, true), "symlink outside native directory rejected");
        require(!match(apks, false), "direct mode cannot follow outside symlink either");
        require(Arrays.equals(SPLIT, Files.readAllBytes(outside.toPath())), "outside file untouched");
        require(Files.isSymbolicLink(link.toPath()), "verifier does not remove symlink");
        Files.delete(link.toPath()); nativeFile(link.getName(), SPLIT);
        File nested = zip("nested-native.apk", new String[]{ABI + "sub/libescape.so"}, new byte[][]{BASE});
        require(!match(Collections.singletonList(nested.getPath()), false), "nested native path rejected");
        File traversal = zip("traversal-native.apk", new String[]{ABI + "../libescape.so"}, new byte[][]{BASE});
        require(!match(Collections.singletonList(traversal.getPath()), false), "traversal native path rejected");
        require(!new File(root, "libescape.so").exists(), "no ZIP extraction side effects");
    }

    private static void boundsAndCancellation() throws Exception {
        List<String> apks = completeSet();
        require(!AirNativeLibraries.matches(null, natives.getPath(), true), "null APK set rejected");
        require(!match(Collections.emptyList(), false), "empty APK set rejected");
        require(!match(Collections.singletonList(null), true), "null code path rejected");
        require(!match(Collections.nCopies(129, apks.get(0)), false), "APK count bounded");
        String[] names = new String[513]; byte[][] data = new byte[names.length][];
        for (int i = 0; i < names.length; i++) { names[i] = ABI + "lib" + i + ".so"; data[i] = BASE; }
        File many = zip("too-many-libraries.apk", names, data);
        require(!match(Collections.singletonList(many.getPath()), false), "native metadata memory bounded");
        File invalid = new File(root, "invalid.apk"); Files.write(invalid.toPath(), BASE);
        try {
            match(Collections.singletonList(invalid.getPath()), true);
            throw new AssertionError("Invalid ZIP cannot pass");
        } catch (IOException expected) { checks++; }
        Thread.currentThread().interrupt();
        try {
            match(apks, true); throw new AssertionError("Cancellation cannot pass");
        } catch (InterruptedIOException expected) {
            require(Thread.currentThread().isInterrupted(), "cancellation flag preserved");
        } finally { Thread.interrupted(); }
        require(match(apks, true), "a later uncancelled attempt can verify normally");
    }

    private static void largeGameLibrary() throws Exception {
        // Game-packaged UE4 can exceed the 20 MiB limit for downloaded loader
        // payloads. This runs with -Xmx32m, so full-file buffering cannot pass.
        long length = 20L * 1024L * 1024L + 131;
        File file = new File(natives, "liblarge.so");
        byte[] buffer = new byte[64 * 1024];
        for (int i = 0; i < buffer.length; i++) buffer[i] = (byte)(i % 251);
        try (FileOutputStream out = new FileOutputStream(file)) {
            for (long left = length; left > 0; left -= Math.min(left, buffer.length))
                out.write(buffer, 0, (int)Math.min(left, buffer.length));
        }
        File apk = new File(root, "large-game-library.apk");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(apk));
             FileInputStream in = new FileInputStream(file)) {
            zip.putNextEntry(new ZipEntry(ABI + file.getName()));
            int count; while ((count = in.read(buffer)) != -1) zip.write(buffer, 0, count);
            zip.closeEntry();
        }
        require(file.length() == length, "large native fixture exceeds loader-upload limit");
        require(match(Collections.singletonList(apk.getPath()), true), "large game library checked with bounded streaming memory");
    }

    private static List<String> completeSet() throws Exception {
        File base = zip("base.apk", new String[]{ABI + "libbase.so"}, new byte[][]{BASE});
        File abi = zip("split_config.arm64_v8a.apk", new String[]{ABI + "libUE4.so"}, new byte[][]{SPLIT});
        File language = zip("split_config.en.apk", new String[]{"res/values/strings"}, new byte[][]{BASE});
        File assets = zip("split_obbassets.apk", new String[]{"assets/game.pak"}, new byte[][]{SPLIT});
        return new ArrayList<>(Arrays.asList(base.getPath(), abi.getPath(), language.getPath(), assets.getPath()));
    }
    private static File zip(String name, String[] names, byte[][] content) throws Exception {
        File file = new File(root, name);
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
            zip.putNextEntry(new ZipEntry("AndroidManifest.xml")); zip.write(BASE); zip.closeEntry();
            for (int i = 0; i < names.length; i++) {
                zip.putNextEntry(new ZipEntry(names[i])); zip.write(content[i]); zip.closeEntry();
            }
        }
        return file;
    }
    private static void nativeFile(String name, byte[] content) throws Exception {
        Files.write(new File(natives, name).toPath(), content);
    }
    private static boolean match(List<String> apks, boolean required) throws IOException {
        return AirNativeLibraries.matches(apks, natives.getPath(), required);
    }
    private static void require(boolean value, String message) {
        checks++; if (!value) throw new AssertionError(message);
    }
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static void remove(Path path) throws Exception {
        if (Files.isDirectory(path) && !Files.isSymbolicLink(path)) {
            File[] children = path.toFile().listFiles();
            if (children != null) for (File child : children) remove(child.toPath());
        }
        Files.delete(path);
    }
}
