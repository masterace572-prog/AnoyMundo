package com.xlox.utils;

import static org.junit.Assert.*;

import android.app.Application;

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
        assertEquals(AppManager.ObbState.MISSING, manager.inspectObb(AIR, Collections.singletonList(absent)));
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
    public void bgmiAndPatchFilesCannotMasqueradeAsAirMain() throws Exception {
        file("main.123." + BGMI + ".obb", true);
        file("patch.123." + AIR + ".obb", true);
        assertEquals(AppManager.ObbState.AVAILABLE, manager.inspectObb(BGMI, Collections.singletonList(source)));
        assertEquals(AppManager.ObbState.MISSING, manager.inspectObb(AIR, Collections.singletonList(source)));
        file("main.123." + AIR + ".obb", true);
        assertEquals(AppManager.ObbState.AVAILABLE, manager.inspectObb(AIR, Collections.singletonList(source)));
    }

    @Test
    public void zeroLengthAndIncompleteDownloadsDoNotClaimReadiness() throws Exception {
        file("main.123." + AIR + ".obb", false);
        file("main.124." + AIR + ".obb.part", true);
        file("main.invalid." + AIR + ".obb", true);
        assertEquals(AppManager.ObbState.MISSING, manager.inspectObb(AIR, Collections.singletonList(source)));
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
        file("main.10." + AIR + ".obb", true);
        assertEquals(AppManager.ObbState.AVAILABLE, manager.inspectObb(AIR, Arrays.asList(blocked, source)));
    }

    @Test
    public void securityExceptionIsAccessRequiredAndNeverClaimsReady() {
        File denied = new File(source, "denied") {
            @Override public boolean isDirectory() { throw new SecurityException("fixture"); }
        };
        assertEquals(AppManager.ObbState.ACCESS_REQUIRED, manager.inspectObb(AIR, Collections.singletonList(denied)));
    }

    @Test
    public void unexpectedLookupErrorStaysUnknown() {
        File broken = new File(source, "broken") {
            @Override public boolean isDirectory() { throw new IllegalStateException("fixture"); }
        };
        assertEquals(AppManager.ObbState.UNKNOWN, manager.inspectObb(AIR, Collections.singletonList(broken)));
    }
}
