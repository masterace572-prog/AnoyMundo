package com.xlox.utils;

import static org.junit.Assert.*;

import android.app.Application;
import android.os.Looper;

import static org.robolectric.Shadows.shadowOf;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.Collections;

/** Real temporary files + production lookup, without container/native initialization. */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = {21, 28})
public class AppManagerObbStateTest {
    private static final String BGMI = "com.pubg.imobile";
    private static final String AIR = "com.krafton.bgmilite";

    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private AppManager manager;
    private File source;

    @Before
    public void create() throws Exception {
        manager = new AppManager(RuntimeEnvironment.getApplication());
        source = temporary.newFolder("source");
    }

    private File file(String name, boolean nonempty) throws Exception {
        File result = new File(source, name);
        try (FileOutputStream out = new FileOutputStream(result)) {
            if (nonempty) out.write(1);
        }
        return result;
    }

    @Test
    public void emptyOrAbsentDirectoryIsMissingAndDoesNotCreateAnything() {
        assertEquals(AppManager.ObbState.MISSING, manager.inspectObb(BGMI, Collections.singletonList(source)));
        File absent = new File(source, "absent");
        assertEquals(AppManager.ObbState.MISSING, manager.inspectObb(BGMI, Collections.singletonList(absent)));
        assertFalse(absent.exists());
        assertArrayEquals(new String[0], source.list());
    }

    @Test
    public void matchingNonemptyMainIsAvailableWithoutCopyingOrChangingIt() throws Exception {
        File main = file("main.123." + BGMI + ".obb", true);
        long modified = main.lastModified();
        assertEquals(AppManager.ObbState.AVAILABLE, manager.inspectObb(BGMI, Collections.singletonList(source)));
        assertEquals(1L, main.length());
        assertEquals(modified, main.lastModified());
        assertEquals(1, source.list().length);
    }

    @Test
    public void bgmiCannotBorrowAirFilesOrAPatchAsItsRequiredMain() throws Exception {
        file("main.123." + AIR + ".obb", true);
        file("patch.123." + BGMI + ".obb", true);
        assertEquals(AppManager.ObbState.MISSING, manager.inspectObb(BGMI, Collections.singletonList(source)));
        assertEquals(AppManager.ObbState.NOT_REQUIRED, manager.inspectObb(AIR, Collections.singletonList(source)));
        file("main.123." + BGMI + ".obb", true);
        assertEquals(AppManager.ObbState.AVAILABLE, manager.inspectObb(BGMI, Collections.singletonList(source)));
    }

    @Test
    public void zeroLengthAndIncompleteDownloadsDoNotClaimReadiness() throws Exception {
        file("main.123." + BGMI + ".obb", false);
        file("main.124." + BGMI + ".obb.part", true);
        file("main.invalid." + BGMI + ".obb", true);
        assertEquals(AppManager.ObbState.MISSING, manager.inspectObb(BGMI, Collections.singletonList(source)));
    }

    @Test
    public void blockedListingIsAccessRequiredNotAMissingObb() {
        File blocked = new File(source, "blocked") {
            @Override public boolean isDirectory() { return true; }
            @Override public File[] listFiles() { return null; }
        };
        assertEquals(AppManager.ObbState.ACCESS_REQUIRED, manager.inspectObb(BGMI, Collections.singletonList(blocked)));
    }

    @Test
    public void readableFallbackStillWinsAfterABlockedDirectory() throws Exception {
        File blocked = new File(source, "blocked") {
            @Override public boolean isDirectory() { return true; }
            @Override public File[] listFiles() { return null; }
        };
        file("main.10." + BGMI + ".obb", true);
        assertEquals(AppManager.ObbState.AVAILABLE, manager.inspectObb(BGMI, Arrays.asList(blocked, source)));
    }

    @Test
    public void securityExceptionIsAccessRequiredAndNeverClaimsReady() {
        File denied = new File(source, "denied") {
            @Override public boolean isDirectory() { throw new SecurityException("fixture"); }
        };
        assertEquals(AppManager.ObbState.ACCESS_REQUIRED, manager.inspectObb(BGMI, Collections.singletonList(denied)));
    }

    @Test
    public void unexpectedLookupErrorStaysUnknown() {
        File broken = new File(source, "broken") {
            @Override public boolean isDirectory() { throw new IllegalStateException("fixture"); }
        };
        assertEquals(AppManager.ObbState.UNKNOWN, manager.inspectObb(BGMI, Collections.singletonList(broken)));
    }
    @Test
    public void airNeedsNoObbEvenWhenSharedStorageIsCompletelyBlocked() {
        File forbidden = new File(source, "never-touch") {
            @Override public boolean isDirectory() { throw new AssertionError("Air must not inspect OBB folders"); }
            @Override public File[] listFiles() { throw new AssertionError("Air must not list OBB folders"); }
        };
        assertEquals(AppManager.ObbState.NOT_REQUIRED, manager.inspectObb(AIR, Collections.singletonList(forbidden)));
        assertEquals(AppManager.ObbState.NOT_REQUIRED, manager.inspectObb(AIR));
        assertTrue(manager.isObbCurrent(AIR));
        assertFalse(forbidden.exists());
    }

    @Test
    public void airCopyIsANoOpAndNeverCreatesOrRequestsAnObb() {
        final boolean[] completed = {false};
        manager.copyObbAsync(AIR, new AppManager.CopyCallback() {
            @Override public void onCopyCompleted(boolean success, String error) {
                assertTrue(success); assertNull(error); completed[0] = true;
            }
            @Override public void onProgressUpdate(int percent) { fail("There is no OBB to copy for Air"); }
        });
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(completed[0]);
        assertArrayEquals(new String[0], source.list());
    }

    @Test
    public void permissionPolicyKeepsBgmiGatesButDoesNotBlockAirOnObbAccess() {
        assertTrue(GameTarget.BGMI.requiresObb());
        assertFalse(GameTarget.BGMI_AIR.requiresObb());
        for (StorageAccess.Need need : StorageAccess.Need.values()) {
            assertTrue(StorageAccess.requiredForGame(GameTarget.BGMI, need));
            assertTrue(StorageAccess.requiredForGame(null, need));
            assertEquals(need == StorageAccess.Need.INSTALL,
                    StorageAccess.requiredForGame(GameTarget.BGMI_AIR, need));
        }
    }

}
