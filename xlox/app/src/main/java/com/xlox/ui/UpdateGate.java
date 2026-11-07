package com.xlox.ui;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatDialog;

import com.xlox.R;
import com.xlox.utils.Downtwo;
import com.xlox.utils.UpdateException;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Mandatory library update gate.
 *
 * Always checks the panel first. When a newer library exists the only action
 * is "Update now"; the dialog cannot be dismissed (Back closes the app) and the
 * listener only fires once the library is current. When the check itself
 * fails the only action is "Try again".
 */
public final class UpdateGate {

    private static final String TAG = "UpdateGate";
    private static final long MIN_CHECKING_MS = 450L;
    private static final long DONE_VISIBLE_MS = 650L;

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "anoy-update");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    public interface Listener {
        void onReady();
    }

    private final Activity activity;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    private AppCompatDialog dialog;
    private ProgressBar spinner;
    private ImageView icon;
    private volatile long lastUnknownPost;
    private TextView title;
    private TextView message;
    private TextView detail;
    private ProgressBar progress;
    private TextView progressText;
    private Button action;

    private Downtwo.Release pending;
    private boolean finished;
    private int lastPercent = -2;

    private UpdateGate(Activity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
    }

    /** Shows the gate immediately and checks for an update. */
    public static UpdateGate start(Activity activity, Listener listener) {
        UpdateGate gate = new UpdateGate(activity, listener);
        if (gate.ensureDialog()) {
            gate.runCheck();
        }
        return gate;
    }

    /**
     * Background re-check without any UI. The gate only appears (and blocks)
     * when an update is required; failures are ignored.
     */
    public static UpdateGate recheck(Activity activity, Listener listener) {
        UpdateGate gate = new UpdateGate(activity, listener);
        EXECUTOR.execute(() -> {
            try {
                Downtwo.Status status = Downtwo.check(activity);
                if (status.updateRequired) {
                    gate.post(() -> {
                        if (gate.ensureDialog()) gate.showUpdate(status);
                    });
                }
            } catch (UpdateException e) {
                Log.i(TAG, "Silent re-check skipped: " + e.getMessage());
            } catch (Throwable t) {
                Log.w(TAG, "Silent re-check failed", t);
            }
        });
        return gate;
    }

    public boolean isShowing() {
        return dialog != null && dialog.isShowing();
    }

    /** Stops callbacks; call from onDestroy. */
    public void dispose() {
        cancelled.set(true);
        main.removeCallbacksAndMessages(null);
        keepScreenOn(false);
        dismissQuietly();
    }

    // ---------------------------------------------------------------- dialog

    private boolean ensureDialog() {
        if (!Ui.alive(activity) || cancelled.get()) return false;
        if (dialog != null) return true;
        try {
            dialog = new AppCompatDialog(activity, R.style.AnoyDialog);
            View root = LayoutInflater.from(dialog.getContext()).inflate(R.layout.dialog_update, null, false);
            spinner = root.findViewById(R.id.gateSpinner);
            icon = root.findViewById(R.id.gateIcon);
            title = root.findViewById(R.id.gateTitle);
            message = root.findViewById(R.id.gateMessage);
            detail = root.findViewById(R.id.gateDetail);
            progress = root.findViewById(R.id.gateProgress);
            progressText = root.findViewById(R.id.gateProgressText);
            action = root.findViewById(R.id.gateAction);
            dialog.setContentView(root);
            dialog.setCancelable(false);
            dialog.setCanceledOnTouchOutside(false);
            dialog.setOnKeyListener((d, keyCode, event) -> {
                if (keyCode != KeyEvent.KEYCODE_BACK) return false;
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    // The update is mandatory: leaving the gate leaves the app.
                    activity.finishAffinity();
                }
                return true;
            });
            dialog.show();
            sizeWindow();
            return true;
        } catch (RuntimeException e) {
            // A window token problem must never crash startup; retry shortly.
            Log.e(TAG, "Could not show the update gate", e);
            dialog = null;
            main.postDelayed(() -> {
                if (ensureDialog()) runCheck();
            }, 500L);
            return false;
        }
    }

    private void sizeWindow() {
        Window window = dialog.getWindow();
        if (window == null) return;
        DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        int width = Math.min(Ui.dp(activity, 340), metrics.widthPixels - Ui.dp(activity, 48));
        window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void dismissQuietly() {
        if (dialog == null) return;
        try {
            if (dialog.isShowing()) dialog.dismiss();
        } catch (RuntimeException ignored) {
            // Window already gone with the activity.
        }
    }

    private void post(Runnable r) {
        main.post(() -> {
            if (!cancelled.get() && Ui.alive(activity)) r.run();
        });
    }

    private void keepScreenOn(boolean on) {
        try {
            if (on) activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } catch (RuntimeException ignored) {
            // Activity window unavailable.
        }
    }

    // ---------------------------------------------------------------- states

    private void render(int titleRes, CharSequence body, boolean busy, CharSequence buttonLabel) {
        title.setText(titleRes);
        message.setText(body);
        spinner.setVisibility(busy ? View.VISIBLE : View.GONE);
        icon.setVisibility(View.GONE);
        detail.setVisibility(View.GONE);
        progress.setVisibility(View.GONE);
        progressText.setVisibility(View.GONE);
        if (buttonLabel == null) {
            action.setVisibility(View.GONE);
            action.setOnClickListener(null);
        } else {
            action.setText(buttonLabel);
            action.setEnabled(true);
            action.setVisibility(View.VISIBLE);
        }
    }

    /** Large icon tile above the title for non-busy states. */
    private void showIcon(int drawable, int tile) {
        icon.setImageResource(drawable);
        icon.setBackgroundResource(tile);
        icon.setVisibility(View.VISIBLE);
    }

    private void runCheck() {
        render(R.string.update_checking_title, activity.getString(R.string.update_checking_message), true, null);
        final long started = SystemClock.elapsedRealtime();
        EXECUTOR.execute(() -> {
            try {
                Downtwo.Status status = Downtwo.check(activity);
                long wait = Math.max(0L, MIN_CHECKING_MS - (SystemClock.elapsedRealtime() - started));
                main.postDelayed(() -> {
                    if (!cancelled.get() && Ui.alive(activity)) onChecked(status);
                }, wait);
            } catch (UpdateException e) {
                post(() -> showCheckFailed(e.getMessage()));
            } catch (Throwable t) {
                Log.e(TAG, "Update check crashed", t);
                post(() -> showCheckFailed(activity.getString(R.string.net_generic)));
            }
        });
    }

    private void onChecked(Downtwo.Status status) {
        if (status.updateRequired) {
            showUpdate(status);
        } else if (status.libUsable) {
            showDone(status.localVersion);
        } else {
            // Nothing is published yet and nothing is installed: there is no
            // update to force. The home screen reports the missing library.
            finish();
        }
    }

    private void showUpdate(Downtwo.Status status) {
        pending = status.release;
        CharSequence body = status.libUsable
                ? activity.getString(R.string.update_required_message,
                status.release.version, status.localVersion)
                : activity.getString(R.string.update_required_message_missing, status.release.version);
        render(R.string.update_required_title, body, false, activity.getString(R.string.update_now));
        showIcon(R.drawable.ic_download_blue, R.drawable.bg_tile_blue_large);
        if (status.release.size > 0) {
            detail.setText(activity.getString(R.string.update_size, Ui.bytes(status.release.size)));
            detail.setVisibility(View.VISIBLE);
        }
        action.setOnClickListener(v -> {
            Ui.tap(v);
            startDownload();
        });
    }

    private void showCheckFailed(String reason) {
        render(R.string.update_check_failed_title, reason, false, activity.getString(R.string.try_again));
        showIcon(R.drawable.ic_wifi_off_orange, R.drawable.bg_tile_orange_large);
        action.setOnClickListener(v -> {
            Ui.tap(v);
            runCheck();
        });
    }

    private void showDownloadFailed(String reason) {
        keepScreenOn(false);
        render(R.string.update_failed_title, reason, false, activity.getString(R.string.update_now));
        showIcon(R.drawable.ic_alert_red, R.drawable.bg_tile_red_large);
        action.setOnClickListener(v -> {
            Ui.tap(v);
            startDownload();
        });
    }

    private void showDone(String version) {
        render(R.string.update_done_title, activity.getString(R.string.update_done_message, version), false, null);
        showIcon(R.drawable.ic_circle_check_green, R.drawable.bg_tile_green_large);
        main.postDelayed(this::finish, DONE_VISIBLE_MS);
    }

    private void startDownload() {
        final Downtwo.Release release = pending;
        if (release == null) {
            runCheck();
            return;
        }
        render(R.string.update_downloading_title, activity.getString(R.string.update_downloading_message), false, null);
        final long expected = release.size;
        lastPercent = -2;
        showProgress(0L, expected, expected > 0 ? 0 : -1);
        keepScreenOn(true);

        EXECUTOR.execute(() -> {
            try {
                Downtwo.download(activity, release, new Downtwo.Progress() {
                    @Override
                    public void onProgress(long done, long reported) {
                        // No Content-Length: fall back to the size from the manifest.
                        final long total = reported > 0 ? reported : (expected >= done ? expected : -1L);
                        int percent = total > 0 ? (int) Math.min(100L, done * 100L / total) : -1;
                        if (percent == lastPercent && percent >= 0) return;
                        if (percent < 0) {
                            // Unknown size: refresh the byte counter at most ~6 times a second.
                            long now = SystemClock.uptimeMillis();
                            if (now - lastUnknownPost < 160L) return;
                            lastUnknownPost = now;
                        }
                        lastPercent = percent;
                        post(() -> showProgress(done, total, percent));
                    }

                    @Override
                    public void onInstalling() {
                        post(() -> render(R.string.update_installing_title,
                                activity.getString(R.string.update_installing_message), true, null));
                    }
                }, cancelled);
                post(() -> {
                    keepScreenOn(false);
                    showDone(release.version);
                });
            } catch (UpdateException e) {
                post(() -> showDownloadFailed(e.getMessage()));
            } catch (Throwable t) {
                Log.e(TAG, "Update download crashed", t);
                post(() -> showDownloadFailed(activity.getString(R.string.net_generic)));
            }
        });
    }

    private void showProgress(long done, long total, int percent) {
        progressText.setVisibility(View.VISIBLE);
        if (percent >= 0) {
            spinner.setVisibility(View.GONE);
            progress.setVisibility(View.VISIBLE);
            progress.setIndeterminate(false);
            progress.setProgress(percent);
            progressText.setText(activity.getString(R.string.update_progress, percent,
                    Ui.bytes(done), Ui.bytes(total)));
        } else {
            // Size unknown: a spinner, never a bar that looks complete.
            progress.setVisibility(View.GONE);
            spinner.setVisibility(View.VISIBLE);
            progressText.setText(activity.getString(R.string.update_progress_unknown, Ui.bytes(done)));
        }
    }

    private void finish() {
        if (finished || cancelled.get()) return;
        finished = true;
        keepScreenOn(false);
        dismissQuietly();
        if (listener != null && Ui.alive(activity)) listener.onReady();
    }
}
