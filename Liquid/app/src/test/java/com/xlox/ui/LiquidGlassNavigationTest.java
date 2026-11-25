package com.xlox.ui;

import static org.junit.Assert.*;

import android.app.Application;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;

import com.xlox.R;

import java.time.Duration;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

/** Additional real Android view/lifecycle/geometry tests for the isolated copy. */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = {21, 28, 35}, qualifiers = "w360dp-h800dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public class LiquidGlassNavigationTest {
    private ActivityController<HomeSettingsNavigationTest.HostActivity> controller;
    private HomeSettingsNavigationTest.HostActivity activity;
    private ViewGroup root;
    private CapsuleNavigation capsule;
    private View host;

    @Before public void create() {
        controller = Robolectric.buildActivity(HomeSettingsNavigationTest.HostActivity.class).setup().visible();
        activity = controller.get();
        root = activity.findViewById(R.id.mainRoot);
        capsule = activity.findViewById(R.id.bottomCapsule);
        host = activity.findViewById(R.id.bottomNavigationHost);
        layout(360, 800);
        Settings.Global.putFloat(activity.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
    }

    @After public void destroy() { controller.pause().stop().destroy(); }

    private void layout(int width, int height) {
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    @Test public void clearNavigationOverlaysThePageInsteadOfAnOpaqueFooter() {
        View pager = activity.findViewById(R.id.homeSettingsPager);
        assertEquals(root.getHeight(), pager.getHeight());
        assertTrue(host.getTop() < pager.getBottom());
        assertEquals(0, Color.alpha(((ColorDrawable) host.getBackground()).getColor()));
        assertTrue(activity.findViewById(R.id.navigationHome).getHeight() >= Ui.dp(activity, 48));
        assertTrue(activity.findViewById(R.id.navigationSettings).getHeight() >= Ui.dp(activity, 48));
    }

    @Test public void scrollClearanceIsIdempotentAndTracksNavigationInsets() {
        View scroll = activity.findViewById(R.id.mainScroll);
        int original = scroll.getPaddingBottom();
        assertEquals(host.getHeight(), original);
        for (int i = 0; i < 8; i++) layout(360, 800);
        assertEquals(original, scroll.getPaddingBottom());
        host.setPadding(host.getPaddingLeft(), host.getPaddingTop(), host.getPaddingRight(),
                host.getPaddingBottom() + 30);
        layout(360, 800);
        assertEquals(original + 30, scroll.getPaddingBottom());
        assertEquals(host.getHeight(), activity.findViewById(R.id.settingsScroll).getPaddingBottom());
    }

    @Test public void lastSettingsActionCanScrollCompletelyAboveTheGlass() {
        layout(240, 640);
        activity.navigation.select(HomeSettingsPager.SETTINGS, false);
        layout(240, 640);
        ScrollView scroll = activity.findViewById(R.id.settingsScroll);
        int maxScroll = Math.max(0, scroll.getChildAt(0).getHeight()
                + scroll.getPaddingTop() + scroll.getPaddingBottom() - scroll.getHeight());
        assertTrue("Settings content must genuinely scroll on a narrow screen", maxScroll > 0);
        scroll.scrollTo(0, maxScroll);
        Rect action = new Rect();
        View signOut = activity.findViewById(R.id.signOut);
        signOut.getDrawingRect(action);
        root.offsetDescendantRectToMyCoords(signOut, action);
        assertTrue("Sign out remains reachable above the floating navigation", action.bottom <= host.getTop());
        assertTrue(action.top >= 0);
    }

    @Test public void reducedMotionDoesNotQueueDecorativeSpringFrames() {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        Settings.Global.putFloat(activity.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 0f);
        capsule.setProgress(.7f);
        assertEquals(0f, capsule.glassFlow(), 0f);
        assertFalse(capsule.hasScheduledGlassFrame());
    }

    @Test public void backdropRejectsItselfAndAnyRecursiveRootSource() {
        for (View invalid : new View[]{capsule, root, host}) {
            try {
                capsule.setBackdropSource(invalid);
                fail("Recursive source must be rejected");
            } catch (IllegalArgumentException expected) { /* fail safely, original source retained */ }
        }
        assertTrue(capsule.backdrop().listening());
    }

    @Test public void backdropBuffersAreSmallAndSamplingIsRateLimited() {
        long now = SystemClock.uptimeMillis() + 100;
        capsule.backdrop().capture(now);
        assertNotNull(capsule.backdrop().shader());
        assertTrue(capsule.backdrop().pixelCount() > 0);
        assertTrue(capsule.backdrop().pixelCount() <= LiquidGlassBackdrop.MAX_PIXELS);
        int count = capsule.backdrop().captureCount();
        capsule.backdrop().capture(now + 1);
        assertEquals(count, capsule.backdrop().captureCount());
    }

    @Test public void disposeRemovesSamplerCallbacksAndBitmapReferences() {
        capsule.backdrop().capture(SystemClock.uptimeMillis() + 100);
        assertTrue(capsule.backdrop().pixelCount() > 0);
        activity.navigation.dispose();
        assertFalse(capsule.backdrop().listening());
        assertEquals(0, capsule.backdrop().pixelCount());
        assertFalse(capsule.hasScheduledGlassFrame());
        assertEquals(0, activity.findViewById(R.id.mainScroll).getPaddingBottom());
    }

    @Test public void exactSelectorPositionDoesNotLagBehindTheFinger() {
        capsule.setProgress(.48f);
        View indicator = activity.findViewById(R.id.navigationIndicator);
        assertEquals(capsule.getSegmentWidth() * .48f, indicator.getTranslationX(), .01f);
        capsule.setProgress(Float.NaN);
        assertEquals(capsule.getSegmentWidth() * .48f, indicator.getTranslationX(), .01f);
        capsule.setProgress(50f);
        assertEquals(capsule.getSegmentWidth(), indicator.getTranslationX(), .01f);
    }
}
