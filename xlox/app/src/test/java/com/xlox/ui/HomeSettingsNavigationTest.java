package com.xlox.ui;

import static org.junit.Assert.*;

import android.app.Application;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.viewpager.widget.ViewPager;

import com.google.android.material.button.MaterialButton;
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

/** Actual Android view inflation / geometry on host Android; no native game or live account. */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = {21, 28}, qualifiers = "w360dp-h800dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public class HomeSettingsNavigationTest {

    public static class HostActivity extends AppCompatActivity {
        HomeSettingsNavigation navigation;
        int pageChanges;
        int motionChanges;

        @Override
        protected void onCreate(Bundle state) {
            setTheme(R.style.AppTheme);
            super.onCreate(state);
            setContentView(R.layout.activity_main);
            navigation = new HomeSettingsNavigation(findViewById(R.id.mainRoot),
                    new HomeSettingsNavigation.Listener() {
                        @Override
                        public void onPageSelected(boolean settings) {
                            pageChanges++;
                        }

                        @Override
                        public void onMotionChanged(boolean moving) {
                            motionChanges++;
                        }
                    });
        }

        @Override
        protected void onDestroy() {
            navigation.dispose();
            super.onDestroy();
        }
    }

    private ActivityController<HostActivity> controller;
    private HostActivity activity;
    private ViewGroup root;
    private HomeSettingsPager pager;

    @Before
    public void create() {
        controller = Robolectric.buildActivity(HostActivity.class).setup().visible();
        activity = controller.get();
        root = activity.findViewById(R.id.mainRoot);
        pager = activity.findViewById(R.id.homeSettingsPager);
        layout(360, 800);
        Settings.Global.putFloat(activity.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
    }

    @After
    public void destroy() {
        controller.pause().stop().destroy();
    }

    private void layout(int width, int height) {
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private void settle() {
        for (int i = 0; i < 90; i++) {
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16));
            pager.computeScroll();
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    @Test
    public void bothPagesAndBothGameActionsBindBeforeFirstLayout() {
        HomeSettingsPager fresh = new HomeSettingsPager(activity);
        assertNotNull(fresh.findViewById(R.id.primaryAction));
        assertNotNull(fresh.findViewById(R.id.airPrimaryAction));
        assertNotNull(fresh.findViewById(R.id.licenceKeyValue));
        assertEquals(2, fresh.getAdapter().getCount());
        assertEquals(1, fresh.getOffscreenPageLimit());
    }

    @Test
    public void homeAndSettingsTabsAnimateInBothDirections() {
        assertTrue(activity.navigation.homeIsSettled());
        assertTrue(activity.findViewById(R.id.navigationHome).isSelected());
        activity.findViewById(R.id.navigationSettings).performClick();
        assertEquals(HomeSettingsPager.SETTINGS, activity.navigation.currentPage());
        settle();
        assertEquals(pager.getWidth(), pager.getScrollX());
        assertTrue(activity.findViewById(R.id.navigationSettings).isSelected());
        assertFalse(activity.navigation.homeIsSettled());
        activity.findViewById(R.id.navigationHome).performClick();
        settle();
        assertEquals(0, pager.getScrollX());
        assertTrue(activity.navigation.homeIsSettled());
        assertTrue(activity.pageChanges >= 2);
        assertTrue(activity.motionChanges >= 2);
    }

    @Test
    public void halfwayDragShowsHalfOfEachOpaquePageAndMovesTheCapsule() {
        View home = activity.findViewById(R.id.homePage);
        View settings = activity.findViewById(R.id.settingsPage);
        View capsule = activity.findViewById(R.id.bottomCapsule);
        View highlight = activity.findViewById(R.id.navigationIndicator);
        int capsuleTop = capsule.getTop();
        assertTrue(pager.beginFakeDrag());
        pager.fakeDragBy(-pager.getWidth() / 2f);
        assertEquals(pager.getWidth() / 2, pager.getScrollX());
        assertEquals(-pager.getWidth() / 2, home.getLeft() - pager.getScrollX());
        assertEquals(pager.getWidth() / 2, settings.getLeft() - pager.getScrollX());
        assertEquals(View.VISIBLE, home.getVisibility());
        assertEquals(View.VISIBLE, settings.getVisibility());
        assertEquals(1f, home.getAlpha(), 0f);
        assertEquals(1f, settings.getAlpha(), 0f);
        assertEquals(highlight.getWidth() / 2f, highlight.getTranslationX(), 1f);
        assertEquals(capsuleTop, capsule.getTop());
        pager.endFakeDrag();
        settle();
    }

    @Test
    public void cancelledPartialDragReturnsHomeWithoutChangingGameControls() {
        View bgmi = activity.findViewById(R.id.primaryAction);
        View air = activity.findViewById(R.id.airPrimaryAction);
        assertTrue(pager.beginFakeDrag());
        // Stay below the page-change threshold, with zero fake-drag velocity.
        pager.fakeDragBy(-pager.getWidth() / 10f);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500));
        pager.endFakeDrag();
        settle();
        assertEquals(HomeSettingsPager.HOME, activity.navigation.currentPage());
        assertEquals(0, pager.getScrollX());
        assertSame(bgmi, activity.findViewById(R.id.primaryAction));
        assertSame(air, activity.findViewById(R.id.airPrimaryAction));
        assertFalse(air.isEnabled());
    }

    @Test
    public void touchSwipesSelectSettingsAndHomeWithoutTabTaps() {
        swipe(0.85f, 0.12f, 0.4f);
        assertEquals(HomeSettingsPager.SETTINGS, activity.navigation.currentPage());
        assertEquals(pager.getWidth(), pager.getScrollX());
        swipe(0.12f, 0.85f, 0.4f);
        assertEquals(HomeSettingsPager.HOME, activity.navigation.currentPage());
        assertEquals(0, pager.getScrollX());
    }

    @Test
    public void diagonalSwipeFromScrolledSettingsContentReachesHome() {
        activity.navigation.select(HomeSettingsPager.SETTINGS, false);
        ScrollView scroll = activity.findViewById(R.id.settingsScroll);
        scroll.scrollTo(0, 180);
        assertTrue(scroll.getScrollY() > 0);
        // A real, predominantly horizontal finger path, not a fake drag or a
        // perfectly level swipe dispatched directly to ViewPager. The vertical
        // ScrollView must not claim it just because the finger also moves down.
        long down = SystemClock.uptimeMillis();
        for (int i = 0; i <= 12; i++) {
            int action = i == 0 ? MotionEvent.ACTION_DOWN : i == 12
                    ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE;
            MotionEvent event = MotionEvent.obtain(down, down + i * 20L, action,
                    60f + 20f * i, 220f + (160f / 12f) * i, 0);
            root.dispatchTouchEvent(event);
            event.recycle();
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20));
        }
        settle();
        assertEquals(HomeSettingsPager.HOME, activity.navigation.currentPage());
        assertEquals(0, pager.getScrollX());
    }

    private void swipe(float start, float end, float y) {
        long down = SystemClock.uptimeMillis();
        for (int i = 0; i <= 12; i++) {
            int action = i == 0 ? MotionEvent.ACTION_DOWN : i == 12
                    ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE;
            float x = (start + (end - start) * i / 12f) * pager.getWidth();
            MotionEvent event = MotionEvent.obtain(down, down + i * 20L, action,
                    x, y * pager.getHeight(), 0);
            pager.dispatchTouchEvent(event);
            event.recycle();
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20));
        }
        settle();
    }

    @Test
    public void helpLivesOnlyInSettingsAndSignOutIsCenteredRedWithExistingId() {
        View home = activity.findViewById(R.id.homePage);
        View settings = activity.findViewById(R.id.settingsPage);
        assertNull(home.findViewById(R.id.helpButton));
        assertNotNull(settings.findViewById(R.id.helpButton));
        MaterialButton signOut = settings.findViewById(R.id.signOut);
        assertEquals(Gravity.CENTER, signOut.getGravity());
        assertEquals(ContextCompat.getColor(activity, R.color.danger), signOut.getCurrentTextColor());
        assertEquals(activity.getString(R.string.sign_out), signOut.getText().toString());
        assertEquals("Home", ((TextView) home.findViewById(R.id.homeTitle)).getText().toString());
    }

    @Test
    public void scrollPositionsAndGameButtonsAreRetainedBetweenTabs() {
        ScrollView scroll = activity.findViewById(R.id.settingsScroll);
        activity.navigation.select(HomeSettingsPager.SETTINGS, false);
        scroll.scrollTo(0, 180);
        int position = scroll.getScrollY();
        assertTrue(position > 0);
        activity.navigation.select(HomeSettingsPager.HOME, false);
        activity.navigation.select(HomeSettingsPager.SETTINGS, false);
        assertEquals(position, scroll.getScrollY());
        View actionBar = activity.findViewById(R.id.actionBar);
        assertSame(activity.findViewById(R.id.homePage), actionBar.getParent());
        assertEquals(View.VISIBLE, actionBar.getVisibility());
    }

    @Test
    public void reducedMotionSkipsTapAnimationButStillAllowsFingerTracking() {
        Settings.Global.putFloat(activity.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 0f);
        activity.findViewById(R.id.navigationSettings).performClick();
        assertEquals(pager.getWidth(), pager.getScrollX());
        assertTrue(pager.beginFakeDrag());
        pager.fakeDragBy(pager.getWidth() / 2f);
        assertEquals(pager.getWidth() / 2, pager.getScrollX());
        pager.endFakeDrag();
        settle();
    }

    @Test
    public void offscreenPageIsExcludedFromAccessibilityWithoutHidingItsPixels() {
        View home = activity.findViewById(R.id.homePage);
        View settings = activity.findViewById(R.id.settingsPage);
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
                settings.getImportantForAccessibility());
        activity.navigation.select(HomeSettingsPager.SETTINGS, false);
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
                home.getImportantForAccessibility());
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO, settings.getImportantForAccessibility());
        assertEquals(View.VISIBLE, home.getVisibility());
        assertEquals(1f, home.getAlpha(), 0f);
    }

    @Test
    public void capsuleFitsNarrowScreensAndDoesNotOverlapThePager() {
        layout(240, 640);
        View capsule = activity.findViewById(R.id.bottomCapsule);
        View host = activity.findViewById(R.id.bottomNavigationHost);
        assertTrue(capsule.getWidth() <= host.getWidth() - host.getPaddingLeft() - host.getPaddingRight());
        assertTrue(activity.findViewById(R.id.navigationHome).getHeight() >= Ui.dp(activity, 48));
        assertTrue(activity.findViewById(R.id.navigationSettings).getHeight() >= Ui.dp(activity, 48));
        assertEquals(pager.getBottom(), host.getTop());
        assertTrue("Decoration cannot consume the page viewport", pager.getHeight() > root.getHeight() / 2);
        assertTrue("Default-scale capsule stays compact", capsule.getHeight() < Ui.dp(activity, 120));
        Rect bounds = new Rect();
        capsule.getDrawingRect(bounds);
        root.offsetDescendantRectToMyCoords(capsule, bounds);
        assertTrue(bounds.left >= 0 && bounds.right <= root.getWidth());
    }

    @Test
    public void pagerRestoresSelectedPageAfterHostRecreation() {
        activity.navigation.select(HomeSettingsPager.SETTINGS, false);
        Bundle state = new Bundle();
        controller.saveInstanceState(state).pause().stop().destroy();
        controller = Robolectric.buildActivity(HostActivity.class).create(state).start()
                .restoreInstanceState(state).resume().visible();
        activity = controller.get();
        root = activity.findViewById(R.id.mainRoot);
        pager = activity.findViewById(R.id.homeSettingsPager);
        layout(360, 800);
        assertEquals(HomeSettingsPager.SETTINGS, activity.navigation.currentPage());
        assertTrue(activity.findViewById(R.id.navigationSettings).isSelected());
    }
}
