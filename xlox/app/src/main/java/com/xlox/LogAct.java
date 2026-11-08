package com.xlox;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.Spanned;
import android.text.TextWatcher;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.google.android.material.button.MaterialButton;

import com.xlox.ui.Ui;
import com.xlox.ui.UpdateGate;
import com.xlox.utils.LibManager;
import com.xlox.utils.Net;
import com.xlox.utils.Prefs;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Sign in with a licence key.
 *
 * Order on every launch: mandatory library update gate, then storage /
 * install permissions, then the form becomes usable. JNI: {@code Check} keeps
 * its native name and signature.
 */
public class LogAct extends AppCompatActivity {

    static {
        try {
            System.loadLibrary("XloX");
        } catch (UnsatisfiedLinkError e) {
            Log.e("LogAct", "libXloX.so failed to load", e);
        }
    }

    private static native String Check(Context mContext, String userKey);

    private static final String TAG = "LogAct";
    private static final String PREF_USER = "USER";
    private static final Pattern KEY_PATTERN = Pattern.compile("^[A-Z0-9_-]{4,64}$");
    private static final int MAX_KEY_LENGTH = 64;
    private static final int REQUEST_STORAGE = 100;
    /** Pause on the "Signed in" check before the home screen. */
    private static final long SUCCESS_HOLD_MS = 400L;

    /** Debug builds only (screenshot QA): simulate a sign-in result. */
    public static final String EXTRA_QA_RESULT = "qa_result";
    /** Debug builds only (screenshot QA): show an update-gate state. */
    public static final String EXTRA_QA_GATE = "qa_gate";

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "anoy-login"));
    private final Handler main = new Handler(Looper.getMainLooper());

    private Prefs prefs;
    private EditText keyInput;
    private View keyField;
    private View keyIcon;
    private ImageButton toggleKey;
    private ImageButton pasteKey;
    private TextView keyError;
    private MaterialButton loginButton;
    private View notice;
    private TextView noticeText;
    private View footer;
    /** Messages that belong to the key itself (shown under the field). */
    private final Set<String> fieldErrors = new HashSet<>();

    private UpdateGate gate;
    private boolean gatePassed;
    private boolean signingIn;
    private boolean keyVisible;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.edgeToEdge(this);
        setContentView(R.layout.activity_login);
        prefs = new Prefs(this);

        keyInput = findViewById(R.id.userkey);
        // A single-line key can't wrap: cap its scale so ANOY-7D-XXXXX fits at 2.0.
        Ui.capFontScale(keyInput, 1.5f);
        keyField = findViewById(R.id.keyField);
        keyIcon = findViewById(R.id.keyIcon);
        toggleKey = findViewById(R.id.toggleKey);
        pasteKey = findViewById(R.id.pasteKey);
        keyError = findViewById(R.id.keyError);
        loginButton = findViewById(R.id.login);
        notice = findViewById(R.id.loginNotice);
        noticeText = findViewById(R.id.loginNoticeText);
        footer = findViewById(R.id.loginFooter);
        for (int id : new int[]{R.string.error_key_empty, R.string.error_key_format, R.string.error_key_unknown,
                R.string.error_key_blocked, R.string.error_key_expired, R.string.error_key_devices,
                R.string.clipboard_empty}) {
            fieldErrors.add(getString(id));
        }
        setupInsets();

        keyInput.setFilters(new InputFilter[]{new KeyFilter(), new InputFilter.LengthFilter(MAX_KEY_LENGTH)});
        String saved = prefs.getSt(PREF_USER, "");
        if (saved != null && !saved.isEmpty()) {
            keyInput.setText(sanitize(saved));
            keyInput.setSelection(keyInput.getText().length());
        }
        keyInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                clearError();
                updateLoginEnabled();
            }
        });
        keyInput.setOnFocusChangeListener((v, hasFocus) -> syncKeyIcon());
        keyInput.setOnEditorActionListener((v, actionId, event) -> {
            boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId == EditorInfo.IME_ACTION_DONE || enter) {
                attemptSignIn();
                return true;
            }
            return false;
        });

        toggleKey.setOnClickListener(v -> {
            Ui.tap(v);
            setKeyVisible(!keyVisible);
        });
        pasteKey.setOnClickListener(v -> {
            Ui.tap(v);
            pasteFromClipboard();
        });
        loginButton.setOnClickListener(v -> attemptSignIn());
        findViewById(R.id.loginNoticeRetry).setOnClickListener(v -> {
            Ui.tap(v);
            attemptSignIn();
        });
        findViewById(R.id.getKey).setOnClickListener(v -> {
            Ui.tap(v);
            Ui.openUrl(this, getString(R.string.telegram_url));
        });

        updateVersionFooter();
        setFormEnabled(false);
        Ui.stagger(findViewById(R.id.loginContent), 0);
        Ui.reveal(findViewById(R.id.loginActions), 120);

        // QA only (debug builds): show one update-gate state without the network.
        String qaGate = BuildConfig.DEBUG && getIntent() != null ? getIntent().getStringExtra(EXTRA_QA_GATE) : null;
        if (qaGate != null) {
            gate = UpdateGate.qaPreview(this, qaGate, this::onGatePassed);
            return;
        }
        // Every launch: check the library first. Sign in stays disabled until
        // the gate reports the library is current.
        gate = UpdateGate.start(this, this::onGatePassed);
    }

    /** Status bar on top; navigation bar or keyboard (whichever is taller) below. */
    private void setupInsets() {
        View root = findViewById(R.id.loginRoot);
        Ui.maxWidth(findViewById(R.id.loginContent), 440);
        Ui.maxWidth(findViewById(R.id.loginActions), 440);
        Ui.onInsets(root, (bars, bottom, ime) -> {
            root.setPadding(bars.left, bars.top, bars.right, bottom);
            // Keyboard open: keep the pill pinned above it, drop the footer.
            footer.setVisibility(ime ? View.GONE : View.VISIBLE);
        });
    }

    /** QA only (debug builds): plays a sign-in outcome through the real UI states. */
    private void runQaResult(String result) {
        if (keyInput.getText().length() == 0) keyInput.setText("ANOY-7D-QA7K2");
        if ("empty".equals(result)) {
            keyInput.setText("");
            attemptSignIn();
            return;
        }
        if ("malformed".equals(result)) {
            keyInput.setText("AB");
            attemptSignIn();
            return;
        }
        setSigningIn(true);
        if ("loading".equals(result)) return;
        if ("success".equals(result)) {
            // Freeze on the "Signed in" check (the real one lasts 400 ms).
            main.postDelayed(() -> Ui.setSuccess(loginButton, getString(R.string.signed_in)), 600L);
            return;
        }
        final boolean ok = "ok".equals(result);
        final String message;
        switch (result) {
            case "wrong":
                message = getString(R.string.error_key_unknown);
                break;
            case "offline":
                message = getString(R.string.net_offline);
                break;
            case "vpn":
                message = getString(R.string.error_vpn);
                break;
            case "server":
                message = getString(R.string.error_server_busy);
                break;
            case "maintenance":
                message = "Server under maintenance. We'll be back shortly.";
                break;
            default:
                message = null;
                break;
        }
        main.postDelayed(() -> onSignInResult(ok, message), 900L);
    }

    private void onGatePassed() {
        gatePassed = true;
        updateVersionFooter();
        setFormEnabled(true);
        requestPermissionsIfNeeded();
        if (keyInput.getText().length() == 0) {
            keyInput.requestFocus();
        }
        String qa = BuildConfig.DEBUG && getIntent() != null ? getIntent().getStringExtra(EXTRA_QA_RESULT) : null;
        if (qa != null) runQaResult(qa);
    }

    @Override
    protected void onDestroy() {
        if (gate != null) gate.dispose();
        main.removeCallbacksAndMessages(null);
        executor.shutdownNow();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ form

    private void setFormEnabled(boolean enabled) {
        keyInput.setEnabled(enabled);
        toggleKey.setEnabled(enabled);
        pasteKey.setEnabled(enabled);
        updateLoginEnabled();
    }

    private void updateLoginEnabled() {
        // While signing in the pill stays blue (loading); taps are ignored by
        // attemptSignIn and by Ui.setLoading.
        loginButton.setEnabled(signingIn || (gatePassed && keyInput.getText().toString().trim().length() > 0));
    }

    private void setKeyVisible(boolean visible) {
        keyVisible = visible;
        int selection = keyInput.getSelectionEnd();
        keyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | (visible ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                : InputType.TYPE_TEXT_VARIATION_PASSWORD));
        // setInputType resets the typeface on some versions; keep the mono face.
        keyInput.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(this, R.font.jetbrains_mono_regular));
        keyInput.setSelection(Math.max(0, Math.min(selection, keyInput.getText().length())));
        toggleKey.setImageResource(visible ? R.drawable.ic_eye_off : R.drawable.ic_eye);
        toggleKey.setSelected(visible);
        toggleKey.setContentDescription(getString(visible ? R.string.hide_key_description
                : R.string.show_key_description));
    }

    private void pasteFromClipboard() {
        String text = null;
        try {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            ClipData clip = clipboard == null ? null : clipboard.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0) {
                CharSequence value = clip.getItemAt(0).coerceToText(this);
                if (value != null) text = sanitize(value.toString());
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Clipboard unavailable", e);
        }
        if (text == null || text.isEmpty()) {
            showError(getString(R.string.clipboard_empty));
            return;
        }
        keyInput.setText(text);
        keyInput.setSelection(keyInput.getText().length());
    }

    /**
     * Problems with the key go under the field (red border, alert icon, shake,
     * REJECT haptic). Network / server / VPN / maintenance messages go to the
     * notice card with "Try again".
     */
    private void showError(String message) {
        if (message == null) message = getString(R.string.error_sign_in_generic);
        if (fieldErrors.contains(message)) {
            Ui.fade(notice, false);
            keyError.setText(message);
            keyError.setVisibility(View.VISIBLE);
            keyField.setActivated(true);
            syncKeyIcon();
            Ui.reject(keyField);
        } else {
            keyError.setVisibility(View.GONE);
            keyField.setActivated(false);
            syncKeyIcon();
            noticeText.setText(message);
            ((android.widget.ImageView) findViewById(R.id.loginNoticeIcon)).setImageResource(
                    message.equals(getString(R.string.net_offline)) ? R.drawable.ic_wifi_off : R.drawable.ic_triangle_alert);
            Ui.fade(notice, true);
            Ui.rejectHaptic(notice);
            // Connection problems aren't fixed by typing: drop the keyboard and
            // bring the whole card (and its Try again) into view once resized.
            hideKeyboard();
            notice.postDelayed(() -> {
                if (notice.getVisibility() == View.VISIBLE) {
                    notice.requestRectangleOnScreen(
                            new android.graphics.Rect(0, 0, notice.getWidth(), notice.getHeight()), false);
                }
            }, Ui.MEDIUM + 80L);
        }
    }

    private void clearError() {
        if (keyError.getVisibility() != View.GONE) keyError.setVisibility(View.GONE);
        if (notice.getVisibility() != View.GONE) Ui.fade(notice, false);
        keyField.setActivated(false);
        syncKeyIcon();
    }

    /** The leading key icon follows the field: blue while focused, red on error. */
    private void syncKeyIcon() {
        if (keyIcon == null) return;
        keyIcon.setActivated(keyField.isActivated());
        keyIcon.setSelected(keyInput.hasFocus());
        keyIcon.refreshDrawableState();
    }

    private void updateVersionFooter() {
        String lib = LibManager.installedVersion(this);
        TextView footer = findViewById(R.id.versionFooter);
        footer.setText(getString(R.string.version_footer, BuildConfig.VERSION_NAME,
                lib != null ? lib : getString(R.string.not_installed_short)));
    }

    // --------------------------------------------------------------- sign in

    private void attemptSignIn() {
        if (signingIn || !gatePassed) return;
        String key = sanitize(keyInput.getText().toString());
        if (!key.equals(keyInput.getText().toString())) {
            keyInput.setText(key);
            keyInput.setSelection(key.length());
        }
        if (key.isEmpty()) {
            showError(getString(R.string.error_key_empty));
            return;
        }
        if (!KEY_PATTERN.matcher(key).matches()) {
            showError(getString(R.string.error_key_format));
            return;
        }
        if (!Net.isOnline(this)) {
            showError(getString(R.string.net_offline));
            return;
        }
        if (Net.isVpn(this)) {
            showError(getString(R.string.error_vpn));
            return;
        }

        hideKeyboard();
        clearError();
        prefs.setSt(PREF_USER, key);
        setSigningIn(true);
        Ui.tap(loginButton);

        final Context app = getApplicationContext();
        try {
            executor.execute(() -> {
                String result;
                try {
                    result = Check(app, key);
                } catch (Throwable t) {
                    Log.e(TAG, "Native licence check unavailable", t);
                    result = null;
                    final String message = getString(R.string.error_native_missing);
                    main.post(() -> onSignInResult(false, message));
                    return;
                }
                final boolean ok = "OK".equals(result);
                final String message = ok ? null : friendlyLoginError(result);
                main.post(() -> onSignInResult(ok, message));
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            setSigningIn(false);
        }
    }

    private void onSignInResult(boolean ok, String message) {
        if (!Ui.alive(this)) return;
        if (ok) {
            // "Signed in" check + CONFIRM haptic, then the home screen.
            Ui.setSuccess(loginButton, getString(R.string.signed_in));
            main.postDelayed(() -> {
                if (!Ui.alive(this)) return;
                Intent intent = new Intent(this, MAct.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                if (BuildConfig.DEBUG && getIntent() != null && getIntent().hasExtra(EXTRA_QA_RESULT)) {
                    intent.putExtra(MAct.EXTRA_PREVIEW, true);
                }
                startActivity(intent);
                overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
                finish();
            }, SUCCESS_HOLD_MS);
            return;
        }
        setSigningIn(false);
        showError(message);
    }

    private void setSigningIn(boolean busy) {
        signingIn = busy;
        keyInput.setEnabled(!busy);
        toggleKey.setEnabled(!busy);
        pasteKey.setEnabled(!busy);
        Ui.setLoading(loginButton, busy, getString(busy ? R.string.signing_in : R.string.sign_in));
        updateLoginEnabled();
    }

    /** Maps raw panel / libcurl messages to plain language. */
    private String friendlyLoginError(String raw) {
        if (raw == null || raw.trim().isEmpty()) return getString(R.string.error_sign_in_generic);
        String r = raw.trim();
        String u = r.toUpperCase(Locale.US);
        if (u.contains("RESOLVE HOST") || u.contains("RESOLVE PROXY")) {
            return getString(Net.isOnline(this) ? R.string.net_no_host : R.string.net_offline);
        }
        if (u.contains("TIMEOUT") || u.contains("TIMED OUT")) return getString(R.string.net_timeout);
        if (u.contains("CONNECT TO SERVER") || u.contains("COULDN'T CONNECT")) return getString(R.string.net_refused);
        if (u.contains("SSL") || u.contains("CERTIFICATE")) return getString(R.string.net_ssl);
        if (u.contains("SEND FAILURE") || u.contains("RECV FAILURE") || u.contains("EMPTY REPLY")
                || u.contains("PARTIAL FILE")) {
            return getString(R.string.net_interrupted);
        }
        if (u.contains("NOT REGISTERED")) return getString(R.string.error_key_unknown);
        if (u.contains("BLOCKED")) return getString(R.string.error_key_blocked);
        if (u.contains("EXPIRED")) return getString(R.string.error_key_expired);
        if (u.contains("MAX DEVICE")) return getString(R.string.error_key_devices);
        if (u.contains("OUT OF SYNC")) return getString(R.string.error_clock);
        if (u.contains("INVALID TOKEN")) return getString(R.string.error_token);
        if (u.contains("BAD PARAMETER") || u.contains("INVALID PARAMETER")) return getString(R.string.error_key_format);
        if (u.contains("SERVER UNAVAILABLE") || u.contains("HTTP 5")) return getString(R.string.error_server_busy);
        if (u.contains("HTTP ")) return getString(R.string.error_sign_in_generic) + " (" + r + ")";
        if (u.contains("PARSE_ERROR") || u.contains("JSON") || u.contains("TYPE_ERROR")) {
            return getString(R.string.error_bad_response);
        }
        // Maintenance text and any other panel reason are already readable.
        return r.length() > 160 ? r.substring(0, 160) : r;
    }

    private void hideKeyboard() {
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(keyInput.getWindowToken(), 0);
        } catch (RuntimeException ignored) {
            // Keyboard service unavailable.
        }
    }

    static String sanitize(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder(raw.length());
        String upper = raw.trim().toUpperCase(Locale.US);
        for (int i = 0; i < upper.length() && sb.length() < MAX_KEY_LENGTH; i++) {
            char c = upper.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '_') sb.append(c);
        }
        return sb.toString();
    }

    /** Uppercases input and drops anything that can't be part of a key. */
    private static final class KeyFilter implements InputFilter {
        @Override
        public CharSequence filter(CharSequence source, int start, int end, Spanned dest, int dstart, int dend) {
            StringBuilder out = null;
            for (int i = start; i < end; i++) {
                char c = source.charAt(i);
                char u = Character.toUpperCase(c);
                boolean allowed = (u >= 'A' && u <= 'Z') || (u >= '0' && u <= '9') || u == '-' || u == '_';
                if (!allowed || u != c) {
                    if (out == null) out = new StringBuilder(source.subSequence(start, i));
                    if (allowed) out.append(u);
                } else if (out != null) {
                    out.append(c);
                }
            }
            return out == null ? null : out.toString();
        }
    }

    // ----------------------------------------------------------- permissions

    private void requestPermissionsIfNeeded() {
        // A missing OEM settings page must never block sign in.
        try {
            // Storage access is needed to copy the OBB. Nothing here installs
            // packages, so "Install unknown apps" is never requested.
            if (!hasStorageAccess()) requestStorageAccess();
        } catch (RuntimeException e) {
            Log.w(TAG, "Special-access settings unavailable", e);
        }
    }

    static boolean hasStorageAccess(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return Environment.isExternalStorageManager();
        return ActivityCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasStorageAccess() {
        return hasStorageAccess(this);
    }

    private void requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.fromParts("package", getPackageName(), null));
                startActivityForResult(intent, REQUEST_STORAGE);
            } catch (ActivityNotFoundException e) {
                try {
                    startActivityForResult(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
                            REQUEST_STORAGE);
                } catch (RuntimeException nested) {
                    Log.w(TAG, "All-files-access settings unavailable", nested);
                }
            }
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE,
                            Manifest.permission.READ_EXTERNAL_STORAGE}, REQUEST_STORAGE);
        }
    }
}
