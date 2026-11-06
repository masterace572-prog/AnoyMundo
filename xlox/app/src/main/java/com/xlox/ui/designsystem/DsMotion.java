package com.xlox.ui.designsystem;

import android.annotation.SuppressLint;
import android.animation.ValueAnimator;
import android.content.Context;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.animation.Interpolator;

import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;

/** Motion tokens: critically damped springs, staggered entrances and micro-physics. */
public final class DsMotion {

    private static final String TAG = "DsMotion";

    public static final long STATE = 150L;
    public static final long SCREEN = 380L;
    public static final long SHEET = 300L;
    public static final long STAGGER = 40L;

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

    /**
     * The final position must live on the SpringForce. A bare SpringForce has
     * an UNSET (Double.MAX_VALUE) final position, and SpringAnimation's
     * sanityCheck() throws "Final position of the spring cannot be greater than
     * the max value" at start() when that force is installed.
     */
    private static SpringForce criticallyDamped(float stiffness, float finalPosition) {
        return new SpringForce(finalPosition)
                .setStiffness(stiffness)
                .setDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY);
    }

    private static SpringAnimation spring(View v, DynamicAnimation.ViewProperty property,
                                          float target, float stiffness) {
        SpringAnimation a = new SpringAnimation(v, property);
        a.setSpring(criticallyDamped(stiffness, target));
        return a;
    }

    /** Cosmetic springs must never abort a screen launch on a library/OEM quirk. */
    private static void startSpring(View v, DynamicAnimation.ViewProperty property,
                                    float target, float stiffness) {
        try {
            spring(v, property, target, stiffness).start();
        } catch (RuntimeException e) {
            Log.w(TAG, "Spring animation unavailable; applying target directly", e);
            try {
                property.setValue(v, target);
            } catch (RuntimeException ignored) {
                // Leave the view in its current state rather than crashing.
            }
        }
    }

    /** Shared-axis / staggered fade-and-slide cascade, 40ms between items. */
    public static void stagger(View... views) {
        if (views == null || views.length == 0) return;
        boolean enabled = views[0] != null && animationsEnabled(views[0].getContext());
        for (int i = 0; i < views.length; i++) {
            final View v = views[i];
            if (v == null) continue;
            final long delay = i * STAGGER;
            if (!enabled) {
                v.setAlpha(1f);
                v.setTranslationY(0f);
                continue;
            }
            v.animate().cancel();
            v.setAlpha(0f);
            v.setTranslationY(Tokens.px(v.getContext(), com.xlox.R.dimen.space_24));
            v.postDelayed(() -> {
                startSpring(v, DynamicAnimation.ALPHA, 1f, SpringForce.STIFFNESS_LOW);
                startSpring(v, DynamicAnimation.TRANSLATION_Y, 0f,
                        SpringForce.STIFFNESS_MEDIUM);
            }, delay);
        }
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
        startSpring(view, DynamicAnimation.ALPHA, 1f, SpringForce.STIFFNESS_LOW);
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

    /** Press-scale physics (0.97x). The listener returns false so the platform
     *  still dispatches performClick() to the original click listener. */
    @SuppressLint("ClickableViewAccessibility")
    public static void attachPressScale(View v, final float pressed) {
        if (v == null) return;
        v.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    if (animationsEnabled(view.getContext())) {
                        startSpring(view, DynamicAnimation.SCALE_X, pressed,
                                SpringForce.STIFFNESS_MEDIUM);
                        startSpring(view, DynamicAnimation.SCALE_Y, pressed,
                                SpringForce.STIFFNESS_MEDIUM);
                    }
                    Haptics.tick(view);
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    if (animationsEnabled(view.getContext())) {
                        startSpring(view, DynamicAnimation.SCALE_X, 1f,
                                SpringForce.STIFFNESS_MEDIUM);
                        startSpring(view, DynamicAnimation.SCALE_Y, 1f,
                                SpringForce.STIFFNESS_MEDIUM);
                    } else {
                        view.setScaleX(1f);
                        view.setScaleY(1f);
                    }
                    break;
                default:
                    break;
            }
            return false;
        });
    }

    /** Damped horizontal shake for an authentication failure. */
    public static void shake(View v) {
        if (v == null) return;
        if (!animationsEnabled(v.getContext())) return;
        final float amp = Tokens.px(v.getContext(), com.xlox.R.dimen.space_8);
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(360L);
        a.setInterpolator(interpolator());
        a.addUpdateListener(anim -> {
            float t = (Float) anim.getAnimatedValue();
            v.setTranslationX((float) (Math.sin(t * Math.PI * 4) * amp * (1f - t)));
        });
        a.start();
    }

    /** Spring a view to a scale target (used by the splash emblem). */
    public static void springScale(View v, float from, float to) {
        if (v == null) return;
        if (!animationsEnabled(v.getContext())) {
            v.setScaleX(to);
            v.setScaleY(to);
            v.setAlpha(1f);
            return;
        }
        v.setScaleX(from);
        v.setScaleY(from);
        v.setAlpha(0f);
        startSpring(v, DynamicAnimation.SCALE_X, to, SpringForce.STIFFNESS_LOW);
        startSpring(v, DynamicAnimation.SCALE_Y, to, SpringForce.STIFFNESS_LOW);
        startSpring(v, DynamicAnimation.ALPHA, 1f, SpringForce.STIFFNESS_LOW);
    }
}
