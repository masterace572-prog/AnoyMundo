package com.xlox.ui;

import android.app.Activity;
import android.os.Build;
import android.view.LayoutInflater;
import android.view.View;
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
        ((TextView) view.findViewById(R.id.permExplainTitle)).setText(install
                ? R.string.perm_explain_install_title : R.string.perm_explain_all_files_title);
        ((TextView) view.findViewById(R.id.permExplainMessage)).setText(install
                ? R.string.perm_explain_install_message : R.string.perm_explain_all_files_message);
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
}
