package com.xlox;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.xlox.ui.Ui;
import com.xlox.ui.UpdateGate;
import com.xlox.utils.AppManager;
import com.xlox.utils.LibManager;

import java.io.File;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import net_62v.external.MetaActivationManager;
import net_62v.external.MetaActivityManager;

/**
 * Home screen after a successful sign in. JNI: exdate / modStatus / credit
 * keep their native names and signatures.
 */
public class MAct extends AppCompatActivity {

    private static native String exdate();

    /** Status / safe text configured in the admin panel (/server). */
    private static native String modStatus();

    private static native String credit();

    static {
        try {
            System.loadLibrary("XloX");
        } catch (UnsatisfiedLinkError e) {
            Log.e("MAct", "libXloX.so failed to load", e);
        }
    }

    private static final String TAG = "MAct";
    private static final String PKG_BGMI = "com.pubg.imobile";
    /** Debug-only: lets CI screenshot this screen without a live session. */
    public static final String EXTRA_PREVIEW = "qa_preview";
    private static final long RECHECK_INTERVAL_MS = 60_000L;
    private static final long LAUNCH_DELAY_MS = 1200L;

    private final Handler main = new Handler(Looper.getMainLooper());

    private TextView panelNote;
    private TextView statusValue;
    private TextView expiresValue;
    private TextView timeLeftValue;
    private TextView gameStateValue;
    private TextView libraryValue;
    private TextView serverValue;
    private TextView statusMessage;
    private ProgressBar taskProgress;
    private Button primaryAction;
    private ProgressBar actionSpinner;

    private long expiryMillis = -1L;
    private boolean expired;
    private boolean gameInstalled;
    private boolean gameRunning;
    private boolean busy;
    private UpdateGate gate;
    private long lastRecheck;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            renderCountdown();
            if (!expired && expiryMillis > 0) {
                long now = System.currentTimeMillis();
                main.postDelayed(this, 1000L - (now % 1000L));
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String expiry = readNative(NativeField.EXPIRY);
        boolean preview = BuildConfig.DEBUG && getIntent() != null
                && getIntent().getBooleanExtra(EXTRA_PREVIEW, false);
        if (!preview && (expiry == null || expiry.isEmpty() || "NULL".equalsIgnoreCase(expiry))) {
            // Process was restarted and the native session is gone: sign in again.
            Log.i(TAG, "No active session, returning to sign in");
            openLogin();
            return;
        }

        setContentView(R.layout.activity_main);
        panelNote = findViewById(R.id.panelNote);
        statusValue = findViewById(R.id.statusValue);
        expiresValue = findViewById(R.id.expiresValue);
        timeLeftValue = findViewById(R.id.timeLeftValue);
        gameStateValue = findViewById(R.id.gameStateValue);
        libraryValue = findViewById(R.id.libraryValue);
        serverValue = findViewById(R.id.serverValue);
        statusMessage = findViewById(R.id.statusMessage);
        taskProgress = findViewById(R.id.taskProgress);
        primaryAction = findViewById(R.id.primaryAction);
        actionSpinner = findViewById(R.id.actionSpinner);

        ((TextView) findViewById(R.id.gameValue)).setText(R.string.game_name);
        ((TextView) findViewById(R.id.modelValue)).setText(deviceName());
        ((TextView) findViewById(R.id.androidValue)).setText(getString(R.string.android_version,
                Build.VERSION.RELEASE, Build.VERSION.SDK_INT));

        findViewById(R.id.signOut).setOnClickListener(v -> {
            Ui.tap(v);
            openLogin();
        });
        findViewById(R.id.help).setOnClickListener(v -> {
            Ui.tap(v);
            Ui.openUrl(this, getString(R.string.telegram_url));
        });
        primaryAction.setOnClickListener(v -> onPrimaryAction());

        expiryMillis = parseExpiryMillis(expiry);
        if (expiryMillis > 0) {
            expiresValue.setText(new SimpleDateFormat("d MMM yyyy, h:mm a", Locale.getDefault())
                    .format(new Date(expiryMillis)));
        } else if (expiry != null && !expiry.isEmpty() && !"NULL".equalsIgnoreCase(expiry)) {
            expiresValue.setText(expiry);
        }
        applyPanelStatus();
        refreshLibrary();
        refreshGameState();
        lastRecheck = SystemClock.elapsedRealtime(); // LogAct just ran the gate.
        Ui.reveal(findViewById(R.id.mainContent), 0);
        Ui.reveal(findViewById(R.id.actionBar), 80);
    }

    @Override
    protected void onStart() {
        super.onStart();
        main.removeCallbacks(ticker);
        if (primaryAction != null) main.post(ticker);
    }

    @Override
    protected void onStop() {
        main.removeCallbacks(ticker);
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (primaryAction == null) return;
        refreshGameState();
        refreshLibrary();
        reportLoaderError();
        maybeRecheckLibrary();
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if (gate != null) gate.dispose();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- native

    private enum NativeField { EXPIRY, STATUS, CREDIT }

    private static String readNative(NativeField field) {
        try {
            String value;
            switch (field) {
                case EXPIRY:
                    value = exdate();
                    break;
                case STATUS:
                    value = modStatus();
                    break;
                default:
                    value = credit();
                    break;
            }
            return value == null ? null : value.trim();
        } catch (Throwable t) {
            Log.w(TAG, "Native " + field + " unavailable", t);
            return null;
        }
    }

    private void applyPanelStatus() {
        String status = readNative(NativeField.STATUS);
        if (status == null || status.isEmpty()) {
            serverValue.setText(R.string.dash);
            serverValue.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        } else {
            serverValue.setText(status);
            boolean safe = status.toLowerCase(Locale.US).contains("safe");
            serverValue.setTextColor(ContextCompat.getColor(this, safe ? R.color.green : R.color.orange));
        }
        String note = readNative(NativeField.CREDIT);
        if (note == null || note.isEmpty()) {
            panelNote.setVisibility(View.GONE);
        } else {
            panelNote.setText(note);
            panelNote.setVisibility(View.VISIBLE);
        }
    }

    // ------------------------------------------------------------- countdown

    private void renderCountdown() {
        if (timeLeftValue == null) return;
        if (expiryMillis <= 0) {
            timeLeftValue.setText(R.string.dash);
            statusValue.setText(R.string.dash);
            return;
        }
        long remaining = expiryMillis - System.currentTimeMillis();
        if (remaining <= 0) {
            if (!expired) {
                expired = true;
                showMessage(getString(R.string.licence_expired_message), true);
                refreshPrimary();
            }
            statusValue.setText(R.string.expired);
            statusValue.setTextColor(ContextCompat.getColor(this, R.color.red));
            timeLeftValue.setText(R.string.dash);
            return;
        }
        statusValue.setText(R.string.active);
        statusValue.setTextColor(ContextCompat.getColor(this, R.color.green));
        long total = remaining / 1000L;
        long days = total / 86400L;
        long hours = (total % 86400L) / 3600L;
        long minutes = (total % 3600L) / 60L;
        long seconds = total % 60L;
        timeLeftValue.setText(days > 0
                ? getString(R.string.time_left_value, days, hours, minutes, seconds)
                : getString(R.string.time_left_value_short, hours, minutes, seconds));
    }

    /** The panel sends ISO-8601 UTC ("2026-11-14T08:35:00.000Z"). */
    static long parseExpiryMillis(String value) {
        if (value == null) return -1L;
        String v = value.trim();
        if (v.isEmpty() || "NULL".equalsIgnoreCase(v)) return -1L;
        try {
            long numeric = Long.parseLong(v);
            return numeric < 100_000_000_000L ? numeric * 1000L : numeric;
        } catch (NumberFormatException ignored) {
            // Not epoch seconds; try date formats below.
        }
        String[][] formats = {
                {"yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "UTC"},
                {"yyyy-MM-dd'T'HH:mm:ss'Z'", "UTC"},
                {"yyyy-MM-dd'T'HH:mm:ss.SSSXXX", null},
                {"yyyy-MM-dd'T'HH:mm:ssXXX", null},
                {"yyyy-MM-dd HH:mm:ss", "UTC"},
                {"yyyy-MM-dd", "UTC"},
        };
        for (String[] format : formats) {
            try {
                SimpleDateFormat parser = new SimpleDateFormat(format[0], Locale.US);
                parser.setLenient(false);
                if (format[1] != null) parser.setTimeZone(TimeZone.getTimeZone(format[1]));
                Date parsed = parser.parse(v);
                if (parsed != null) return parsed.getTime();
            } catch (ParseException | IllegalArgumentException ignored) {
                // Try the next format.
            }
        }
        return -1L;
    }

    // ----------------------------------------------------------- game state

    private void refreshGameState() {
        gameInstalled = isInstalled(PKG_BGMI);
        if (!gameInstalled) gameRunning = false;
        gameStateValue.setText(!gameInstalled ? R.string.state_not_installed
                : gameRunning ? R.string.state_running : R.string.state_ready);
        gameStateValue.setTextColor(ContextCompat.getColor(this,
                !gameInstalled ? R.color.orange : gameRunning ? R.color.green : R.color.text_secondary));
        refreshPrimary();
    }

    private void refreshLibrary() {
        String version = LibManager.installedVersion(this);
        libraryValue.setText(version != null ? version : getString(R.string.state_not_installed));
        libraryValue.setTextColor(ContextCompat.getColor(this,
                version != null ? R.color.text_secondary : R.color.orange));
    }

    private boolean isInstalled(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        } catch (RuntimeException e) {
            Log.w(TAG, "Package lookup failed", e);
            return false;
        }
    }

    private void refreshPrimary() {
        if (busy) return;
        int label;
        if (expired) label = R.string.sign_in_again;
        else if (!gameInstalled) label = R.string.install_game;
        else if (gameRunning) label = R.string.stop;
        else label = R.string.launch;
        Ui.setBusy(primaryAction, actionSpinner, false, getString(label));
    }

    private void setBusy(boolean value, CharSequence label) {
        busy = value;
        if (value) {
            Ui.setBusy(primaryAction, actionSpinner, true, label);
        } else {
            refreshPrimary();
        }
    }

    // --------------------------------------------------------------- actions

    private void onPrimaryAction() {
        if (busy) return;
        Ui.tap(primaryAction);
        if (expired) {
            openLogin();
        } else if (!gameInstalled) {
            openStore();
        } else if (gameRunning) {
            stopGame();
        } else {
            startLaunch();
        }
    }

    private void startLaunch() {
        hideMessage();
        try {
            if (!MetaActivationManager.getActivationStatus()) {
                showMessage(getString(R.string.error_activation), true);
                return;
            }
        } catch (Throwable t) {
            Log.e(TAG, "Activation status unavailable", t);
            showMessage(getString(R.string.error_activation), true);
            return;
        }
        if (!LibManager.isUsableLib(this)) {
            // Mandatory: no launch without a current library.
            openGate(false);
            return;
        }
        if (!LogAct.hasStorageAccess(this)) {
            showMessage(getString(R.string.storage_permission_needed), true);
            return;
        }
        setBusy(true, getString(R.string.preparing));
        showProgress(0, true);
        showMessage(getString(R.string.preparing), false);
        new AppManager(this).copyObbAsync(PKG_BGMI, new AppManager.CopyCallback() {
            @Override
            public void onProgressUpdate(int percent) {
                if (!Ui.alive(MAct.this)) return;
                showProgress(percent, false);
                statusMessage.setText(getString(R.string.preparing_progress, percent));
            }

            @Override
            public void onCopyCompleted(boolean success, String error) {
                if (!Ui.alive(MAct.this)) return;
                hideProgress();
                if (!success) {
                    setBusy(false, null);
                    String reason = error == null || error.isEmpty() ? getString(R.string.net_generic) : error;
                    showMessage(getString(R.string.main_obb_not_found).equals(reason)
                            || getString(R.string.obb_folder_not_found).equals(reason)
                            ? getString(R.string.error_obb_missing)
                            : getString(R.string.error_obb_copy, reason), true);
                    return;
                }
                showMessage(getString(R.string.launching), false);
                main.postDelayed(MAct.this::launchGame, LAUNCH_DELAY_MS);
            }
        });
    }

    private void launchGame() {
        if (!Ui.alive(this)) return;
        try {
            Intent intent = MetaActivityManager.obtainSplashLaunchIntent(PKG_BGMI, this);
            if (intent == null) throw new IllegalStateException("No launch intent");
            startActivity(intent);
            gameRunning = true;
            hideMessage();
        } catch (Throwable t) {
            Log.e(TAG, "Launch failed", t);
            showMessage(getString(R.string.error_launch), true);
        }
        setBusy(false, null);
        refreshGameState();
    }

    private void stopGame() {
        try {
            MetaActivityManager.killAppByPkg(PKG_BGMI);
            gameRunning = false;
            showMessage(getString(R.string.game_stopped), false);
        } catch (Throwable t) {
            Log.e(TAG, "Stop failed", t);
            showMessage(getString(R.string.error_stop), true);
        }
        refreshGameState();
    }

    private void openStore() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + PKG_BGMI)));
        } catch (ActivityNotFoundException e) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=" + PKG_BGMI)));
            } catch (ActivityNotFoundException nested) {
                showMessage(getString(R.string.error_store), true);
            }
        }
    }

    private void openLogin() {
        Intent intent = new Intent(this, LogAct.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
        finish();
    }

    // ----------------------------------------------------- library updates

    private void maybeRecheckLibrary() {
        if (busy || gameRunning || (gate != null && gate.isShowing())) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastRecheck < RECHECK_INTERVAL_MS) return;
        lastRecheck = now;
        openGate(true);
    }

    private void openGate(boolean silent) {
        if (gate != null) gate.dispose();
        UpdateGate.Listener onReady = () -> {
            refreshLibrary();
            hideMessage();
        };
        gate = silent ? UpdateGate.recheck(this, onReady) : UpdateGate.start(this, onReady);
    }

    private void reportLoaderError() {
        String error = LibManager.lastLoadError(this);
        if (error != null && !error.isEmpty()) {
            showMessage(getString(R.string.error_library, error), true);
            LibManager.clearLoadError(this);
            return;
        }
        File lib = LibManager.libFile(this);
        if (lib.isFile()) {
            String problem = LibManager.alignmentProblem(lib);
            if (problem != null) showMessage(getString(R.string.error_library, problem), true);
        }
    }

    // ---------------------------------------------------------------- status

    private void showMessage(String text, boolean error) {
        statusMessage.setText(text);
        statusMessage.setTextColor(ContextCompat.getColor(this, error ? R.color.red : R.color.text_secondary));
        statusMessage.setVisibility(View.VISIBLE);
    }

    private void hideMessage() {
        statusMessage.setVisibility(View.GONE);
    }

    private void showProgress(int percent, boolean indeterminate) {
        taskProgress.setVisibility(View.VISIBLE);
        taskProgress.setIndeterminate(indeterminate);
        if (!indeterminate) taskProgress.setProgress(percent);
    }

    private void hideProgress() {
        taskProgress.setVisibility(View.GONE);
    }

    private String deviceName() {
        String brand = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.trim();
        String model = Build.MODEL == null ? "" : Build.MODEL.trim();
        if (model.isEmpty()) return getString(R.string.dash);
        if (!brand.isEmpty() && !model.toLowerCase(Locale.US).startsWith(brand.toLowerCase(Locale.US))) {
            return Character.toUpperCase(brand.charAt(0)) + brand.substring(1) + " " + model;
        }
        return model;
    }
}
