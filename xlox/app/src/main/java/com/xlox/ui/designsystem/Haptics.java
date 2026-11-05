package com.xlox.ui.designsystem;

import android.os.Build;
import android.view.HapticFeedbackConstants;
import android.view.View;

/** Micro-haptics. Uses framework feedback constants, so no extra permission. */
public final class Haptics {

    private Haptics() {}

    private static void perform(View v, int kind) {
        if (v != null) v.performHapticFeedback(kind);
    }

    /** Light tap: button press, clipboard, visibility toggle. */
    public static void tick(View v) {
        perform(v, HapticFeedbackConstants.VIRTUAL_KEY);
    }

    /** Very light tick for the appearance switch. */
    public static void toggle(View v) {
        perform(v, HapticFeedbackConstants.CLOCK_TICK);
    }

    public static void success(View v) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            perform(v, HapticFeedbackConstants.CONFIRM);
        } else {
            perform(v, HapticFeedbackConstants.KEYBOARD_TAP);
        }
    }

    /** Distinct rejection feedback for a failed sign-in. */
    public static void error(View v) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            perform(v, HapticFeedbackConstants.REJECT);
        } else {
            perform(v, HapticFeedbackConstants.LONG_PRESS);
        }
    }
}
