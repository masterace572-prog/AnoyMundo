package com.xlox;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import android.Manifest;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
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
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.xlox.ui.ThemeMode;
import com.xlox.ui.designsystem.DesignSystem;
import com.xlox.ui.designsystem.DsMotion;
import com.xlox.ui.designsystem.PrimaryButton;
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
    private ImageButton themeToggle;
    private ImageButton eyeToggle;
    private LinearLayout mainContainer;
    private LinearLayout loginCard;
    private LinearLayout accessKeyField;
    private TextView accessKeyHelper;
    private boolean isPasswordVisible = false;

    private static final int REQUEST_MANAGE_STORAGE_PERMISSION = 100;
    private static final int REQUEST_MANAGE_UNKNOWN_APP_SOURCES = 200;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeMode.apply(this);
        super.onCreate(savedInstanceState);

        if (iskokofm()) {
            DesignSystem.showMessage(this, getString(R.string.vpn_detected_title),
                    getString(R.string.vpn_detected_message));
            return;
        }

        setContentView(R.layout.activity_login);
        ThemeMode.applyWindow(this);
        DesignSystem.applySafeDrawing(findViewById(R.id.loginRoot), true, true, true);

        prefs = new Prefs(this);
        checkAndRequestPermissions();

        textUsername = findViewById(R.id.userkey);
        btnLogin = findViewById(R.id.login);
        themeToggle = findViewById(R.id.themeToggle);
        eyeToggle = findViewById(R.id.eyeToggle);
        mainContainer = findViewById(R.id.mainContainer);
        loginCard = findViewById(R.id.loginCard);
        accessKeyField = findViewById(R.id.accessKeyField);
        accessKeyHelper = findViewById(R.id.accessKeyHelper);

        textUsername.setText(prefs.getSt(USER, ""));
        ThemeMode.decorateToggle(this, themeToggle);

        eyeToggle.setOnClickListener(v -> {
            if (isPasswordVisible) {
                textUsername.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                eyeToggle.setImageResource(R.drawable.ds_ic_visibility);
                eyeToggle.setContentDescription(getString(R.string.show_key));
            } else {
                textUsername.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
                eyeToggle.setImageResource(R.drawable.ds_ic_visibility_off);
                eyeToggle.setContentDescription(getString(R.string.hide_key));
            }
            isPasswordVisible = !isPasswordVisible;
            textUsername.setSelection(textUsername.getText().length());
        });

        findViewById(R.id.pasteKey).setOnClickListener(v -> {
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
            clearFieldError();
        });

        findViewById(R.id.buyKey).setOnClickListener(v ->
                openExternalUrl(getString(R.string.telegram_url)));

        themeToggle.setOnClickListener(v -> ThemeMode.toggle(this));

        DsMotion.fadeIn(loginCard);

        btnLogin.setOnClickListener(v -> {
            String userKey = textUsername.getText().toString().trim();
            if (userKey.isEmpty()) {
                showFieldError();
                return;
            }
            clearFieldError();
            if (iskokofm()) {
                DesignSystem.showMessage(this, getString(R.string.vpn_detected_title),
                        getString(R.string.vpn_detected_message));
                return;
            }

            prefs.setSt(USER, userKey);
            btnLogin.setLoading(true);
            Login(this, userKey);
        });
    }

    private void showFieldError() {
        accessKeyField.setBackgroundResource(R.drawable.ds_field_error);
        accessKeyHelper.setText(R.string.enter_your_key);
        accessKeyHelper.setTextColor(Tokens.error(this));
    }

    private void clearFieldError() {
        accessKeyField.setBackgroundResource(R.drawable.ds_field);
        accessKeyHelper.setText(R.string.access_key_hint);
        accessKeyHelper.setTextColor(Tokens.textTertiary(this));
    }

    private void Login(final Context m_Context, final String userKey) {
        Handler loginHandler = new Handler(msg -> {
            if (msg.what == 0) {
                Intent i = new Intent(m_Context, MAct.class);
                startActivity(i);
                finish();
            } else {
                btnLogin.reset();
                DesignSystem.snackbar(LogAct.this, (String) msg.obj);
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
