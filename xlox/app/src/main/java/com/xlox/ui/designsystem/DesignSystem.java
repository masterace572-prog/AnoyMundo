package com.xlox.ui.designsystem;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.widget.AppCompatButton;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.core.graphics.Insets;
import androidx.core.content.ContextCompat;

import com.xlox.R;
import com.xlox.ui.ThemeMode;

/**
 * Shared edge-to-edge, inset, snackbar and dialog implementation.
 *
 * targetSdk 28 is frozen for the virtual-container SDK, so all inset work uses
 * androidx.core compat APIs rather than platform Window/InsetsController calls.
 * Nothing is ever drawn under a system bar: every screen consumes safeDrawing
 * insets on its content root, and the login screen also consumes IME insets.
 */
public final class DesignSystem {

    private static final String SNACKBAR_TAG = "ds_snackbar";
    private static final long SNACKBAR_MILLIS = 3500L;

    private DesignSystem() {}

    /** Transparent status/nav bars, edge-to-edge layout, theme-aware icon contrast. */
    public static void applyEdgeToEdge(Activity activity) {
        Window window = activity.getWindow();
        if (window == null) return;
        WindowCompat.setDecorFitsSystemWindows(window, false);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams params = window.getAttributes();
            params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            window.setAttributes(params);
        }
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(window, window.getDecorView());
        if (controller != null) {
            boolean light = !ThemeMode.isDark(activity);
            controller.setAppearanceLightStatusBars(light);
            controller.setAppearanceLightNavigationBars(light);
        }
        ViewCompat.requestApplyInsets(window.getDecorView());
    }

    /** Consume safeDrawing (and optionally IME) insets as padding on a content root. */
    public static void applySafeDrawing(View view, boolean top, boolean bottom, boolean ime) {
        if (view == null) return;
        final int left = view.getPaddingLeft();
        final int baseTop = view.getPaddingTop();
        final int right = view.getPaddingRight();
        final int baseBottom = view.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(view, (v, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
                            | WindowInsetsCompat.Type.displayCutout());
            int keyboard = ime
                    ? insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                    : 0;
            int bottomInset = Math.max(bottom ? bars.bottom : 0, keyboard);
            boolean rtl = v.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            v.setPadding(left + (rtl ? bars.right : bars.left),
                    baseTop + (top ? bars.top : 0),
                    right + (rtl ? bars.left : bars.right),
                    baseBottom + bottomInset);
            return insets;
        });
        ViewCompat.requestApplyInsets(view);
    }

    /** Consume only the navigation-bar / gesture inset on a bottom-anchored view. */
    public static void applyBottomInsets(View view) {
        if (view == null) return;
        final int base = view.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(view, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(),
                    v.getPaddingRight(), base + bars.bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(view);
    }

    /** Floating matte snackbar with a hairline, an icon and a Text action. */
    public static void snackbar(Activity activity, CharSequence message) {
        showSnackbar(activity, message, false);
    }

    public static void snackbarError(Activity activity, CharSequence message) {
        showSnackbar(activity, message, true);
    }

    private static void showSnackbar(Activity activity, CharSequence message, boolean error) {
        if (activity == null || activity.isFinishing()) return;
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (!(content instanceof FrameLayout)) return;
        View previous = content.findViewWithTag(SNACKBAR_TAG);
        if (previous != null) content.removeView(previous);

        View bar = LayoutInflater.from(activity).inflate(R.layout.ds_snackbar, content, false);
        bar.setTag(SNACKBAR_TAG);
        ((TextView) bar.findViewById(R.id.snackbarText)).setText(message);
        ImageView icon = bar.findViewById(R.id.snackbarIcon);
        icon.setImageResource(error ? R.drawable.ds_ic_error : R.drawable.ds_ic_info);
        icon.setColorFilter(error ? Tokens.errorInk(activity) : Tokens.accentInk(activity));

        AppCompatButton action = bar.findViewById(R.id.snackbarAction);
        int gutter = (int) Tokens.px(activity, R.dimen.space_16);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        params.leftMargin = gutter;
        params.rightMargin = gutter;
        params.bottomMargin = gutter;
        content.addView(bar, params);
        applyBottomInsets(bar);
        DsMotion.fadeIn(bar);
        bar.postDelayed(() -> DsMotion.fadeOut(bar, () -> content.removeView(bar)),
                SNACKBAR_MILLIS);
    }

    public static void snackbar(Activity activity, CharSequence message,
                                CharSequence actionLabel, Runnable onAction) {
        showSnackbar(activity, message, false);
        ViewGroup content = activity.findViewById(android.R.id.content);
        View bar = content.findViewWithTag(SNACKBAR_TAG);
        if (bar == null || actionLabel == null) return;
        AppCompatButton action = bar.findViewById(R.id.snackbarAction);
        action.setVisibility(View.VISIBLE);
        action.setText(actionLabel);
        action.setOnClickListener(v -> {
            content.removeView(bar);
            if (onAction != null) onAction.run();
        });
    }

    /** Dialog: elevated matte surface, tight headline, right-aligned Text actions. */
    public static Dialog dialog(Context context, CharSequence title, CharSequence message,
                                CharSequence positive, Runnable onPositive, boolean destructive,
                                CharSequence negative, Runnable onNegative) {
        Dialog dialog = new Dialog(context, R.style.ThemeOverlay_Ds_Dialog);
        dialog.setContentView(R.layout.ds_dialog);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT);
        }
        ((TextView) dialog.findViewById(R.id.dialogHeadline)).setText(title);
        ((TextView) dialog.findViewById(R.id.dialogBody)).setText(message);

        AppCompatButton confirm = dialog.findViewById(R.id.dialogPositive);
        confirm.setText(positive);
        if (destructive) confirm.setTextColor(Tokens.errorInk(context));
        confirm.setOnClickListener(v -> {
            dialog.dismiss();
            if (onPositive != null) onPositive.run();
        });

        AppCompatButton cancel = dialog.findViewById(R.id.dialogNegative);
        if (negative != null) {
            cancel.setVisibility(View.VISIBLE);
            cancel.setText(negative);
            cancel.setOnClickListener(v -> {
                dialog.dismiss();
                if (onNegative != null) onNegative.run();
            });
        }
        return dialog;
    }

    /** Simple themed confirmation dialog used by the VPN guard. */
    public static void showMessage(Context context, CharSequence title, CharSequence message) {
        dialog(context, title, message, context.getString(R.string.close), null,
                false, null, null).show();
    }
}
