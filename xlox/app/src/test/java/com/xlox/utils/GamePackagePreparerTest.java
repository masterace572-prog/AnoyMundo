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
    private int clones;
    private boolean removed;
    private Runnable onClone;
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
        onClone = () -> virtual = copy(installed);
        preparer = new GamePackagePreparer(pkg -> {
            assertEquals(GAME.packageName, pkg);
            if (removed) throw new PackageManager.NameNotFoundException(pkg);
            return installed;
        }, new GamePackagePreparer.Container() {
            @Override public PackageInfo read(String pkg) { assertEquals(GAME.packageName, pkg); return virtual; }
            @Override public void cloneInstalledPackage(String pkg) {
                assertEquals(GAME.packageName, pkg);
                clones++;
                onClone.run();
            }
        });
    }

    private File apk(String name) throws Exception {
        File file = new File(temporary.getRoot(), name);
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
            zip.putNextEntry(new ZipEntry("AndroidManifest.xml"));
            zip.write("manifest-fixture".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return file;
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
        assertTrue(result.isReady()); assertEquals(0, result.splitCount); assertEquals(1, clones);
    }

    @Test public void clonesBaseAndEveryAbiLanguageAndAssetSplitTogether() {
        GamePackagePreparer.Result result = preparer.prepare(GAME);
        assertTrue(result.isReady()); assertEquals(3, result.splitCount); assertEquals(1, clones);
        assertArrayEquals(installed.splitNames, virtual.splitNames);
        assertArrayEquals(installed.applicationInfo.splitSourceDirs, virtual.applicationInfo.splitSourceDirs);
    }

    @Test public void alreadyCompletePackageDoesNotRecloneOrMutateItsPaths() {
        virtual = copy(installed);
        String[] paths = virtual.applicationInfo.splitSourceDirs.clone();
        expect(GamePackagePreparer.Status.READY);
        assertEquals(0, clones); assertArrayEquals(paths, virtual.applicationInfo.splitSourceDirs);
    }

    @Test public void staleBaseOnlyContainerIsRepairedWithAllSplits() {
        virtual = copy(installed); virtual.splitNames = null;
        virtual.applicationInfo.splitSourceDirs = null; virtual.applicationInfo.splitPublicSourceDirs = null;
        expect(GamePackagePreparer.Status.READY);
        assertEquals(1, clones); assertEquals(3, virtual.applicationInfo.splitSourceDirs.length);
    }

    @Test public void missingContainerSplitTriggersWholePackageRepair() {
        virtual = copy(installed); virtual.splitNames = Arrays.copyOf(virtual.splitNames, 2);
        virtual.applicationInfo.splitSourceDirs = Arrays.copyOf(virtual.applicationInfo.splitSourceDirs, 2);
        virtual.applicationInfo.splitPublicSourceDirs = virtual.applicationInfo.splitSourceDirs.clone();
        expect(GamePackagePreparer.Status.READY); assertEquals(1, clones);
    }

    @Test public void staleContainerVersionIsRefreshed() {
        virtual = copy(installed); virtual.versionCode--;
        expect(GamePackagePreparer.Status.READY); assertEquals(1, clones);
        assertEquals(installed.versionCode, virtual.versionCode);
    }

    @Test public void missingInstalledSplitFailsBeforeTouchingTheContainer() {
        installed.applicationInfo.splitSourceDirs[1] = new File(temporary.getRoot(), "missing.apk").getPath();
        expect(GamePackagePreparer.Status.APK_UNREADABLE); assertEquals(0, clones);
    }

    @Test public void unnamedOrMismatchedInstalledSplitsAreIncomplete() {
        installed.splitNames = null;
        expect(GamePackagePreparer.Status.INCOMPLETE_APKS); assertEquals(0, clones);
    }

    @Test public void duplicateInstalledSplitNamesAreRejected() {
        installed.splitNames[1] = installed.splitNames[0];
        expect(GamePackagePreparer.Status.INCOMPLETE_APKS); assertEquals(0, clones);
    }

    @Test public void baseCannotMasqueradeAsASplit() {
        installed.applicationInfo.splitSourceDirs[0] = installed.applicationInfo.sourceDir;
        expect(GamePackagePreparer.Status.INCOMPLETE_APKS); assertEquals(0, clones);
    }

    @Test public void truncatedOrNonApkSourcesFailSafely() throws Exception {
        File broken = temporary.newFile("broken.apk");
        try (FileOutputStream out = new FileOutputStream(broken)) { out.write(1); }
        installed.applicationInfo.sourceDir = broken.getPath();
        expect(GamePackagePreparer.Status.APK_UNREADABLE); assertEquals(0, clones);
    }

    @Test public void emptyBaseIsNotPassedToSdk() throws Exception {
        installed.applicationInfo.sourceDir = temporary.newFile("empty.apk").getPath();
        expect(GamePackagePreparer.Status.APK_UNREADABLE); assertEquals(0, clones);
    }

    @Test public void failedReadBackDoesNotAuthorizeLaunch() {
        onClone = () -> { virtual = copy(installed); virtual.splitNames = null; };
        expect(GamePackagePreparer.Status.CONTAINER_UNAVAILABLE); assertEquals(1, clones);
    }

    @Test public void sdkLinkageFailureIsAControlledResultNotAnAppCrash() {
        onClone = () -> { throw new UnsatisfiedLinkError("offline SDK fixture"); };
        expect(GamePackagePreparer.Status.CONTAINER_UNAVAILABLE);
    }

    @Test public void packageUpdatedDuringPreparationMustBeRetried() {
        onClone = () -> { virtual = copy(installed); installed.versionCode++; };
        expect(GamePackagePreparer.Status.PACKAGE_CHANGED);
    }

    @Test public void packageRemovedDuringPreparationMustBeRetried() {
        onClone = () -> { virtual = copy(installed); removed = true; };
        expect(GamePackagePreparer.Status.PACKAGE_CHANGED);
    }

    @Test public void aDifferentHostGameCannotBePreparedUnderThisTarget() {
        installed.packageName = GameTarget.BGMI.packageName;
        expect(GamePackagePreparer.Status.INCOMPLETE_APKS); assertEquals(0, clones);
    }

    @Test public void notInstalledIsReportedWithoutSdkCalls() {
        removed = true;
        expect(GamePackagePreparer.Status.NOT_INSTALLED); assertEquals(0, clones);
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
        expect(GamePackagePreparer.Status.READY); assertEquals(0, clones);
        assertTrue(savedData.isFile()); assertEquals(1L, savedData.length());
    }

    @Test public void staleSplitSetIsReplacedEvenWhenTheVersionCodeMatches() {
        virtual = copy(installed); virtual.splitNames[2] = "old.feature";
        expect(GamePackagePreparer.Status.READY); assertEquals(1, clones);
        assertArrayEquals(installed.splitNames, virtual.splitNames);
    }
}
