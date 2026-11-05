package com.xlox;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatButton;
import androidx.core.app.ActivityCompat;

import android.Manifest;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.xlox.utils.Downtwo;
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
    private AppCompatButton btnLogin;

    private Dialog loadingDialog;

    // UI
    private TextView premium;
    private TextView pasteKey;
    private LinearLayout loginCard;
    private LinearLayout mainContainer;
    private ImageView logo;
    private ImageView eyeToggle;
    private boolean isPasswordVisible = false;

    // Permissions
    private static final int REQUEST_MANAGE_STORAGE_PERMISSION = 100;
    private static final int REQUEST_MANAGE_UNKNOWN_APP_SOURCES = 200;

    private static final String PREFS_NAME = "com.xlox.prefs";
    private static final String PREF_PERMISSIONS_GRANTED = "permissions_granted";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (iskokofm()) {
            new AlertDialog.Builder(LogAct.this).setTitle("VPN Detected").setMessage("Please disable VPN to continue").setPositiveButton("OK", null).show();
            return;
        }

        setContentView(R.layout.activity_login);
        prefs = new Prefs(this);
        checkAndRequestPermissions();

        textUsername = findViewById(R.id.userkey);
        btnLogin = findViewById(R.id.login);
        premium = findViewById(R.id.premium);
        loginCard = findViewById(R.id.loginCard);
        mainContainer = findViewById(R.id.mainContainer);
        logo = findViewById(R.id.logo);
        eyeToggle = findViewById(R.id.eyeToggle);
        pasteKey = findViewById(R.id.pasteKey);

        textUsername.setText(prefs.getSt(USER, ""));

        if (eyeToggle != null) {
            eyeToggle.setOnClickListener(v -> {
                if (isPasswordVisible) {
                    textUsername.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                    eyeToggle.setAlpha(0.5f);
                } else {
                    textUsername.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
                    eyeToggle.setAlpha(1.0f);
                }
                isPasswordVisible = !isPasswordVisible;
                textUsername.setSelection(textUsername.getText().length());
            });
        }

        if (pasteKey != null) {
            pasteKey.setOnClickListener(v -> {
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

        startIntroAnimation();

        btnLogin.setOnClickListener(v -> {
            String userKey = textUsername.getText().toString().trim();
            if (userKey.isEmpty()) {
                textUsername.setError("Please enter key");
                return;
            }
            if (iskokofm()) {
                new AlertDialog.Builder(LogAct.this).setTitle("VPN Detected").setMessage("Please disable VPN to continue").setPositiveButton("OK", null).show();
                return;
            }

            prefs.setSt(USER, userKey);
            
            btnLogin.setEnabled(false);
            btnLogin.setText("Checking key...");
            btnLogin.setTextColor(Color.WHITE);
            
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
            logo.setTranslationY(-180f);
            logo.animate().alpha(1f).translationY(0f).setDuration(1200).setInterpolator(new DecelerateInterpolator(1.4f)).start();
            ObjectAnimator logoFloat = ObjectAnimator.ofFloat(logo, View.TRANSLATION_Y, 0f, -6f, 0f);
            logoFloat.setStartDelay(1300);
            logoFloat.setDuration(2600);
            logoFloat.setRepeatCount(ValueAnimator.INFINITE);
            logoFloat.start();
        }
        View logoGlow = findViewById(R.id.logoGlow);
        if (logoGlow != null) {
            ObjectAnimator glowRotation = ObjectAnimator.ofFloat(logoGlow, View.ROTATION, 0f, 360f);
            glowRotation.setDuration(7000);
            glowRotation.setInterpolator(new LinearInterpolator());
            glowRotation.setRepeatCount(ValueAnimator.INFINITE);
            glowRotation.start();
            logoGlow.animate().alpha(1f).setDuration(1500).setInterpolator(new DecelerateInterpolator()).start();
        }
        if (premium != null) {
            premium.setAlpha(0f);
            premium.setTranslationY(120f);
            premium.animate().alpha(1f).translationY(0f).setDuration(1200).setStartDelay(450).setInterpolator(new DecelerateInterpolator(1.4f)).start();
        }
        if (loginCard != null) {
            loginCard.setAlpha(0f);
            loginCard.setTranslationY(260f);
            loginCard.animate().alpha(1f).translationY(0f).setDuration(1500).setStartDelay(650).setInterpolator(new DecelerateInterpolator(1.4f)).start();
        }
    }

    private void Login(final Context m_Context, final String userKey) {
        Handler loginHandler = new Handler(msg -> {
            if (msg.what == 0) {
                Intent i = new Intent(m_Context, MAct.class);
                startActivity(i);
                finish();
            } else if (msg.what == 1) {
                btnLogin.setEnabled(true);
                btnLogin.setText("LOGIN");
                btnLogin.setTextColor(Color.parseColor("#FF8C00"));
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
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_MANAGE_STORAGE_PERMISSION);
        }
    }

    private boolean canRequestPackageInstalls() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O || getPackageManager().canRequestPackageInstalls();
    }

    private void requestUnknownAppPermissionsDirect() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQUEST_MANAGE_UNKNOWN_APP_SOURCES);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_MANAGE_STORAGE_PERMISSION) {
            checkAndRequestPermissions();
        } else if (requestCode == REQUEST_MANAGE_UNKNOWN_APP_SOURCES) {
            if (canRequestPackageInstalls()) {
                android.os.Process.killProcess(android.os.Process.myPid());
            }
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
        } catch (Exception ignored) {}
        return false;
    }

    private static native String Check(Context mContext, String userKey);
}
