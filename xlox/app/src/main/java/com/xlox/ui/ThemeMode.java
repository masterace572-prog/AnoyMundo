package com.xlox.ui;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;

import androidx.appcompat.app.AppCompatDelegate;

import com.xlox.R;
import com.xlox.ui.designsystem.DesignSystem;
import com.xlox.ui.designsystem.ThemeToggleView;

/** Dark-first appearance switch. The chosen mode is persisted across launches. */
public final class ThemeMode {

    private static final String PREFS = "anoy_appearance";
    private static final String KEY = "mode";

    private ThemeMode() {}

    /** Call before super.onCreate() so the window picks the right palette. */
    public static void apply(Context context) {
        String value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, "dark");
        int wanted = "light".equals(value) ? AppCompatDelegate.MODE_NIGHT_NO
                : "system".equals(value) ? AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                : AppCompatDelegate.MODE_NIGHT_YES;
        if (AppCompatDelegate.getDefaultNightMode() != wanted) {
            AppCompatDelegate.setDefaultNightMode(wanted);
        }
    }

    public static boolean isDark(Context context) {
        int mask = context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return mask == Configuration.UI_MODE_NIGHT_YES;
    }

    /** Flip between light and dark and let AppCompat recreate the activity. */
    public static void toggle(Context context) {
        String next = isDark(context) ? "light" : "dark";
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, next).apply();
        AppCompatDelegate.setDefaultNightMode(next.equals("dark")
                ? AppCompatDelegate.MODE_NIGHT_YES
                : AppCompatDelegate.MODE_NIGHT_NO);
    }

    /** Keep the pill switch's state and TalkBack label in sync with the palette. */
    public static void decorateToggle(Context context, ThemeToggleView toggle) {
        if (toggle == null) return;
        boolean dark = isDark(context);
        toggle.setDark(dark, false);
        toggle.setContentDescription(context.getString(
                dark ? R.string.switch_to_light : R.string.switch_to_dark));
    }

    /** Transparent bars, edge-to-edge layout and theme-aware icon contrast. */
    public static void applyWindow(Activity activity) {
        DesignSystem.applyEdgeToEdge(activity);
    }
}
