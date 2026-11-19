package com.xlox;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.IntentCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.xlox.ui.Ui;
import com.xlox.utils.AppUpdater;
import com.xlox.utils.UpdateException;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
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
 *   <li>Normal: also "Not now", which continues to sign in (or, when opened
 *       from Settings > Check for app updates, goes back to Settings).</li>
 *   <li>Update now: in-app download with progress (screen kept on), a quick
 *       validity check, then Android's own installer via a PackageInstaller
 *       session - no browser, no file:// URI. Any failure shows "Update
 *       failed" over its reason, and Try again.</li>
 *   <li>2.3.4: a 4 dp bar above Update now; the status and byte lines keep
 *       their space; while downloading the second button reads "Cancel
 *       download" (mandatory updates too) and goes back to Update now; 8 s
 *       without new bytes says "Download is slow. Keep Anoy open."</li>
 * </ul>
 */
public class AppUpdateActivity extends AppCompatActivity {

    private static final String TAG = "AppUpdate";
    /** Debug builds only: sample screen for the screenshot QA, no network or install. */
    public static final String EXTRA_QA_PREVIEW = "qa_update_preview";
    /** Debug builds only: start the sample in a state - downloading | installing | failed. */
    public static final String EXTRA_QA_STATE = "qa_update_state";
    /** 2.3.1: opened from Settings (signed in) - "Not now" returns there instead of sign in. */
    public static final String EXTRA_FROM_SETTINGS = "app_update_from_settings";
    /** After the system confirmation closes without any result, treat it as cancelled. */
    private static final long CONFIRM_TIMEOUT_MS = 6000L;
    private static final long PROGRESS_MIN_INTERVAL_MS = 120L;
    /** No new bytes for this long: "Download is slow. Keep Anoy open." */
    private static final long STALL_MS = 8000L;
    private static final long STALL_CHECK_MS = 1000L;

    private enum State { READY, DOWNLOADING, VERIFYING, INSTALLING, FAILED, INSTALLED }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "anoy-app-update"));
    /** Each download run gets its own flag, so a cancelled run can't touch the next one. */
    private AtomicBoolean cancel = new AtomicBoolean(false);
    private int downloadRun;
    private long lastBytes = -1L;
    private long lastBytesAt;
    private boolean stallShown;

    private AppUpdater.Release release;
    private boolean preview;
    private State state = State.READY;
    private boolean receiverRegistered;
    private boolean awaitingConfirm;
    private boolean leftForConfirm;
    private long lastProgressAt;
    private boolean opened;

    private TextView kickerText;
    private TextView statusText;
    private TextView errorText;
    private View errorBlock;
    private TextView progressText;
    private LinearProgressIndicator progressBar;
    private MaterialButton primary;
    private MaterialButton later;

    private final BroadcastReceiver installStatus = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            onInstallStatus(intent);
        }
    };

    /** While downloading: once a second, is anything still arriving? */
    private final Runnable stallCheck = new Runnable() {
        @Override
        public void run() {
            if (!Ui.alive(AppUpdateActivity.this) || state != State.DOWNLOADING) return;
            if (!stallShown && System.currentTimeMillis() - lastBytesAt >= STALL_MS) {
                stallShown = true;
                Ui.swapText(statusText, getString(R.string.app_update_slow));
            }
            main.postDelayed(this, STALL_CHECK_MS);
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
        kickerText = findViewById(R.id.appUpdateKicker);
        statusText = findViewById(R.id.appUpdateStatus);
        errorText = findViewById(R.id.appUpdateError);
        errorBlock = findViewById(R.id.appUpdateErrorBlock);
        progressText = findViewById(R.id.appUpdateProgressText);
        progressBar = findViewById(R.id.appUpdateProgress);
        primary = findViewById(R.id.appUpdatePrimary);
        later = findViewById(R.id.appUpdateLater);

        View root = findViewById(R.id.appUpdateRoot);
        View content = findViewById(R.id.appUpdateContent);
        View bar = findViewById(R.id.appUpdateBar);
        View panel = findViewById(R.id.appUpdatePanel);
        View divider = findViewById(R.id.appUpdateDivider);
        android.widget.ScrollView scroll = findViewById(R.id.appUpdateScroll);
        Ui.maxWidth(content, 600);
        Ui.maxWidth(panel, 600);
        // Pinned footer: a hairline only while content runs underneath it.
        Runnable syncDivider = () -> divider.setVisibility(scroll.canScrollVertically(1) ? View.VISIBLE : View.INVISIBLE);
        scroll.getViewTreeObserver().addOnScrollChangedListener(syncDivider::run);
        scroll.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> v.post(syncDivider));
        Ui.onInsets(root, (bars, bottom, ime) -> {
            root.setPadding(bars.left, bars.top, bars.right, 0);
            Ui.padBottom(panel, bars.bottom);
        });
        bar.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int needed = (b - t) + Ui.dp(this, 24);
            if (content.getPaddingBottom() != needed) {
                content.setPadding(content.getPaddingLeft(), content.getPaddingTop(),
                        content.getPaddingRight(), needed);
            }
        });

        kickerText.setText(release.mandatory ? R.string.app_update_title_required : R.string.app_update_title);
        // "2.2.0 → 2.3.0": the installed version tertiary, the new one primary.
        TextView versions = findViewById(R.id.appUpdateSubtitle);
        SpannableStringBuilder line = new SpannableStringBuilder(BuildConfig.VERSION_NAME);
        line.setSpan(new ForegroundColorSpan(ContextCompat.getColor(this, R.color.text_tertiary)),
                0, line.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        int arrow = line.length();
        line.append(" \u2192 ");
        line.setSpan(new ForegroundColorSpan(ContextCompat.getColor(this, R.color.text_tertiary)),
                arrow, line.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        line.append(release.versionName);
        versions.setText(line);
        versions.setContentDescription(getString(release.mandatory
                        ? R.string.app_update_subtitle_required : R.string.app_update_subtitle,
                release.versionName, BuildConfig.VERSION_NAME));
        List<String> notes = changelogItems(release.changelog);
        if (notes.isEmpty()) notes.add(getString(R.string.app_update_no_changelog));
        fillNotes(findViewById(R.id.appUpdateNotes), notes);
        // "Not now" / "Required" visibility is set by render().

        primary.setOnClickListener(v -> {
            Ui.tap(v);
            startUpdate();
        });
        later.setOnClickListener(v -> {
            Ui.tap(v);
            if (state == State.DOWNLOADING) {
                cancelDownload();
            } else {
                notNow();
            }
        });
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (release.mandatory) {
                    Ui.snackbar(AppUpdateActivity.this, getString(R.string.app_update_required_back));
                } else if (state == State.DOWNLOADING) {
                    cancelDownload();
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
    }

    /** Trimmed changelog lines without their list markers; empty when the release has none. */
    static List<String> changelogItems(String raw) {
        List<String> items = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) return items;
        for (String line : raw.replace("\r", "").split("\n")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            if (t.startsWith("- ") || t.startsWith("* ") || t.startsWith("\u2022 ")) t = t.substring(2).trim();
            if (!t.isEmpty()) items.add(t);
        }
        return items;
    }

    /** One row per item: a 4 dp tertiary dot in a 16 dp gutter, Body text, 12 dp apart. */
    private void fillNotes(LinearLayout list, List<String> items) {
        LayoutInflater inflater = LayoutInflater.from(this);
        list.removeAllViews();
        for (int i = 0; i < items.size(); i++) {
            View row = inflater.inflate(R.layout.item_changelog, list, false);
            ((TextView) row.findViewById(R.id.changelogText)).setText(items.get(i));
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) row.getLayoutParams();
            lp.topMargin = getResources().getDimensionPixelSize(i == 0 ? R.dimen.space_16 : R.dimen.space_12);
            list.addView(row, lp);
        }
    }

    // ------------------------------------------------------------------ flow

    private void startUpdate() {
        if (state == State.DOWNLOADING || state == State.VERIFYING || state == State.INSTALLING) return;
        cancel.set(true);
        final AtomicBoolean runCancel = new AtomicBoolean(false);
        cancel = runCancel;
        final int run = ++downloadRun;
        lastProgressAt = 0L;
        lastBytes = -1L;
        lastBytesAt = System.currentTimeMillis();
        stallShown = false;
        render(State.DOWNLOADING, null);
        onProgress(run, 0, -1);
        main.removeCallbacks(stallCheck);
        main.postDelayed(stallCheck, STALL_CHECK_MS);
        if (preview) {
            simulateDownload(run, 0);
            return;
        }
        worker.execute(() -> {
            try {
                File apk = AppUpdater.download(this, release,
                        (done, total) -> main.post(() -> onProgress(run, done, total)), runCancel);
                if (runCancel.get()) return; // Cancel download: back to Update now, nothing installed.
                main.post(() -> {
                    if (Ui.alive(this) && run == downloadRun && !runCancel.get()) render(State.VERIFYING, null);
                });
                int session = AppUpdater.install(this, apk);
                Log.i(TAG, "Waiting for the installer (session " + session + ")");
                main.post(() -> {
                    if (!Ui.alive(this) || run != downloadRun) return;
                    render(State.INSTALLING, getString(R.string.app_update_confirm));
                });
            } catch (UpdateException e) {
                Log.w(TAG, "Update failed: " + e.getMessage());
                main.post(() -> {
                    if (Ui.alive(this) && run == downloadRun && !runCancel.get()) fail(e.getMessage());
                });
            } catch (RuntimeException e) {
                Log.e(TAG, "Update crashed", e);
                main.post(() -> {
                    if (Ui.alive(this) && run == downloadRun && !runCancel.get()) {
                        fail(getString(R.string.app_update_install_failed));
                    }
                });
            }
        });
    }

    /** "Cancel download": stop this run and show Update now again (mandatory too). */
    private void cancelDownload() {
        if (state != State.DOWNLOADING) return;
        cancel.set(true);
        downloadRun++;
        Log.i(TAG, "Download cancelled");
        render(State.READY, null);
    }

    private void onProgress(int run, long done, long total) {
        if (!Ui.alive(this) || state != State.DOWNLOADING || run != downloadRun) return;
        long now = System.currentTimeMillis();
        if (done != lastBytes) {
            lastBytes = done;
            lastBytesAt = now;
            if (stallShown) {
                stallShown = false;
                Ui.swapText(statusText, getString(R.string.app_update_keep_open));
            }
        }
        boolean last = total > 0 && done >= total;
        if (!last && now - lastProgressAt < PROGRESS_MIN_INTERVAL_MS) return;
        lastProgressAt = now;
        progressText.setVisibility(View.VISIBLE);
        setBarMode(total <= 0);
        progressBar.setVisibility(View.VISIBLE);
        if (total > 0) {
            int permille = (int) Math.min(1000L, done * 1000L / total);
            // Progress button: "Downloading 42%", the bar above it, the bytes in Mono below.
            primary.setText(getString(R.string.app_update_downloading_percent, permille / 10));
            progressText.setText(Ui.bytesOf(this, done, total));
            progressBar.setMax(1000);
            progressBar.setProgressCompat(permille, !Ui.reducedMotion(this));
        } else {
            // Size unknown: the button keeps "Downloading…" and its spinner.
            progressText.setText(done > 0 ? getString(R.string.app_update_progress_unknown, Ui.bytes(done))
                    : getString(R.string.app_update_downloading));
        }
    }

    /** Switching modes on a visible indicator requires hiding it first (as in UpdateGate). */
    private void setBarMode(boolean indeterminate) {
        if (progressBar.isIndeterminate() == indeterminate) return;
        int visibility = progressBar.getVisibility();
        progressBar.setVisibility(View.INVISIBLE);
        progressBar.setIndeterminate(indeterminate);
        progressBar.setVisibility(visibility);
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
        downloadRun++;
        Log.i(TAG, "Not now: continuing with " + BuildConfig.VERSION_NAME);
        openLogin();
    }

    private void openLogin() {
        if (opened) return;
        opened = true;
        if (getIntent().getBooleanExtra(EXTRA_FROM_SETTINGS, false)) {
            // Signed in already: back to Settings, which is still underneath.
            finish();
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
            return;
        }
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
        if (next != State.DOWNLOADING) main.removeCallbacks(stallCheck);
        // While downloading the second button is "Cancel download", even for a
        // mandatory update (it only returns to Update now, never past it).
        boolean downloading = next == State.DOWNLOADING;
        later.setText(downloading ? R.string.app_update_cancel_download : R.string.app_update_not_now);
        later.setVisibility(downloading || !release.mandatory ? View.VISIBLE : View.GONE);
        findViewById(R.id.appUpdateRequired).setVisibility(release.mandatory && !downloading ? View.VISIBLE : View.GONE);
        // Bar and byte line keep their space; they show only while downloading.
        if (!downloading) {
            progressText.setVisibility(View.INVISIBLE);
            progressBar.setVisibility(View.INVISIBLE);
        }
        switch (next) {
            case READY:
                hideStatus();
                Ui.setLoading(primary, false, getString(R.string.app_update_now));
                primary.setEnabled(true);
                break;
            case DOWNLOADING:
                showStatus(getString(R.string.app_update_keep_open), false);
                Ui.setLoading(primary, true, getString(R.string.app_update_downloading));
                break;
            case VERIFYING:
                showStatus(getString(R.string.app_update_keep_open), false);
                Ui.setLoading(primary, true, getString(R.string.app_update_verifying));
                break;
            case INSTALLING:
                showStatus(message != null ? message : getString(R.string.app_update_keep_open), false);
                Ui.setLoading(primary, true, getString(R.string.app_update_installing));
                break;
            case FAILED:
                showStatus(message, true);
                Ui.setLoading(primary, false, getString(R.string.try_again));
                primary.setEnabled(true);
                break;
            case INSTALLED:
                showStatus(message, false);
                Ui.setSuccess(primary, getString(R.string.app_update_done));
                break;
        }
    }

    /**
     * Quiet Caption above the button (its line is always reserved), or, for a
     * failure, the danger-tint block: "Update failed" (danger, medium) over the
     * reason, minus its own "Try again." (the button reads Try again).
     */
    private void showStatus(String text, boolean error) {
        if (text == null || text.isEmpty()) {
            hideStatus();
            return;
        }
        statusText.animate().cancel();
        statusText.setAlpha(1f);
        statusText.setTag(R.id.tag_swap_text, null);
        if (error) {
            statusText.setVisibility(View.GONE);
            errorText.setText(Ui.dropTryAgain(text));
            errorBlock.setContentDescription(getString(R.string.app_update_error_block,
                    getString(R.string.app_update_failed_title), errorText.getText()));
            errorBlock.setVisibility(View.VISIBLE);
            Ui.rejectHaptic(errorBlock);
            return;
        }
        errorBlock.setVisibility(View.GONE);
        statusText.setText(text);
        statusText.setVisibility(View.VISIBLE);
    }

    private void hideStatus() {
        statusText.animate().cancel();
        statusText.setAlpha(1f);
        statusText.setVisibility(View.INVISIBLE);
        errorBlock.setVisibility(View.GONE);
    }

    // --------------------------------------------------------------- QA only

    /** Debug sample: a fake download that ends in a clear failure (nothing is installed). */
    private void simulateDownload(int run, long done) {
        final long total = 24L * 1024L * 1024L;
        if (!Ui.alive(this) || state != State.DOWNLOADING || run != downloadRun) return;
        if (done >= total) {
            onProgress(run, total, total);
            main.postDelayed(() -> {
                if (Ui.alive(this) && run == downloadRun && state == State.DOWNLOADING) {
                    fail(getString(R.string.app_update_invalid));
                }
            }, 400L);
            return;
        }
        onProgress(run, done, total);
        main.postDelayed(() -> simulateDownload(run, done + total / 24), 300L);
    }

    private void applyQaState() {
        String qa = getIntent().getStringExtra(EXTRA_QA_STATE);
        if (qa == null) return;
        switch (qa.toLowerCase(Locale.US)) {
            case "downloading":
                render(State.DOWNLOADING, null);
                lastProgressAt = 0L;
                onProgress(downloadRun, 11L * 1024L * 1024L, 24L * 1024L * 1024L);
                break;
            case "installing":
                render(State.INSTALLING, getString(R.string.app_update_confirm));
                break;
            case "failed":
                // 2.3.2: preview the readable download error with its HTTP code.
                fail(getString(R.string.app_update_download_http, 504));
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
