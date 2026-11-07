package com.xlox.ui;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.Interpolator;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.CircularProgressIndicatorSpec;
import com.google.android.material.progressindicator.IndeterminateDrawable;
import com.google.android.material.snackbar.Snackbar;
import com.xlox.R;

import java.util.Locale;

/** UI helpers shared by every screen: insets, motion, haptics, feedback. */
public final class Ui {

    private static final String TAG = "Ui";

    /** Motion tokens (UI brief). */
    public static final long FAST = 150L;
    public static final long MEDIUM = 220L;
    public static final long SLOW = 320L;
    /** FastOutSlowIn - enters. */
    public static final Interpolator ENTER = new PathInterpolator(0.4f, 0f, 0.2f, 1f);
    /** LinearOutSlowIn - sheets and incoming elements. */
    public static final Interpolator SHEET = new PathInterpolator(0f, 0f, 0.2f, 1f);

    /** Views tagged with this become the Snackbar anchor (above the action bar). */
    public static final String SNACKBAR_ANCHOR = "snackbar_anchor";

    private Ui() {
    }

    public static int dp(Context context, float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics()));
    }

    /** True while the activity can still safely touch its views. */
    public static boolean alive(Activity activity) {
        if (activity == null || activity.isFinishing()) return false;
        return !activity.isDestroyed();
    }

    // ------------------------------------------------------------ edge-to-edge

    /** Draws behind transparent system bars (API 21-37). Dark bars, light icons. */
    public static void edgeToEdge(Activity activity) {
        Window window = activity.getWindow();
        WindowCompat.setDecorFitsSystemWindows(window, false);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.setNavigationBarContrastEnforced(false);
            window.setStatusBarContrastEnforced(false);
        }
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(window, window.getDecorView());
        controller.setAppearanceLightStatusBars(false);
        controller.setAppearanceLightNavigationBars(false);
    }

    /** Receives system-bar + cutout insets and the keyboard state. */
    public interface InsetsCallback {
        /**
         * @param bars   status / navigation bar and display-cutout insets
         * @param bottom bottom inset including the keyboard when it is open
         * @param ime    whether the keyboard is visible
         */
        void onInsets(Insets bars, int bottom, boolean ime);
    }

    public static void onInsets(View root, InsetsCallback callback) {
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            int imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
            // Before API 30 the keyboard can be folded into the navigation inset.
            int bottom = Math.max(bars.bottom, imeBottom);
            boolean ime = insets.isVisible(WindowInsetsCompat.Type.ime())
                    || imeBottom > bars.bottom
                    || (Build.VERSION.SDK_INT < Build.VERSION_CODES.R && bars.bottom > dp(v.getContext(), 120));
            callback.onInsets(bars, bottom, ime);
            return WindowInsetsCompat.CONSUMED;
        });
        ViewCompat.requestApplyInsets(root);
    }

    /** Pads a view by an inset, on top of the padding it was inflated with. */
    public static void padTop(View view, int inset) {
        Object base = view.getTag(R.id.tag_base_padding_top);
        if (base == null) {
            base = view.getPaddingTop();
            view.setTag(R.id.tag_base_padding_top, base);
        }
        view.setPadding(view.getPaddingLeft(), (Integer) base + inset, view.getPaddingRight(), view.getPaddingBottom());
    }

    public static void padBottom(View view, int inset) {
        Object base = view.getTag(R.id.tag_base_padding_bottom);
        if (base == null) {
            base = view.getPaddingBottom();
            view.setTag(R.id.tag_base_padding_bottom, base);
        }
        view.setPadding(view.getPaddingLeft(), view.getPaddingTop(), view.getPaddingRight(), (Integer) base + inset);
    }

    /**
     * Caps content width (e.g. 440 dp on tablets) by growing the horizontal
     * padding, centred; never below the screen padding it was inflated with.
     */
    public static void maxWidth(View view, int maxDp) {
        final int side = view.getPaddingLeft();
        final int max = dp(view.getContext(), maxDp);
        View parent = (View) view.getParent();
        parent.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int width = r - l;
            int pad = Math.max(side, (width - max) / 2);
            if (view.getPaddingLeft() != pad) {
                view.setPadding(pad, view.getPaddingTop(), pad, view.getPaddingBottom());
            }
        });
    }

    // ------------------------------------------------------------------ haptics

    public static void tap(View view) {
        if (view != null) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
    }

    /** Success: CONFIRM on API 30+, VIRTUAL_KEY before. */
    public static void confirm(View view) {
        if (view == null) return;
        view.performHapticFeedback(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                ? HapticFeedbackConstants.CONFIRM : HapticFeedbackConstants.VIRTUAL_KEY);
    }

    /** Error: REJECT on API 30+, LONG_PRESS before. */
    public static void rejectHaptic(View view) {
        if (view == null) return;
        view.performHapticFeedback(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                ? HapticFeedbackConstants.REJECT : HapticFeedbackConstants.LONG_PRESS);
    }

    /** Error feedback: REJECT haptic + a short horizontal shake. */
    public static void reject(View view) {
        if (view == null) return;
        rejectHaptic(view);
        if (reducedMotion(view.getContext())) return;
        float d = dp(view.getContext(), 6);
        view.animate().cancel();
        ObjectAnimator shake = ObjectAnimator.ofFloat(view, View.TRANSLATION_X, 0f, d, -d, d * 0.6f, -d * 0.4f, 0f);
        shake.setDuration(SLOW);
        shake.start();
    }

    // ------------------------------------------------------------------- motion

    /** Reduced motion = the system "Remove animations" / animator scale 0. */
    public static boolean reducedMotion(Context context) {
        try {
            return Settings.Global.getFloat(context.getContentResolver(),
                    Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Fade + 8 dp rise (220 ms, FastOutSlowIn); instant under reduced motion. */
    public static void reveal(View view, long delayMs) {
        if (view == null) return;
        view.animate().cancel();
        if (reducedMotion(view.getContext())) {
            view.setAlpha(1f);
            view.setTranslationY(0f);
            return;
        }
        view.setAlpha(0f);
        view.setTranslationY(dp(view.getContext(), 8));
        view.animate().alpha(1f).translationY(0f).setStartDelay(delayMs).setDuration(MEDIUM)
                .setInterpolator(ENTER).start();
    }

    /** Reveals each visible child with a 40 ms stagger. */
    public static void stagger(ViewGroup group, long startDelayMs) {
        if (group == null) return;
        int n = 0;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;
            reveal(child, startDelayMs + 40L * n++);
        }
    }

    /** Crossfade a view in or out (150 ms). */
    public static void fade(View view, boolean show) {
        if (view == null) return;
        view.animate().cancel();
        if (reducedMotion(view.getContext())) {
            view.setAlpha(1f);
            view.setVisibility(show ? View.VISIBLE : View.GONE);
            return;
        }
        if (show) {
            if (view.getVisibility() != View.VISIBLE) {
                view.setAlpha(0f);
                view.setVisibility(View.VISIBLE);
            }
            view.animate().alpha(1f).setDuration(FAST).setInterpolator(ENTER).start();
        } else if (view.getVisibility() == View.VISIBLE) {
            view.animate().alpha(0f).setDuration(FAST).setInterpolator(ENTER)
                    .withEndAction(() -> {
                        view.setVisibility(View.GONE);
                        view.setAlpha(1f);
                    }).start();
        }
    }

    /** Skeleton pulse (alpha 1 -> 0.45) for at most {@code maxMs}, then static. */
    public static void pulse(View view, long maxMs) {
        if (view == null) return;
        Object running = view.getTag(R.id.tag_pulse);
        if (running instanceof ValueAnimator) ((ValueAnimator) running).cancel();
        view.setAlpha(1f);
        if (reducedMotion(view.getContext())) return;
        ObjectAnimator a = ObjectAnimator.ofFloat(view, View.ALPHA, 1f, 0.45f);
        a.setDuration(700L);
        a.setRepeatMode(ValueAnimator.REVERSE);
        a.setRepeatCount((int) Math.max(1, (maxMs / 700L) | 1)); // odd count ends back at 1
        a.setInterpolator(ENTER);
        view.setTag(R.id.tag_pulse, a);
        a.start();
    }

    public static void stopPulse(View view) {
        if (view == null) return;
        Object running = view.getTag(R.id.tag_pulse);
        if (running instanceof ValueAnimator) ((ValueAnimator) running).cancel();
        view.setAlpha(1f);
    }

    /** A small spring used only for the success check. */
    public static void spring(View view) {
        if (view == null || reducedMotion(view.getContext())) return;
        view.setScaleX(0.96f);
        view.setScaleY(0.96f);
        view.animate().scaleX(1f).scaleY(1f).setDuration(SLOW)
                .setInterpolator(new OvershootInterpolator(2.2f)).start();
    }

    // ---------------------------------------------------------------- buttons

    /** 18 dp inline indeterminate indicator for a MaterialButton icon. */
    public static IndeterminateDrawable<CircularProgressIndicatorSpec> buttonProgress(Context context) {
        CircularProgressIndicatorSpec spec = new CircularProgressIndicatorSpec(context, null, 0,
                R.style.Widget_Anoy_ButtonProgress);
        return IndeterminateDrawable.createCircularDrawable(context, spec);
    }

    /**
     * Loading pill: label + inline indicator; the button keeps its colour and
     * ignores taps while loading.
     */
    public static void setLoading(MaterialButton button, boolean loading, CharSequence label) {
        if (button == null) return;
        button.setText(label);
        if (loading) {
            if (!(button.getIcon() instanceof IndeterminateDrawable)) {
                button.setIcon(buttonProgress(button.getContext()));
            }
            button.setClickable(false);
        } else {
            button.setIcon(null);
            button.setClickable(true);
        }
    }

    /** Success pill: check icon + label + CONFIRM haptic + small spring. */
    public static void setSuccess(MaterialButton button, CharSequence label) {
        if (button == null) return;
        button.setIconResource(R.drawable.ic_check);
        button.setText(label);
        button.setClickable(false);
        confirm(button);
        spring(button);
    }

    public static String bytes(long value) {
        if (value < 1024) return value + " B";
        if (value < 1024L * 1024L) return String.format(Locale.US, "%.0f KB", value / 1024.0);
        return String.format(Locale.US, "%.1f MB", value / (1024.0 * 1024.0));
    }

    // --------------------------------------------------------------- feedback

    /** Raised Snackbar (14 dp radius, blue action) anchored above the action bar. */
    public static void snackbar(Activity activity, CharSequence message) {
        if (!alive(activity) || message == null) return;
        View content = activity.findViewById(android.R.id.content);
        if (content == null) return;
        Snackbar bar = Snackbar.make(content, message, Snackbar.LENGTH_LONG);
        View anchor = content.findViewWithTag(SNACKBAR_ANCHOR);
        if (anchor != null && anchor.isShown()) bar.setAnchorView(anchor);
        bar.show();
    }

    public static void openUrl(Activity activity, String url) {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(TAG, "No activity for " + url, e);
            snackbar(activity, activity.getString(R.string.error_link));
        }
    }

    // ------------------------------------------------------------------ sheets

    /** Modal bottom sheet: expanded, no collapsed state, 28 dp corners, 60 % scrim. */
    public static BottomSheetDialog sheet(Activity activity, View content, boolean dismissable) {
        BottomSheetDialog dialog = new BottomSheetDialog(activity, R.style.ThemeOverlay_Anoy_BottomSheet);
        dialog.setContentView(content);
        dialog.setCancelable(dismissable);
        dialog.setCanceledOnTouchOutside(dismissable);
        BottomSheetBehavior<?> behavior = dialog.getBehavior();
        behavior.setSkipCollapsed(true);
        behavior.setDraggable(dismissable);
        behavior.setHideable(dismissable);
        behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        return dialog;
    }
}
