package com.xlox.ui;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import android.view.View;
import android.view.Window;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;

import com.xlox.R;

/** Small helper for the clean light / dark appearance switch. */
public final class ThemeMode {

    private static final String PREFS = "anoy_appearance";
    private static final String KEY = "mode";

    private ThemeMode() {}

    /** Call before super.onCreate() so the window picks the right palette. */
    public static void apply(Context context) {
        String value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, "system");
        int wanted = "dark".equals(value) ? AppCompatDelegate.MODE_NIGHT_YES
                : "light".equals(value) ? AppCompatDelegate.MODE_NIGHT_NO
                : AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
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

    /** Keep the system bars in sync with the selected appearance. */
    public static void applyWindow(Activity activity) {
        Window window = activity.getWindow();
        if (window == null) return;
        int background = ContextCompat.getColor(activity, R.color.anoyBg);
        window.setStatusBarColor(background);
        window.setNavigationBarColor(background);
        View decor = window.getDecorView();
        int flags = decor.getSystemUiVisibility();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (isDark(activity)) {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            } else {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (isDark(activity)) {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            } else {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
        }
        decor.setSystemUiVisibility(flags);
    }
}
