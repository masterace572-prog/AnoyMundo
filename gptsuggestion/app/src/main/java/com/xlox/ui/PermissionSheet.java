package com.xlox.ui;

import android.app.Activity;
import android.os.Build;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.xlox.R;
import com.xlox.utils.StorageAccess;

/**
 * The plain-language sheet shown right before Anoy sends the user to the
 * All files access or Install unknown apps settings page. "Continue to
 * Settings" opens the page exactly as before; "Not now" (or dismissing the
 * sheet) leaves it closed. The Files runtime dialog explains itself and gets
 * no sheet.
 *
 * <p>2.3.0 look (spec 3.9): kicker PERMISSION, the permission's name, WHY WE
 * ASK and HOW TO ALLOW (numbered steps), both built from the existing copy.
 */
public final class PermissionSheet {

    public interface Callback {
        void onContinue();

        void onNotNow();
    }

    private PermissionSheet() {
    }

    /** True for the needs whose prompt is a settings page on this phone. */
    public static boolean explains(StorageAccess.Need need) {
        if (need == StorageAccess.Need.ALL_FILES) return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R;
        if (need == StorageAccess.Need.INSTALL) return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O;
        return false;
    }

    /** Shows the sheet; exactly one of the callbacks runs. */
    public static BottomSheetDialog show(@NonNull Activity activity, @NonNull StorageAccess.Need need,
                                         @NonNull Callback callback) {
        View view = LayoutInflater.from(activity).inflate(R.layout.sheet_permission, null, false);
        boolean install = need == StorageAccess.Need.INSTALL;
        TextView title = view.findViewById(R.id.permExplainTitle);
        title.setText(install ? R.string.settings_install : R.string.settings_all_files);
        // TalkBack hears the existing one-line request first.
        title.setContentDescription(activity.getString(install
                ? R.string.perm_explain_install_title : R.string.perm_explain_all_files_title));
        ((TextView) view.findViewById(R.id.permExplainMessage)).setText(install
                ? R.string.perm_why_install : R.string.perm_why_all_files);
        addSteps(activity, view.findViewById(R.id.permSteps),
                R.string.perm_step_settings, R.string.perm_step_turn_on);
        BottomSheetDialog sheet = Ui.sheet(activity, view, true);
        final boolean[] answered = {false};
        view.findViewById(R.id.permExplainContinue).setOnClickListener(v -> {
            if (answered[0]) return;
            answered[0] = true;
            Ui.tap(v);
            sheet.dismiss();
            callback.onContinue();
        });
        view.findViewById(R.id.permExplainNotNow).setOnClickListener(v -> sheet.dismiss());
        sheet.setOnDismissListener(d -> {
            if (answered[0]) return;
            answered[0] = true;
            callback.onNotNow();
        });
        sheet.show();
        return sheet;
    }

    /** Numbered steps: Mono 12 tertiary numbers in a 24 dp gutter, Body text, 8 dp apart. */
    private static void addSteps(Activity activity, LinearLayout list, int... steps) {
        LayoutInflater inflater = LayoutInflater.from(activity);
        for (int i = 0; i < steps.length; i++) {
            View row = inflater.inflate(R.layout.item_step, list, false);
            ((TextView) row.findViewById(R.id.stepNumber)).setText(
                    activity.getString(R.string.perm_step_number, i + 1));
            ((TextView) row.findViewById(R.id.stepText)).setText(steps[i]);
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) row.getLayoutParams();
            lp.topMargin = activity.getResources().getDimensionPixelSize(R.dimen.space_8);
            list.addView(row, lp);
        }
    }
}
