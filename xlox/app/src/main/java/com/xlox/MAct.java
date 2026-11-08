package com.xlox;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.widget.TextViewCompat;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;

import com.xlox.ui.Ui;
import com.xlox.ui.UpdateGate;
import com.xlox.utils.AppManager;
import com.xlox.utils.LibManager;
import com.xlox.utils.Prefs;

import java.io.File;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    /** Skeletons pulse for at most this long, then stay static. */
    private static final long SKELETON_PULSE_MS = 3000L;
    /** Duration segment of a licence key: ANOY-7D-XXXXX, ANOY-2H-XXXXX. */
    private static final Pattern KEY_DURATION = Pattern.compile("^[A-Z0-9]+-(\\d{1,4})([HD])-", Pattern.CASE_INSENSITIVE);
    /** The panel's long-standing default note, replaced by plain copy. */
    private static final String DEFAULT_NOTE = "play safe else flag ban";

    /** Debug builds only (screenshot QA) - see docs/ui-redesign/README.md. */
    public static final String EXTRA_QA_EXPIRY = "qa_expiry";
    public static final String EXTRA_QA_STATUS = "qa_status";
    public static final String EXTRA_QA_NOTE = "qa_note";
    public static final String EXTRA_QA_LOADING = "qa_loading";
    public static final String EXTRA_QA_ACTION = "qa_action";

    private final Handler main = new Handler(Looper.getMainLooper());

    private TextView panelNote;
    private View panelNoteCard;
    private View licenceCard;
    private int statusLook = -1;
    private TextView statusValue;
    private TextView licenceExpiredText;
    private LinearProgressIndicator licenceProgress;
    private View heroSkeleton;
    private View serverSkeleton;
    private TextView expiresValue;
    private TextView timeLeftValue;
    private TextView gameStateValue;
    private TextView libraryValue;
    private TextView serverValue;
    private TextView statusMessage;
    private LinearProgressIndicator taskProgress;
    private MaterialButton primaryAction;

    private long expiryMillis = -1L;
    /** Full licence length from the key's duration segment, or -1 if unknown. */
    private long totalMillis = -1L;
    private long lastA11yMinute = -1L;
    private boolean preview;
    private SimpleDateFormat longDate;
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
        preview = BuildConfig.DEBUG && getIntent() != null
                && getIntent().getBooleanExtra(EXTRA_PREVIEW, false);
        if (!preview && (expiry == null || expiry.isEmpty() || "NULL".equalsIgnoreCase(expiry))) {
            // Process was restarted and the native session is gone: sign in again.
            Log.i(TAG, "No active session, returning to sign in");
            openLogin();
            return;
        }

        Ui.edgeToEdge(this);
        setContentView(R.layout.activity_main);
        panelNote = findViewById(R.id.panelNote);
        panelNoteCard = findViewById(R.id.panelNoteCard);
        licenceCard = findViewById(R.id.licenceCard);
        statusValue = findViewById(R.id.statusValue);
        licenceExpiredText = findViewById(R.id.licenceExpiredText);
        licenceProgress = findViewById(R.id.licenceProgress);
        heroSkeleton = findViewById(R.id.heroSkeleton);
        serverSkeleton = findViewById(R.id.serverSkeleton);
        expiresValue = findViewById(R.id.expiresValue);
        timeLeftValue = findViewById(R.id.timeLeftValue);
        gameStateValue = findViewById(R.id.gameStateValue);
        libraryValue = findViewById(R.id.libraryValue);
        serverValue = findViewById(R.id.serverValue);
        statusMessage = findViewById(R.id.statusMessage);
        taskProgress = findViewById(R.id.taskProgress);
        primaryAction = findViewById(R.id.primaryAction);
        setupInsets();

        ((TextView) findViewById(R.id.gameValue)).setText(R.string.game_name);
        ((TextView) findViewById(R.id.modelValue)).setText(deviceName());
        ((TextView) findViewById(R.id.androidValue)).setText(getString(R.string.android_version,
                Build.VERSION.RELEASE, Build.VERSION.SDK_INT));

        findViewById(R.id.signOut).setOnClickListener(v -> {
            Ui.tap(v);
            confirmSignOut();
        });
        findViewById(R.id.help).setOnClickListener(v -> {
            Ui.tap(v);
            Ui.openUrl(this, getString(R.string.telegram_url));
        });
        primaryAction.setOnClickListener(v -> onPrimaryAction());

        String savedKey = new Prefs(this).getSt("USER", "");
        if (preview) {
            expiry = qaExpiry();
            savedKey = "ANOY-30D-QA7K2";
        }
        expiryMillis = parseExpiryMillis(expiry);
        totalMillis = keyDurationMillis(savedKey);
        longDate = new SimpleDateFormat("d MMMM yyyy", Locale.getDefault());
        if (expiryMillis > 0) {
            expiresValue.setTag(new SimpleDateFormat("d MMM yyyy, h:mm a", Locale.getDefault())
                    .format(new Date(expiryMillis)));
        } else if (expiry != null && !expiry.isEmpty() && !"NULL".equalsIgnoreCase(expiry)) {
            expiresValue.setTag(expiry);
        }
        applyPanelStatus();
        refreshLibrary();
        refreshGameState();
        lastRecheck = SystemClock.elapsedRealtime(); // LogAct just ran the gate.
        applyQaAction();
        // Content reveals with a 40 ms stagger, 8 dp rise, 220 ms.
        Ui.stagger(findViewById(R.id.mainContent), 0);
        Ui.reveal(findViewById(R.id.actionBar), 120);
    }

    /** Status bar above the content; navigation bar under the action bar. */
    private void setupInsets() {
        View root = findViewById(R.id.mainRoot);
        View content = findViewById(R.id.mainContent);
        View actionBar = findViewById(R.id.actionBar);
        View actionPanel = findViewById(R.id.actionPanel);
        Ui.maxWidth(content, 600);
        Ui.maxWidth(actionPanel, 600);
        Ui.onInsets(root, (bars, bottom, ime) -> {
            root.setPadding(bars.left, bars.top, bars.right, 0);
            Ui.padBottom(actionPanel, bars.bottom);
        });
        // Content can always scroll clear of the pinned action bar.
        actionBar.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int needed = (b - t) + Ui.dp(this, 8);
            if (content.getPaddingBottom() != needed) {
                content.setPadding(content.getPaddingLeft(), content.getPaddingTop(),
                        content.getPaddingRight(), needed);
            }
        });
    }

    /** Sign out asks first, in a bottom sheet. */
    private void confirmSignOut() {
        View sheetView = LayoutInflater.from(this).inflate(R.layout.sheet_sign_out, null, false);
        BottomSheetDialog sheet = Ui.sheet(this, sheetView, true);
        sheetView.findViewById(R.id.signOutConfirm).setOnClickListener(v -> {
            Ui.tap(v);
            sheet.dismiss();
            openLogin();
        });
        sheetView.findViewById(R.id.signOutCancel).setOnClickListener(v -> sheet.dismiss());
        sheet.show();
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
        if (!preview) applyPanelStatus();
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
        boolean loading = preview && getIntent().getBooleanExtra(EXTRA_QA_LOADING, false);
        String status = preview ? getIntent().getStringExtra(EXTRA_QA_STATUS) : readNative(NativeField.STATUS);
        if (preview && status == null && !loading) status = "Safe";
        status = tidyPanelText(status);
        if (status == null || status.isEmpty() || loading) {
            // Unknown: a skeleton that pulses briefly, then a dash.
            serverValue.setVisibility(View.GONE);
            serverSkeleton.setVisibility(View.VISIBLE);
            Ui.pulse(serverSkeleton, SKELETON_PULSE_MS);
            if (!loading) main.postDelayed(() -> {
                if (serverSkeleton.getVisibility() != View.VISIBLE) return;
                Ui.stopPulse(serverSkeleton);
                serverSkeleton.setVisibility(View.GONE);
                setChip(serverValue, getString(R.string.dash), 0, 0);
            }, SKELETON_PULSE_MS);
        } else {
            Ui.stopPulse(serverSkeleton);
            serverSkeleton.setVisibility(View.GONE);
            String lower = status.toLowerCase(Locale.US);
            boolean safe = lower.contains("safe") && !lower.contains("unsafe") && !lower.contains("not safe");
            setChip(serverValue, status, safe ? R.drawable.ic_shield_check : R.drawable.ic_triangle_alert,
                    safe ? CHIP_SUCCESS : CHIP_WARNING);
        }
        String rawNote = preview ? qaNote() : readNative(NativeField.CREDIT);
        String note = tidyNote(rawNote);
        if (note.isEmpty()) {
            panelNoteCard.setVisibility(View.GONE);
        } else {
            panelNote.setText(note);
            panelNoteCard.setContentDescription(getString(R.string.notice_description, note));
            panelNoteCard.setVisibility(View.VISIBLE);
        }
    }

    /**
     * Panel note copy: emoji removed, ALL-CAPS sentences (3+ words) set in
     * sentence case, the panel's default line replaced by plain copy.
     */
    private String tidyNote(String raw) {
        String text = tidyPanelText(raw);
        if (text == null || text.isEmpty()) return "";
        String bare = text.toLowerCase(Locale.US).replaceAll("[^a-z ]", "").replaceAll("\\s+", " ").trim();
        if (bare.equals(DEFAULT_NOTE)) return getString(R.string.notice_fallback);
        return text;
    }

    /** Removes emoji / pictographs and fixes shouting; null stays null. */
    static String tidyPanelText(String raw) {
        if (raw == null) return null;
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); ) {
            int cp = raw.codePointAt(i);
            i += Character.charCount(cp);
            boolean emoji = (cp >= 0x1F000 && cp <= 0x1FAFF) || (cp >= 0x2600 && cp <= 0x27BF)
                    || (cp >= 0x2B00 && cp <= 0x2BFF) || (cp >= 0x2300 && cp <= 0x23FF)
                    || (cp >= 0xFE00 && cp <= 0xFE0F) || (cp >= 0xE0000 && cp <= 0xE007F)
                    || cp == 0x200D || cp == 0x20E3 || cp == 0x2122 || cp == 0x00A9 || cp == 0x00AE
                    || Character.getType(cp) == Character.SURROGATE;
            if (!emoji) sb.appendCodePoint(cp);
        }
        String text = sb.toString().replaceAll("\\s+", " ").trim();
        boolean hasLower = !text.equals(text.toUpperCase(Locale.US));
        boolean hasUpper = !text.equals(text.toLowerCase(Locale.US));
        if (!hasLower && hasUpper && text.split(" ").length >= 3) {
            // Sentence case: lower everything, capitalise each sentence start.
            char[] c = text.toLowerCase(Locale.US).toCharArray();
            boolean start = true;
            for (int i = 0; i < c.length; i++) {
                if (start && Character.isLetter(c[i])) {
                    c[i] = Character.toUpperCase(c[i]);
                    start = false;
                } else if (c[i] == '.' || c[i] == '!' || c[i] == '?') {
                    start = true;
                }
            }
            text = new String(c);
        }
        return text;
    }

    // ------------------------------------------------------------- countdown

    private void renderCountdown() {
        if (timeLeftValue == null) return;
        boolean loading = preview && getIntent().getBooleanExtra(EXTRA_QA_LOADING, false);
        if (expiryMillis <= 0 || loading) {
            // Unknown expiry: skeleton (pulses up to 3 s), neutral chip.
            timeLeftValue.setVisibility(View.GONE);
            licenceProgress.setVisibility(View.GONE);
            if (heroSkeleton.getVisibility() != View.VISIBLE) {
                heroSkeleton.setVisibility(View.VISIBLE);
                Ui.pulse(heroSkeleton, SKELETON_PULSE_MS);
            }
            Object raw = expiresValue.getTag();
            expiresValue.setText(raw != null ? getString(R.string.expires_on, raw) : "");
            expiresValue.setVisibility(raw != null ? View.VISIBLE : View.GONE);
            setStatusLook(LOOK_UNKNOWN);
            announce(getString(R.string.licence_a11y_unknown), -2L);
            return;
        }
        heroSkeleton.setVisibility(View.GONE);
        Ui.stopPulse(heroSkeleton);
        String expiresText = String.valueOf(expiresValue.getTag());
        long remaining = expiryMillis - System.currentTimeMillis();
        if (remaining <= 0) {
            if (!expired) {
                expired = true;
                refreshPrimary();
            }
            setStatusLook(LOOK_EXPIRED);
            timeLeftValue.setVisibility(View.GONE);
            licenceProgress.setVisibility(View.GONE);
            licenceExpiredText.setVisibility(View.VISIBLE);
            expiresValue.setText(getString(R.string.expired_on, expiresText));
            announce(getString(R.string.licence_a11y_expired, longDate.format(new Date(expiryMillis))), -1L);
            return;
        }
        setStatusLook(LOOK_ACTIVE);
        timeLeftValue.setVisibility(View.VISIBLE);
        licenceExpiredText.setVisibility(View.GONE);
        expiresValue.setText(getString(R.string.expires_on, expiresText));
        long total = remaining / 1000L;
        long days = total / 86400L;
        long hours = (total % 86400L) / 3600L;
        long minutes = (total % 3600L) / 60L;
        long seconds = total % 60L;
        // "29d 21h 04m"; under a day the seconds tick: "21h 04m 09s".
        timeLeftValue.setText(days > 0
                ? getString(R.string.time_left_value, days, hours, minutes)
                : getString(R.string.time_left_value_short, hours, minutes, seconds));
        if (totalMillis > 0 && remaining <= totalMillis) {
            licenceProgress.setVisibility(View.VISIBLE);
            licenceProgress.setProgressCompat((int) (remaining * 1000L / totalMillis), false);
        } else {
            licenceProgress.setVisibility(View.GONE);
        }
        long minute = total / 60L;
        if (minute != lastA11yMinute) {
            StringBuilder left = new StringBuilder();
            if (days > 0) left.append(getResources().getQuantityString(R.plurals.a11y_days, (int) days, (int) days));
            if (hours > 0 || days > 0) {
                if (left.length() > 0) left.append(' ');
                left.append(getResources().getQuantityString(R.plurals.a11y_hours, (int) hours, (int) hours));
            }
            if (days == 0) {
                if (left.length() > 0) left.append(' ');
                left.append(getResources().getQuantityString(R.plurals.a11y_minutes, (int) minutes, (int) minutes));
            }
            announce(getString(R.string.licence_a11y_active, left, longDate.format(new Date(expiryMillis))), minute);
        }
    }

    /** TalkBack reads the licence card as one sentence; updated once a minute. */
    private void announce(String sentence, long minuteKey) {
        if (minuteKey == lastA11yMinute) return;
        lastA11yMinute = minuteKey;
        licenceCard.setContentDescription(sentence);
    }

    private static final int LOOK_UNKNOWN = 0;
    private static final int LOOK_ACTIVE = 1;
    private static final int LOOK_EXPIRED = 2;

    /** Licence status chip: icon + word, outline only. */
    private void setStatusLook(int look) {
        if (look == statusLook) return;
        statusLook = look;
        if (look == LOOK_ACTIVE) {
            setChip(statusValue, getString(R.string.active), R.drawable.ic_shield_check, CHIP_SUCCESS);
        } else if (look == LOOK_EXPIRED) {
            setChip(statusValue, getString(R.string.expired), R.drawable.ic_shield_x, CHIP_DANGER);
        } else {
            setChip(statusValue, getString(R.string.dash), 0, 0);
        }
    }

    private static final int CHIP_SUCCESS = 1;
    private static final int CHIP_WARNING = 2;
    private static final int CHIP_DANGER = 3;

    /** Outline pill in the status colour with a leading Lucide glyph. */
    private void setChip(TextView chip, CharSequence text, int iconRes, int kind) {
        int colour = kind == CHIP_SUCCESS ? R.color.success : kind == CHIP_WARNING ? R.color.warning
                : kind == CHIP_DANGER ? R.color.danger : R.color.text_secondary;
        int bg = kind == CHIP_SUCCESS ? R.drawable.bg_chip_success : kind == CHIP_WARNING ? R.drawable.bg_chip_warning
                : kind == CHIP_DANGER ? R.drawable.bg_chip_danger : R.drawable.bg_chip_neutral;
        chip.setVisibility(View.VISIBLE);
        chip.setText(text);
        chip.setTextColor(ContextCompat.getColor(this, colour));
        chip.setBackgroundResource(bg);
        android.graphics.drawable.Drawable icon = null;
        if (iconRes != 0) {
            icon = ContextCompat.getDrawable(this, iconRes);
            if (icon != null) {
                int size = getResources().getDimensionPixelSize(R.dimen.icon_chip);
                icon = icon.mutate();
                icon.setBounds(0, 0, size, size);
            }
        }
        chip.setCompoundDrawablesRelative(icon, null, null, null);
        TextViewCompat.setCompoundDrawableTintList(chip,
                ColorStateList.valueOf(ContextCompat.getColor(this, colour)));
    }

    /** Licence length from the saved key ("ANOY-7D-..." = 7 days), or -1. */
    static long keyDurationMillis(String key) {
        if (key == null) return -1L;
        Matcher m = KEY_DURATION.matcher(key.trim());
        if (!m.find()) return -1L;
        try {
            long n = Long.parseLong(m.group(1));
            if (n <= 0) return -1L;
            return "H".equalsIgnoreCase(m.group(2)) ? n * 3_600_000L : n * 86_400_000L;
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    // ---------------------------------------------------------------- QA

    /** Sample expiry: 29d 21h 04m from now, "past", or an explicit ISO value. */
    private String qaExpiry() {
        String v = getIntent().getStringExtra(EXTRA_QA_EXPIRY);
        long now = System.currentTimeMillis();
        if (v == null) return String.valueOf(now + ((29L * 24 + 21) * 60 + 4) * 60_000L + 30_000L);
        if ("past".equals(v)) return String.valueOf(now - 3_600_000L);
        if ("hours".equals(v)) return String.valueOf(now + ((21L * 60) + 4) * 60_000L + 9_000L);
        return v;
    }

    private String qaNote() {
        String v = getIntent().getStringExtra(EXTRA_QA_NOTE);
        if (v == null) return "\uD83D\uDD25 PLAY SAFE ELSE FLAG BAN \uD83D\uDD25";
        return v;
    }

    /** Debug: freeze the action bar in a launch state for screenshots. */
    private void applyQaAction() {
        if (!preview) return;
        String a = getIntent().getStringExtra(EXTRA_QA_ACTION);
        if (a == null) return;
        switch (a) {
            case "preparing":
                setBusy(true, getString(R.string.action_preparing_progress, 42));
                showProgress(42, false);
                showMessage(getString(R.string.preparing), false);
                break;
            case "launching":
                setBusy(true, getString(R.string.launching));
                break;
            case "error":
                showMessage(getString(R.string.error_obb_missing), true);
                break;
            case "snackbar":
                // Held on screen so the capture can't miss it.
                Ui.snackbar(this, getString(R.string.error_link), Snackbar.LENGTH_INDEFINITE);
                break;
            case "signout":
                confirmSignOut();
                break;
            default:
                break;
        }
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
        if (!gameInstalled) {
            setChip(gameStateValue, getString(R.string.state_not_installed), R.drawable.ic_circle_alert, CHIP_WARNING);
        } else if (gameRunning) {
            setChip(gameStateValue, getString(R.string.state_running), R.drawable.ic_play, CHIP_SUCCESS);
        } else {
            setChip(gameStateValue, getString(R.string.state_ready), R.drawable.ic_circle_check, CHIP_SUCCESS);
        }
        refreshPrimary();
    }

    private void refreshLibrary() {
        String version = LibManager.installedVersion(this);
        libraryValue.setText(version != null ? version : getString(R.string.state_not_installed));
        libraryValue.setTextColor(ContextCompat.getColor(this,
                version != null ? R.color.text_secondary : R.color.warning));
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
        Ui.setLoading(primaryAction, false, getString(label));
    }

    private void setBusy(boolean value, CharSequence label) {
        busy = value;
        if (value) {
            Ui.setLoading(primaryAction, true, label);
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
        setBusy(true, getString(R.string.action_preparing));
        showProgress(0, true);
        showMessage(getString(R.string.preparing), false);
        new AppManager(this).copyObbAsync(PKG_BGMI, new AppManager.CopyCallback() {
            @Override
            public void onProgressUpdate(int percent) {
                if (!Ui.alive(MAct.this)) return;
                showProgress(percent, false);
                primaryAction.setText(getString(R.string.action_preparing_progress, percent));
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
                hideMessage(); // The pill itself reads "Launching…".
                primaryAction.setText(R.string.launching);
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
            Ui.confirm(primaryAction);
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

    /** Caption above the pill; errors get a danger glyph and REJECT haptic. */
    private void showMessage(String text, boolean error) {
        statusMessage.setText(text);
        statusMessage.setTextColor(ContextCompat.getColor(this, error ? R.color.danger : R.color.text_secondary));
        android.graphics.drawable.Drawable icon = null;
        if (error) {
            icon = ContextCompat.getDrawable(this, R.drawable.ic_circle_alert);
            if (icon != null) {
                int size = getResources().getDimensionPixelSize(R.dimen.icon_small);
                icon = icon.mutate();
                icon.setBounds(0, 0, size, size);
            }
            Ui.rejectHaptic(statusMessage);
        }
        statusMessage.setCompoundDrawablesRelative(icon, null, null, null);
        TextViewCompat.setCompoundDrawableTintList(statusMessage,
                ColorStateList.valueOf(ContextCompat.getColor(this, R.color.danger)));
        Ui.fade(statusMessage, true);
    }

    private void hideMessage() {
        Ui.fade(statusMessage, false);
    }

    private void showProgress(int percent, boolean indeterminate) {
        if (taskProgress.isIndeterminate() != indeterminate) {
            taskProgress.setVisibility(View.INVISIBLE);
            taskProgress.setIndeterminate(indeterminate);
        }
        taskProgress.setVisibility(View.VISIBLE);
        if (!indeterminate) taskProgress.setProgressCompat(percent, !Ui.reducedMotion(this));
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
