package com.xlox.ui.designsystem;

import android.content.Context;

import androidx.core.content.ContextCompat;

import com.xlox.R;

/**
 * Java accessor for the v2 token layer. Light/dark resolution happens in
 * values/colors.xml and values-night/colors.xml — one palette per appearance,
 * zero colour literals in any UI class.
 */
public final class Tokens {

    private Tokens() {}

    public static int background(Context c) { return ContextCompat.getColor(c, R.color.background); }
    public static int surface(Context c) { return ContextCompat.getColor(c, R.color.surface); }
    public static int surfaceElevated(Context c) { return ContextCompat.getColor(c, R.color.surface_elevated); }
    public static int surfaceSubtle(Context c) { return ContextCompat.getColor(c, R.color.surface_subtle); }
    public static int surfaceRecessed(Context c) { return ContextCompat.getColor(c, R.color.surface_recessed); }
    public static int outline(Context c) { return ContextCompat.getColor(c, R.color.outline); }
    public static int hairline(Context c) { return ContextCompat.getColor(c, R.color.hairline); }
    public static int hairlineStrong(Context c) { return ContextCompat.getColor(c, R.color.hairline_strong); }
    public static int topLight(Context c) { return ContextCompat.getColor(c, R.color.top_light); }
    public static int textPrimary(Context c) { return ContextCompat.getColor(c, R.color.text_primary); }
    public static int textSecondary(Context c) { return ContextCompat.getColor(c, R.color.text_secondary); }
    public static int textTertiary(Context c) { return ContextCompat.getColor(c, R.color.text_tertiary); }
    public static int icon(Context c) { return ContextCompat.getColor(c, R.color.icon); }
    public static int accent(Context c) { return ContextCompat.getColor(c, R.color.accent); }
    public static int accentPressed(Context c) { return ContextCompat.getColor(c, R.color.accent_pressed); }
    public static int accentInk(Context c) { return ContextCompat.getColor(c, R.color.accent_ink); }
    public static int onAccent(Context c) { return ContextCompat.getColor(c, R.color.on_accent); }
    public static int accentSubtle(Context c) { return ContextCompat.getColor(c, R.color.accent_subtle); }
    public static int accentWash(Context c) { return ContextCompat.getColor(c, R.color.accent_wash); }
    public static int success(Context c) { return ContextCompat.getColor(c, R.color.success); }
    public static int successInk(Context c) { return ContextCompat.getColor(c, R.color.success_ink); }
    public static int successWash(Context c) { return ContextCompat.getColor(c, R.color.success_wash); }
    public static int warning(Context c) { return ContextCompat.getColor(c, R.color.warning); }
    public static int warningInk(Context c) { return ContextCompat.getColor(c, R.color.warning_ink); }
    public static int warningWash(Context c) { return ContextCompat.getColor(c, R.color.warning_wash); }
    public static int error(Context c) { return ContextCompat.getColor(c, R.color.error); }
    public static int errorInk(Context c) { return ContextCompat.getColor(c, R.color.error_ink); }
    public static int errorWash(Context c) { return ContextCompat.getColor(c, R.color.error_wash); }

    public static float px(Context c, int dimenRes) {
        return c.getResources().getDimension(dimenRes);
    }
}
