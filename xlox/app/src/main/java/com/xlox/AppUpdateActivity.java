package com.xlox;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.IntentCompat;
import androidx.core.widget.TextViewCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.xlox.ui.Ui;
import com.xlox.utils.AppUpdater;
import com.xlox.utils.UpdateException;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 2.2.0: the app (APK) update screen, opened by the splash when the panel
 * announces a newer versionCode. Runs BEFORE sign in and the library
 * UpdateGate, which stay exactly as they were.
 * <ul>
 *   <li>Mandatory: changelog + "Update now" only; back does not leave.</li>
 *   <li>Normal: also "Not now", which continues to sign in.</li>
 *   <li>Update now: in-app download with progress (screen kept on), a quick
 *       validity check, then Android's own installer via a PackageInstaller
 *       session - no browser, no file:// URI. Any failure shows its reason
 *       and Retry.</li>
 * </ul>
 */
public class AppUpdateActivity extends AppCompatActivity {

    private static final String TAG = "AppUpdate";
    /** Debug builds only: sample screen for the screenshot QA, no network or install. */
    public static final String EXTRA_QA_PREVIEW = "qa_update_preview";
    /** Debug builds only: start the sample in a state - downloading | installing | failed. */
    public static final String EXTRA_QA_STATE = "qa_update_state";
    /** After the system confirmation closes without any result, treat it as cancelled. */
    private static final long CONFIRM_TIMEOUT_MS = 6000L;
    private static final long PROGRESS_MIN_INTERVAL_MS = 120L;

    private enum State { READY, DOWNLOADING, VERIFYING, INSTALLING, FAILED, INSTALLED }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "anoy-app-update"));
    private final AtomicBoolean cancel = new AtomicBoolean(false);

    private AppUpdater.Release release;
    private boolean preview;
    private State state = State.READY;
    private boolean receiverRegistered;
    private boolean awaitingConfirm;
    private boolean leftForConfirm;
    private long lastProgressAt;
    private boolean opened;

    private ImageView icon;
    private TextView titleText;
    private TextView statusText;
    private LinearProgressIndicator progress;
    private TextView progressText;
    private MaterialButton primary;
    private MaterialButton later;

    private final BroadcastReceiver installStatus = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            onInstallStatus(intent);
        }
    };

    private final Runnable confirmTimeout = () -> {
        if (state == State.INSTALLING && awaitingConfirm) {
            Log.i(TAG, "Install confirmation closed without a result");
            awaitingConfirm = false;
            fail(getString(R.string.app_update_install_cancelled));
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        release = AppUpdater.Release.from(getIntent());
        preview = BuildConfig.DEBUG && getIntent().getBooleanExtra(EXTRA_QA_PREVIEW, false);
        if (release == null) {
            Log.w(TAG, "Opened without a release");
            openLogin();
            return;
        }
        Ui.edgeToEdge(this);
        setContentView(R.layout.activity_app_update);
        icon = findViewById(R.id.appUpdateIcon);
        titleText = findViewById(R.id.appUpdateTitle);
        statusText = findViewById(R.id.appUpdateStatus);
        progress = findViewById(R.id.appUpdateProgress);
        progressText = findViewById(R.id.appUpdateProgressText);
        primary = findViewById(R.id.appUpdatePrimary);
        later = findViewById(R.id.appUpdateLater);

        View root = findViewById(R.id.appUpdateRoot);
        View content = findViewById(R.id.appUpdateContent);
        View bar = findViewById(R.id.appUpdateBar);
        View panel = findViewById(R.id.appUpdatePanel);
        Ui.maxWidth(content, 600);
        Ui.maxWidth(panel, 600);
        Ui.onInsets(root, (bars, bottom, ime) -> {
            root.setPadding(bars.left, bars.top, bars.right, 0);
            Ui.padBottom(panel, bars.bottom);
        });
        bar.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int needed = (b - t) + Ui.dp(this, 16);
            if (content.getPaddingBottom() != needed) {
                content.setPadding(content.getPaddingLeft(), content.getPaddingTop(),
                        content.getPaddingRight(), needed);
            }
        });

        titleText.setText(release.mandatory ? R.string.app_update_title_required : R.string.app_update_title);
        ((TextView) findViewById(R.id.appUpdateSubtitle)).setText(getString(release.mandatory
                        ? R.string.app_update_subtitle_required : R.string.app_update_subtitle,
                release.versionName, BuildConfig.VERSION_NAME));
        String notes = formatChangelog(release.changelog);
        ((TextView) findViewById(R.id.appUpdateNotes)).setText(notes != null ? notes
                : getString(R.string.app_update_no_changelog));
        later.setVisibility(release.mandatory ? View.GONE : View.VISIBLE);

        primary.setOnClickListener(v -> {
            Ui.tap(v);
            startUpdate();
        });
        later.setOnClickListener(v -> {
            Ui.tap(v);
            notNow();
        });
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (release.mandatory) {
                    Ui.snackbar(AppUpdateActivity.this, getString(R.string.app_update_required_back));
                } else if (state != State.INSTALLING && state != State.VERIFYING) {
                    notNow();
                }
            }
        });

        if (!preview) {
            ContextCompat.registerReceiver(this, installStatus, new IntentFilter(AppUpdater.ACTION_INSTALL_STATUS),
                    ContextCompat.RECEIVER_NOT_EXPORTED);
            receiverRegistered = true;
        }
        Log.i(TAG, "Offering " + release.versionName + " (" + release.versionCode + ")"
                + (release.mandatory ? ", mandatory" : ""));
        render(State.READY, null);
        if (preview) applyQaState();
        Ui.stagger((android.view.ViewGroup) content, 0);
        Ui.reveal(bar, 120);
    }

    /** Bulleted, trimmed changelog lines; a plain line when the release has none. */
    static String formatChangelog(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        StringBuilder b = new StringBuilder();
        for (String line : raw.replace("\r", "").split("\n")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            if (t.startsWith("- ") || t.startsWith("* ") || t.startsWith("\u2022 ")) t = t.substring(2).trim();
            if (b.length() > 0) b.append('\n');
            b.append("\u2022  ").append(t);
        }
        return b.length() == 0 ? null : b.toString();
    }

    // ------------------------------------------------------------------ flow

    private void startUpdate() {
        if (state == State.DOWNLOADING || state == State.VERIFYING || state == State.INSTALLING) return;
        cancel.set(false);
        lastProgressAt = 0L;
        render(State.DOWNLOADING, null);
        onProgress(0, -1);
        if (preview) {
            simulateDownload(0);
            return;
        }
        worker.execute(() -> {
            try {
                File apk = AppUpdater.download(this, release, (done, total) -> main.post(() -> onProgress(done, total)),
                        cancel);
                main.post(() -> {
                    if (Ui.alive(this)) render(State.VERIFYING, null);
                });
                int session = AppUpdater.install(this, apk);
                Log.i(TAG, "Waiting for the installer (session " + session + ")");
                main.post(() -> {
                    if (!Ui.alive(this)) return;
                    render(State.INSTALLING, getString(R.string.app_update_confirm));
                });
            } catch (UpdateException e) {
                Log.w(TAG, "Update failed: " + e.getMessage());
                main.post(() -> {
                    if (Ui.alive(this) && !cancel.get()) fail(e.getMessage());
                });
            } catch (RuntimeException e) {
                Log.e(TAG, "Update crashed", e);
                main.post(() -> {
                    if (Ui.alive(this)) fail(getString(R.string.app_update_install_failed));
                });
            }
        });
    }

    private void onProgress(long done, long total) {
        if (!Ui.alive(this) || state != State.DOWNLOADING) return;
        long now = System.currentTimeMillis();
        boolean last = total > 0 && done >= total;
        if (!last && now - lastProgressAt < PROGRESS_MIN_INTERVAL_MS) return;
        lastProgressAt = now;
        progress.setVisibility(View.VISIBLE);
        progressText.setVisibility(View.VISIBLE);
        if (total > 0) {
            int permille = (int) Math.min(1000L, done * 1000L / total);
            if (progress.isIndeterminate()) {
                progress.setVisibility(View.INVISIBLE);
                progress.setIndeterminate(false);
                progress.setVisibility(View.VISIBLE);
            }
            progress.setProgressCompat(permille, !Ui.reducedMotion(this));
            progressText.setText(getString(R.string.app_update_progress, permille / 10,
                    Ui.bytes(done), Ui.bytes(total)));
        } else {
            if (!progress.isIndeterminate()) {
                progress.setVisibility(View.INVISIBLE);
                progress.setIndeterminate(true);
                progress.setVisibility(View.VISIBLE);
            }
            progressText.setText(done > 0 ? getString(R.string.app_update_progress_unknown, Ui.bytes(done))
                    : getString(R.string.app_update_downloading));
        }
    }

    private void onInstallStatus(Intent intent) {
        if (intent == null || !Ui.alive(this)) return;
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        String detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        Log.i(TAG, "Installer status " + status);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent.class);
            if (confirm == null) {
                fail(getString(R.string.app_update_install_failed));
                return;
            }
            try {
                awaitingConfirm = true;
                leftForConfirm = false;
                startActivity(confirm);
            } catch (RuntimeException e) {
                Log.w(TAG, "Installer confirmation could not open", e);
                awaitingConfirm = false;
                fail(getString(R.string.app_update_install_failed));
            }
            return;
        }
        awaitingConfirm = false;
        main.removeCallbacks(confirmTimeout);
        if (status == PackageInstaller.STATUS_SUCCESS) {
            // Normally Android replaces the app and this process ends first.
            render(State.INSTALLED, getString(R.string.app_update_installed));
            return;
        }
        fail(AppUpdater.statusMessage(this, status, detail));
    }

    private void fail(String reason) {
        render(State.FAILED, reason == null ? getString(R.string.app_update_install_failed) : reason);
    }

    private void notNow() {
        if (release.mandatory) return;
        cancel.set(true);
        Log.i(TAG, "Not now: continuing with " + BuildConfig.VERSION_NAME);
        openLogin();
    }

    private void openLogin() {
        if (opened) return;
        opened = true;
        startActivity(new Intent(this, LogAct.class));
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
        finish();
    }

    // ---------------------------------------------------------------- render

    private void render(State next, String message) {
        state = next;
        boolean working = next == State.DOWNLOADING || next == State.VERIFYING || next == State.INSTALLING;
        if (working) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        later.setEnabled(!working || next == State.DOWNLOADING);
        switch (next) {
            case READY:
                setIcon(R.drawable.ic_arrow_down_to_line, R.drawable.bg_status_blue, R.color.blue);
                hideStatus();
                progress.setVisibility(View.GONE);
                progressText.setVisibility(View.GONE);
                Ui.setLoading(primary, false, getString(R.string.app_update_now));
                primary.setEnabled(true);
                break;
            case DOWNLOADING:
                setIcon(R.drawable.ic_arrow_down_to_line, R.drawable.bg_status_blue, R.color.blue);
                showStatus(getString(R.string.app_update_keep_open), false);
                Ui.setLoading(primary, true, getString(R.string.app_update_downloading));
                break;
            case VERIFYING:
                progress.setVisibility(View.GONE);
                progressText.setVisibility(View.GONE);
                showStatus(getString(R.string.app_update_keep_open), false);
                Ui.setLoading(primary, true, getString(R.string.app_update_verifying));
                break;
            case INSTALLING:
                progress.setVisibility(View.GONE);
                progressText.setVisibility(View.GONE);
                showStatus(message != null ? message : getString(R.string.app_update_keep_open), false);
                Ui.setLoading(primary, true, getString(R.string.app_update_installing));
                break;
            case FAILED:
                setIcon(R.drawable.ic_circle_alert, R.drawable.bg_status_danger, R.color.danger);
                titleText.setText(R.string.app_update_failed_title);
                progress.setVisibility(View.GONE);
                progressText.setVisibility(View.GONE);
                showStatus(message, true);
                Ui.setLoading(primary, false, getString(R.string.app_update_retry));
                primary.setEnabled(true);
                break;
            case INSTALLED:
                setIcon(R.drawable.ic_circle_check, R.drawable.bg_status_blue, R.color.blue);
                showStatus(message, false);
                Ui.setSuccess(primary, getString(R.string.app_update_done));
                break;
        }
        if (next != State.FAILED) {
            titleText.setText(release.mandatory ? R.string.app_update_title_required : R.string.app_update_title);
        }
    }

    private void setIcon(int drawable, int tile, int colour) {
        icon.setImageResource(drawable);
        icon.setBackgroundResource(tile);
        icon.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(this, colour)));
    }

    private void showStatus(String text, boolean error) {
        if (text == null || text.isEmpty()) {
            hideStatus();
            return;
        }
        statusText.setText(text);
        statusText.setTextColor(ContextCompat.getColor(this, error ? R.color.danger : R.color.text_secondary));
        Drawable glyph = null;
        if (error) {
            glyph = ContextCompat.getDrawable(this, R.drawable.ic_circle_alert);
            if (glyph != null) {
                int size = getResources().getDimensionPixelSize(R.dimen.icon_small);
                glyph = glyph.mutate();
                glyph.setBounds(0, 0, size, size);
            }
            Ui.rejectHaptic(statusText);
        }
        statusText.setCompoundDrawablesRelative(glyph, null, null, null);
        TextViewCompat.setCompoundDrawableTintList(statusText,
                ColorStateList.valueOf(ContextCompat.getColor(this, R.color.danger)));
        statusText.setVisibility(View.VISIBLE);
    }

    private void hideStatus() {
        statusText.setVisibility(View.GONE);
    }

    // --------------------------------------------------------------- QA only

    /** Debug sample: a fake download that ends in a clear failure (nothing is installed). */
    private void simulateDownload(long done) {
        final long total = 24L * 1024L * 1024L;
        if (!Ui.alive(this) || state != State.DOWNLOADING) return;
        if (done >= total) {
            onProgress(total, total);
            main.postDelayed(() -> {
                if (Ui.alive(this)) fail(getString(R.string.app_update_invalid));
            }, 400L);
            return;
        }
        onProgress(done, total);
        main.postDelayed(() -> simulateDownload(done + total / 12), 160L);
    }

    private void applyQaState() {
        String qa = getIntent().getStringExtra(EXTRA_QA_STATE);
        if (qa == null) return;
        switch (qa.toLowerCase(Locale.US)) {
            case "downloading":
                render(State.DOWNLOADING, null);
                lastProgressAt = 0L;
                onProgress(11L * 1024L * 1024L, 24L * 1024L * 1024L);
                break;
            case "installing":
                render(State.INSTALLING, getString(R.string.app_update_confirm));
                break;
            case "failed":
                fail(getString(R.string.net_offline));
                break;
            default:
                break;
        }
    }

    // ------------------------------------------------------------- lifecycle

    @Override
    protected void onPause() {
        super.onPause();
        if (awaitingConfirm) leftForConfirm = true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (awaitingConfirm && leftForConfirm) {
            // Back from Android's confirmation. A real install ends this
            // process; a cancel may send nothing at all on some phones.
            main.removeCallbacks(confirmTimeout);
            main.postDelayed(confirmTimeout, CONFIRM_TIMEOUT_MS);
        }
    }

    @Override
    protected void onDestroy() {
        cancel.set(true);
        main.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        if (receiverRegistered) {
            try {
                unregisterReceiver(installStatus);
            } catch (RuntimeException ignored) {
                // Not registered.
            }
            receiverRegistered = false;
        }
        super.onDestroy();
    }
}
