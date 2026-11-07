package com.xlox.ui;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.core.widget.ImageViewCompat;

import com.airbnb.lottie.LottieAnimationView;
import com.airbnb.lottie.LottieDrawable;
import com.airbnb.lottie.RenderMode;
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
 */
public final class UpdateGate {

    private static final String TAG = "UpdateGate";
    private static final long MIN_CHECKING_MS = 450L;
    private static final long DONE_VISIBLE_MS = 700L;

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

    private BottomSheetDialog dialog;
    private LottieAnimationView anim;
    private int animRes;
    private ImageView icon;
    private volatile long lastUnknownPost;
    private TextView title;
    private TextView message;
    private TextView detail;
    private LinearProgressIndicator progress;
    private TextView progressText;
    private MaterialButton action;

    private Downtwo.Release pending;
    private boolean finished;
    /** QA preview: keep the "done" state on screen. */
    private boolean holdDone;
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
        if (anim != null) anim.cancelAnimation();
        dismissQuietly();
    }

    // ---------------------------------------------------------------- dialog

    private boolean ensureDialog() {
        if (!Ui.alive(activity) || cancelled.get()) return false;
        if (dialog != null) return true;
        try {
            View root = LayoutInflater.from(activity).inflate(R.layout.dialog_update, null, false);
            anim = root.findViewById(R.id.gateAnim);
            anim.setRenderMode(RenderMode.HARDWARE);
            icon = root.findViewById(R.id.gateIcon);
            title = root.findViewById(R.id.gateTitle);
            message = root.findViewById(R.id.gateMessage);
            detail = root.findViewById(R.id.gateDetail);
            progress = root.findViewById(R.id.gateProgress);
            progressText = root.findViewById(R.id.gateProgressText);
            action = root.findViewById(R.id.gateAction);
            dialog = Ui.sheet(activity, root, false);
            dialog.setOnKeyListener((d, keyCode, event) -> {
                if (keyCode != KeyEvent.KEYCODE_BACK) return false;
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    // The update is mandatory: leaving the gate leaves the app.
                    activity.finishAffinity();
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

    private static final int VISUAL_NONE = 0;
    private static final int VISUAL_SYNC = 1;
    private static final int VISUAL_SUCCESS = 2;

    private void render(int titleRes, CharSequence body, int visual, CharSequence buttonLabel) {
        title.setText(titleRes);
        message.setText(body);
        setVisual(visual);
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

    /** Lottie slot: anoy_sync loops while checking, anoy_success plays once. */
    private void setVisual(int visual) {
        if (visual == VISUAL_NONE) {
            anim.cancelAnimation();
            anim.setVisibility(View.GONE);
            animRes = 0;
            return;
        }
        int res = visual == VISUAL_SYNC ? R.raw.anoy_sync : R.raw.anoy_success;
        anim.setVisibility(View.VISIBLE);
        if (animRes == res) return;
        animRes = res;
        anim.setAnimation(res);
        if (Ui.reducedMotion(activity)) {
            // Reduced motion: the final frame, no movement.
            anim.setProgress(1f);
            return;
        }
        anim.setRepeatCount(visual == VISUAL_SYNC ? LottieDrawable.INFINITE : 0);
        anim.playAnimation();
    }

    /** 56 dp tile above the title for non-animated states. */
    private void showIcon(int drawable, int tile, int tint) {
        icon.setImageResource(drawable);
        icon.setBackgroundResource(tile);
        ImageViewCompat.setImageTintList(icon, ColorStateList.valueOf(ContextCompat.getColor(activity, tint)));
        icon.setVisibility(View.VISIBLE);
    }

    private void renderChecking() {
        render(R.string.update_checking_title, activity.getString(R.string.update_checking_message), VISUAL_SYNC, null);
    }

    private void renderRequired(String version, String localVersion, boolean libUsable, long size) {
        CharSequence body = libUsable
                ? activity.getString(R.string.update_required_message, version, localVersion)
                : activity.getString(R.string.update_required_message_missing, version);
        render(R.string.update_required_title, body, VISUAL_NONE, activity.getString(R.string.update_now));
        showIcon(R.drawable.ic_download, R.drawable.bg_status_blue, R.color.blue);
        if (size > 0) {
            detail.setText(Ui.bytes(size));
            detail.setContentDescription(activity.getString(R.string.update_size, Ui.bytes(size)));
            detail.setVisibility(View.VISIBLE);
        }
        action.setOnClickListener(v -> {
            Ui.tap(v);
            startDownload();
        });
    }

    private void renderDownloading() {
        render(R.string.update_downloading_title, activity.getString(R.string.update_downloading_message),
                VISUAL_NONE, null);
        showIcon(R.drawable.ic_download, R.drawable.bg_status_blue, R.color.blue);
    }

    private void renderInstalling() {
        render(R.string.update_installing_title, activity.getString(R.string.update_installing_message),
                VISUAL_NONE, null);
        showIcon(R.drawable.ic_package, R.drawable.bg_status_blue, R.color.blue);
        progress.setVisibility(View.VISIBLE);
        setIndeterminate(true);
    }

    private void renderCheckFailed(String reason) {
        render(R.string.update_check_failed_title, reason, VISUAL_NONE, activity.getString(R.string.try_again));
        showIcon(R.drawable.ic_wifi_off, R.drawable.bg_status_danger, R.color.danger);
        action.setIconResource(R.drawable.ic_rotate_cw);
        action.setOnClickListener(v -> {
            Ui.tap(v);
            runCheck();
        });
    }

    private void renderDownloadFailed(String reason) {
        render(R.string.update_failed_title, reason, VISUAL_NONE, activity.getString(R.string.try_again));
        showIcon(R.drawable.ic_circle_alert, R.drawable.bg_status_danger, R.color.danger);
        action.setIconResource(R.drawable.ic_rotate_cw);
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
        renderCheckFailed(reason);
    }

    private void showDownloadFailed(String reason) {
        keepScreenOn(false);
        renderDownloadFailed(reason);
    }

    private void showDone(String version) {
        render(R.string.update_done_title, activity.getString(R.string.update_done_message, version),
                VISUAL_SUCCESS, null);
        Ui.confirm(title);
        if (!holdDone) main.postDelayed(this::finish, DONE_VISIBLE_MS);
    }

    private void startDownload() {
        final Downtwo.Release release = pending;
        if (release == null) {
            runCheck();
            return;
        }
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

    /** 6 dp bar with mono tabular text; indeterminate when the size is unknown. */
    private void showProgress(long done, long total, int percent) {
        progress.setVisibility(View.VISIBLE);
        progressText.setVisibility(View.VISIBLE);
        if (percent >= 0) {
            setIndeterminate(false);
            progress.setProgressCompat(percent, !Ui.reducedMotion(activity));
            progressText.setText(activity.getString(R.string.update_progress, percent,
                    Ui.bytes(done), Ui.bytes(total)));
        } else {
            // Size unknown: an indeterminate bar, never one that looks complete.
            setIndeterminate(true);
            progressText.setText(activity.getString(R.string.update_progress_unknown, Ui.bytes(done)));
        }
    }

    private void finish() {
        if (finished || cancelled.get()) return;
        finished = true;
        keepScreenOn(false);
        if (anim != null) anim.cancelAnimation();
        dismissQuietly();
        if (listener != null && Ui.alive(activity)) listener.onReady();
    }
}
