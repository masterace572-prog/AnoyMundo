package com.xlox.ui.designsystem;

import android.content.Context;

import androidx.core.content.ContextCompat;

import com.xlox.R;

/**
 * Java accessor for the B.1 token layer. Light/dark resolution happens in
 * values/colors.xml and values-night/colors.xml — one palette per appearance
 * and no colour literal in any UI class.
 */
public final class Tokens {

    private Tokens() {}

    public static int background(Context c) { return ContextCompat.getColor(c, R.color.background); }
    public static int surface(Context c) { return ContextCompat.getColor(c, R.color.surface); }
    public static int surfaceElevated(Context c) { return ContextCompat.getColor(c, R.color.surface_elevated); }
    public static int surfaceSubtle(Context c) { return ContextCompat.getColor(c, R.color.surface_subtle); }
    public static int outline(Context c) { return ContextCompat.getColor(c, R.color.outline); }
    public static int textPrimary(Context c) { return ContextCompat.getColor(c, R.color.text_primary); }
    public static int textSecondary(Context c) { return ContextCompat.getColor(c, R.color.text_secondary); }
    public static int textTertiary(Context c) { return ContextCompat.getColor(c, R.color.text_tertiary); }
    public static int accent(Context c) { return ContextCompat.getColor(c, R.color.accent); }
    public static int accentPressed(Context c) { return ContextCompat.getColor(c, R.color.accent_pressed); }
    public static int onAccent(Context c) { return ContextCompat.getColor(c, R.color.on_accent); }
    public static int accentSubtle(Context c) { return ContextCompat.getColor(c, R.color.accent_subtle); }
    public static int error(Context c) { return ContextCompat.getColor(c, R.color.error); }
    public static int success(Context c) { return ContextCompat.getColor(c, R.color.success); }
    public static int warning(Context c) { return ContextCompat.getColor(c, R.color.warning); }
}
