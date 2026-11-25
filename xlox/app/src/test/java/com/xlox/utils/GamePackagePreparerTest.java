package com.xlox.utils;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

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
@Config(application = Application.class, sdk = {21, 28, 35})
public class GamePackagePreparerTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final GameTarget GAME = GameTarget.BGMI_AIR;
    private PackageInfo installed;
    private PackageInfo virtual;
    private int installs;
    private File work;
    private String lastInput;
    private boolean removed;
    private boolean defaultUser = true;
    private boolean launchReady = true;
    private boolean running;
    private int runningChecks;
    private Runnable onRunningRead = () -> {};
    private int userRequests;
    private int userChecks;
    private int launchChecks;
    private int waits;
    private Runnable onInstall;
    private Runnable onUserRead = () -> {};
    private Runnable onAddUser = () -> defaultUser = true;
    private Runnable onResolve = () -> {};
    private GamePackagePreparer.Pause onPause = millis -> {};
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
            @Override public boolean hasDefaultUser(String pkg) {
                assertEquals(GAME.packageName, pkg); userChecks++; onUserRead.run(); return defaultUser;
            }
            @Override public void addDefaultUser(String pkg) {
                assertEquals(GAME.packageName, pkg); userRequests++; onAddUser.run();
            }
            @Override public boolean hasLaunchActivity(String pkg) {
                assertEquals(GAME.packageName, pkg); launchChecks++; onResolve.run(); return launchReady;
            }
            @Override public boolean isRunning(String pkg) {
                assertEquals(GAME.packageName, pkg); runningChecks++; onRunningRead.run(); return running;
            }
        }, work, millis -> {
            assertEquals(100L, millis); waits++; onPause.sleep(millis);
        });
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
            @Override public boolean hasDefaultUser(String pkg) { throw new AssertionError("No imported package"); }
            @Override public void addDefaultUser(String pkg) { fail("Must not create a user when staging failed"); }
            @Override public boolean hasLaunchActivity(String pkg) { throw new AssertionError("No imported package"); }
            @Override public boolean isRunning(String pkg) { return false; }
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
            @Override public boolean hasDefaultUser(String pkg) { throw new AssertionError("No imported package"); }
            @Override public void addDefaultUser(String pkg) { fail("Must not create a user when staging failed"); }
            @Override public boolean hasLaunchActivity(String pkg) { throw new AssertionError("No imported package"); }
            @Override public boolean isRunning(String pkg) { return false; }
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

    @Test public void missingDefaultUserIsAddedWithoutReimportingOrClearingSavedData() throws Exception {
        virtual = copy(installed); defaultUser = false;
        File saved = temporary.newFile("existing-save");
        Files.write(saved.toPath(), new byte[]{42, 43});
        expect(GamePackagePreparer.Status.READY);
        assertEquals(0, installs); assertEquals(1, userRequests); assertEquals(1, waits);
        assertArrayEquals(new byte[]{42, 43}, bytes(saved));
    }

    @Test public void asynchronousUserRegistrationMustBeObservedBeforeLaunchIsReady() {
        defaultUser = false;
        onAddUser = () -> {}; // the SDK schedules work; return is not completion
        onPause = millis -> { if (waits == 3) defaultUser = true; };
        expect(GamePackagePreparer.Status.READY);
        assertEquals(1, userRequests); assertEquals(3, waits); assertEquals(1, launchChecks);
    }

    @Test public void neverUsesAnAlternateUserWhenDefaultUserRegistrationTimesOut() {
        defaultUser = false; onAddUser = () -> {};
        expect(GamePackagePreparer.Status.CONTAINER_UNAVAILABLE);
        assertEquals(1, userRequests); assertEquals(100, waits); assertEquals(0, launchChecks);
    }

    @Test public void existingDefaultUserIsNeverRecreated() {
        expect(GamePackagePreparer.Status.READY);
        assertEquals(0, userRequests); assertEquals(0, waits); assertEquals(1, launchChecks);
    }

    @Test public void sdkUserLookupFailureIsControlledWithoutChangingAnExistingPackage() {
        virtual = copy(installed);
        onUserRead = () -> { throw new IllegalStateException("offline service failure"); };
        expect(GamePackagePreparer.Status.CONTAINER_UNAVAILABLE);
        assertEquals(0, installs); assertEquals(0, userRequests); assertEquals(0, launchChecks);
    }

    @Test public void sdkUserCreationFailureCannotAuthorizeLaunch() {
        virtual = copy(installed); defaultUser = false;
        onAddUser = () -> { throw new UnsatisfiedLinkError("offline SDK failure"); };
        expect(GamePackagePreparer.Status.CONTAINER_UNAVAILABLE);
        assertEquals(0, installs); assertEquals(1, userRequests); assertEquals(0, launchChecks);
    }

    @Test public void existingPackageWaitsForComponentParsingWithoutReinstalling() {
        virtual = copy(installed); launchReady = false;
        onPause = millis -> { if (waits == 3) launchReady = true; };
        expect(GamePackagePreparer.Status.READY);
        assertEquals(0, installs); assertEquals(3, waits); assertEquals(0, userRequests);
    }

    @Test public void importerReturnAndCompleteFilesDoNotReplaceLaunchComponentReadiness() {
        launchReady = false;
        onPause = millis -> { if (waits == 4) launchReady = true; };
        expect(GamePackagePreparer.Status.READY);
        assertEquals(1, installs); assertEquals(4, waits); assertEquals(5, launchChecks);
    }

    @Test public void aCompletePackageWithoutALauncherTimesOutInsteadOfLaunchingPartially() {
        launchReady = false;
        expect(GamePackagePreparer.Status.CONTAINER_UNAVAILABLE);
        assertEquals(100, waits); assertEquals(101, launchChecks); assertEquals(0, userRequests);
    }

    @Test public void launchResolverFailureIsAControlledResult() {
        onResolve = () -> { throw new IllegalStateException("offline resolver failure"); };
        expect(GamePackagePreparer.Status.CONTAINER_UNAVAILABLE);
        assertEquals(0, waits); assertEquals(0, userRequests);
    }

    @Test public void stagedApksStayAvailableUntilAsynchronousReadinessHasBeenObserved() throws Exception {
        File otherGame = apk("unlisted.apk"); launchReady = false;
        onInstall = this::assertStagedSet;
        onPause = millis -> {
            assertStagedSet(); assertTrue(new File(lastInput).isDirectory());
            if (waits == 2) launchReady = true;
        };
        expect(GamePackagePreparer.Status.READY);
        assertEquals(2, waits); assertEquals(0, work.listFiles().length); assertTrue(otherGame.isFile());
        assertTrue(new File(installed.applicationInfo.sourceDir).isFile());
    }

    @Test public void stagingIsRemovedIfReadinessTimesOutButOriginalApksRemain() throws Exception {
        File otherGame = apk("unlisted.apk"); launchReady = false;
        expect(GamePackagePreparer.Status.CONTAINER_UNAVAILABLE);
        assertEquals(100, waits); assertEquals(0, work.listFiles().length); assertTrue(otherGame.isFile());
        assertTrue(new File(installed.applicationInfo.sourceDir).isFile());
    }

    @Test public void interruptionWhileWaitingCleansStagingAndPreservesTheInterruptFlag() throws Exception {
        apk("unlisted.apk"); launchReady = false;
        onPause = millis -> { throw new InterruptedException("offline cancelled I/O worker"); };
        try {
            expect(GamePackagePreparer.Status.CONTAINER_UNAVAILABLE);
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(1, waits); assertEquals(0, work.listFiles().length);
            assertTrue(new File(installed.applicationInfo.sourceDir).isFile());
        } finally { Thread.interrupted(); }
    }

    @Test public void alreadyInterruptedPreparationNeverCallsTheInstaller() {
        Thread.currentThread().interrupt();
        try {
            expect(GamePackagePreparer.Status.CONTAINER_UNAVAILABLE);
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(0, installs); assertEquals(0, userChecks);
        } finally { Thread.interrupted(); }
    }

    @Test public void hostUpdateDuringReadinessWaitRequiresRetry() {
        launchReady = false;
        onPause = millis -> { installed.versionCode++; launchReady = true; };
        expect(GamePackagePreparer.Status.PACKAGE_CHANGED);
        assertEquals(1, waits);
    }

    @Test public void readinessOnTheLastBoundedCheckIsAccepted() {
        launchReady = false;
        onPause = millis -> { if (waits == 100) launchReady = true; };
        expect(GamePackagePreparer.Status.READY);
        assertEquals(100, waits); assertEquals(101, launchChecks);
    }

    @Test public void rejectedImportNeverCreatesAnExternalFallbackUser() {
        defaultUser = false; onInstall = () -> {};
        expect(GamePackagePreparer.Status.CONTAINER_UNAVAILABLE);
        assertEquals(0, userChecks); assertEquals(0, userRequests); assertEquals(0, launchChecks);
    }

    @Test public void partialReadBackNeverRegistersAUserForAnIncompleteApkSet() {
        defaultUser = false;
        onInstall = () -> { virtual = copy(installed); virtual.splitNames = null; };
        expect(GamePackagePreparer.Status.CONTAINER_UNAVAILABLE);
        assertEquals(0, userChecks); assertEquals(0, userRequests); assertEquals(0, launchChecks);
    }

    private static final byte[] BASE_NATIVE = "base-native-fixture".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SPLIT_NATIVE = "arm64-split-fixture".getBytes(StandardCharsets.UTF_8);
    private File natives;

    private void nativeApk(String path, String name, byte[] body) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(path))) {
            zip.putNextEntry(new ZipEntry("AndroidManifest.xml"));
            zip.write("manifest-fixture".getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("lib/arm64-v8a/" + name));
            zip.write(body); zip.closeEntry();
        }
    }

    private void nativeFixture() throws Exception {
        nativeApk(installed.applicationInfo.sourceDir, "libbase.so", BASE_NATIVE);
        nativeApk(installed.applicationInfo.splitSourceDirs[0], "libUE4.so", SPLIT_NATIVE);
        installed.applicationInfo.flags |= ApplicationInfo.FLAG_EXTRACT_NATIVE_LIBS;
        natives = temporary.newFolder("container-native");
        virtual = copy(installed);
        virtual.applicationInfo.nativeLibraryDir = natives.getPath();
        onInstall = this::successfulNativeImport;
    }

    /** Only the fake original-SDK adapter writes files, never the verifier. */
    private void successfulNativeImport() {
        virtual = copy(installed);
        virtual.applicationInfo.nativeLibraryDir = natives.getPath();
        try {
            Files.write(new File(natives, "libbase.so").toPath(), BASE_NATIVE);
            Files.write(new File(natives, "libUE4.so").toPath(), SPLIT_NATIVE);
        } catch (Exception e) { throw new AssertionError(e); }
    }

    @Test public void intactAirNativeCopiesNeedNoReimportOrRunningQuery() throws Exception {
        nativeFixture(); successfulNativeImport();
        GamePackagePreparer.Result result = preparer.prepare(GAME);
        assertTrue(result.isReady()); assertFalse(result.repairedAirNativeLibraries);
        assertEquals(0, installs); assertEquals(0, runningChecks);
    }

    @Test public void missingAbiSplitLibraryRetriesTheWholeOriginalSdkImportOnce() throws Exception {
        nativeFixture();
        Files.write(new File(natives, "libbase.so").toPath(), BASE_NATIVE);
        GamePackagePreparer.Result result = preparer.prepare(GAME);
        assertTrue(result.isReady()); assertTrue(result.repairedAirNativeLibraries);
        assertEquals(1, installs); assertEquals(2, runningChecks);
        assertArrayEquals(SPLIT_NATIVE, bytes(new File(natives, "libUE4.so")));
        assertEquals(3, virtual.splitNames.length);
    }

    @Test public void truncatedBaseLibraryIsRepairedEvenWhenAllApksAlreadyMatch() throws Exception {
        nativeFixture(); successfulNativeImport();
        Files.write(new File(natives, "libbase.so").toPath(), new byte[]{42});
        expect(GamePackagePreparer.Status.READY); assertEquals(1, installs);
        assertArrayEquals(BASE_NATIVE, bytes(new File(natives, "libbase.so")));
    }

    @Test public void nativeCrcDetectsSameSizeSameTimestampCorruptionAndPreservesSaves() throws Exception {
        nativeFixture(); successfulNativeImport();
        File library = new File(natives, "libUE4.so"); long modified = library.lastModified();
        byte[] bad = SPLIT_NATIVE.clone(); bad[0] ^= 1;
        Files.write(library.toPath(), bad); assertTrue(library.setLastModified(modified));
        File saved = temporary.newFile("existing-air-save");
        Files.write(saved.toPath(), new byte[]{51, 52});
        GamePackagePreparer.Result result = preparer.prepare(GAME);
        assertTrue(result.isReady()); assertTrue(result.repairedAirNativeLibraries);
        assertEquals(1, installs); assertEquals(0, userRequests);
        assertArrayEquals(new byte[]{51, 52}, bytes(saved));
        assertArrayEquals(SPLIT_NATIVE, bytes(library));
    }

    @Test public void aFreshImportWithFailedNativeCopyIsNotRetriedOrMarkedReady() throws Exception {
        nativeFixture(); virtual = null;
        onInstall = () -> { virtual = copy(installed); virtual.applicationInfo.nativeLibraryDir = natives.getPath(); };
        expect(GamePackagePreparer.Status.NATIVE_LIBRARIES_UNAVAILABLE);
        assertEquals(1, installs); assertEquals(0, userRequests);
    }

    @Test public void failedNativeRepairCannotLoopOrLaunchWithTheOldBrokenCopy() throws Exception {
        nativeFixture();
        onInstall = () -> {}; // SDK silently did not copy, but its PackageInfo still matches
        expect(GamePackagePreparer.Status.NATIVE_LIBRARIES_UNAVAILABLE);
        assertEquals(1, installs); assertEquals(0, userRequests);
        assertEquals(0, natives.listFiles().length);
    }

    @Test public void copyingOnlyTheBaseLibraryDoesNotMakeAFreshSplitInstallReady() throws Exception {
        nativeFixture(); virtual = null;
        onInstall = () -> {
            successfulNativeImport(); assertTrue(new File(natives, "libUE4.so").delete());
        };
        expect(GamePackagePreparer.Status.NATIVE_LIBRARIES_UNAVAILABLE); assertEquals(1, installs);
    }

    @Test public void nativeRepairNeverReplacesMappedCodeOfARunningAirGame() throws Exception {
        nativeFixture(); running = true;
        expect(GamePackagePreparer.Status.GAME_RUNNING);
        assertEquals(0, installs); assertEquals(1, runningChecks);
        assertEquals(0, natives.listFiles().length); assertEquals(0, work.listFiles().length);
    }

    @Test public void runningAirWithStaleApksIsNotStoppedOrReimportedEither() throws Exception {
        virtual = copy(installed); virtual.versionCode--; running = true;
        expect(GamePackagePreparer.Status.GAME_RUNNING);
        assertEquals(0, installs); assertEquals(0, userRequests);
    }

    @Test public void aGameStartingDuringStagingStopsRepairAndCleansOnlyStaging() throws Exception {
        nativeFixture(); File other = apk("unlisted.apk");
        onRunningRead = () -> { if (runningChecks == 2) running = true; };
        expect(GamePackagePreparer.Status.GAME_RUNNING);
        assertEquals(0, installs); assertEquals(2, runningChecks);
        assertEquals(0, work.listFiles().length); assertTrue(other.isFile());
        assertTrue(new File(installed.applicationInfo.sourceDir).isFile());
    }

    @Test public void unreadableRunningStateDoesNotAuthorizeNativeRepair() throws Exception {
        nativeFixture(); onRunningRead = () -> { throw new IllegalStateException("offline SDK fixture"); };
        expect(GamePackagePreparer.Status.CONTAINER_UNAVAILABLE);
        assertEquals(0, installs); assertEquals(0, work.listFiles().length);
    }

    @Test public void aStoreUpdateBeforeNativeRepairIsDetectedBeforeSdkReplacement() throws Exception {
        nativeFixture(); onRunningRead = () -> installed.versionCode++;
        expect(GamePackagePreparer.Status.PACKAGE_CHANGED);
        assertEquals(0, installs); assertEquals(0, natives.listFiles().length);
    }

    @Test public void interruptionBeforeNativeHashingDoesNotTriggerRepair() throws Exception {
        nativeFixture(); successfulNativeImport(); onResolve = () -> Thread.currentThread().interrupt();
        try {
            expect(GamePackagePreparer.Status.CONTAINER_UNAVAILABLE);
            assertTrue(Thread.currentThread().isInterrupted()); assertEquals(0, installs);
        } finally { Thread.interrupted(); }
    }

    @Test public void directFromApkNativeModeDoesNotRequireInventedExtractedFilesOn23Plus() throws Exception {
        nativeFixture(); installed.applicationInfo.flags = 0; virtual.applicationInfo.flags = 0;
        onInstall = () -> {}; // no extraction is expected in direct-from-APK mode
        GamePackagePreparer.Result result = preparer.prepare(GAME);
        if (Build.VERSION.SDK_INT >= 23) {
            assertTrue(result.isReady()); assertEquals(0, installs);
        } else {
            assertEquals(GamePackagePreparer.Status.NATIVE_LIBRARIES_UNAVAILABLE, result.status);
            assertEquals(1, installs);
        }
    }

    @Test public void corruptExtractedFileCannotShadowALegalDirectFromApkLibrary() throws Exception {
        nativeFixture(); installed.applicationInfo.flags = 0; virtual.applicationInfo.flags = 0;
        Files.write(new File(natives, "libUE4.so").toPath(), new byte[]{1});
        expect(GamePackagePreparer.Status.READY); assertEquals(1, installs);
    }

    @Test public void hostExtractionFlagCannotBeLostByContainerReadBack() throws Exception {
        nativeFixture(); virtual.applicationInfo.flags = 0;
        expect(GamePackagePreparer.Status.READY); assertEquals(1, installs);
    }

    @Test public void containerExtractionFlagAlsoRequiresCompleteNativeCopies() throws Exception {
        nativeFixture(); installed.applicationInfo.flags = 0;
        expect(GamePackagePreparer.Status.READY); assertEquals(1, installs);
    }

    @Test public void nativeRepairRetainsExactStagedSetThroughAsynchronousReadiness() throws Exception {
        nativeFixture(); File other = apk("unlisted.apk");
        onInstall = () -> { assertStagedSet(); successfulNativeImport(); launchReady = false; };
        onPause = millis -> {
            assertTrue(new File(lastInput, "base.apk").isFile());
            assertEquals(4, new File(lastInput).listFiles().length);
            if (waits == 2) launchReady = true;
        };
        expect(GamePackagePreparer.Status.READY); assertEquals(1, installs); assertEquals(2, waits);
        assertEquals(0, work.listFiles().length); assertTrue(other.isFile());
    }

    @Test public void invalidSdkReadBackAfterNativeRepairStillFailsClosedAndCleansStaging() throws Exception {
        nativeFixture(); apk("unlisted.apk");
        onInstall = () -> { assertStagedSet(); virtual.splitNames = null; };
        expect(GamePackagePreparer.Status.CONTAINER_UNAVAILABLE);
        assertEquals(1, installs); assertEquals(0, work.listFiles().length);
        assertEquals(0, userRequests);
    }

    @Test public void bgmiNativeFilesAndItsRunningStateAreNotSubjectToTheAirRepair() throws Exception {
        nativeFixture(); installed.packageName = GameTarget.BGMI.packageName;
        installed.applicationInfo.packageName = GameTarget.BGMI.packageName;
        virtual = copy(installed); // includes missing native files; Air-only policy must not run
        GamePackagePreparer bgmiOnly = new GamePackagePreparer(pkg -> installed, new GamePackagePreparer.Container() {
            @Override public PackageInfo read(String pkg) { assertEquals(GameTarget.BGMI.packageName, pkg); return virtual; }
            @Override public void installApkSet(String dir) { fail("BGMI preparation must remain unchanged"); }
            @Override public boolean hasDefaultUser(String pkg) { return true; }
            @Override public void addDefaultUser(String pkg) { fail("Existing user must not be recreated"); }
            @Override public boolean hasLaunchActivity(String pkg) { return true; }
            @Override public boolean isRunning(String pkg) { throw new AssertionError("Air-only running guard"); }
        }, work);
        GamePackagePreparer.Result result = bgmiOnly.prepare(GameTarget.BGMI);
        assertTrue(result.isReady()); assertFalse(result.repairedAirNativeLibraries);
        assertEquals(0, natives.listFiles().length);
    }
}
