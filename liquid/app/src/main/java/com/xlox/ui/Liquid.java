package com.xlox.ui;

import android.app.Activity;
import android.content.Context;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;

import com.xlox.R;

/** Visual-only policy. Never reads or writes the loader's licence preferences. */
public final class Liquid {
    private static final String PREFS = "liquid_appearance";
    private static final String EFFECTS = "glass_effects";
    private static long policyCheckedAt;
    private static boolean cachedEffects;

    private Liquid() { }

    public static boolean preferredEffects(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(EFFECTS, true);
    }

    /** High contrast and battery saving always win over decorative transparency. */
    public static boolean effects(Context context) {
        // Never make a settings-provider/Binder round trip for every draw call.
        long now = android.os.SystemClock.elapsedRealtime();
        if (policyCheckedAt > 0 && now - policyCheckedAt < 500) return cachedEffects;
        boolean allowed = preferredEffects(context);
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (power != null && power.isPowerSaveMode()) allowed = false;
        try {
            if (Settings.Secure.getInt(context.getContentResolver(),
                    "high_text_contrast_enabled", 0) != 0) allowed = false;
        } catch (RuntimeException ignored) {
            allowed = false; // A solid, legible surface is the safe fallback.
        }
        cachedEffects = allowed;
        policyCheckedAt = now;
        return allowed;
    }

    public static void setEffects(Activity activity, boolean enabled) {
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(EFFECTS, enabled).apply();
        policyCheckedAt = 0;
        refresh(activity.findViewById(android.R.id.content));
    }

    /** Called after inflation on every app-owned screen, not in the virtual game. */
    public static void attach(Activity activity) {
        ViewGroup host = activity.findViewById(android.R.id.content);
        if (host == null || host.getChildCount() == 0) return;
        host.getChildAt(0).setBackground(new LiquidBackdrop(activity));
        View settings = activity.findViewById(R.id.settingsPage);
        if (settings != null) settings.setBackground(new LiquidBackdrop(activity));
        bind(activity, R.id.actionPanel, R.id.mainScroll);
        bind(activity, R.id.appUpdatePanel, R.id.appUpdateScroll);
    }

    private static void bind(Activity activity, int panelId, int sourceId) {
        View panel = activity.findViewById(panelId);
        if (panel instanceof GlassLayout) {
            ((GlassLayout) panel).setBackdrop(activity.findViewById(sourceId));
        }
    }

    private static void refresh(View view) {
        if (view == null) return;
        if (view instanceof GlassLayout) ((GlassLayout) view).refreshEffects();
        view.invalidate();
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) refresh(group.getChildAt(i));
        }
    }
}
