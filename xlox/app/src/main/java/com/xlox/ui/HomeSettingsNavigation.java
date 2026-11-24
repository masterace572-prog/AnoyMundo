package com.xlox.ui;

import android.view.View;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.core.view.AccessibilityDelegateCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.viewpager.widget.ViewPager;

import com.xlox.R;

/** Shared tap, swipe, highlight and accessibility state for Home / Settings. */
public final class HomeSettingsNavigation extends ViewPager.SimpleOnPageChangeListener {

    public interface Listener {
        void onPageSelected(boolean settings);
        void onMotionChanged(boolean moving);
    }

    private final HomeSettingsPager pager;
    private final CapsuleNavigation capsule;
    private final TextView home;
    private final TextView settings;
    private final View homePage;
    private final View settingsPage;
    private final Listener listener;
    private final int activeColor;
    private final int inactiveColor;
    private int scrollState = ViewPager.SCROLL_STATE_IDLE;

    public HomeSettingsNavigation(View root, Listener listener) {
        this.listener = listener;
        pager = root.findViewById(R.id.homeSettingsPager);
        capsule = root.findViewById(R.id.bottomCapsule);
        home = root.findViewById(R.id.navigationHome);
        settings = root.findViewById(R.id.navigationSettings);
        homePage = root.findViewById(R.id.homePage);
        settingsPage = root.findViewById(R.id.settingsPage);
        activeColor = ContextCompat.getColor(root.getContext(), R.color.text_primary);
        inactiveColor = ContextCompat.getColor(root.getContext(), R.color.text_secondary);
        home.setOnClickListener(v -> {
            Ui.tap(v);
            select(HomeSettingsPager.HOME, true);
        });
        settings.setOnClickListener(v -> {
            Ui.tap(v);
            select(HomeSettingsPager.SETTINGS, true);
        });
        AccessibilityDelegateCompat tab = new AccessibilityDelegateCompat() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfoCompat info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName("android.widget.Button");
                info.setSelected(host.isSelected());
            }
        };
        ViewCompat.setAccessibilityDelegate(home, tab);
        ViewCompat.setAccessibilityDelegate(settings, tab);
        pager.addOnPageChangeListener(this);
        updateSelection();
        updateProgress(pager.getCurrentItem());
    }

    public void select(int page, boolean animate) {
        if (page != HomeSettingsPager.HOME && page != HomeSettingsPager.SETTINGS) {
            throw new IllegalArgumentException("Unknown navigation page");
        }
        pager.setCurrentItem(page, animate && !Ui.reducedMotion(pager.getContext()));
        updateSelection();
        if (!animate || Ui.reducedMotion(pager.getContext())) updateProgress(page);
    }

    public int currentPage() {
        return pager.getCurrentItem();
    }

    public boolean homeIsSettled() {
        return currentPage() == HomeSettingsPager.HOME && scrollState == ViewPager.SCROLL_STATE_IDLE;
    }

    @Override
    public void onPageScrolled(int position, float offset, int pixels) {
        updateProgress(position + offset);
    }

    @Override
    public void onPageSelected(int position) {
        updateSelection();
        listener.onPageSelected(position == HomeSettingsPager.SETTINGS);
    }

    @Override
    public void onPageScrollStateChanged(int state) {
        scrollState = state;
        listener.onMotionChanged(state != ViewPager.SCROLL_STATE_IDLE);
    }

    private void updateProgress(float position) {
        float progress = Math.max(0f, Math.min(1f, position));
        capsule.setProgress(progress);
        home.setTextColor(ColorUtils.blendARGB(activeColor, inactiveColor, progress));
        settings.setTextColor(ColorUtils.blendARGB(inactiveColor, activeColor, progress));
    }

    private void updateSelection() {
        boolean onSettings = currentPage() == HomeSettingsPager.SETTINGS;
        home.setSelected(!onSettings);
        settings.setSelected(onSettings);
        // Retained offscreen views must not expose hidden launch / account actions
        // to TalkBack or keyboard traversal. Visibility and alpha remain untouched.
        homePage.setImportantForAccessibility(onSettings
                ? View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                : View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        settingsPage.setImportantForAccessibility(onSettings
                ? View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                : View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    }

    public void dispose() {
        pager.removeOnPageChangeListener(this);
        home.setOnClickListener(null);
        settings.setOnClickListener(null);
    }
}
