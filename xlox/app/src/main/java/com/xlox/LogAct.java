package com.xlox;

import android.Manifest;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.TransitionDrawable;
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
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.splashscreen.SplashScreen;

import com.xlox.ui.ThemeMode;
import com.xlox.ui.designsystem.DesignSystem;
import com.xlox.ui.designsystem.DsMotion;
import com.xlox.ui.designsystem.Haptics;
import com.xlox.ui.designsystem.PrimaryButton;
import com.xlox.ui.designsystem.ThemeToggleView;
import com.xlox.ui.designsystem.Tokens;
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
    private PrimaryButton btnLogin;
    private ThemeToggleView themeToggle;
    private ImageButton eyeToggle;
    private LinearLayout mainContainer;
    private LinearLayout loginCard;
    private LinearLayout accessKeyField;
    private View pasteKey;
    private TransitionDrawable fieldFocus;
    private boolean isPasswordVisible = false;

    private static final int REQUEST_MANAGE_STORAGE_PERMISSION = 100;
    private static final int REQUEST_MANAGE_UNKNOWN_APP_SOURCES = 200;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // AndroidX SplashScreen must be installed before super.onCreate().
        SplashScreen splash = com.xlox.ui.designsystem.Splash.install(this);
        ThemeMode.apply(this);
        super.onCreate(savedInstanceState);

        // The splash owns the system bars until its view is removed; restore
        // the palette-aware icon contrast as soon as it has gone.
        Runnable restoreBars = () -> {
            if (!isFinishing() && !isDestroyed()) ThemeMode.applyWindow(this);
        };

        if (iskokofm()) {
            DesignSystem.showMessage(this, getString(R.string.vpn_detected_title),
                    getString(R.string.vpn_detected_message));
            com.xlox.ui.designsystem.Splash.exit(splash, restoreBars);
            return;
        }

        setContentView(R.layout.activity_login);
        ThemeMode.applyWindow(this);
        DesignSystem.applySafeDrawing(findViewById(R.id.loginRoot), true, true, true);
        com.xlox.ui.designsystem.Splash.exit(splash, restoreBars);

        prefs = new Prefs(this);
        checkAndRequestPermissions();

        textUsername = findViewById(R.id.userkey);
        btnLogin = findViewById(R.id.login);
        themeToggle = findViewById(R.id.themeToggle);
        eyeToggle = findViewById(R.id.eyeToggle);
        mainContainer = findViewById(R.id.mainContainer);
        loginCard = findViewById(R.id.loginCard);
        accessKeyField = findViewById(R.id.accessKeyField);
        pasteKey = findViewById(R.id.pasteKey);

        textUsername.setText(prefs.getSt(USER, ""));
        ThemeMode.decorateToggle(this, themeToggle);

        // Recessed field with a crossfading coral focus ring.
        fieldFocus = new TransitionDrawable(new Drawable[]{
                ContextCompat.getDrawable(this, R.drawable.ds_field_rest),
                ContextCompat.getDrawable(this, R.drawable.ds_field_focus)});
        fieldFocus.setCrossFadeEnabled(true);
        accessKeyField.setBackground(fieldFocus);
        textUsername.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) fieldFocus.startTransition((int) DsMotion.STATE);
            else fieldFocus.reverseTransition((int) DsMotion.STATE);
        });

        eyeToggle.setOnClickListener(v -> {
            Haptics.toggle(v);
            if (isPasswordVisible) {
                textUsername.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                eyeToggle.setImageResource(R.drawable.ds_ic_eye);
                eyeToggle.setContentDescription(getString(R.string.show_key));
            } else {
                textUsername.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
                eyeToggle.setImageResource(R.drawable.ds_ic_eye_off);
                eyeToggle.setContentDescription(getString(R.string.hide_key));
            }
            isPasswordVisible = !isPasswordVisible;
            textUsername.setSelection(textUsername.getText().length());
        });

        pasteKey.setOnClickListener(v -> {
            Haptics.tick(v);
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard != null && clipboard.hasPrimaryClip()
                    && clipboard.getPrimaryClip() != null
                    && clipboard.getPrimaryClip().getItemCount() > 0) {
                CharSequence copiedKey = clipboard.getPrimaryClip().getItemAt(0).coerceToText(this);
                if (copiedKey != null && copiedKey.length() > 0) {
                    textUsername.setText(copiedKey);
                    textUsername.setSelection(textUsername.getText().length());
                    clearFieldError();
                    DsMotion.fadeIn(accessKeyField);
                    return;
                }
            }
            DesignSystem.snackbarError(this, getString(R.string.clipboard_empty));
        });

        View buyKeyRow = findViewById(R.id.buyKey);
        buyKeyRow.setOnClickListener(v -> openExternalUrl(getString(R.string.telegram_url)));

        themeToggle.setOnClickListener(v -> ThemeMode.toggle(this));

        DsMotion.springScale(findViewById(R.id.logo), 0.82f, 1f);
        DsMotion.stagger(findViewById(R.id.loginTopBar),
                findViewById(R.id.emblemContainer),
                findViewById(R.id.welcomeHeading),
                findViewById(R.id.premium),
                loginCard,
                buyKeyRow,
                findViewById(R.id.telegramCaption));

        btnLogin.setOnClickListener(v -> {
            String userKey = textUsername.getText().toString().trim();
            if (userKey.isEmpty()) {
                failSignIn(getString(R.string.enter_your_key));
                return;
            }
            clearFieldError();
            if (iskokofm()) {
                DesignSystem.showMessage(this, getString(R.string.vpn_detected_title),
                        getString(R.string.vpn_detected_message));
                return;
            }

            prefs.setSt(USER, userKey);
            setAuthenticating(true);
            Login(this, userKey);
        });
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // Safety net in case a platform splash implementation never delivers
        // an exit-animation callback. Cosmetic only: never let a bar refresh
        // abort startup on an OEM window implementation.
        if (hasFocus) {
            try {
                ThemeMode.applyWindow(this);
            } catch (RuntimeException ignored) {
                // Keep the activity usable; the splash callback retries.
            }
        }
    }

    private void setAuthenticating(boolean active) {
        btnLogin.setBusy(active);
        textUsername.setEnabled(!active);
        eyeToggle.setEnabled(!active);
        pasteKey.setEnabled(!active);
    }

    private void failSignIn(String reason) {
        setAuthenticating(false);
        Haptics.error(btnLogin);
        DsMotion.shake(accessKeyField);
        showFieldError();
        DesignSystem.snackbarError(this, reason);
    }

    private void showFieldError() {
        accessKeyField.setBackgroundResource(R.drawable.ds_field_error);
        textUsername.setHintTextColor(Tokens.errorInk(this));
    }

    private void clearFieldError() {
        accessKeyField.setBackground(fieldFocus);
        fieldFocus.resetTransition();
        textUsername.setHintTextColor(Tokens.textTertiary(this));
    }

    private void Login(final Context m_Context, final String userKey) {
        Handler loginHandler = new Handler(msg -> {
            if (msg.what == 0) {
                Haptics.success(btnLogin);
                Intent i = new Intent(m_Context, MAct.class);
                startActivity(i);
                overridePendingTransition(R.anim.ds_enter, R.anim.ds_exit);
                finish();
            } else {
                failSignIn(String.valueOf(msg.obj));
            }
            return true;
        });

        new Thread(() -> {
            String result;
            try {
                result = Check(m_Context, userKey);
            } catch (Throwable t) {
                Log.e("LogAct", "Native licence check unavailable", t);
                result = getString(R.string.loader_library_missing);
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
        // A missing OEM settings activity must never abort startup: the login
        // screen has to survive even on ROMs without special-access pages.
        try {
            if (!isStoragePermissionGranted()) {
                requestStoragePermissionDirect();
            } else if (!canRequestPackageInstalls()) {
                requestUnknownAppPermissionsDirect();
            }
        } catch (RuntimeException e) {
            Log.w("LogAct", "Special-access settings unavailable", e);
        }
    }

    private boolean isStoragePermissionGranted() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager();
    }

    private void requestStoragePermissionDirect() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.fromParts("package", getPackageName(), null));
                startActivityForResult(intent, REQUEST_MANAGE_STORAGE_PERMISSION);
            } catch (android.content.ActivityNotFoundException e) {
                // Fall back to the generic list; some OEM builds ship none.
                try {
                    startActivityForResult(
                            new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
                            REQUEST_MANAGE_STORAGE_PERMISSION);
                } catch (RuntimeException nested) {
                    Log.w("LogAct", "All-files-access settings unavailable", nested);
                }
            }
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
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, REQUEST_MANAGE_UNKNOWN_APP_SOURCES);
            } catch (RuntimeException e) {
                Log.w("LogAct", "Install-unknown-apps settings unavailable", e);
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        // Never kill the process here: canRequestPackageInstalls() is queried
        // live, so a restart is unnecessary, and self-terminating reads as a
        // crash on launch. Never loop back into the same settings screen.
        if (requestCode == REQUEST_MANAGE_STORAGE_PERMISSION
                && isStoragePermissionGranted() && !canRequestPackageInstalls()) {
            requestUnknownAppPermissionsDirect();
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
