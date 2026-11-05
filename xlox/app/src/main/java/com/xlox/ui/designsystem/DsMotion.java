package com.xlox.ui.designsystem;

import android.content.Context;
import android.os.Build;
import android.provider.Settings;
import android.view.View;
import android.animation.ValueAnimator;
import android.view.animation.Interpolator;

import androidx.interpolator.view.animation.FastOutSlowInInterpolator;

/** Motion tokens. Calm, finite, and disabled when the system asks for it. */
public final class DsMotion {

    public static final long STATE = 150L;
    public static final long SCREEN = 250L;
    public static final long SHEET = 300L;

    private DsMotion() {}

    public static Interpolator interpolator() {
        return new FastOutSlowInInterpolator();
    }

    /** Honour Settings > Accessibility > Remove animations. */
    public static boolean animationsEnabled(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return ValueAnimator.areAnimatorsEnabled();
        }
        return Settings.Global.getFloat(context.getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f;
    }

    public static void fadeIn(View view) {
        if (view == null) return;
        view.animate().cancel();
        if (!animationsEnabled(view.getContext())) {
            view.setAlpha(1f);
            view.setTranslationY(0f);
            return;
        }
        view.setAlpha(0f);
        view.animate().alpha(1f).setDuration(STATE)
                .setInterpolator(interpolator()).start();
    }

    public static void fadeOut(View view, Runnable end) {
        if (view == null) return;
        view.animate().cancel();
        if (!animationsEnabled(view.getContext())) {
            if (end != null) end.run();
            return;
        }
        view.animate().alpha(0f).setDuration(STATE)
                .setInterpolator(interpolator())
                .withEndAction(end).start();
    }
}
