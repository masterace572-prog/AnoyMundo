package com.xlox;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.xlox.ui.ThemeMode;
import com.xlox.ui.designsystem.DesignSystem;
import com.xlox.ui.designsystem.DsMotion;
import com.xlox.ui.designsystem.Haptics;
import com.xlox.ui.designsystem.PrimaryButton;
import com.xlox.ui.designsystem.StateView;
import com.xlox.ui.designsystem.ThemeToggleView;
import com.xlox.ui.designsystem.Tokens;
import com.xlox.utils.AppManager;
import com.xlox.utils.Downtwo;
import com.xlox.utils.LibManager;

import java.io.File;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import net_62v.external.MetaActivationManager;
import net_62v.external.MetaActivityManager;
import net_62v.external.MetaStorageManager;

public class MAct extends AppCompatActivity {

    private static native String exdate();

    /** Real status / safe text as configured in the admin panel (/Server). */
    private static native String modStatus();
    private static native String credit();

    static {
        try {
            System.loadLibrary("XloX");
        } catch (UnsatisfiedLinkError ignored) {
        }
    }

    private static final String PKG_BGMI = "com.pubg.imobile";
    private static final String PREF_NAME = "com.xlox.download";
    private static final String PREF_VERSION_KEY = "version";

    private StateView downloadBox;
    private StateView statusToggle;
    private View restartButton;
    private TextView appVersionText;
    private TextView creditText;
    private View safetyBanner;
    private ThemeToggleView themeToggle;
    private TextView systemReady;
    private View subscriptionRule;
    private TextView subscriptionStatus;

    private TextView startText;
    private View startBgmi;
    private PrimaryButton launchButton;
    private View startFB;
    private View fbCard;

    private TextView gameName;
    private TextView packageName;
    private TextView gameStatus;
    private TextView expiryValue;
    private TextView timeRemaining;
    private TextView deviceValue;
    private TextView deviceStatus;

    private TextView daysValue;
    private TextView hoursValue;
    private TextView minutesValue;
    private TextView secondsValue;

    private View obbButton;
    private boolean isObbCopied = false;
    private boolean isGameRunning = false;
    private boolean isGameInstalled = false;
    private boolean isUpdateAvailable = false;
    private boolean isDownloading = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeMode.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        ThemeMode.applyWindow(this);
        DesignSystem.applySafeDrawing(findViewById(R.id.mainRoot), true, true, false);

        checkPermissions();

        downloadBox = findViewById(R.id.downloadBox);
        statusToggle = findViewById(R.id.statusToggle);
        restartButton = findViewById(R.id.restartButton);
        appVersionText = findViewById(R.id.appVersionText);
        creditText = findViewById(R.id.creditText);
        safetyBanner = findViewById(R.id.safetyBanner);
        systemReady = findViewById(R.id.systemReady);
        themeToggle = findViewById(R.id.themeToggle);
        subscriptionRule = findViewById(R.id.subscriptionRule);
        subscriptionStatus = findViewById(R.id.subscriptionStatus);

        startText = findViewById(R.id.startText);
        startBgmi = findViewById(R.id.startBgmi);
        if (startBgmi instanceof PrimaryButton) launchButton = (PrimaryButton) startBgmi;
        startFB = findViewById(R.id.startFB);
        fbCard = findViewById(R.id.fbCard);

        gameName = findViewById(R.id.gameName);
        packageName = findViewById(R.id.packageName);
        gameStatus = findViewById(R.id.gameStatus);
        expiryValue = findViewById(R.id.expiryValue);
        timeRemaining = findViewById(R.id.timeRemaining);
        deviceValue = findViewById(R.id.deviceValue);
        deviceStatus = findViewById(R.id.deviceStatus);
        if (deviceValue != null) {
            deviceValue.setText(Build.MODEL == null
                    ? getString(R.string.android_device) : Build.MODEL);
        }

        obbButton = findViewById(R.id.obbButton);

        daysValue = findViewById(R.id.daysBox).findViewById(R.id.timeValue);
        hoursValue = findViewById(R.id.hoursBox).findViewById(R.id.timeValue);
        minutesValue = findViewById(R.id.minutesBox).findViewById(R.id.timeValue);
        secondsValue = findViewById(R.id.secondsBox).findViewById(R.id.timeValue);
        ((TextView) findViewById(R.id.daysBox).findViewById(R.id.timeLabel)).setText(R.string.days);
        ((TextView) findViewById(R.id.hoursBox).findViewById(R.id.timeLabel)).setText(R.string.hours);
        ((TextView) findViewById(R.id.minutesBox).findViewById(R.id.timeLabel)).setText(R.string.minutes);
        ((TextView) findViewById(R.id.secondsBox).findViewById(R.id.timeLabel)).setText(R.string.seconds);
        setKeyTimeUnavailable();
        startKeyCountdown();

        ThemeMode.decorateToggle(this, themeToggle);
        DsMotion.stagger(findViewById(R.id.mainTopBar),
                findViewById(R.id.mainCard),
                findViewById(R.id.bentoRow),
                safetyBanner,
                findViewById(R.id.statusArea),
                startBgmi,
                systemReady);

        if (themeToggle != null) {
            themeToggle.setOnClickListener(v -> ThemeMode.toggle(this));
        }
        if (systemReady != null) {
            systemReady.setOnClickListener(v -> openExternalUrl(getString(R.string.telegram_url)));
        }

        checkObbApplied();
        updateGameStatus();
        updateVersionDisplay();
        applyPanelStatus();

        if (obbButton != null) {
            obbButton.setOnClickListener(v -> openExternalUrl(getString(R.string.telegram_url)));
        }
        if (restartButton != null) {
            restartButton.setOnClickListener(v -> restartApp());
        }
        if (startBgmi != null) {
            startBgmi.setOnClickListener(v -> {
                if (launchButton != null && launchButton.isBusy()) return;
                Haptics.tick(v);
                if (isUpdateAvailable) {
                    startDownload();
                } else if (!isGameInstalled) {
                    installGame();
                } else if (isGameRunning) {
                    stopGame();
                } else {
                    handleLaunchFlow();
                }
            });
        }
        if (startFB != null) {
            startFB.setOnClickListener(v -> openExternalUrl(getString(R.string.telegram_url)));
        }

        // Start Workflow: Update Check -> Download Files (if needed)
        startWorkflow();
    }

    @Override
    protected void onResume() {
        super.onResume();
        showLoaderDiagnostics();
        applyPanelStatus();
    }

    /** Display the real "Safe" status and safe text set in the admin panel. */
    private void applyPanelStatus() {
        String status = getString(R.string.not_available);
        String text = "";
        try {
            String s = modStatus();
            if (s != null && !s.trim().isEmpty()) status = s.trim();
            String c = credit();
            if (c != null) text = c.trim();
        } catch (Throwable t) {
            Log.d("MAct", "Panel status unavailable", t);
        }

        final String panelStatus = status;
        final String panelCredit = text;
        runOnUiThread(() -> {
            if (deviceStatus != null) {
                deviceStatus.setText(panelStatus);
                boolean safe = panelStatus.equalsIgnoreCase("Safe")
                        || panelStatus.toLowerCase(Locale.US).contains("safe");
                deviceStatus.setTextColor(safe ? Tokens.successInk(this) : Tokens.warningInk(this));
                if (subscriptionRule != null) {
                    subscriptionRule.setBackgroundColor(
                            safe ? Tokens.success(this) : Tokens.warning(this));
                }
            }
            // The safety banner is bound to the live panel "safe text" — it is
            // hidden when the panel has nothing to say, never filled with mock copy.
            if (creditText != null) {
                creditText.setText(panelCredit);
                if (safetyBanner != null) {
                    safetyBanner.setVisibility(panelCredit.isEmpty() ? View.GONE : View.VISIBLE);
                }
            }
        });
    }

    private void showLoaderDiagnostics() {
        Log.i("MAct", "Loader diagnostics: " + LibManager.diagnostics(this));
        String error = LibManager.lastLoadError(this);
        if (error != null && !error.isEmpty()) {
            showStatusToggle(getString(R.string.library_missing), true);
            DesignSystem.snackbarError(this, getString(R.string.diagnostic_detail, error));
            LibManager.clearLoadError(this);
            return;
        }
        File lib = LibManager.libFile(this);
        if (lib.isFile()) {
            String problem = LibManager.alignmentProblem(lib);
            if (problem != null) {
                showStatusToggle(getString(R.string.library_incompatible), true);
                DesignSystem.snackbarError(this, getString(R.string.diagnostic_detail, problem));
            }
        }
    }

    private void setKeyTimeUnavailable() {
        if (daysValue != null) daysValue.setText(R.string.not_available);
        if (hoursValue != null) hoursValue.setText(R.string.not_available);
        if (minutesValue != null) minutesValue.setText(R.string.not_available);
        if (secondsValue != null) secondsValue.setText(R.string.not_available);
    }

    private void startKeyCountdown() {
        final String expiry;
        try {
            expiry = exdate();
        } catch (Throwable t) {
            Log.e("MAct", "Native expiry unavailable", t);
            setKeyTimeUnavailable();
            return;
        }
        if (expiry == null || expiry.trim().isEmpty() || "NULL".equalsIgnoreCase(expiry.trim())) {
            if (expiryValue != null) expiryValue.setText(R.string.not_available);
            if (timeRemaining != null) timeRemaining.setText(R.string.not_available);
            return;
        }

        final long expiryMillis = parseExpiryMillis(expiry.trim());
        if (expiryValue != null) {
            if (expiryMillis > 0L) {
                expiryValue.setText(new SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
                        .format(new Date(expiryMillis)));
            } else {
                expiryValue.setText(expiry.trim());
            }
        }
        if (expiryMillis <= 0L) {
            if (timeRemaining != null) timeRemaining.setText(R.string.not_available);
            return;
        }

        final Handler timerHandler = new Handler(Looper.getMainLooper());
        final Runnable timer = new Runnable() {
            @Override public void run() {
                long remaining = expiryMillis - System.currentTimeMillis();
                if (remaining <= 0L) {
                    setTimeValues(0, 0, 0, 0);
                    if (subscriptionStatus != null) {
                        subscriptionStatus.setText(R.string.expired);
                        subscriptionStatus.setTextColor(Tokens.errorInk(MAct.this));
                    }
                    if (subscriptionRule != null) {
                        subscriptionRule.setBackgroundColor(Tokens.error(MAct.this));
                    }
                    if (timeRemaining != null) timeRemaining.setText(R.string.expired);
                    return;
                }
                if (subscriptionStatus != null) {
                    subscriptionStatus.setText(R.string.active);
                    subscriptionStatus.setTextColor(Tokens.successInk(MAct.this));
                }
                if (subscriptionRule != null) {
                    subscriptionRule.setBackgroundColor(Tokens.success(MAct.this));
                }
                long totalSeconds = TimeUnit.MILLISECONDS.toSeconds(remaining);
                long days = totalSeconds / 86400L;
                long hours = (totalSeconds % 86400L) / 3600L;
                long minutes = (totalSeconds % 3600L) / 60L;
                long seconds = totalSeconds % 60L;
                setTimeValues(days, hours, minutes, seconds);
                timerHandler.postDelayed(this, 1000L);
            }
        };
        timer.run();
    }

    private long parseExpiryMillis(String value) {
        try {
            long numeric = Long.parseLong(value);
            return numeric < 100000000000L ? numeric * 1000L : numeric;
        } catch (NumberFormatException ignored) {
        }
        String[] formats = {"yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd HH:mm:ss",
                "yyyy-MM-dd HH:mm", "yyyy-MM-dd", "dd-MM-yyyy HH:mm:ss",
                "dd/MM/yyyy HH:mm:ss", "MMM dd, yyyy HH:mm:ss"};
        for (String format : formats) {
            try {
                SimpleDateFormat parser = new SimpleDateFormat(format, Locale.US);
                parser.setLenient(false);
                Date parsed = parser.parse(value);
                if (parsed != null) return parsed.getTime();
            } catch (ParseException ignored) {
            }
        }
        return -1L;
    }

    private void setTimeValues(long days, long hours, long minutes, long seconds) {
        if (daysValue != null) daysValue.setText(String.format(Locale.US, "%02d", days));
        if (hoursValue != null) hoursValue.setText(String.format(Locale.US, "%02d", hours));
        if (minutesValue != null) minutesValue.setText(String.format(Locale.US, "%02d", minutes));
        if (secondsValue != null) secondsValue.setText(String.format(Locale.US, "%02d", seconds));
        if (timeRemaining != null) {
            timeRemaining.setText(getString(R.string.time_remaining, days, hours, minutes));
        }
    }

    private void updateVersionDisplay() {
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String localVersion = prefs.getString(PREF_VERSION_KEY, "1.0");
        if (appVersionText != null) {
            appVersionText.setText(getString(R.string.version_x, localVersion));
        }
    }

    /** Real startup workflow: check the panel version before the user taps Launch. */
    private void startWorkflow() {
        runOnUiThread(() -> {
            if (downloadBox != null) downloadBox.setVisibility(View.GONE);
            if (restartButton != null) restartButton.setVisibility(View.VISIBLE);
        });
        isUpdateAvailable = false;

        if (!LibManager.isUsableLib(this)) {
            Log.i("MAct", "No usable loader library on disk - fetching the panel release");
            startDownload();
            return;
        }

        final Downtwo[] holder = new Downtwo[1];
        holder[0] = new Downtwo(this, (success, result) -> {
            if (!success) {
                Log.w("MAct", "Version check failed: "
                        + (holder[0] != null ? holder[0].getFailureMessage() : ""));
                return;
            }
            if (Downtwo.RESULT_UPDATE.equals(result) || Downtwo.RESULT_NOTIFIED.equals(result)) {
                isUpdateAvailable = true;
                Log.i("MAct", "Panel has a newer loader release: " + result);
            }
            runOnUiThread(() -> updateButtonState(isGameRunning));
        });
        holder[0].execute(Downtwo.downloadUrl(), "check_only");
    }

    private void buttonProgress(int percent, String label) {
        if (launchButton != null) launchButton.setBusy(true);
        if (downloadBox != null) {
            if (downloadBox.getVisibility() != View.VISIBLE) {
                downloadBox.showLoading(label, percent);
            } else {
                downloadBox.updateProgress(label, percent);
            }
        }
    }

    private void buttonDone() {
        if (launchButton != null) launchButton.reset();
        if (downloadBox != null) downloadBox.showLoading(getString(R.string.ready), 100);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (downloadBox != null) downloadBox.setVisibility(View.GONE);
        }, DsMotion.SHEET);
    }

    private void startDownload() {
        if (isDownloading) {
            Log.d("MAct", "A loader download is already running");
            return;
        }
        isDownloading = true;
        runOnUiThread(() -> {
            buttonProgress(0, getString(R.string.updating));
            if (restartButton != null) restartButton.setVisibility(View.GONE);
        });

        final Downtwo[] holder = new Downtwo[1];
        holder[0] = new Downtwo(this, (success, result) -> {
            isDownloading = false;
            if (success) {
                runOnUiThread(() -> {
                    isUpdateAvailable = false;
                    hideStatusToggle();
                    updateVersionDisplay();
                    buttonDone();
                    if (restartButton != null) restartButton.setVisibility(View.VISIBLE);
                    new Handler(Looper.getMainLooper()).postDelayed(() ->
                            setLaunchButtonText(isGameInstalled
                                    ? getString(R.string.launch) : getString(R.string.install)),
                            DsMotion.SHEET);
                });
            } else {
                String reason = holder[0] != null ? holder[0].getFailureMessage() : "";
                if (reason == null || reason.isEmpty()) {
                    reason = getString(R.string.loader_download_unavailable);
                }
                Log.e("MAct", "Loader download failed: " + reason);
                final String message = reason;
                runOnUiThread(() -> {
                    isUpdateAvailable = false;
                    if (downloadBox != null) downloadBox.setVisibility(View.GONE);
                    if (launchButton != null) launchButton.reset();
                    setLaunchButtonText(isGameInstalled
                            ? getString(R.string.launch) : getString(R.string.install));
                    showStatusToggle(message, true);
                });
            }
        });
        holder[0].setProgressListener(progress -> runOnUiThread(() ->
                buttonProgress(progress, getString(R.string.updating))));
        holder[0].execute(Downtwo.downloadUrl());
    }

    private void restartApp() {
        Intent intent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            Runtime.getRuntime().exit(0);
        }
    }

    private void checkObbApplied() {
        new Thread(() -> {
            try {
                PackageInfo info = getPackageManager().getPackageInfo(PKG_BGMI, 0);
                String gameObb = "main." + info.versionCode + "." + PKG_BGMI + ".obb";
                String destPath = MetaStorageManager.obtainAppExternalStorageDir(PKG_BGMI) + "/Android/obb/" + PKG_BGMI;
                File destFile = new File(destPath, gameObb);
                if (destFile.exists()) {
                    isObbCopied = true;
                    runOnUiThread(() -> { if (obbButton != null) obbButton.setVisibility(View.GONE); });
                }
            } catch (Exception ignored) {
            }
        }).start();
    }

    private void updateGameStatus() {
        isGameInstalled = false;
        try {
            PackageInfo packageInfo = getPackageManager().getPackageInfo(PKG_BGMI, 0);
            if (packageInfo != null) {
                isGameInstalled = true;
                if (gameName != null) gameName.setText(R.string.game_name);
                if (packageName != null) packageName.setText(PKG_BGMI);
                if (gameStatus != null) gameStatus.setText(R.string.ready);
                updateButtonState(false);
            }
        } catch (Exception ignored) {
            isGameInstalled = false;
            if (gameStatus != null) gameStatus.setText(R.string.not_installed);
            setLaunchButtonText(getString(R.string.install));
            if (startText != null) startText.setText(R.string.install);
        }
    }

    private void setLaunchButtonText(String label) {
        if (startText != null) startText.setText(label);
        if (launchButton != null) launchButton.setLabel(label);
        else if (startBgmi instanceof Button) ((Button) startBgmi).setText(label);
    }

    private void updateButtonState(boolean running) {
        isGameRunning = running;
        runOnUiThread(() -> {
            if (running) {
                setLaunchButtonText(getString(R.string.stop));
                if (startText != null) startText.setText(R.string.stop);
                if (downloadBox != null) {
                    downloadBox.showStatus(getString(R.string.running));
                    if (restartButton != null) restartButton.setVisibility(View.GONE);
                }
            } else {
                setLaunchButtonText(isUpdateAvailable
                        ? getString(R.string.update)
                        : (isGameInstalled ? getString(R.string.launch) : getString(R.string.install)));
                if (startText != null) {
                    startText.setText(isUpdateAvailable
                            ? getString(R.string.update) : getString(R.string.launch));
                }
                if (downloadBox != null) {
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        if (!isGameRunning && !isUpdateAvailable) {
                            downloadBox.setVisibility(View.GONE);
                        }
                    }, 1000L);
                }
            }
        });
    }

    private void handleLaunchFlow() {
        try {
            if (!MetaActivationManager.getActivationStatus()) {
                showStatusToggle(getString(R.string.launch_failed), true);
                return;
            }
        } catch (Exception ignored) {
            showStatusToggle(getString(R.string.launch_failed), true);
            return;
        }

        if (!LibManager.isUsableLib(this)) {
            Log.w("MAct", "Loader library missing or corrupt - requesting the panel release");
            showStatusToggle(getString(R.string.downloading), false);
            startDownload();
            return;
        }

        if (launchButton != null) launchButton.setBusy(true);
        if (!isObbCopied) {
            copyObbFiles();
        } else {
            showStatusToggle(getString(R.string.launching), false);
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                hideStatusToggle();
                launchGame();
            }, 2500L);
        }
    }

    private void showStatusToggle(String message, boolean isError) {
        if (statusToggle == null) return;
        runOnUiThread(() -> {
            if (isError) statusToggle.showError(message);
            else statusToggle.showStatus(message);
        });
    }

    private void hideStatusToggle() {
        if (statusToggle == null) return;
        runOnUiThread(() -> statusToggle.hide());
    }

    private void launchGame() {
        try {
            Intent intent = MetaActivityManager.obtainSplashLaunchIntent(PKG_BGMI, this);
            if (intent != null) {
                startActivity(intent);
                if (launchButton != null) launchButton.reset();
                updateButtonState(true);
            } else if (launchButton != null) {
                launchButton.reset();
            }
        } catch (Exception e) {
            Log.e("MAct", "Launch error", e);
            if (launchButton != null) launchButton.reset();
            showStatusToggle(getString(R.string.launch_failed), true);
        }
    }

    private void stopGame() {
        try {
            MetaActivityManager.killAppByPkg(PKG_BGMI);
            updateButtonState(false);
        } catch (Exception e) {
            DesignSystem.snackbarError(this, getString(R.string.could_not_stop));
        }
    }

    private void installGame() {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(Uri.parse("market://details?id=" + PKG_BGMI));
            startActivity(intent);
        } catch (Exception e) {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(Uri.parse("https://play.google.com/store/apps/details?id=" + PKG_BGMI));
            startActivity(intent);
        }
    }

    private void openExternalUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Log.e("MAct", "Unable to open contact link", e);
        }
    }

    /** UI-only wrapper around the unchanged OBB copy business logic. */
    private void copyObbFiles() {
        if (obbButton == null) return;
        Button btn = (Button) obbButton;
        btn.setEnabled(false);
        buttonProgress(0, getString(R.string.copying));

        AppManager appManager = new AppManager(this);
        appManager.copyObbFolderAsync(PKG_BGMI, new AppManager.CopyCallback() {
            @Override
            public void onCopyCompleted(boolean success) {
                runOnUiThread(() -> {
                    if (success) {
                        isObbCopied = true;
                        btn.setVisibility(View.GONE);
                        buttonDone();
                        DesignSystem.snackbar(MAct.this, getString(R.string.ready));
                    } else {
                        btn.setEnabled(true);
                        btn.setText(getString(R.string.apply_obb));
                        if (downloadBox != null) downloadBox.setVisibility(View.GONE);
                        if (launchButton != null) launchButton.reset();
                        DesignSystem.snackbarError(MAct.this, getString(R.string.copy_failed));
                    }
                });
            }

            @Override
            public void onProgressUpdate(int progress) {
                runOnUiThread(() -> buttonProgress(progress, getString(R.string.copying)));
            }
        }, false);
    }

    private void checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 100);
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                startActivity(intent);
            }
        }
    }
}
