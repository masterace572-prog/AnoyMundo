package com.xlox.utils;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Real temporary APK-shaped ZIPs + Android PackageInfo, without starting a game/SDK. */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = {21, 28})
public class GamePackagePreparerTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final GameTarget GAME = GameTarget.BGMI_AIR;
    private PackageInfo installed;
    private PackageInfo virtual;
    private int installs;
    private File work;
    private String lastInput;
    private boolean removed;
    private Runnable onInstall;
    private GamePackagePreparer preparer;

    @Before
    public void create() throws Exception {
        installed = new PackageInfo();
        installed.packageName = GAME.packageName;
        installed.versionCode = 123;
        installed.lastUpdateTime = 98765L;
        installed.applicationInfo = new ApplicationInfo();
        installed.applicationInfo.packageName = GAME.packageName;
        installed.applicationInfo.sourceDir = apk("base.apk").getPath();
        installed.applicationInfo.publicSourceDir = installed.applicationInfo.sourceDir;
        installed.splitNames = new String[]{"config.arm64_v8a", "config.en", "feature.assets"};
        installed.applicationInfo.splitSourceDirs = new String[]{
                apk("split_config.arm64_v8a.apk").getPath(), apk("split_config.en.apk").getPath(),
                apk("split_feature.assets.apk").getPath()};
        installed.applicationInfo.splitPublicSourceDirs = installed.applicationInfo.splitSourceDirs.clone();
        work = temporary.newFolder("work");
        onInstall = () -> virtual = copy(installed);
        preparer = new GamePackagePreparer(pkg -> {
            assertEquals(GAME.packageName, pkg);
            if (removed) throw new PackageManager.NameNotFoundException(pkg);
            return installed;
        }, new GamePackagePreparer.Container() {
            @Override public PackageInfo read(String pkg) { assertEquals(GAME.packageName, pkg); return virtual; }
            @Override public void installApkSet(String directory) {
                lastInput = directory;
                installs++;
                onInstall.run();
            }
        }, work);
    }

    private File apk(String name) throws Exception { return apk(name, "manifest-fixture"); }

    private File apk(String name, String manifest) throws Exception {
        File file = new File(temporary.getRoot(), name);
        assertTrue(file.getParentFile().isDirectory() || file.getParentFile().mkdirs());
        byte[] content = manifest.getBytes(StandardCharsets.UTF_8);
        CRC32 crc = new CRC32(); crc.update(content);
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
            ZipEntry entry = new ZipEntry("AndroidManifest.xml");
            entry.setMethod(ZipEntry.STORED); entry.setSize(content.length); entry.setCrc(crc.getValue()); entry.setTime(0L);
            zip.putNextEntry(entry); zip.write(content); zip.closeEntry();
        }
        return file;
    }

    private byte[] bytes(File file) {
        try { return Files.readAllBytes(file.toPath()); }
        catch (Exception e) { throw new AssertionError(e); }
    }

    private void assertStagedSet() {
        File input = new File(lastInput);
        assertNotEquals(temporary.getRoot(), input);
        assertEquals(work, input.getParentFile());
        assertEquals(4, input.listFiles().length);
        assertArrayEquals(bytes(new File(installed.applicationInfo.sourceDir)), bytes(new File(input, "base.apk")));
        for (int i = 0; i < installed.applicationInfo.splitSourceDirs.length; i++) {
            assertArrayEquals(bytes(new File(installed.applicationInfo.splitSourceDirs[i])), bytes(new File(input, "split_" + i + ".apk")));
        }
        virtual = copy(installed);
    }

    private static PackageInfo copy(PackageInfo original) {
        PackageInfo info = new PackageInfo();
        info.packageName = original.packageName;
        info.versionCode = original.versionCode;
        info.lastUpdateTime = original.lastUpdateTime;
        info.splitNames = original.splitNames == null ? null : original.splitNames.clone();
        info.applicationInfo = new ApplicationInfo(original.applicationInfo);
        info.applicationInfo.splitSourceDirs = original.applicationInfo.splitSourceDirs == null
                ? null : original.applicationInfo.splitSourceDirs.clone();
        info.applicationInfo.splitPublicSourceDirs = original.applicationInfo.splitPublicSourceDirs == null
                ? null : original.applicationInfo.splitPublicSourceDirs.clone();
        return info;
    }

    private void expect(GamePackagePreparer.Status status) { assertEquals(status, preparer.prepare(GAME).status); }

    @Test public void singleApkUsesTheSameWholePackagePath() {
        installed.splitNames = null;
        installed.applicationInfo.splitSourceDirs = null;
        installed.applicationInfo.splitPublicSourceDirs = null;
        GamePackagePreparer.Result result = preparer.prepare(GAME);
        assertTrue(result.isReady()); assertEquals(0, result.splitCount); assertEquals(1, installs);
    }

    @Test public void installsBaseAndEveryAbiLanguageAndAssetSplitTogether() {
        GamePackagePreparer.Result result = preparer.prepare(GAME);
        assertTrue(result.isReady()); assertEquals(3, result.splitCount); assertEquals(1, installs);
        assertArrayEquals(installed.splitNames, virtual.splitNames);
        assertArrayEquals(installed.applicationInfo.splitSourceDirs, virtual.applicationInfo.splitSourceDirs);
    }

    @Test public void alreadyCompletePackageDoesNotReinstallOrMutateItsPaths() {
        virtual = copy(installed);
        String[] paths = virtual.applicationInfo.splitSourceDirs.clone();
        expect(GamePackagePreparer.Status.READY);
        assertEquals(0, installs); assertArrayEquals(paths, virtual.applicationInfo.splitSourceDirs);
    }

    @Test public void staleBaseOnlyContainerIsRepairedWithAllSplits() {
        virtual = copy(installed); virtual.splitNames = null;
        virtual.applicationInfo.splitSourceDirs = null; virtual.applicationInfo.splitPublicSourceDirs = null;
        expect(GamePackagePreparer.Status.READY);
        assertEquals(1, installs); assertEquals(3, virtual.applicationInfo.splitSourceDirs.length);
    }

    @Test public void missingContainerSplitTriggersWholePackageRepair() {
        virtual = copy(installed); virtual.splitNames = Arrays.copyOf(virtual.splitNames, 2);
        virtual.applicationInfo.splitSourceDirs = Arrays.copyOf(virtual.applicationInfo.splitSourceDirs, 2);
        virtual.applicationInfo.splitPublicSourceDirs = virtual.applicationInfo.splitSourceDirs.clone();
        expect(GamePackagePreparer.Status.READY); assertEquals(1, installs);
    }

    @Test public void staleContainerVersionIsRefreshed() {
        virtual = copy(installed); virtual.versionCode--;
        expect(GamePackagePreparer.Status.READY); assertEquals(1, installs);
        assertEquals(installed.versionCode, virtual.versionCode);
    }

    @Test public void missingInstalledSplitFailsBeforeTouchingTheContainer() {
        installed.applicationInfo.splitSourceDirs[1] = new File(temporary.getRoot(), "missing.apk").getPath();
        expect(GamePackagePreparer.Status.APK_UNREADABLE); assertEquals(0, installs);
    }

    @Test public void unnamedOrMismatchedInstalledSplitsAreIncomplete() {
        installed.splitNames = null;
        expect(GamePackagePreparer.Status.INCOMPLETE_APKS); assertEquals(0, installs);
    }

    @Test public void duplicateInstalledSplitNamesAreRejected() {
        installed.splitNames[1] = installed.splitNames[0];
        expect(GamePackagePreparer.Status.INCOMPLETE_APKS); assertEquals(0, installs);
    }

    @Test public void baseCannotMasqueradeAsASplit() {
        installed.applicationInfo.splitSourceDirs[0] = installed.applicationInfo.sourceDir;
        expect(GamePackagePreparer.Status.INCOMPLETE_APKS); assertEquals(0, installs);
    }

    @Test public void truncatedOrNonApkSourcesFailSafely() throws Exception {
        File broken = temporary.newFile("broken.apk");
        try (FileOutputStream out = new FileOutputStream(broken)) { out.write(1); }
        installed.applicationInfo.sourceDir = broken.getPath();
        expect(GamePackagePreparer.Status.APK_UNREADABLE); assertEquals(0, installs);
    }

    @Test public void emptyBaseIsNotPassedToSdk() throws Exception {
        installed.applicationInfo.sourceDir = temporary.newFile("empty.apk").getPath();
        expect(GamePackagePreparer.Status.APK_UNREADABLE); assertEquals(0, installs);
    }

    @Test public void failedReadBackDoesNotAuthorizeLaunch() {
        onInstall = () -> { virtual = copy(installed); virtual.splitNames = null; };
        expect(GamePackagePreparer.Status.CONTAINER_UNAVAILABLE); assertEquals(1, installs);
    }

    @Test public void sdkLinkageFailureIsAControlledResultNotAnAppCrash() {
        onInstall = () -> { throw new UnsatisfiedLinkError("offline SDK fixture"); };
        expect(GamePackagePreparer.Status.CONTAINER_UNAVAILABLE);
    }

    @Test public void packageUpdatedDuringPreparationMustBeRetried() {
        onInstall = () -> { virtual = copy(installed); installed.versionCode++; };
        expect(GamePackagePreparer.Status.PACKAGE_CHANGED);
    }

    @Test public void packageRemovedDuringPreparationMustBeRetried() {
        onInstall = () -> { virtual = copy(installed); removed = true; };
        expect(GamePackagePreparer.Status.PACKAGE_CHANGED);
    }

    @Test public void aDifferentHostGameCannotBePreparedUnderThisTarget() {
        installed.packageName = GameTarget.BGMI.packageName;
        expect(GamePackagePreparer.Status.INCOMPLETE_APKS); assertEquals(0, installs);
    }

    @Test public void notInstalledIsReportedWithoutSdkCalls() {
        removed = true;
        expect(GamePackagePreparer.Status.NOT_INSTALLED); assertEquals(0, installs);
    }

    @Test public void internalCopiesAreAcceptedWithoutReinstallingOrClearingData() throws Exception {
        virtual = copy(installed);
        virtual.applicationInfo.sourceDir = apk("container-base.apk").getPath();
        for (int i = 0; i < virtual.applicationInfo.splitSourceDirs.length; i++) {
            virtual.applicationInfo.splitSourceDirs[i] = apk("container-" + i + ".apk").getPath();
        }
        virtual.applicationInfo.splitPublicSourceDirs = virtual.applicationInfo.splitSourceDirs.clone();
        File savedData = temporary.newFile("saved-game-data");
        try (FileOutputStream out = new FileOutputStream(savedData)) { out.write(42); }
        expect(GamePackagePreparer.Status.READY); assertEquals(0, installs);
        assertTrue(savedData.isFile()); assertEquals(1L, savedData.length());
    }

    @Test public void staleSplitSetIsReplacedEvenWhenTheVersionCodeMatches() {
        virtual = copy(installed); virtual.splitNames[2] = "old.feature";
        expect(GamePackagePreparer.Status.READY); assertEquals(1, installs);
        assertArrayEquals(installed.splitNames, virtual.splitNames);
    }

    @Test public void coLocatedSetUsesTheWholeDirectoryWithoutMovingAnOriginalApk() {
        expect(GamePackagePreparer.Status.READY);
        assertEquals(temporary.getRoot().getPath(), lastInput);
        assertTrue(new File(installed.applicationInfo.sourceDir).isFile());
        for (String path : installed.applicationInfo.splitSourceDirs) assertTrue(new File(path).isFile());
        assertEquals(0, work.listFiles().length);
    }

    @Test public void unrelatedSiblingApksAreNeverImportedAndStagingIsRemoved() throws Exception {
        File otherGame = apk("unrelated.apk");
        onInstall = this::assertStagedSet;
        expect(GamePackagePreparer.Status.READY);
        assertEquals(1, installs); assertTrue(otherGame.isFile());
        assertEquals(0, work.listFiles().length);
    }

    @Test public void splitsInDifferentDirectoriesAreStagedAsOneExactSet() throws Exception {
        installed.applicationInfo.splitSourceDirs[1] = apk("another-code-dir/language.apk").getPath();
        installed.applicationInfo.splitPublicSourceDirs = installed.applicationInfo.splitSourceDirs.clone();
        onInstall = this::assertStagedSet;
        expect(GamePackagePreparer.Status.READY);
        assertEquals(1, installs); assertEquals(0, work.listFiles().length);
    }

    @Test public void stagingIsCleanedWhenTheSdkThrowsWithoutDeletingOriginals() throws Exception {
        File otherGame = apk("unrelated.apk");
        onInstall = () -> { assertStagedSet(); throw new IllegalStateException("offline failure"); };
        expect(GamePackagePreparer.Status.CONTAINER_UNAVAILABLE);
        assertEquals(0, work.listFiles().length); assertTrue(otherGame.isFile());
        assertTrue(new File(installed.applicationInfo.sourceDir).isFile());
    }

    @Test public void lowStagingSpaceIsReportedBeforeCallingSdkOrTouchingOriginals() throws Exception {
        apk("unlisted.apk");
        File noSpace = new File(work.getPath()) { @Override public long getUsableSpace() { return 0L; } };
        GamePackagePreparer limited = new GamePackagePreparer(pkg -> installed, new GamePackagePreparer.Container() {
            @Override public PackageInfo read(String pkg) { return null; }
            @Override public void installApkSet(String dir) { fail("Must not install when staging has no space"); }
        }, noSpace);
        assertEquals(GamePackagePreparer.Status.INSUFFICIENT_SPACE, limited.prepare(GAME).status);
        assertEquals(0, work.listFiles().length);
        assertTrue(new File(installed.applicationInfo.sourceDir).isFile());
    }

    @Test public void unavailableStagingRootIsAControlledFailure() throws Exception {
        apk("unlisted.apk");
        GamePackagePreparer limited = new GamePackagePreparer(pkg -> installed, new GamePackagePreparer.Container() {
            @Override public PackageInfo read(String pkg) { return null; }
            @Override public void installApkSet(String dir) { fail("Must not install a partial staged set"); }
        }, temporary.newFile("not-a-directory"));
        assertEquals(GamePackagePreparer.Status.CONTAINER_UNAVAILABLE, limited.prepare(GAME).status);
        assertTrue(new File(installed.applicationInfo.sourceDir).isFile());
    }

    @Test public void sameSizeButDifferentContainerContentsNeedRepair() throws Exception {
        virtual = copy(installed);
        String original = installed.applicationInfo.splitSourceDirs[0];
        File wrong = apk("wrong-split.apk", "MANIFEST-fixture");
        assertEquals(new File(original).length(), wrong.length());
        virtual.applicationInfo.splitSourceDirs[0] = wrong.getPath();
        virtual.applicationInfo.splitPublicSourceDirs = virtual.applicationInfo.splitSourceDirs.clone();
        expect(GamePackagePreparer.Status.READY); assertEquals(1, installs);
    }

    @Test public void anUpdateWithUnchangedSizeAndTimestampStillRequiresRetry() {
        onInstall = () -> {
            virtual = copy(installed);
            File split = new File(installed.applicationInfo.splitSourceDirs[0]);
            long modified = split.lastModified();
            try { apk(split.getName(), "MANIFEST-fixture"); assertTrue(split.setLastModified(modified)); }
            catch (Exception e) { throw new AssertionError(e); }
        };
        expect(GamePackagePreparer.Status.PACKAGE_CHANGED);
    }

    @Test public void readBackCannotPassWithWrongSameSizeSplitContents() {
        onInstall = () -> {
            virtual = copy(installed);
            try { virtual.applicationInfo.splitSourceDirs[1] = apk("wrong-read-back.apk", "MANIFEST-fixture").getPath(); }
            catch (Exception e) { throw new AssertionError(e); }
            virtual.applicationInfo.splitPublicSourceDirs = virtual.applicationInfo.splitSourceDirs.clone();
        };
        expect(GamePackagePreparer.Status.CONTAINER_UNAVAILABLE);
    }

    @Test public void anArchiveWithoutAManifestIsNotAnInstalledApk() throws Exception {
        File noManifest = temporary.newFile("no-manifest.apk");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(noManifest))) {
            zip.putNextEntry(new ZipEntry("not-a-manifest")); zip.write(42); zip.closeEntry();
        }
        installed.applicationInfo.sourceDir = noManifest.getPath();
        expect(GamePackagePreparer.Status.APK_UNREADABLE); assertEquals(0, installs);
    }

    @Test public void inconsistentPublicSplitPathsDoNotMasqueradeAsACompleteSet() {
        installed.applicationInfo.splitPublicSourceDirs = Arrays.copyOf(installed.applicationInfo.splitPublicSourceDirs, 2);
        expect(GamePackagePreparer.Status.INCOMPLETE_APKS); assertEquals(0, installs);
    }

    @Test public void splitOrderingMayDifferButEveryNameMustKeepItsOwnApk() throws Exception {
        installed.applicationInfo.splitSourceDirs[0] = apk("split_config.arm64_v8a.apk", "MANIFEST-fixture").getPath();
        virtual = copy(installed);
        String name = virtual.splitNames[0]; virtual.splitNames[0] = virtual.splitNames[1]; virtual.splitNames[1] = name;
        String path = virtual.applicationInfo.splitSourceDirs[0];
        virtual.applicationInfo.splitSourceDirs[0] = virtual.applicationInfo.splitSourceDirs[1];
        virtual.applicationInfo.splitSourceDirs[1] = path;
        virtual.applicationInfo.splitPublicSourceDirs = virtual.applicationInfo.splitSourceDirs.clone();
        expect(GamePackagePreparer.Status.READY); assertEquals(0, installs);
    }

}
