package com.xlox;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

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

    static {
        try {
            System.loadLibrary("XloX");
        } catch (UnsatisfiedLinkError ignored) {}
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
    
    private TextView startText;
    private ImageView actionIcon;
    private View startBgmi;
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
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(Color.BLACK);
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            );
        }

        setContentView(R.layout.activity_main);

        checkPermissions();

        // Download UI
        downloadBox = findViewById(R.id.downloadBox);
        downloadProgressBar = findViewById(R.id.downloadProgressBar);
        downloadStatusText = findViewById(R.id.downloadStatusText);
        percentText = findViewById(R.id.percentText);
        restartButton = findViewById(R.id.restartButton);
        loadingCursor = findViewById(R.id.loadingCursor);
        appVersionText = findViewById(R.id.appVersionText);

        // Action UI
        startText = findViewById(R.id.startText);
        actionIcon = findViewById(R.id.actionIcon);
        startBgmi = findViewById(R.id.startBgmi);
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

        checkObbApplied();
        updateGameStatus();
        updateVersionDisplay();

        if (obbButton != null) {
            obbButton.setOnClickListener(v -> openExternalUrl(TELEGRAM_URL));
        }

        if (restartButton != null) {
            restartButton.setOnClickListener(v -> restartApp());
        }

        if (startBgmi != null) {
            startBgmi.setOnClickListener(v -> {
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
            showStatusToggle("LIB NOT LOADED", true);
            Toast.makeText(this, error, Toast.LENGTH_LONG).show();
            // Shown once; a repeated failure writes the file again.
            LibManager.clearLoadError(this);
            return;
        }
        File lib = LibManager.libFile(this);
        if (lib.isFile()) {
            String problem = LibManager.alignmentProblem(lib);
            if (problem != null) {
                showStatusToggle("LIB NOT 16 KB COMPATIBLE", true);
                Toast.makeText(this, problem, Toast.LENGTH_LONG).show();
            }
        }
    }

    private void startPageAnimation() {
        View logoGlow = findViewById(R.id.logoGlow);
        View mainLogo = findViewById(R.id.mainLogo);
        if (mainLogo != null) {
            ObjectAnimator logoFloat = ObjectAnimator.ofFloat(mainLogo, View.TRANSLATION_Y, 0f, -6f, 0f);
            logoFloat.setDuration(2600);
            logoFloat.setRepeatCount(ObjectAnimator.INFINITE);
            logoFloat.start();
        }
        if (logoGlow != null) {
            ObjectAnimator glowRotation = ObjectAnimator.ofFloat(logoGlow, View.ROTATION, 0f, 360f);
            glowRotation.setDuration(7000);
            glowRotation.setInterpolator(new LinearInterpolator());
            glowRotation.setRepeatCount(ObjectAnimator.INFINITE);
            glowRotation.start();
            logoGlow.animate().translationY(-5f).setDuration(1300)
                    .withEndAction(() -> logoGlow.animate().translationY(0f).setDuration(1300).start()).start();
        }
        View[] views = {findViewById(R.id.brandHeader), findViewById(R.id.mainCard),
                findViewById(R.id.statusToggle), findViewById(R.id.keyTimeRow), findViewById(R.id.fbCard)};
        long delay = 0;
        for (View view : views) {
            if (view != null) {
                view.setAlpha(0f);
                view.setTranslationY(34f);
                view.animate().alpha(1f).translationY(0f).setDuration(560).setStartDelay(delay).start();
                delay += 110;
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

        final Handler timerHandler = new Handler(getMainLooper());
        final Runnable timer = new Runnable() {
            @Override public void run() {
                long remaining = expiryMillis - System.currentTimeMillis();
                if (remaining <= 0L) {
                    setTimeValues(0, 0, 0, 0);
                    if (subscriptionStatus != null) {
                        subscriptionStatus.setText("Expired");
                        subscriptionStatus.setTextColor(Color.parseColor("#FF5252"));
                    }
                    if (timeRemaining != null) timeRemaining.setText("Expired");
                    return;
                }
                if (subscriptionStatus != null) {
                    subscriptionStatus.setText("Active");
                    subscriptionStatus.setTextColor(Color.parseColor("#25C779"));
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
        } catch (NumberFormatException ignored) {}

        String[] formats = {"yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd",
                "dd-MM-yyyy HH:mm:ss", "dd/MM/yyyy HH:mm:ss", "MMM dd, yyyy HH:mm:ss"};
        for (String format : formats) {
            try {
                SimpleDateFormat parser = new SimpleDateFormat(format, Locale.US);
                parser.setLenient(false);
                Date parsed = parser.parse(value);
                if (parsed != null) return parsed.getTime();
            } catch (ParseException ignored) {}
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

    private void startDownload() {
        if (isDownloading) {
            Log.d("MAct", "A loader download is already running");
            return;
        }
        isDownloading = true;
        runOnUiThread(() -> {
            if (downloadBox != null) downloadBox.setVisibility(View.VISIBLE);
            setLaunchButtonText("UPDATING...");
            if (downloadStatusText != null) downloadStatusText.setText("Preparing process...");
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
                    if (downloadStatusText != null) downloadStatusText.setText("All process done");
                    if (percentText != null) percentText.setText("100%");
                    if (downloadProgressBar != null) downloadProgressBar.setProgress(100);
                    if (loadingCursor != null) loadingCursor.setVisibility(View.GONE);

                    updateVersionDisplay(); // Refresh version text after update
                    setLaunchButtonText(isGameInstalled ? "LAUNCH GAME" : "INSTALL GAME");

                    if (restartButton != null) {
                        restartButton.setVisibility(View.VISIBLE);
                        restartButton.setAlpha(0f);
                        restartButton.animate().alpha(1f).setDuration(500).start();
                    }
                });
            } else {
                // Surface the real reason instead of failing silently: this was
                // the main "lib not loading" symptom.
                String reason = holder[0] != null ? holder[0].getFailureMessage() : "";
                if (reason == null || reason.isEmpty()) reason = "Loader download unavailable";
                Log.e("MAct", "Loader download failed: " + reason);
                final String message = reason;
                runOnUiThread(() -> {
                    isUpdateAvailable = false;
                    if (downloadBox != null) downloadBox.setVisibility(View.GONE);
                    if (loadingCursor != null) loadingCursor.setVisibility(View.GONE);
                    setLaunchButtonText(isGameInstalled ? "LAUNCH GAME" : "INSTALL GAME");
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
            if (downloadStatusText != null) downloadStatusText.setText("Preparing process...");
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
            } catch (Exception ignored) {}
        }).start();
    }

    private void updateGameStatus() {
        isGameInstalled = false;
        try {
            PackageInfo packageInfo = getPackageManager().getPackageInfo(PKG_BGMI, 0);
            if (packageInfo != null) {
                isGameInstalled = true;
                if (gameName != null) gameName.setText("BGMI INDIA");
                if (packageName != null) packageName.setText(PKG_BGMI);
                if (gameStatus != null) gameStatus.setText("SAFE");
                if (deviceStatus != null) {
                    deviceStatus.setText("Safe");
                    deviceStatus.setTextColor(Color.parseColor("#315FE5"));
                }
                updateButtonState(false);
            }
        } catch (Exception ignored) {
            isGameInstalled = false;
            if (gameStatus != null) gameStatus.setText("Not Installed");
            if (deviceStatus != null) {
                deviceStatus.setText("Not installed");
                deviceStatus.setTextColor(Color.parseColor("#FF9800"));
            }
            setLaunchButtonText("INSTALL GAME");
            if (startText != null) {
                startText.setText("INSTALL GAME");
                startText.setTextColor(Color.parseColor("#FF8C00"));
            }
            if (actionIcon != null) {
                actionIcon.setImageResource(android.R.drawable.ic_menu_save);
                actionIcon.setColorFilter(Color.parseColor("#FF8C00"));
            }
        }
    }

    private void setLaunchButtonText(String label) {
        if (startText != null) startText.setText(label);
        if (startBgmi instanceof Button) ((Button) startBgmi).setText(label);
    }

    private void updateButtonState(boolean running) {
        isGameRunning = running;
        runOnUiThread(() -> {
            if (running) {
                setLaunchButtonText("STOP GAME");
                if (startText != null) {
                    startText.setText("STOP GAME");
                    startText.setTextColor(Color.parseColor("#FF4444"));
                }
                if (actionIcon != null) {
                    actionIcon.setImageResource(android.R.drawable.ic_delete);
                    actionIcon.setColorFilter(Color.parseColor("#FF4444"));
                }
                if (startBgmi != null) {
                    startBgmi.setBackgroundResource(R.drawable.bg_ref_stop_button);
                }
                if (downloadBox != null) {
                    downloadBox.setVisibility(View.VISIBLE);
                    if (downloadStatusText != null) downloadStatusText.setText("Game Running...");
                    if (percentText != null) percentText.setText("ACTIVE");
                    if (downloadProgressBar != null) {
                        downloadProgressBar.setIndeterminate(true);
                        downloadProgressBar.setProgress(100);
                    }
                    if (restartButton != null) restartButton.setVisibility(View.GONE);
                }
            } else {
                if (isUpdateAvailable) {
                    setLaunchButtonText("UPDATE AVAILABLE");
                    if (startText != null) {
                        startText.setText("UPDATE AVAILABLE");
                        startText.setTextColor(Color.parseColor("#FF8C00"));
                    }
                } else {
                    setLaunchButtonText("LAUNCH GAME");
                    if (startText != null) {
                        startText.setText("LAUNCH");
                        startText.setTextColor(Color.parseColor("#FF8C00"));
                    }
                }
                if (actionIcon != null) {
                    actionIcon.setImageResource(android.R.drawable.ic_media_play);
                    actionIcon.setColorFilter(Color.parseColor("#FF8C00"));
                }
                if (startBgmi != null) {
                    startBgmi.setBackgroundResource(R.drawable.bg_orange_action);
                }
                if (downloadBox != null) {
                    new Handler().postDelayed(() -> {
                        if (!isGameRunning && !isUpdateAvailable) downloadBox.setVisibility(View.GONE);
                    }, 1000);
                }
            }
        });
    }

    private void handleLaunchFlow() {
        try {
            if (!MetaActivationManager.getActivationStatus()) {
                showStatusToggle("LAUNCHING FAILED", true);
                return;
            }
        } catch (Exception ignored) {
            showStatusToggle("LAUNCHING FAILED", true);
            return;
        }

        if (!LibManager.isUsableLib(this)) {
            Log.w("MAct", "Loader library missing or corrupt - requesting the panel release");
            showStatusToggle("DOWNLOADING LIBRARY...", false);
            startDownload();
            return;
        }

        if (!isObbCopied) {
            copyObbFiles();
        } else {
            showStatusToggle("LAUNCHING BGMI", false);
            new Handler().postDelayed(() -> {
                hideStatusToggle();
                launchGame();
            }, 2500);
        }
    }

    private void showStatusToggle(String message, boolean isError) {
        if (statusToggle == null) return;
        
        runOnUiThread(() -> {
            statusToggle.setVisibility(View.VISIBLE);
            statusToggle.setAlpha(0f);
            statusToggle.animate().alpha(1f).setDuration(300).start();
            
            if (statusToggleText != null) {
                statusToggleText.setText(message);
                statusToggleText.setTextColor(isError ? Color.parseColor("#FF5252") : Color.WHITE);
            }
            
            if (statusLoader != null) {
                statusLoader.setVisibility(isError ? View.GONE : View.VISIBLE);
            }
            
            if (statusErrorIcon != null) {
                statusErrorIcon.setVisibility(isError ? View.VISIBLE : View.GONE);
            }
            
            if (isError) {
                new Handler().postDelayed(this::hideStatusToggle, 3000);
            }
        });
    }

    private void hideStatusToggle() {
        if (statusToggle == null) return;
        
        runOnUiThread(() -> {
            statusToggle.animate().alpha(0f).setDuration(300).withEndAction(() -> {
                statusToggle.setVisibility(View.GONE);
            }).start();
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
            Toast.makeText(this, "Stop Failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
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
            Log.e("MAct", "Unable to open social link", e);
        }
    }

    private void copyObbFiles() {
        if (obbButton == null) return;
        
        Button btn = (Button) obbButton;
        btn.setEnabled(false);
        if (downloadBox != null) downloadBox.setVisibility(View.VISIBLE);
        if (downloadStatusText != null) downloadStatusText.setText("Preparing process...");
        if (percentText != null) percentText.setText("0%");
        if (downloadProgressBar != null) {
            downloadProgressBar.setIndeterminate(false);
            downloadProgressBar.setMax(100);
            downloadProgressBar.setProgress(0);
        }
        
        AppManager appManager = new AppManager(this);
        appManager.copyObbFolderAsync(PKG_BGMI, new AppManager.CopyCallback() {
            @Override
            public void onCopyCompleted(boolean success) {
                runOnUiThread(() -> {
                    if (success) {
                        isObbCopied = true;
                        btn.setVisibility(View.GONE);
                        if (downloadStatusText != null) downloadStatusText.setText("Facebook access ready");
                        if (percentText != null) percentText.setText("100%");
                        if (downloadProgressBar != null) downloadProgressBar.setProgress(100);
                        new Handler().postDelayed(() -> {
                            if (downloadBox != null) downloadBox.setVisibility(View.GONE);
                        }, 900);
                        Toast.makeText(MAct.this, "OBB Copied Successfully", Toast.LENGTH_SHORT).show();
                    } else {
                        btn.setEnabled(true);
                        btn.setText("APPLY OBB");
                        if (downloadBox != null) downloadBox.setVisibility(View.GONE);
                        Toast.makeText(MAct.this, "OBB Copy Failed", Toast.LENGTH_SHORT).show();
                    }
                });
            }

            @Override
            public void onProgressUpdate(int progress) {
                runOnUiThread(() -> {
                    if (downloadStatusText != null) downloadStatusText.setText("Preparing process...");
                    if (percentText != null) percentText.setText(progress + "%");
                    if (downloadProgressBar != null) downloadProgressBar.setProgress(progress);
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
