package com.xlox;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.AlertDialog;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.provider.Settings;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.xlox.ui.AnoyButton;
import com.xlox.ui.ThemeMode;
import com.xlox.utils.Prefs;

public class LogAct extends AppCompatActivity {

    static {
        try {
            System.loadLibrary("XloX");
        } catch (UnsatisfiedLinkError ignored) {
        }
    }

    private Prefs prefs;
    private final String USER = "USER";

    private EditText textUsername;
    private AnoyButton btnLogin;
    private AnoyButton pasteKey;
    private AnoyButton buyKey;
    private ImageButton themeToggle;

    private TextView premium;
    private LinearLayout loginCard;
    private LinearLayout mainContainer;
    private ImageView logo;
    private ImageView eyeToggle;
    private boolean isPasswordVisible = false;

    private static final int REQUEST_MANAGE_STORAGE_PERMISSION = 100;
    private static final int REQUEST_MANAGE_UNKNOWN_APP_SOURCES = 200;
    private static final String TELEGRAM_URL = "https://t.me/libAkAudioVisiual";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeMode.apply(this);
        super.onCreate(savedInstanceState);

        if (iskokofm()) {
            new AlertDialog.Builder(this)
                    .setTitle("VPN detected")
                    .setMessage("Turn off VPN to continue.")
                    .setPositiveButton("OK", null).show();
            return;
        }

        setContentView(R.layout.activity_login);
        ThemeMode.applyWindow(this);
        prefs = new Prefs(this);
        checkAndRequestPermissions();

        textUsername = findViewById(R.id.userkey);
        btnLogin = findViewById(R.id.login);
        pasteKey = findViewById(R.id.pasteKey);
        buyKey = findViewById(R.id.buyKey);
        themeToggle = findViewById(R.id.themeToggle);
        premium = findViewById(R.id.premium);
        loginCard = findViewById(R.id.loginCard);
        mainContainer = findViewById(R.id.mainContainer);
        logo = findViewById(R.id.logo);
        eyeToggle = findViewById(R.id.eyeToggle);

        int blue = ContextCompat.getColor(this, R.color.anoyBlue);
        if (pasteKey != null) {
            pasteKey.setDotColor(blue);
            pasteKey.setFillColor(Color.argb(28, 0, 122, 255));
        }
        if (buyKey != null) {
            buyKey.setDotColor(blue);
            buyKey.setFillColor(Color.argb(28, 0, 122, 255));
        }

        textUsername.setText(prefs.getSt(USER, ""));

        if (eyeToggle != null) {
            eyeToggle.setOnClickListener(v -> {
                if (isPasswordVisible) {
                    textUsername.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                    eyeToggle.setAlpha(0.55f);
                } else {
                    textUsername.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
                    eyeToggle.setAlpha(1f);
                }
                isPasswordVisible = !isPasswordVisible;
                textUsername.setSelection(textUsername.getText().length());
            });
        }

        if (pasteKey != null) {
            pasteKey.setOnClickListener(v -> {
                pasteKey.pulseDots(650L);
                ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (clipboard != null && clipboard.hasPrimaryClip()
                        && clipboard.getPrimaryClip() != null
                        && clipboard.getPrimaryClip().getItemCount() > 0) {
                    CharSequence copiedKey = clipboard.getPrimaryClip().getItemAt(0).coerceToText(this);
                    if (copiedKey != null) {
                        textUsername.setText(copiedKey);
                        textUsername.setSelection(textUsername.getText().length());
                    }
                }
            });
        }

        if (buyKey != null) {
            buyKey.setOnClickListener(v -> {
                buyKey.pulseDots(650L);
                openExternalUrl(TELEGRAM_URL);
            });
        }

        if (themeToggle != null) {
            themeToggle.setOnClickListener(v -> ThemeMode.toggle(this));
        }

        startIntroAnimation();

        btnLogin.setOnClickListener(v -> {
            String userKey = textUsername.getText().toString().trim();
            if (userKey.isEmpty()) {
                textUsername.setError("Enter your key");
                return;
            }
            if (iskokofm()) {
                new AlertDialog.Builder(this)
                        .setTitle("VPN detected")
                        .setMessage("Turn off VPN to continue.")
                        .setPositiveButton("OK", null).show();
                return;
            }

            prefs.setSt(USER, userKey);
            btnLogin.showDots();
            Login(this, userKey);
        });
    }

    private void startIntroAnimation() {
        if (mainContainer != null) {
            mainContainer.setVisibility(View.VISIBLE);
            mainContainer.setAlpha(1f);
        }
        if (logo != null) {
            logo.setAlpha(0f);
            logo.setTranslationY(-24f);
            logo.animate().alpha(1f).translationY(0f).setDuration(700)
                    .setInterpolator(new DecelerateInterpolator(1.4f)).start();
            ObjectAnimator logoFloat = ObjectAnimator.ofFloat(logo, View.TRANSLATION_Y, 0f, -4f, 0f);
            logoFloat.setStartDelay(800);
            logoFloat.setDuration(3200);
            logoFloat.setRepeatCount(ValueAnimator.INFINITE);
            logoFloat.start();
        }
        View logoGlow = findViewById(R.id.logoGlow);
        if (logoGlow != null) {
            ObjectAnimator glowRotation = ObjectAnimator.ofFloat(logoGlow, View.ROTATION, 0f, 360f);
            glowRotation.setDuration(9000);
            glowRotation.setInterpolator(new LinearInterpolator());
            glowRotation.setRepeatCount(ValueAnimator.INFINITE);
            glowRotation.start();
        }
        if (premium != null) {
            premium.setAlpha(0f);
            premium.setTranslationY(16f);
            premium.animate().alpha(1f).translationY(0f).setDuration(600).setStartDelay(90)
                    .setInterpolator(new DecelerateInterpolator(1.4f)).start();
        }
        if (loginCard != null) {
            loginCard.setAlpha(0f);
            loginCard.setTranslationY(28f);
            loginCard.animate().alpha(1f).translationY(0f).setDuration(650).setStartDelay(140)
                    .setInterpolator(new DecelerateInterpolator(1.4f)).start();
        }
    }

    private void Login(final Context m_Context, final String userKey) {
        Handler loginHandler = new Handler(msg -> {
            if (msg.what == 0) {
                Intent i = new Intent(m_Context, MAct.class);
                startActivity(i);
                finish();
            } else {
                btnLogin.reset();
                Toast.makeText(m_Context, (String) msg.obj, Toast.LENGTH_SHORT).show();
            }
            return true;
        });

        new Thread(() -> {
            String result;
            try {
                result = Check(m_Context, userKey);
            } catch (Throwable t) {
                Log.e("LogAct", "Native licence check unavailable", t);
                result = "Loader library missing - reinstall the app";
            }
            if ("OK".equals(result)) {
                loginHandler.sendEmptyMessage(0);
            } else {
                Message msg = Message.obtain();
                msg.what = 1;
                msg.obj = result;
                loginHandler.sendMessage(msg);
            }
        }).start();
    }

    private void openExternalUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Log.e("LogAct", "Unable to open contact link", e);
        }
    }

    private void checkAndRequestPermissions() {
        if (!isStoragePermissionGranted()) {
            requestStoragePermissionDirect();
        } else if (!canRequestPackageInstalls()) {
            requestUnknownAppPermissionsDirect();
        }
    }

    private boolean isStoragePermissionGranted() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager();
    }

    private void requestStoragePermissionDirect() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            intent.setData(Uri.fromParts("package", getPackageName(), null));
            startActivityForResult(intent, REQUEST_MANAGE_STORAGE_PERMISSION);
        } else {
            androidx.core.app.ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQUEST_MANAGE_STORAGE_PERMISSION);
        }
    }

    private boolean canRequestPackageInstalls() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O || getPackageManager().canRequestPackageInstalls();
    }

    private void requestUnknownAppPermissionsDirect() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQUEST_MANAGE_UNKNOWN_APP_SOURCES);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_MANAGE_STORAGE_PERMISSION) {
            checkAndRequestPermissions();
        } else if (requestCode == REQUEST_MANAGE_UNKNOWN_APP_SOURCES
                && canRequestPackageInstalls()) {
            android.os.Process.killProcess(android.os.Process.myPid());
        }
    }

    private boolean iskokofm() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Network activeNetwork = cm.getActiveNetwork();
                if (activeNetwork != null) {
                    NetworkCapabilities caps = cm.getNetworkCapabilities(activeNetwork);
                    if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static native String Check(Context mContext, String userKey);
}
