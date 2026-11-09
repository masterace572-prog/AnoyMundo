package com.xlox.ui;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.xlox.BuildConfig;
import com.xlox.R;
import com.xlox.utils.Downtwo;
import com.xlox.utils.UpdateException;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Mandatory library update gate, shown as a non-dismissable bottom sheet.
 *
 * Always checks the panel first. When a newer library exists the only action
 * is "Update now"; the sheet cannot be dismissed (Back closes the app) and the
 * listener only fires once the library is current. When the check itself
 * fails the only action is "Try again".
 *
 * 2.3.4 (UI only): checking, downloading and installing that run past 6 s
 * (downloading: 6 s without progress) say "Taking longer than usual. Keep Anoy
 * open." Failure reasons drop their own "Try again." next to the button.
 */
public final class UpdateGate {

    private static final String TAG = "UpdateGate";
    private static final long MIN_CHECKING_MS = 450L;
    /** "You're up to date" stays ~900 ms, then the sheet leaves (240 ms). */
    private static final long DONE_VISIBLE_MS = 900L;
    /** "Library 1.13 installed" (Settings re-download) stays a little longer. */
    private static final long REDOWNLOAD_DONE_VISIBLE_MS = 1500L;

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
    /** A wait that runs past Ui.SLOW_MS says so (text only; the flow is unchanged). */
    private final Runnable slowWait = new Runnable() {
        @Override
        public void run() {
            if (message != null && Ui.alive(activity) && !cancelled.get()) {
                Ui.swapText(message, activity.getString(R.string.update_slow));
            }
        }
    };
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    private BottomSheetDialog dialog;
    private View icon;
    private volatile long lastUnknownPost;
    private TextView title;
    private TextView message;
    private TextView detail;
    private LinearProgressIndicator progress;
    private TextView progressText;
    private MaterialButton action;
    private MaterialButton close;

    /**
     * Settings > Re-download library (2.3.1): the same check, download and
     * install, but optional - Back never leaves the app, and after a failure
     * the sheet can be closed (the installed library keeps working).
     */
    private boolean optional;
    /** Optional mode: checking, downloading or installing (Back does nothing). */
    private boolean working;

    private Downtwo.Release pending;
    private boolean finished;
    /** QA preview: keep the "done" state on screen. */
    private boolean holdDone;
    private int lastPercent = -2;

    private UpdateGate(Activity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
    }

    /**
     * Settings > Re-download library: checks what the panel publishes and
     * downloads + installs it again through the usual path, with the usual
     * sheet. The listener runs when the sheet closes (installed or not).
     */
    public static UpdateGate redownload(Activity activity, Listener listener) {
        UpdateGate gate = new UpdateGate(activity, listener);
        gate.optional = true;
        if (gate.ensureDialog()) gate.runRedownload();
        return gate;
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

    /**
     * Debug builds only (screenshot QA): renders one state with sample values
     * and no network. "done" stays on screen; every other state is static.
     */
    public static UpdateGate qaPreview(Activity activity, String state, Listener listener) {
        UpdateGate gate = new UpdateGate(activity, listener);
        if (!BuildConfig.DEBUG || !gate.ensureDialog()) return gate;
        gate.holdDone = true;
        switch (state) {
            case "checking":
                gate.renderChecking();
                break;
            case "required":
                gate.renderRequired("2.4.1", "2.3.0", true, 4_404_019L);
                break;
            case "downloading":
                gate.renderDownloading();
                gate.showProgress(1_849_688L, 4_404_019L, 42);
                break;
            case "downloading_unknown":
                gate.renderDownloading();
                gate.showProgress(1_849_688L, -1L, -1);
                break;
            case "installing":
                gate.renderInstalling();
                break;
            case "done":
                gate.showDone("2.4.1");
                break;
            case "redownloaded":
                gate.optional = true;
                gate.showDone("1.13");
                break;
            case "check_failed":
                gate.renderCheckFailed(activity.getString(R.string.net_offline));
                break;
            case "download_failed":
                gate.renderDownloadFailed(activity.getString(R.string.update_corrupt));
                break;
            default:
                gate.finish();
                break;
        }
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
            View root = LayoutInflater.from(activity).inflate(R.layout.dialog_update, null, false);
            icon = root.findViewById(R.id.gateIcon);
            title = root.findViewById(R.id.gateTitle);
            message = root.findViewById(R.id.gateMessage);
            detail = root.findViewById(R.id.gateDetail);
            progress = root.findViewById(R.id.gateProgress);
            progressText = root.findViewById(R.id.gateProgressText);
            action = root.findViewById(R.id.gateAction);
            close = root.findViewById(R.id.gateClose);
            close.setOnClickListener(v -> {
                Ui.tap(v);
                finish();
            });
            dialog = Ui.sheet(activity, root, false);
            dialog.setOnKeyListener((d, keyCode, event) -> {
                if (keyCode != KeyEvent.KEYCODE_BACK) return false;
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    if (optional) {
                        // Re-download: Back closes the sheet unless work is running.
                        if (!working) finish();
                    } else {
                        // The update is mandatory: leaving the gate leaves the app.
                        activity.finishAffinity();
                    }
                }
                return true;
            });
            dialog.show();
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

    // ------------------------------------------------------------- rendering

    /**
     * One state of the sheet: title, Body S message (danger for errors), an
     * optional action. The bar, the byte line, the size and the check are
     * hidden; each state shows what it needs.
     */
    private void render(int titleRes, CharSequence body, boolean error, CharSequence buttonLabel) {
        main.removeCallbacks(slowWait);
        title.setText(titleRes);
        message.animate().cancel();
        message.setAlpha(1f);
        message.setTag(R.id.tag_swap_text, null);
        message.setText(body);
        message.setVisibility(View.VISIBLE);
        // Close only after a failed re-download; the action carries the rest.
        close.setVisibility(optional && error ? View.VISIBLE : View.GONE);
        message.setTextColor(ContextCompat.getColor(activity, error ? R.color.danger : R.color.text_secondary));
        icon.setVisibility(View.GONE);
        detail.setVisibility(View.GONE);
        progress.setVisibility(View.GONE);
        progressText.setVisibility(View.GONE);
        if (buttonLabel == null) {
            action.setVisibility(View.GONE);
            action.setOnClickListener(null);
        } else {
            Ui.setLoading(action, false, buttonLabel);
            action.setEnabled(true);
            action.setVisibility(View.VISIBLE);
        }
    }

    /** 3 dp indeterminate bar (checking, installing). */
    private void showIndeterminateBar() {
        progress.setVisibility(View.VISIBLE);
        setIndeterminate(true);
    }

    private void renderChecking() {
        render(R.string.update_checking_title, activity.getString(R.string.update_checking_message), false, null);
        showIndeterminateBar();
        armSlow();
    }

    /** (Re)starts the 6 s "Taking longer than usual" timer for the current wait. */
    private void armSlow() {
        main.removeCallbacks(slowWait);
        main.postDelayed(slowWait, Ui.SLOW_MS);
    }

    private void renderRequired(String version, String localVersion, boolean libUsable, long size) {
        CharSequence body = libUsable
                ? activity.getString(R.string.update_required_message, version, localVersion)
                : activity.getString(R.string.update_required_message_missing, version);
        render(R.string.update_required_title, body, false, activity.getString(R.string.update_now));
        if (size > 0) {
            detail.setText(activity.getString(R.string.update_size, Ui.bytes(size)));
            detail.setVisibility(View.VISIBLE);
        }
        action.setOnClickListener(v -> {
            Ui.tap(v);
            startDownload();
        });
    }

    private void renderDownloading() {
        render(R.string.update_downloading_title, activity.getString(R.string.update_downloading_message),
                false, null);
        armSlow();
    }

    private void renderInstalling() {
        render(R.string.update_installing_title, activity.getString(R.string.update_installing_message),
                false, null);
        showIndeterminateBar();
        armSlow();
    }

    private void renderCheckFailed(String reason) {
        render(R.string.update_check_failed_title, Ui.dropTryAgain(reason), true, activity.getString(R.string.try_again));
        action.setOnClickListener(v -> {
            Ui.tap(v);
            if (optional) runRedownload();
            else runCheck();
        });
    }

    private void renderDownloadFailed(String reason) {
        render(R.string.update_failed_title, Ui.dropTryAgain(reason), true, activity.getString(R.string.try_again));
        action.setOnClickListener(v -> {
            Ui.tap(v);
            startDownload();
        });
    }

    /** Switching modes on a visible indicator requires hiding it first. */
    private void setIndeterminate(boolean indeterminate) {
        if (progress.isIndeterminate() == indeterminate) return;
        int visibility = progress.getVisibility();
        progress.setVisibility(View.INVISIBLE);
        progress.setIndeterminate(indeterminate);
        progress.setVisibility(visibility);
    }

    // ---------------------------------------------------------------- states

    private void runCheck() {
        renderChecking();
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

    /** Re-download: the panel's current release, then the usual download + install. */
    private void runRedownload() {
        working = true;
        renderChecking();
        final long started = SystemClock.elapsedRealtime();
        EXECUTOR.execute(() -> {
            try {
                Downtwo.Status status = Downtwo.check(activity);
                long wait = Math.max(0L, MIN_CHECKING_MS - (SystemClock.elapsedRealtime() - started));
                main.postDelayed(() -> {
                    if (cancelled.get() || !Ui.alive(activity)) return;
                    if (status.release == null || !status.release.available) {
                        showCheckFailed(activity.getString(R.string.update_no_release));
                        return;
                    }
                    Log.i(TAG, "Re-downloading library " + status.release.version
                            + " (installed " + status.localVersion + ")");
                    pending = status.release;
                    startDownload();
                }, wait);
            } catch (UpdateException e) {
                post(() -> showCheckFailed(e.getMessage()));
            } catch (Throwable t) {
                Log.e(TAG, "Re-download check crashed", t);
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
        renderRequired(status.release.version, status.localVersion, status.libUsable, status.release.size);
    }

    private void showCheckFailed(String reason) {
        working = false;
        renderCheckFailed(reason);
    }

    private void showDownloadFailed(String reason) {
        working = false;
        keepScreenOn(false);
        renderDownloadFailed(reason);
    }

    private void showDone(String version) {
        // The bar (if any) fills to 100 % over 180 ms; then the check.
        boolean hadBar = progress.getVisibility() == View.VISIBLE;
        int from = progress.isIndeterminate() ? 0 : progress.getProgress();
        working = false;
        render(R.string.update_done_title, activity.getString(R.string.update_done_message, version),
                false, null);
        if (optional) {
            // Re-download: one line, "Library 1.13 installed" + the check.
            title.setText(activity.getString(R.string.redownload_done, version));
            message.setVisibility(View.GONE);
        }
        icon.setVisibility(View.VISIBLE);
        if (hadBar) {
            progress.setVisibility(View.VISIBLE);
            setIndeterminate(false);
            if (Ui.reducedMotion(activity)) {
                progress.setProgressCompat(100, false);
            } else {
                ValueAnimator fill = ValueAnimator.ofInt(from, 100);
                fill.setDuration(Ui.SHORT);
                fill.setInterpolator(new LinearInterpolator());
                fill.addUpdateListener(a -> progress.setProgressCompat((int) a.getAnimatedValue(), false));
                fill.start();
            }
        }
        Ui.confirm(title);
        if (!holdDone) main.postDelayed(this::finish, optional ? REDOWNLOAD_DONE_VISIBLE_MS : DONE_VISIBLE_MS);
    }

    private void startDownload() {
        final Downtwo.Release release = pending;
        if (release == null) {
            runCheck();
            return;
        }
        working = true;
        renderDownloading();
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
                        post(UpdateGate.this::renderInstalling);
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

    /** 3 dp bar with "x.x of y.y MB" in Mono; indeterminate when the size is unknown. */
    private void showProgress(long done, long total, int percent) {
        // Progress arrived: the download is moving. Back to the normal line and
        // restart the slow timer.
        if (message != null && message.getTag(R.id.tag_swap_text) == null
                && activity.getString(R.string.update_slow).contentEquals(message.getText())) {
            Ui.swapText(message, activity.getString(R.string.update_downloading_message));
        }
        armSlow();
        progress.setVisibility(View.VISIBLE);
        progressText.setVisibility(View.VISIBLE);
        if (percent >= 0) {
            setIndeterminate(false);
            progress.setProgressCompat(percent, !Ui.reducedMotion(activity));
            progressText.setText(Ui.bytesOf(activity, done, total));
            progressText.setContentDescription(activity.getString(R.string.update_progress, percent,
                    Ui.bytes(done), Ui.bytes(total)));
        } else {
            // Size unknown: an indeterminate bar, never one that looks complete.
            setIndeterminate(true);
            progressText.setText(activity.getString(R.string.update_progress_unknown, Ui.bytes(done)));
            progressText.setContentDescription(null);
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
