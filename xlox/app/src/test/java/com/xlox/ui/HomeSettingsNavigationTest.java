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
import androidx.core.content.res.ResourcesCompat;
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
        int gameClicks;

        @Override
        protected void onCreate(Bundle state) {
            setTheme(R.style.AppTheme);
            super.onCreate(state);
            setContentView(R.layout.activity_main);
            findViewById(R.id.primaryAction).setOnClickListener(v -> gameClicks++);
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
        assertNotNull(fresh.findViewById(R.id.airStatusMessage));
        assertNotNull(fresh.findViewById(R.id.airTaskProgress));
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
        CapsuleNavigation capsule = activity.findViewById(R.id.bottomCapsule);
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
        assertEquals(capsule.getSegmentWidth() / 2f, highlight.getTranslationX(), 1f);
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
            root.dispatchTouchEvent(event);
            event.recycle();
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20));
        }
        settle();
    }

    @Test
    public void draggingTheBottomSelectorSwitchesBothWaysWithoutTabTaps() {
        Rect capsule = bounds(activity.findViewById(R.id.bottomCapsule));
        float left = capsule.centerX() - capsule.width() / 4f;
        float right = capsule.centerX() + capsule.width() / 4f;
        drag(left, capsule.centerY(), right, capsule.centerY(), MotionEvent.ACTION_UP);
        assertEquals(HomeSettingsPager.SETTINGS, activity.navigation.currentPage());
        assertEquals(pager.getWidth(), pager.getScrollX());
        drag(right, capsule.centerY(), left, capsule.centerY(), MotionEvent.ACTION_UP);
        assertEquals(HomeSettingsPager.HOME, activity.navigation.currentPage());
        assertEquals(0, pager.getScrollX());
    }

    @Test
    public void capsuleTracksPixelsMidDragAndCancellationRestoresTheStartingPage() {
        CapsuleNavigation capsule = activity.findViewById(R.id.bottomCapsule);
        Rect box = bounds(capsule);
        float start = box.centerX() - capsule.getSegmentWidth() / 2f;
        long down = SystemClock.uptimeMillis();
        touch(down, MotionEvent.ACTION_DOWN, start, box.centerY());
        touch(down, MotionEvent.ACTION_MOVE, start + capsule.getSegmentWidth() * .45f, box.centerY());
        assertTrue(pager.isFakeDragging());
        assertEquals(pager.getWidth() * .45f, pager.getScrollX(), 2f);
        assertEquals(capsule.getSegmentWidth() * .45f,
                activity.findViewById(R.id.navigationIndicator).getTranslationX(), 2f);
        touch(down, MotionEvent.ACTION_MOVE, start + capsule.getSegmentWidth() * .8f, box.centerY());
        touch(down, MotionEvent.ACTION_CANCEL, start + capsule.getSegmentWidth() * .8f, box.centerY());
        settle();
        assertFalse(pager.isFakeDragging());
        assertEquals(HomeSettingsPager.HOME, activity.navigation.currentPage());
        assertEquals(0, pager.getScrollX());
        assertEquals(0, activity.gameClicks);
    }

    @Test
    public void capsuleGesturesCancelLabelClicksButRealTapsStillSelectBothPages() {
        View home = activity.findViewById(R.id.navigationHome);
        View settings = activity.findViewById(R.id.navigationSettings);
        tap(settings);
        settle();
        assertEquals(HomeSettingsPager.SETTINGS, activity.navigation.currentPage());
        tap(home);
        settle();
        assertEquals(HomeSettingsPager.HOME, activity.navigation.currentPage());
        int[] clicks = {0};
        home.setOnClickListener(v -> clicks[0]++);
        settings.setOnClickListener(v -> clicks[0]++);
        Rect box = bounds(activity.findViewById(R.id.bottomCapsule));
        drag(box.centerX() - box.width() / 4f, box.centerY(),
                box.centerX() + box.width() / 4f, box.centerY(), MotionEvent.ACTION_UP);
        assertEquals(HomeSettingsPager.SETTINGS, activity.navigation.currentPage());
        assertEquals(0, clicks[0]);
        tap(home);
        assertEquals(1, clicks[0]);
    }

    @Test
    public void verticalCapsuleMotionDoesNotTurnIntoATabClickOrPageSwipe() {
        View home = activity.findViewById(R.id.navigationHome);
        int[] clicks = {0};
        home.setOnClickListener(v -> clicks[0]++);
        Rect box = bounds(home);
        long down = SystemClock.uptimeMillis();
        touch(down, MotionEvent.ACTION_DOWN, box.centerX(), box.centerY() - 8);
        touch(down, MotionEvent.ACTION_MOVE, box.centerX() + 1, box.centerY() + 10);
        touch(down, MotionEvent.ACTION_UP, box.centerX() + 2, box.centerY() + 10);
        settle();
        assertEquals(0, clicks[0]);
        assertFalse(home.isPressed());
        assertFalse(pager.isFakeDragging());
        assertEquals(0, pager.getScrollX());
    }

    @Test
    public void insetSelectorKeepsFullTabPitchAndAccessibleTargetsAtBothEnds() {
        CapsuleNavigation capsule = activity.findViewById(R.id.bottomCapsule);
        View indicator = activity.findViewById(R.id.navigationIndicator);
        View home = activity.findViewById(R.id.navigationHome);
        View settings = activity.findViewById(R.id.navigationSettings);
        int inset = activity.getResources().getDimensionPixelSize(R.dimen.nav_indicator_inset);
        assertEquals(Ui.dp(activity, 2), inset);
        assertEquals(capsule.getPaddingLeft() + inset, indicator.getLeft());
        assertEquals(home.getHeight() - 2 * inset, indicator.getHeight());
        assertEquals(capsule.getSegmentWidth() - 2 * inset, indicator.getWidth());
        assertTrue(home.getHeight() >= Ui.dp(activity, 48));
        assertTrue(settings.getHeight() >= Ui.dp(activity, 48));
        activity.navigation.select(HomeSettingsPager.SETTINGS, false);
        assertEquals(capsule.getSegmentWidth(), indicator.getTranslationX(), .1f);
        assertEquals(capsule.getWidth() - capsule.getPaddingRight() - inset,
                indicator.getRight() + indicator.getTranslationX(), 1f);
    }

    @Test
    public void diagonalSwipeStartingOnLaunchCancelsTheButtonAndTapStillWorks() {
        View action = activity.findViewById(R.id.primaryAction);
        Rect box = bounds(action);
        drag(box.right - 20, box.centerY(), box.left + 12,
                box.centerY() + (box.width() - 32) * 0.6f, MotionEvent.ACTION_UP);
        assertEquals(HomeSettingsPager.SETTINGS, activity.navigation.currentPage());
        assertEquals(0, activity.gameClicks);
        assertFalse(action.isPressed());
        activity.navigation.select(HomeSettingsPager.HOME, false);
        tap(action);
        assertEquals(1, activity.gameClicks);
    }

    @Test
    public void verticalSwipeOverSettingsRowScrollsWithoutChangingPageOrClicking() {
        activity.navigation.select(HomeSettingsPager.SETTINGS, false);
        View row = activity.findViewById(R.id.licenceKeyRow);
        int[] clicks = {0};
        row.setOnClickListener(v -> clicks[0]++);
        Rect box = bounds(row);
        drag(box.left + 30, box.centerY(), box.left + 60, 24, MotionEvent.ACTION_UP);
        assertEquals(HomeSettingsPager.SETTINGS, activity.navigation.currentPage());
        assertTrue(activity.<ScrollView>findViewById(R.id.settingsScroll).getScrollY() > 0);
        assertEquals(0, clicks[0]);
        assertFalse(row.isPressed());
    }

    @Test
    public void verticalSwipeStartingOnGameButtonKeepsItsScrollAndNeverLaunches() {
        TextView note = activity.findViewById(R.id.panelNote);
        StringBuilder longNote = new StringBuilder();
        for (int i = 0; i < 60; i++) longNote.append("A longer announcement for scrolling.\n");
        note.setText(longNote);
        activity.findViewById(R.id.panelNoteCard).setVisibility(View.VISIBLE);
        layout(360, 800);
        ScrollView scroll = activity.findViewById(R.id.mainScroll);
        scroll.scrollTo(0, activity.findViewById(R.id.gameCard).getTop());
        int before = scroll.getScrollY();
        assertTrue(before > 0);
        Rect box = bounds(activity.findViewById(R.id.primaryAction));
        assertTrue(box.centerY() < pager.getBottom());
        drag(box.left + 30, box.centerY(), box.left + 65, box.centerY() + 120, MotionEvent.ACTION_UP);
        assertTrue(scroll.getScrollY() < before);
        assertEquals(HomeSettingsPager.HOME, activity.navigation.currentPage());
        assertEquals(0, activity.gameClicks);
    }

    @Test
    public void realTouchHalfwayShowsBothPagesAndCancelledStrokeReturnsHome() {
        long down = SystemClock.uptimeMillis();
        touch(down, MotionEvent.ACTION_DOWN, 320, 180);
        touch(down, MotionEvent.ACTION_MOVE, 298, 185);
        touch(down, MotionEvent.ACTION_MOVE, 220, 215);
        touch(down, MotionEvent.ACTION_MOVE, 124, 250);
        int offset = pager.getScrollX();
        assertTrue("Finger movement must move pixels before release", offset > pager.getWidth() / 3);
        assertTrue(offset < pager.getWidth() * 2 / 3);
        View home = activity.findViewById(R.id.homePage);
        View settings = activity.findViewById(R.id.settingsPage);
        assertEquals(-offset, home.getLeft() - offset);
        assertEquals(pager.getWidth() - offset, settings.getLeft() - offset);
        assertEquals(1f, home.getAlpha(), 0f);
        assertEquals(1f, settings.getAlpha(), 0f);
        View highlight = activity.findViewById(R.id.navigationIndicator);
        assertEquals(activity.<CapsuleNavigation>findViewById(R.id.bottomCapsule).getSegmentWidth()
                * offset / (float) pager.getWidth(), highlight.getTranslationX(), 1f);
        touch(down, MotionEvent.ACTION_CANCEL, 124, 250);
        settle();
        assertEquals(HomeSettingsPager.HOME, activity.navigation.currentPage());
        assertEquals(0, pager.getScrollX());
        assertEquals(0, activity.gameClicks);
        tap(activity.findViewById(R.id.primaryAction));
        assertEquals(1, activity.gameClicks);
    }

    @Test
    public void aVerticalGestureCannotBecomeAPageSwipeMidStroke() {
        activity.navigation.select(HomeSettingsPager.SETTINGS, false);
        long down = SystemClock.uptimeMillis();
        touch(down, MotionEvent.ACTION_DOWN, 80, 350);
        touch(down, MotionEvent.ACTION_MOVE, 84, 290);
        touch(down, MotionEvent.ACTION_MOVE, 160, 250);
        touch(down, MotionEvent.ACTION_MOVE, 300, 245);
        touch(down, MotionEvent.ACTION_UP, 300, 245);
        settle();
        assertEquals(HomeSettingsPager.SETTINGS, activity.navigation.currentPage());
        assertEquals(pager.getWidth(), pager.getScrollX());
        assertTrue(activity.<ScrollView>findViewById(R.id.settingsScroll).getScrollY() > 0);
    }

    @Test
    public void eachCardOwnsItsActionFeedbackAndOnlyGameSpecificState() {
        View bgmi = activity.findViewById(R.id.gameCard);
        View air = activity.findViewById(R.id.airGameCard);
        assertNotNull(bgmi.findViewById(R.id.gameTile));
        assertNotNull(bgmi.findViewById(R.id.gameVersion));
        assertNotNull(bgmi.findViewById(R.id.gameStateValue));
        assertNotNull(bgmi.findViewById(R.id.primaryAction));
        assertNotNull(bgmi.findViewById(R.id.statusMessage));
        assertNotNull(bgmi.findViewById(R.id.taskProgress));
        assertNull(bgmi.findViewById(R.id.airPrimaryAction));
        assertNull(bgmi.findViewById(R.id.serverValue));
        assertNull(bgmi.findViewById(R.id.statusValue));
        assertNotNull(air.findViewById(R.id.airPrimaryAction));
        assertNotNull(air.findViewById(R.id.airStatusMessage));
        assertNotNull(air.findViewById(R.id.airTaskProgress));
        assertNull(air.findViewById(R.id.primaryAction));
        assertNull(air.findViewById(R.id.serverValue));
        assertEquals("Coming soon", activity.<TextView>findViewById(R.id.airPrimaryAction).getText().toString());
        assertFalse(activity.findViewById(R.id.airPrimaryAction).isEnabled());
        assertEquals(View.GONE, activity.findViewById(R.id.airGameStateValue).getVisibility());
    }

    @Test
    public void compactExpiryAndGlobalStatusesStayInTheTopRightHeader() {
        TextView active = activity.findViewById(R.id.statusValue);
        TextView safe = activity.findViewById(R.id.serverValue);
        StatusText.set(active, "Active", R.color.success, R.color.text_secondary);
        StatusText.set(safe, "Safe", R.color.success, R.color.text_secondary);
        layout(360, 800);
        View header = activity.findViewById(R.id.mainHeader);
        Rect title = bounds(activity.findViewById(R.id.homeTitle));
        Rect expiry = bounds(activity.findViewById(R.id.timeLeftValue));
        Rect status = bounds(active);
        Rect server = bounds(safe);
        assertNotNull(header.findViewById(R.id.licenceSummary));
        assertNotNull(header.findViewById(R.id.headerStatuses));
        assertTrue(expiry.left >= title.right);
        assertTrue(status.top >= expiry.bottom);
        assertEquals(status.top + active.getBaseline(), server.top + safe.getBaseline());
        assertTrue(server.left > status.right);
        assertEquals(expiry.right, server.right);
        assertTrue(bounds(activity.findViewById(R.id.gameCard)).top >= bounds(header).bottom);
        assertTrue(activity.<TextView>findViewById(R.id.timeLeftValue).getTextSize()
                < activity.<TextView>findViewById(R.id.homeTitle).getTextSize());
    }

    @Test
    public void longHeaderStatusesWrapInsideTheirColumnInsteadOfColliding() {
        TextView active = activity.findViewById(R.id.statusValue);
        TextView server = activity.findViewById(R.id.serverValue);
        StatusText.set(active, "Expires soon", R.color.warning, R.color.text_secondary);
        StatusText.set(server, "Server under maintenance", R.color.warning, R.color.text_secondary);
        active.setTextSize(24f);
        server.setTextSize(24f);
        layout(320, 800);
        Rect first = bounds(active);
        Rect second = bounds(server);
        Rect column = bounds(activity.findViewById(R.id.headerAccount));
        assertTrue(second.top >= first.bottom);
        assertEquals(column.right, first.right);
        assertEquals(column.right, second.right);
        assertTrue(first.left >= column.left);
        assertTrue(second.left >= column.left);
        assertTrue(bounds(activity.findViewById(R.id.gameCard)).top
                >= bounds(activity.findViewById(R.id.mainHeader)).bottom);
    }

    @Test
    public void staticDashboardFontsLoadOnSupportedAndroidAndTabsUseWhiteOnBlueText() {
        assertNotNull(ResourcesCompat.getFont(activity, R.font.manrope_regular));
        assertNotNull(ResourcesCompat.getFont(activity, R.font.manrope_medium));
        assertNotNull(ResourcesCompat.getFont(activity, R.font.manrope_semibold));
        assertEquals(22f, activity.<TextView>findViewById(R.id.homeTitle).getTextSize(), 0.1f);
        assertEquals(13f, activity.<TextView>findViewById(R.id.timeLeftValue).getTextSize(), 0.1f);
        assertEquals(ContextCompat.getColor(activity, R.color.on_accent),
                activity.<TextView>findViewById(R.id.navigationHome).getCurrentTextColor());
        activity.navigation.select(HomeSettingsPager.SETTINGS, false);
        assertEquals(ContextCompat.getColor(activity, R.color.on_accent),
                activity.<TextView>findViewById(R.id.navigationSettings).getCurrentTextColor());
        assertEquals(ContextCompat.getColor(activity, R.color.text_secondary),
                activity.<TextView>findViewById(R.id.navigationHome).getCurrentTextColor());
    }

    private Rect bounds(View view) {
        Rect box = new Rect();
        view.getDrawingRect(box);
        root.offsetDescendantRectToMyCoords(view, box);
        return box;
    }

    private void touch(long down, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
        root.dispatchTouchEvent(event);
        event.recycle();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20));
    }

    private void drag(float x1, float y1, float x2, float y2, int terminal) {
        long down = SystemClock.uptimeMillis();
        for (int i = 0; i <= 12; i++) {
            int action = i == 0 ? MotionEvent.ACTION_DOWN : i == 12 ? terminal : MotionEvent.ACTION_MOVE;
            touch(down, action, x1 + (x2 - x1) * i / 12f, y1 + (y2 - y1) * i / 12f);
        }
        settle();
    }

    private void tap(View view) {
        Rect box = bounds(view);
        long down = SystemClock.uptimeMillis();
        touch(down, MotionEvent.ACTION_DOWN, box.centerX(), box.centerY());
        touch(down, MotionEvent.ACTION_UP, box.centerX(), box.centerY());
        Shadows.shadowOf(Looper.getMainLooper()).idle();
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
        assertEquals("Welcome back", ((TextView) home.findViewById(R.id.homeTitle)).getText().toString());
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
        View bgmi = activity.findViewById(R.id.primaryAction);
        View air = activity.findViewById(R.id.airPrimaryAction);
        assertSame(bgmi, activity.findViewById(R.id.gameCard).findViewById(R.id.primaryAction));
        assertSame(air, activity.findViewById(R.id.airGameCard).findViewById(R.id.airPrimaryAction));
        assertEquals(View.VISIBLE, bgmi.getVisibility());
        assertEquals(View.VISIBLE, air.getVisibility());
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
