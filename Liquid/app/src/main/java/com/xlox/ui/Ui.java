package com.xlox.ui;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.text.TextUtils;
import android.util.TypedValue;
import android.widget.TextView;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.Window;
import android.view.animation.Interpolator;
import android.view.animation.LinearInterpolator;
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

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** UI helpers shared by every screen: insets, motion, haptics, feedback. */
public final class Ui {

    private static final String TAG = "Ui";

    /**
     * The one set of motion tokens (2.3.4; res/values/motion.xml): press 100,
     * short 180 (fades, swaps), medium 280 (screens), sheets 320 in / 240 out
     * (res/anim/sheet_in, sheet_out), long 450 (the splash only).
     */
    public static final long PRESS = 100L;
    public static final long SHORT = 180L;
    public static final long MEDIUM = 280L;
    public static final long LONG = 450L;
    public static final long SHEET_IN = 320L;
    public static final long SHEET_OUT = 240L;
    /** Text swaps: 100 ms out, 180 ms in, linear. */
    public static final long SWAP_OUT = PRESS;
    public static final long SWAP_IN = SHORT;
    /** Enter: decelerate (0.2, 0, 0, 1). */
    public static final Interpolator ENTER = new PathInterpolator(0.2f, 0f, 0f, 1f);
    /** Exit: accelerate (0.4, 0, 1, 1). */
    public static final Interpolator EXIT = new PathInterpolator(0.4f, 0f, 1f, 1f);
    /** Linear: only for short alpha swaps. */
    public static final Interpolator LINEAR = new LinearInterpolator();

    /**
     * Calm waiting (2.3.4): nothing new appears for the first 400 ms of a wait,
     * and a wait that runs past {@link #SLOW_MS} says so in plain words.
     */
    public static final long QUIET_MS = 400L;
    public static final long SLOW_MS = 6000L;

    /** Every time the app shows is India time; stored times stay UTC. */
    public static final TimeZone IST = TimeZone.getTimeZone("Asia/Kolkata");

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
        shake.setDuration(MEDIUM);
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

    /** Crossfade a view in or out (180 ms); instant under reduced motion. */
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
            view.animate().alpha(1f).setDuration(SHORT).setInterpolator(ENTER).start();
        } else if (view.getVisibility() == View.VISIBLE) {
            view.animate().alpha(0f).setDuration(SHORT).setInterpolator(EXIT)
                    .withEndAction(() -> {
                        view.setVisibility(View.GONE);
                        view.setAlpha(1f);
                    }).start();
        }
    }

    /**
     * Like {@link #fade} but hides to INVISIBLE, so a reserved status line keeps
     * its space and nothing below or above it moves.
     */
    public static void fadeReserved(View view, boolean show) {
        if (view == null) return;
        view.animate().cancel();
        if (reducedMotion(view.getContext())) {
            view.setAlpha(1f);
            view.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
            return;
        }
        if (show) {
            if (view.getVisibility() != View.VISIBLE) {
                view.setAlpha(0f);
                view.setVisibility(View.VISIBLE);
            }
            view.animate().alpha(1f).setDuration(SHORT).setInterpolator(ENTER).start();
        } else if (view.getVisibility() == View.VISIBLE) {
            view.animate().alpha(0f).setDuration(SHORT).setInterpolator(EXIT)
                    .withEndAction(() -> {
                        view.setVisibility(View.INVISIBLE);
                        view.setAlpha(1f);
                    }).start();
        }
    }

    /**
     * Changes a label with a crossfade: 100 ms out, 180 ms in, linear. Instant
     * when the view is not on screen or under reduced motion. Repeating the
     * text that is already showing (or on its way) does nothing.
     */
    public static void swapText(TextView view, CharSequence text) {
        if (view == null) return;
        Object pending = view.getTag(R.id.tag_swap_text);
        CharSequence target = pending instanceof CharSequence ? (CharSequence) pending : view.getText();
        if (TextUtils.equals(target, text)) return;
        view.animate().cancel();
        if (!view.isShown() || reducedMotion(view.getContext())) {
            view.setTag(R.id.tag_swap_text, null);
            view.setText(text);
            view.setAlpha(1f);
            return;
        }
        view.setTag(R.id.tag_swap_text, text);
        view.animate().alpha(0f).setDuration(SWAP_OUT).setInterpolator(LINEAR).withEndAction(() -> {
            view.setTag(R.id.tag_swap_text, null);
            view.setText(text);
            view.animate().alpha(1f).setDuration(SWAP_IN).setInterpolator(LINEAR).start();
        }).start();
    }

    /**
     * Drops a trailing "Try again." / "Try again later." from a message shown
     * right next to a visible Try again button (the button already says it).
     */
    public static String dropTryAgain(String message) {
        if (message == null) return null;
        String m = message.trim();
        for (String tail : new String[] {" Try again later.", " Try again."}) {
            if (m.endsWith(tail) && m.length() > tail.length()) return m.substring(0, m.length() - tail.length()).trim();
        }
        return m;
    }

    /**
     * "just now", "12 min ago", "today, 14:32", "yesterday, 14:32",
     * "3 Nov, 14:32" (all IST), or "Not checked yet" when there is no time.
     */
    public static String lastChecked(Context context, long utcMillis) {
        if (utcMillis <= 0L) return context.getString(R.string.settings_not_checked);
        long now = System.currentTimeMillis();
        long age = now - utcMillis;
        if (age >= 0L && age < 60_000L) return context.getString(R.string.time_just_now);
        if (age >= 0L && age < 60L * 60_000L) {
            return context.getString(R.string.time_minutes_ago, (int) (age / 60_000L));
        }
        String time = istFormat("HH:mm").format(new Date(utcMillis));
        SimpleDateFormat day = istFormat("yyyyMMdd");
        String then = day.format(new Date(utcMillis));
        if (then.equals(day.format(new Date(now)))) return context.getString(R.string.time_today, time);
        if (then.equals(day.format(new Date(now - 86_400_000L)))) return context.getString(R.string.time_yesterday, time);
        return istFormat("d MMM, HH:mm").format(new Date(utcMillis));
    }

    private static SimpleDateFormat istFormat(String pattern) {
        SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.ENGLISH);
        format.setTimeZone(IST);
        return format;
    }

    // ---------------------------------------------------------------- buttons

    /** 18 dp inline indeterminate indicator for a MaterialButton icon. */
    public static IndeterminateDrawable<CircularProgressIndicatorSpec> buttonProgress(Context context) {
        CircularProgressIndicatorSpec spec = new CircularProgressIndicatorSpec(context, null, 0,
                R.style.Widget_Anoy_ButtonProgress);
        return IndeterminateDrawable.createCircularDrawable(context, spec);
    }

    /**
     * Loading button: inline 18 dp indicator (+ label, or the indicator alone
     * when the label is empty); the button keeps its colour and size and
     * ignores taps while loading.
     */
    public static void setLoading(MaterialButton button, boolean loading, CharSequence label) {
        if (button == null) return;
        button.setText(label);
        button.setIconPadding(loading && (label == null || label.length() == 0)
                ? 0 : dp(button.getContext(), 8));
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

    /** Success: check icon + label + CONFIRM haptic. No scale, no overshoot. */
    public static void setSuccess(MaterialButton button, CharSequence label) {
        if (button == null) return;
        button.setIconResource(R.drawable.ic_check);
        button.setIconPadding(dp(button.getContext(), 8));
        button.setText(label);
        button.setClickable(false);
        confirm(button);
    }

    public static String bytes(long value) {
        if (value < 1024) return value + " B";
        if (value < 1024L * 1024L) return String.format(Locale.US, "%.0f KB", value / 1024.0);
        return String.format(Locale.US, "%.1f MB", value / (1024.0 * 1024.0));
    }

    /** "18.4 of 43.0 MB": both numbers in the unit of the total (tabular in Mono). */
    public static String bytesOf(Context context, long done, long total) {
        String doneText;
        if (total < 1024) {
            doneText = String.valueOf(done);
        } else if (total < 1024L * 1024L) {
            doneText = String.format(Locale.US, "%.0f", done / 1024.0);
        } else {
            doneText = String.format(Locale.US, "%.1f", done / (1024.0 * 1024.0));
        }
        return context.getString(R.string.progress_bytes, doneText, bytes(total));
    }

    // --------------------------------------------------------------- feedback

    /** Snackbar (surface-2, radius 16, accent action) anchored above the action bar. */
    public static void snackbar(Activity activity, CharSequence message) {
        snackbar(activity, message, Snackbar.LENGTH_LONG);
    }

    public static void snackbar(Activity activity, CharSequence message, int duration) {
        if (!alive(activity) || message == null) return;
        View content = activity.findViewById(android.R.id.content);
        if (content == null) return;
        Snackbar bar = Snackbar.make(content, message, duration);
        View anchor = content.findViewWithTag(SNACKBAR_ANCHOR);
        // Visibility, not isShown(): the view may not be attached yet (onCreate).
        if (anchor != null && anchor.getVisibility() == View.VISIBLE) bar.setAnchorView(anchor);
        bar.show();
    }

    /** Caps the user's font scale for one view (single-line fields that can't wrap). */
    public static void capFontScale(TextView view, float max) {
        float scale = view.getResources().getConfiguration().fontScale;
        if (scale > max) {
            view.setTextSize(TypedValue.COMPLEX_UNIT_PX, view.getTextSize() * max / scale);
        }
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

    /**
     * The one sheet (spec 3.9): surface-2, 28 dp top corners with a hairline top
     * edge (the content's @style/Sheet background), 64 % scrim, 320 ms in /
     * 240 ms out, at most 560 dp wide, expanded with no collapsed state, and
     * padded above the navigation bar so the surface runs behind it.
     */
    public static BottomSheetDialog sheet(Activity activity, View content, boolean dismissable) {
        BottomSheetDialog dialog = new BottomSheetDialog(activity, R.style.ThemeOverlay_Anoy_BottomSheet);
        dialog.setContentView(content);
        ViewCompat.setOnApplyWindowInsetsListener(content, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            padBottom(v, bars.bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(content);
        dialog.setCancelable(dismissable);
        dialog.setCanceledOnTouchOutside(dismissable);
        BottomSheetBehavior<?> behavior = dialog.getBehavior();
        behavior.setSkipCollapsed(true);
        behavior.setMaxWidth(activity.getResources().getDimensionPixelSize(R.dimen.sheet_max_width));
        behavior.setDraggable(dismissable);
        behavior.setHideable(dismissable);
        behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        return dialog;
    }
}
