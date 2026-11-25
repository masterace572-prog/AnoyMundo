package com.xlox.ui;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.*;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.xlox.R;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;

/** Real sheet/consent/copy/lifecycle UI; fake fact reader, never su or a real crash file. */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = {21, 35}, qualifiers = "w360dp-h800dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public class AirCrashReportDialogTest {
    private static final String EXIT = "Filtered exit preview";
    private static final String HISTORY = "Filtered root exit preview";
    private static final String NATIVE = "Filtered native signal/module/PC preview";
    private static final String SECRET = "ANOY-7D-PRIVATEKEY";

    public static class HostActivity extends AppCompatActivity {
        @Override protected void onCreate(Bundle state) {
            setTheme(R.style.AppTheme); super.onCreate(state);
        }
    }
    private static final class Reader implements AirCrashReportDialog.Reader {
        final AtomicInteger plain = new AtomicInteger(), history = new AtomicInteger(), nativeReads = new AtomicInteger();
        final CountDownLatch entered = new CountDownLatch(1), unblock = new CountDownLatch(1), interrupted = new CountDownLatch(1);
        volatile boolean blockNative, failNative;
        @Override public String exits(Context context, boolean root) {
            if (root) { history.incrementAndGet(); return HISTORY; }
            plain.incrementAndGet(); return EXIT;
        }
        @Override public String nativeTrace(Context context) {
            nativeReads.incrementAndGet(); entered.countDown();
            if (blockNative) try { unblock.await(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); interrupted.countDown(); return "cancelled"; }
            if (failNative) throw new IllegalStateException(SECRET);
            return NATIVE;
        }
    }
    private ActivityController<HostActivity> controller;
    private HostActivity activity;
    private AirCrashReportDialog dialog;
    private BottomSheetDialog sheet;
    private Reader reader;
    private ClipboardManager clipboard;

    @Before public void create() throws Exception {
        controller = Robolectric.buildActivity(HostActivity.class).setup().visible();
        activity = controller.get(); reader = new Reader();
        clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("fixture", "unchanged"));
        dialog = new AirCrashReportDialog(activity, reader);
        sheet = ReflectionHelpers.getField(dialog, "sheet");
        settle();
    }
    @After public void destroy() {
        if (dialog != null) dialog.dispose();
        controller.pause().stop().destroy();
    }
    private View button(int id) { return sheet.findViewById(id); }
    private AlertDialog consent() { return ReflectionHelpers.getField(dialog, "consent"); }
    private String preview() { return ((TextView) sheet.findViewById(R.id.airCrashReportText)).getText().toString(); }
    private String copied() { return clipboard.getPrimaryClip().getItemAt(0).getText().toString(); }
    private void idle() { Shadows.shadowOf(Looper.getMainLooper()).idle(); }
    private void settle() throws Exception {
        Future<?> task = ReflectionHelpers.getField(dialog, "task");
        if (task != null && !task.isCancelled()) task.get(5, TimeUnit.SECONDS);
        idle();
    }
    private void confirmNative() {
        button(R.id.airCrashReportNative).performClick();
        consent().getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        idle(); // AlertDialog delivers its positive action through the main Handler.
    }

    @Test public void openingAndCopyingNeverRequestsEitherRootReader() {
        assertEquals(EXIT, preview());
        assertEquals(1, reader.plain.get());
        assertEquals(0, reader.history.get()); assertEquals(0, reader.nativeReads.get());
        button(R.id.airCrashReportCopy).performClick();
        assertEquals(EXIT, copied()); assertEquals(0, reader.nativeReads.get());
        if (android.os.Build.VERSION.SDK_INT >= 33)
            assertTrue(clipboard.getPrimaryClipDescription().getExtras().getBoolean("android.content.extra.IS_SENSITIVE"));
    }

    @Test public void nativeCancelPreservesPreviewAndNeverCallsReader() {
        button(R.id.airCrashReportNative).performClick();
        assertTrue(consent().isShowing());
        String message = ((TextView) consent().findViewById(android.R.id.message)).getText().toString();
        assertEquals(activity.getString(R.string.air_crash_native_message), message);
        assertTrue(message.contains("Separate from exit history")); assertTrue(message.contains("may not exist"));
        assertEquals(0, reader.nativeReads.get());
        consent().getButton(DialogInterface.BUTTON_NEGATIVE).performClick(); idle();
        assertEquals(0, reader.nativeReads.get()); assertEquals(EXIT, preview());
        button(R.id.airCrashReportCopy).performClick(); assertEquals(EXIT, copied());
    }

    @Test public void existingHistoryConsentDoesNotAuthorizeNativeAccess() throws Exception {
        button(R.id.airCrashReportRoot).performClick();
        assertEquals(activity.getString(R.string.air_crash_root_message),
                ((TextView) consent().findViewById(android.R.id.message)).getText().toString());
        consent().getButton(DialogInterface.BUTTON_POSITIVE).performClick(); idle(); settle();
        assertEquals(1, reader.history.get()); assertEquals(0, reader.nativeReads.get());
        assertEquals(HISTORY, preview());
        button(R.id.airCrashReportNative).performClick();
        assertTrue(consent().isShowing()); assertEquals(0, reader.nativeReads.get());
        consent().getButton(DialogInterface.BUTTON_NEGATIVE).performClick(); idle();
        button(R.id.airCrashReportCopy).performClick(); assertEquals(HISTORY, copied());
    }

    @Test public void nativePositiveRunsOnlyNativeAndEveryNewRequestReconfirms() throws Exception {
        confirmNative(); settle();
        assertEquals(1, reader.nativeReads.get()); assertEquals(0, reader.history.get()); assertEquals(1, reader.plain.get());
        assertEquals(NATIVE, preview());
        button(R.id.airCrashReportCopy).performClick(); assertEquals(NATIVE, copied());
        button(R.id.airCrashReportNative).performClick();
        assertTrue(consent().isShowing()); assertEquals(1, reader.nativeReads.get());
        consent().getButton(DialogInterface.BUTTON_NEGATIVE).performClick(); idle();
        assertEquals(1, reader.nativeReads.get());
    }

    @Test public void closingWithUnconfirmedConsentCannotStartALaterRead() {
        button(R.id.airCrashReportNative).performClick();
        AlertDialog pending = consent();
        dialog.dispose(); idle();
        assertFalse(pending.isShowing()); assertFalse(sheet.isShowing());
        pending.getButton(DialogInterface.BUTTON_POSITIVE).performClick(); idle();
        assertEquals(0, reader.nativeReads.get());
    }

    @Test public void closeCancelsNativeWorkerAndDropsPendingPreview() throws Exception {
        reader.blockNative = true; confirmNative();
        assertTrue(reader.entered.await(5, TimeUnit.SECONDS));
        button(R.id.airCrashReportClose).performClick(); idle();
        assertTrue(reader.interrupted.await(5, TimeUnit.SECONDS));
        Future<?> task = ReflectionHelpers.getField(dialog, "task");
        ExecutorService worker = ReflectionHelpers.getField(dialog, "worker");
        assertTrue(task.isCancelled()); assertTrue(worker.isShutdown());
        assertNull(ReflectionHelpers.getField(dialog, "result"));
        assertFalse(sheet.isShowing()); assertEquals("unchanged", copied());
    }

    @Test public void pendingNativeReadDisablesBothRootActionsAndCopy() throws Exception {
        reader.blockNative = true; confirmNative();
        assertTrue(reader.entered.await(5, TimeUnit.SECONDS));
        for (int id : new int[]{R.id.airCrashReportCopy, R.id.airCrashReportRoot, R.id.airCrashReportNative}) {
            assertFalse(button(id).isEnabled()); button(id).performClick();
        }
        assertEquals(1, reader.nativeReads.get()); assertEquals(0, reader.history.get()); assertEquals("unchanged", copied());
        reader.unblock.countDown(); settle();
        assertTrue(button(R.id.airCrashReportCopy).isEnabled());
        assertTrue(button(R.id.airCrashReportRoot).isEnabled()); assertTrue(button(R.id.airCrashReportNative).isEnabled());
    }

    @Test public void nativeFailureShowsSafeTextAndDoesNotPoisonOtherActions() throws Exception {
        reader.failNative = true; confirmNative(); settle();
        assertFalse(preview().contains(SECRET));
        assertEquals(activity.getString(R.string.air_crash_report_failed), preview());
        button(R.id.airCrashReportCopy).performClick(); assertFalse(copied().contains(SECRET));
        assertTrue(button(R.id.airCrashReportRoot).isEnabled()); assertTrue(button(R.id.airCrashReportNative).isEnabled());
        button(R.id.airCrashReportRoot).performClick();
        consent().getButton(DialogInterface.BUTTON_POSITIVE).performClick(); idle(); settle();
        assertEquals(HISTORY, preview()); assertEquals(1, reader.nativeReads.get()); assertEquals(1, reader.history.get());
    }
}
