package com.xlox.ui;

import android.app.Activity;
import android.content.*;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.button.MaterialButton;
import com.xlox.BuildConfig;
import com.xlox.R;
import com.xlox.utils.AirCrashDiagnostics;
import java.util.concurrent.*;

/** A separate, preview-before-copy diagnostic, never the licence-key support block. */
public final class AirCrashReportDialog {
    private final Activity activity;
    private final Context app;
    private final BottomSheetDialog sheet;
    private final TextView report;
    private final MaterialButton copy, root;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "air-exit-report"));
    private Future<?> task;
    private AlertDialog consent;
    private boolean closed;
    private String result;

    public static AirCrashReportDialog show(Activity activity) {
        if (!BuildConfig.AIR_NATIVE_ISOLATION) return null;
        return new AirCrashReportDialog(activity);
    }

    private AirCrashReportDialog(Activity activity) {
        this.activity = activity;
        app = activity.getApplicationContext();
        View content = LayoutInflater.from(activity).inflate(R.layout.sheet_air_crash_report, null, false);
        report = content.findViewById(R.id.airCrashReportText);
        copy = content.findViewById(R.id.airCrashReportCopy);
        root = content.findViewById(R.id.airCrashReportRoot);
        sheet = Ui.sheet(activity, content, true);
        sheet.setOnDismissListener(d -> dispose());
        content.findViewById(R.id.airCrashReportClose).setOnClickListener(v -> sheet.dismiss());
        copy.setOnClickListener(v -> {
            if (result == null) return;
            ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null) return;
            ClipData clip = ClipData.newPlainText(activity.getString(R.string.air_crash_report_title), result);
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                android.os.PersistableBundle extras = new android.os.PersistableBundle();
                extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
                clip.getDescription().setExtras(extras);
            }
            clipboard.setPrimaryClip(clip);
            copy.setText(R.string.air_crash_report_copied);
        });
        root.setOnClickListener(v -> {
            consent = new MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.air_crash_root_title)
                    .setMessage(R.string.air_crash_root_message)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.air_crash_root_allow, (d, which) -> read(true)).create();
            consent.show();
        });
        sheet.show();
        read(false);
    }

    private void read(boolean includeRoot) {
        if (closed) return;
        copy.setEnabled(false);
        root.setEnabled(false);
        report.setText(includeRoot ? R.string.air_crash_report_reading_root : R.string.air_crash_report_reading);
        task = worker.submit(() -> {
            String value;
            try { value = AirCrashDiagnostics.collect(app, includeRoot); }
            catch (RuntimeException ignored) { value = app.getString(R.string.air_crash_report_failed); }
            final String text = value;
            main.post(() -> {
                if (closed || !Ui.alive(activity) || !sheet.isShowing()) return;
                result = text;
                report.setText(text);
                copy.setText(R.string.air_crash_report_copy);
                copy.setEnabled(true);
                root.setEnabled(true);
            });
        });
    }

    public void dispose() {
        if (closed) return;
        closed = true;
        if (task != null) task.cancel(true);
        worker.shutdownNow();
        main.removeCallbacksAndMessages(null);
        if (consent != null) consent.dismiss();
        sheet.setOnDismissListener(null);
        if (sheet.isShowing()) sheet.dismiss();
    }
}
