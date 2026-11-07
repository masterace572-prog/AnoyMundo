package com.xlox.ui;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.Toast;

import com.xlox.R;

import java.util.Locale;

/** Small, dependency-free UI helpers shared by every screen. */
public final class Ui {

    private static final String TAG = "Ui";

    private Ui() {
    }

    public static int dp(Context context, float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics()));
    }

    /** True while the activity can still safely touch its views. */
    public static boolean alive(Activity activity) {
        if (activity == null || activity.isFinishing()) return false;
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1 || !activity.isDestroyed();
    }

    public static void tap(View view) {
        if (view != null) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
    }

    public static void reject(View view) {
        if (view == null) return;
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        float d = dp(view.getContext(), 6);
        view.animate().cancel();
        view.setTranslationX(0f);
        view.animate().translationX(d).setDuration(50).withEndAction(() ->
                view.animate().translationX(-d).setDuration(80).withEndAction(() ->
                        view.animate().translationX(0f).setDuration(60).start()).start()).start();
    }

    /** Gentle fade + rise used when a screen's content appears. */
    public static void reveal(View view, long delayMs) {
        if (view == null) return;
        view.setAlpha(0f);
        view.setTranslationY(dp(view.getContext(), 8));
        view.animate().alpha(1f).translationY(0f).setStartDelay(delayMs).setDuration(260)
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    /** Shows a spinner in place of the button label while work is running. */
    public static void setBusy(Button button, ProgressBar spinner, boolean busy, CharSequence label) {
        if (button == null) return;
        if (busy) {
            button.setText("");
            button.setClickable(false);
            button.setContentDescription(label);
            if (spinner != null) spinner.setVisibility(View.VISIBLE);
        } else {
            button.setText(label);
            button.setClickable(true);
            button.setContentDescription(null);
            if (spinner != null) spinner.setVisibility(View.GONE);
        }
    }

    public static String bytes(long value) {
        if (value < 1024) return value + " B";
        if (value < 1024L * 1024L) return String.format(Locale.US, "%.0f KB", value / 1024.0);
        return String.format(Locale.US, "%.1f MB", value / (1024.0 * 1024.0));
    }

    public static void toast(Context context, CharSequence message) {
        if (context == null || message == null) return;
        Toast.makeText(context.getApplicationContext(), message, Toast.LENGTH_SHORT).show();
    }

    public static void openUrl(Activity activity, String url) {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(TAG, "No activity for " + url, e);
            toast(activity, activity.getString(R.string.error_link));
        }
    }
}
