package com.xlox;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
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
import android.view.animation.LinearInterpolator;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.xlox.ui.AnoyButton;
import com.xlox.ui.ThemeMode;
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
    private static final String TELEGRAM_URL = "https://t.me/libAkAudioVisiual";

    // UI
    private ProgressBar downloadProgressBar;
    private TextView downloadStatusText;
    private TextView percentText;
    private View downloadBox;
    private View restartButton;
    private ProgressBar loadingCursor;
    private TextView appVersionText;
    private TextView creditText;
    private ImageButton themeToggle;
    private TextView systemReady;

    private TextView startText;
    private ImageView actionIcon;
    private View startBgmi;
    private AnoyButton launchButton;
    private View startFB;
    private View fbCard;
    private View statusToggle;
    private View statusLoader;
    private ImageView statusErrorIcon;
    private TextView statusToggleText;

    private TextView gameName;
    private TextView packageName;
    private TextView gameStatus;
    private TextView subscriptionStatus;
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

    @SuppressLint("SetTextI18n")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeMode.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        ThemeMode.applyWindow(this);

        checkPermissions();

        // Download UI
        downloadBox = findViewById(R.id.downloadBox);
        downloadProgressBar = findViewById(R.id.downloadProgressBar);
        downloadStatusText = findViewById(R.id.downloadStatusText);
        percentText = findViewById(R.id.percentText);
        restartButton = findViewById(R.id.restartButton);
        loadingCursor = findViewById(R.id.loadingCursor);
        appVersionText = findViewById(R.id.appVersionText);
        creditText = findViewById(R.id.creditText);
        systemReady = findViewById(R.id.systemReady);
        themeToggle = findViewById(R.id.themeToggle);

        // Action UI
        startText = findViewById(R.id.startText);
        actionIcon = findViewById(R.id.actionIcon);
        startBgmi = findViewById(R.id.startBgmi);
        if (startBgmi instanceof AnoyButton) launchButton = (AnoyButton) startBgmi;
        startFB = findViewById(R.id.startFB);
        fbCard = findViewById(R.id.fbCard);

        gameName = findViewById(R.id.gameName);
        packageName = findViewById(R.id.packageName);
        gameStatus = findViewById(R.id.gameStatus);
        statusToggle = findViewById(R.id.statusToggle);
        statusLoader = findViewById(R.id.statusLoader);
        statusErrorIcon = findViewById(R.id.statusErrorIcon);
        statusToggleText = findViewById(R.id.statusToggleText);
        subscriptionStatus = findViewById(R.id.subscriptionStatus);
        expiryValue = findViewById(R.id.expiryValue);
        timeRemaining = findViewById(R.id.timeRemaining);
        deviceValue = findViewById(R.id.deviceValue);
        deviceStatus = findViewById(R.id.deviceStatus);
        if (deviceValue != null) {
            deviceValue.setText(Build.MODEL == null ? "Android device" : Build.MODEL);
        }

        obbButton = findViewById(R.id.obbButton);

        daysValue = findViewById(R.id.daysBox).findViewById(R.id.timeValue);
        hoursValue = findViewById(R.id.hoursBox).findViewById(R.id.timeValue);
        minutesValue = findViewById(R.id.minutesBox).findViewById(R.id.timeValue);
        secondsValue = findViewById(R.id.secondsBox).findViewById(R.id.timeValue);
        ((TextView) findViewById(R.id.daysBox).findViewById(R.id.timeLabel)).setText("DAYS");
        ((TextView) findViewById(R.id.hoursBox).findViewById(R.id.timeLabel)).setText("HOURS");
        ((TextView) findViewById(R.id.minutesBox).findViewById(R.id.timeLabel)).setText("MINUTES");
        ((TextView) findViewById(R.id.secondsBox).findViewById(R.id.timeLabel)).setText("SECONDS");
        setKeyTimeUnavailable();
        startKeyCountdown();
        startPageAnimation();

        if (themeToggle != null) {
            themeToggle.setOnClickListener(v -> ThemeMode.toggle(this));
        }
        if (systemReady != null) {
            systemReady.setOnClickListener(v -> openExternalUrl(TELEGRAM_URL));
        }

        checkObbApplied();
        updateGameStatus();
        updateVersionDisplay();
        applyPanelStatus();

        if (obbButton != null) {
            obbButton.setOnClickListener(v -> openExternalUrl(TELEGRAM_URL));
        }

        if (restartButton != null) {
            restartButton.setOnClickListener(v -> restartApp());
        }

        if (startBgmi != null) {
            startBgmi.setOnClickListener(v -> {
                // Three hovering dots on every tap, then whichever flow takes
                // over replaces them with real in-button progress.
                if (launchButton != null) launchButton.pulseDots(700L);
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
            startFB.setOnClickListener(v -> openExternalUrl(TELEGRAM_URL));
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

    /**
     * Display the real "Safe" status and safe text the admin set at /Server,
     * read from the /connect reply by the native layer. No hardcoded "Safe".
     */
    private void applyPanelStatus() {
        String status = "--";
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
                deviceStatus.setTextColor(ContextCompat.getColor(this,
                        safe ? R.color.anoyGreen : R.color.anoyOrange));
            }
            if (creditText != null) {
                creditText.setText(panelCredit);
                creditText.setVisibility(panelCredit.isEmpty() ? View.GONE : View.VISIBLE);
            }
        });
    }

    /**
     * Android 15+ 16 KB-page devices and Android 17's read-only System.load()
     * rule can stop the downloaded library (and therefore the ImGui menu) from
     * loading. Surface the recorded reason in the UI instead of leaving the
     * user with a silently missing menu and no clue in logcat.
     */
    private void showLoaderDiagnostics() {
        Log.i("MAct", "Loader diagnostics: " + LibManager.diagnostics(this));
        String error = LibManager.lastLoadError(this);
        if (error != null && !error.isEmpty()) {
            showStatusToggle("Library missing", true);
            Toast.makeText(this, error, Toast.LENGTH_LONG).show();
            LibManager.clearLoadError(this);
            return;
        }
        File lib = LibManager.libFile(this);
        if (lib.isFile()) {
            String problem = LibManager.alignmentProblem(lib);
            if (problem != null) {
                showStatusToggle("Library incompatible", true);
                Toast.makeText(this, problem, Toast.LENGTH_LONG).show();
            }
        }
    }

    private void startPageAnimation() {
        View logoGlow = findViewById(R.id.logoGlow);
        View mainLogo = findViewById(R.id.mainLogo);
        if (mainLogo != null) {
            ObjectAnimator logoFloat = ObjectAnimator.ofFloat(mainLogo, View.TRANSLATION_Y, 0f, -4f, 0f);
            logoFloat.setDuration(3200);
            logoFloat.setRepeatCount(ObjectAnimator.INFINITE);
            logoFloat.start();
        }
        if (logoGlow != null) {
            ObjectAnimator glowRotation = ObjectAnimator.ofFloat(logoGlow, View.ROTATION, 0f, 360f);
            glowRotation.setDuration(9000);
            glowRotation.setInterpolator(new LinearInterpolator());
            glowRotation.setRepeatCount(ObjectAnimator.INFINITE);
            glowRotation.start();
        }
        View[] views = {findViewById(R.id.brandHeader), findViewById(R.id.mainCard),
                findViewById(R.id.statusToggle), findViewById(R.id.keyTimeRow), findViewById(R.id.fbCard)};
        long delay = 0;
        for (View view : views) {
            if (view != null) {
                view.setAlpha(0f);
                view.setTranslationY(20f);
                view.animate().alpha(1f).translationY(0f).setDuration(480).setStartDelay(delay).start();
                delay += 80;
            }
        }
    }

    private void setKeyTimeUnavailable() {
        if (daysValue != null) daysValue.setText("--");
        if (hoursValue != null) hoursValue.setText("--");
        if (minutesValue != null) minutesValue.setText("--");
        if (secondsValue != null) secondsValue.setText("--");
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
            if (expiryValue != null) expiryValue.setText("--");
            if (timeRemaining != null) timeRemaining.setText("--");
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
            if (timeRemaining != null) timeRemaining.setText("--");
            return;
        }

        final Handler timerHandler = new Handler(Looper.getMainLooper());
        final Runnable timer = new Runnable() {
            @Override public void run() {
                long remaining = expiryMillis - System.currentTimeMillis();
                if (remaining <= 0L) {
                    setTimeValues(0, 0, 0, 0);
                    if (subscriptionStatus != null) {
                        subscriptionStatus.setText("Expired");
                        subscriptionStatus.setTextColor(ContextCompat.getColor(MAct.this, R.color.anoyRed));
                    }
                    if (timeRemaining != null) timeRemaining.setText("Expired");
                    return;
                }
                if (subscriptionStatus != null) {
                    subscriptionStatus.setText("Active");
                    subscriptionStatus.setTextColor(ContextCompat.getColor(MAct.this, R.color.anoyGreen));
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

    @SuppressLint("SetTextI18n")
    private void setTimeValues(long days, long hours, long minutes, long seconds) {
        if (daysValue != null) daysValue.setText(String.format(Locale.US, "%02d", days));
        if (hoursValue != null) hoursValue.setText(String.format(Locale.US, "%02d", hours));
        if (minutesValue != null) minutesValue.setText(String.format(Locale.US, "%02d", minutes));
        if (secondsValue != null) secondsValue.setText(String.format(Locale.US, "%02d", seconds));
        if (timeRemaining != null) {
            timeRemaining.setText(String.format(Locale.US, "%dd %02dh %02dm", days, hours, minutes));
        }
    }

    private void updateVersionDisplay() {
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String localVersion = prefs.getString(PREF_VERSION_KEY, "1.0");
        if (appVersionText != null) {
            appVersionText.setText("Version " + localVersion);
        }
    }

    /**
     * Real startup workflow. The original version only reset UI flags, so the
     * app NEVER checked the panel version and never downloaded a missing
     * library until the user happened to press LAUNCH.
     */
    private void startWorkflow() {
        runOnUiThread(() -> {
            if (downloadBox != null) downloadBox.setVisibility(View.GONE);
            if (restartButton != null) restartButton.setVisibility(View.VISIBLE);
            if (loadingCursor != null) loadingCursor.setVisibility(View.GONE);
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
        if (launchButton != null) launchButton.showProgress(percent, label + " " + percent + "%");
    }

    private void buttonDone() {
        if (launchButton == null) return;
        launchButton.showProgress(100, "Ready");
        new Handler(Looper.getMainLooper()).postDelayed(() -> launchButton.reset(), 650L);
    }

    private void startDownload() {
        if (isDownloading) {
            Log.d("MAct", "A loader download is already running");
            return;
        }
        isDownloading = true;
        runOnUiThread(() -> {
            if (downloadBox != null) downloadBox.setVisibility(View.GONE);
            buttonProgress(0, "Updating");
            if (downloadStatusText != null) downloadStatusText.setText("Updating");
            if (percentText != null) percentText.setText("0%");
            if (downloadProgressBar != null) {
                downloadProgressBar.setIndeterminate(false);
                downloadProgressBar.setMax(100);
                downloadProgressBar.setProgress(0);
            }
            if (restartButton != null) restartButton.setVisibility(View.GONE);
            if (loadingCursor != null) loadingCursor.setVisibility(View.VISIBLE);
        });

        final Downtwo[] holder = new Downtwo[1];
        holder[0] = new Downtwo(this, (success, result) -> {
            isDownloading = false;
            if (success) {
                runOnUiThread(() -> {
                    isUpdateAvailable = false;
                    hideStatusToggle();
                    if (downloadStatusText != null) downloadStatusText.setText("Ready");
                    if (percentText != null) percentText.setText("100%");
                    if (downloadProgressBar != null) downloadProgressBar.setProgress(100);
                    if (loadingCursor != null) loadingCursor.setVisibility(View.GONE);
                    updateVersionDisplay();
                    buttonDone();
                    if (restartButton != null) {
                        restartButton.setVisibility(View.VISIBLE);
                        restartButton.setAlpha(0f);
                        restartButton.animate().alpha(1f).setDuration(400).start();
                    }
                    new Handler(Looper.getMainLooper()).postDelayed(() ->
                            setLaunchButtonText(isGameInstalled ? "Launch" : "Install"), 700L);
                });
            } else {
                String reason = holder[0] != null ? holder[0].getFailureMessage() : "";
                if (reason == null || reason.isEmpty()) reason = "Loader download unavailable";
                Log.e("MAct", "Loader download failed: " + reason);
                final String message = reason;
                runOnUiThread(() -> {
                    isUpdateAvailable = false;
                    if (downloadBox != null) downloadBox.setVisibility(View.GONE);
                    if (loadingCursor != null) loadingCursor.setVisibility(View.GONE);
                    if (launchButton != null) launchButton.reset();
                    setLaunchButtonText(isGameInstalled ? "Launch" : "Install");
                    showStatusToggle(message, true);
                });
            }
        });

        holder[0].setProgressListener(progress -> runOnUiThread(() -> {
            if (downloadProgressBar != null) {
                downloadProgressBar.setIndeterminate(false);
                downloadProgressBar.setMax(100);
                downloadProgressBar.setProgress(progress);
            }
            if (percentText != null) percentText.setText(progress + "%");
            if (downloadStatusText != null) downloadStatusText.setText("Updating");
            buttonProgress(progress, "Updating");
        }));

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
                if (gameName != null) gameName.setText("BGMI");
                if (packageName != null) packageName.setText(PKG_BGMI);
                if (gameStatus != null) gameStatus.setText("Ready");
                updateButtonState(false);
            }
        } catch (Exception ignored) {
            isGameInstalled = false;
            if (gameStatus != null) gameStatus.setText("Not installed");
            if (deviceStatus != null) {
                deviceStatus.setText("Not installed");
                deviceStatus.setTextColor(ContextCompat.getColor(this, R.color.anoyOrange));
            }
            setLaunchButtonText("Install");
            if (startText != null) startText.setText("Install");
            if (actionIcon != null) {
                actionIcon.setImageResource(android.R.drawable.ic_menu_save);
                actionIcon.setColorFilter(ContextCompat.getColor(this, R.color.anoyBlue));
            }
        }
    }

    private void setLaunchButtonText(String label) {
        if (startText != null) startText.setText(label);
        if (launchButton != null) launchButton.setIdleLabel(label);
        else if (startBgmi instanceof Button) ((Button) startBgmi).setText(label);
    }

    private void updateButtonState(boolean running) {
        isGameRunning = running;
        runOnUiThread(() -> {
            if (running) {
                setLaunchButtonText("Stop");
                if (startText != null) {
                    startText.setText("Stop");
                    startText.setTextColor(ContextCompat.getColor(this, R.color.anoyRed));
                }
                if (actionIcon != null) {
                    actionIcon.setImageResource(android.R.drawable.ic_delete);
                    actionIcon.setColorFilter(ContextCompat.getColor(this, R.color.anoyRed));
                }
                if (downloadBox != null) {
                    downloadBox.setVisibility(View.VISIBLE);
                    if (downloadStatusText != null) downloadStatusText.setText("Running");
                    if (percentText != null) percentText.setText("100%");
                    if (downloadProgressBar != null) {
                        downloadProgressBar.setIndeterminate(true);
                        downloadProgressBar.setProgress(100);
                    }
                    if (restartButton != null) restartButton.setVisibility(View.GONE);
                }
            } else {
                setLaunchButtonText(isUpdateAvailable ? "Update" : (isGameInstalled ? "Launch" : "Install"));
                if (startText != null) {
                    startText.setText(isUpdateAvailable ? "Update" : "Launch");
                    startText.setTextColor(ContextCompat.getColor(this, R.color.anoyBlue));
                }
                if (actionIcon != null) {
                    actionIcon.setImageResource(android.R.drawable.ic_media_play);
                    actionIcon.setColorFilter(ContextCompat.getColor(this, R.color.anoyBlue));
                }
                if (downloadBox != null) {
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        if (!isGameRunning && !isUpdateAvailable) downloadBox.setVisibility(View.GONE);
                    }, 1000L);
                }
            }
        });
    }

    private void handleLaunchFlow() {
        try {
            if (!MetaActivationManager.getActivationStatus()) {
                showStatusToggle("Launch failed", true);
                return;
            }
        } catch (Exception ignored) {
            showStatusToggle("Launch failed", true);
            return;
        }

        if (!LibManager.isUsableLib(this)) {
            Log.w("MAct", "Loader library missing or corrupt - requesting the panel release");
            showStatusToggle("Downloading", false);
            startDownload();
            return;
        }

        if (!isObbCopied) {
            copyObbFiles();
        } else {
            showStatusToggle("Launching", false);
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                hideStatusToggle();
                launchGame();
            }, 2500L);
        }
    }

    private void showStatusToggle(String message, boolean isError) {
        if (statusToggle == null) return;

        runOnUiThread(() -> {
            statusToggle.setVisibility(View.VISIBLE);
            statusToggle.setAlpha(0f);
            statusToggle.animate().alpha(1f).setDuration(260).start();

            if (statusToggleText != null) {
                statusToggleText.setText(message);
                statusToggleText.setTextColor(ContextCompat.getColor(this,
                        isError ? R.color.anoyRed : R.color.anoyText));
            }
            if (statusLoader != null) {
                statusLoader.setVisibility(isError ? View.GONE : View.VISIBLE);
            }
            if (statusErrorIcon != null) {
                statusErrorIcon.setVisibility(isError ? View.VISIBLE : View.GONE);
            }
            if (isError) {
                new Handler(Looper.getMainLooper()).postDelayed(this::hideStatusToggle, 3000L);
            }
        });
    }

    private void hideStatusToggle() {
        if (statusToggle == null) return;

        runOnUiThread(() -> {
            statusToggle.animate().alpha(0f).setDuration(260).withEndAction(() ->
                    statusToggle.setVisibility(View.GONE)).start();
        });
    }

    private void launchGame() {
        try {
            Intent intent = MetaActivityManager.obtainSplashLaunchIntent(PKG_BGMI, this);
            if (intent != null) {
                startActivity(intent);
                updateButtonState(true);
            }
        } catch (Exception e) {
            Log.e("MAct", "Launch error", e);
        }
    }

    private void stopGame() {
        try {
            MetaActivityManager.killAppByPkg(PKG_BGMI);
            updateButtonState(false);
        } catch (Exception e) {
            Toast.makeText(this, "Could not stop", Toast.LENGTH_SHORT).show();
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

    /**
     * UI-only wrapper around the sensitive OBB copy. AppManager and the
     * copyObbFolderAsync(...) call below are unchanged; progress is now also
     * drawn inside the primary button.
     */
    private void copyObbFiles() {
        if (obbButton == null) return;

        Button btn = (Button) obbButton;
        btn.setEnabled(false);
        if (downloadBox != null) downloadBox.setVisibility(View.GONE);
        if (percentText != null) percentText.setText("0%");
        if (downloadProgressBar != null) {
            downloadProgressBar.setIndeterminate(false);
            downloadProgressBar.setMax(100);
            downloadProgressBar.setProgress(0);
        }
        buttonProgress(0, "Copying");

        AppManager appManager = new AppManager(this);
        appManager.copyObbFolderAsync(PKG_BGMI, new AppManager.CopyCallback() {
            @Override
            public void onCopyCompleted(boolean success) {
                runOnUiThread(() -> {
                    if (success) {
                        isObbCopied = true;
                        btn.setVisibility(View.GONE);
                        if (downloadStatusText != null) downloadStatusText.setText("Ready");
                        if (percentText != null) percentText.setText("100%");
                        if (downloadProgressBar != null) downloadProgressBar.setProgress(100);
                        buttonDone();
                        Toast.makeText(MAct.this, "Ready", Toast.LENGTH_SHORT).show();
                    } else {
                        btn.setEnabled(true);
                        btn.setText("APPLY OBB");
                        if (downloadBox != null) downloadBox.setVisibility(View.GONE);
                        if (launchButton != null) launchButton.reset();
                        Toast.makeText(MAct.this, "Copy failed", Toast.LENGTH_SHORT).show();
                    }
                });
            }

            @Override
            public void onProgressUpdate(int progress) {
                runOnUiThread(() -> {
                    if (downloadStatusText != null) downloadStatusText.setText("Copying");
                    if (percentText != null) percentText.setText(progress + "%");
                    if (downloadProgressBar != null) downloadProgressBar.setProgress(progress);
                    buttonProgress(progress, "Copying");
                });
            }
        }, false); // false to hide the default dialog
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
