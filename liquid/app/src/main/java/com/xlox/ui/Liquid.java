package com.xlox.ui;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
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
        // Runtime accessibility/battery changes must redraw idle screens too.
        // Registration follows the view lifecycle, not a static Activity reference.
        PolicyObserver policy = new PolicyObserver(activity, host);
        host.addOnAttachStateChangeListener(policy);
        if (host.isAttachedToWindow()) policy.onViewAttachedToWindow(host);
        host.getChildAt(0).setBackground(new LiquidBackdrop(activity));
        View settings = activity.findViewById(R.id.settingsPage);
        if (settings != null) settings.setBackground(new LiquidBackdrop(activity));
        bind(activity, R.id.actionPanel, R.id.mainScroll);
        bind(activity, R.id.appUpdatePanel, R.id.appUpdateScroll);
    }

    private static final class PolicyObserver implements View.OnAttachStateChangeListener {
        private final Activity activity;
        private final View root;
        private boolean attached;
        private boolean receiverRegistered;
        private boolean contrastRegistered;
        private final BroadcastReceiver battery = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) { changed(); }
        };
        private final ContentObserver contrast = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override public void onChange(boolean selfChange) { changed(); }
        };

        PolicyObserver(Activity activity, View root) {
            this.activity = activity;
            this.root = root;
        }

        private void changed() {
            policyCheckedAt = 0;
            refresh(root);
        }

        @Override public void onViewAttachedToWindow(View view) {
            if (attached) return;
            attached = true;
            try {
                // A protected system broadcast, including on pre-Android-13 devices.
                activity.registerReceiver(battery,
                        new IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED));
                receiverRegistered = true;
            } catch (RuntimeException ignored) { /* Keep the safe draw-time policy. */ }
            try {
                activity.getContentResolver().registerContentObserver(
                        Settings.Secure.getUriFor("high_text_contrast_enabled"), false, contrast);
                contrastRegistered = true;
            } catch (RuntimeException ignored) { /* Settings access can be OEM-restricted. */ }
            changed();
        }

        @Override public void onViewDetachedFromWindow(View view) {
            attached = false;
            if (receiverRegistered) {
                try { activity.unregisterReceiver(battery); } catch (RuntimeException ignored) { }
                receiverRegistered = false;
            }
            if (contrastRegistered) {
                try { activity.getContentResolver().unregisterContentObserver(contrast); }
                catch (RuntimeException ignored) { }
                contrastRegistered = false;
            }
        }
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
