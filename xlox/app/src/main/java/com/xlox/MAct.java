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
import com.xlox.utils.StorageAccess;

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
    private static final String STATE_STORAGE_PROMPT = "storage_prompt_open";
    private static final String STATE_LAUNCH_AFTER_STORAGE = "launch_after_storage";
    /** Home asks for missing files access after the content has settled. */
    private static final long HOME_STORAGE_ASK_DELAY_MS = 700L;
    /**
     * When the container gives no launch intent (or starting it throws), it may
     * still have started the game itself: watch this long before reporting a
     * failure.
     */
    private static final long LAUNCH_CONFIRM_MS = 4000L;
    private static final long LAUNCH_POLL_MS = 400L;
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
    /**
     * Debug builds only: simulates the container's answer to a launch so CI can
     * check the result handling without BGMI - started | started_polish_throws
     * | null_running | null_dead | throws.
     */
    public static final String EXTRA_QA_LAUNCH = "qa_launch";

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
    /** A launch is waiting for the container to confirm the game started. */
    private boolean launchPending;
    /** Anoy went to the background while a launch was pending (the game came up). */
    private boolean leftDuringLaunch;
    /** The "Couldn't launch" message is on screen. */
    private boolean launchErrorShown;
    private String qaLaunch;
    /** Files-access prompt (for the OBB copy) is open; resolve it when we are back. */
    private boolean storagePromptOpen;
    private boolean leftForStoragePrompt;
    /** The prompt came from Launch: continue with startLaunch() once access is granted. */
    private boolean launchAfterStorage;
    private long qaLaunchAt;
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
        qaLaunch = preview ? getIntent().getStringExtra(EXTRA_QA_LAUNCH) : null;
        if (!preview && (expiry == null || expiry.isEmpty() || "NULL".equalsIgnoreCase(expiry))) {
            // Process was restarted and the native session is gone: sign in again.
            Log.i(TAG, "No active session, returning to sign in");
            openLogin();
            return;
        }

        Ui.edgeToEdge(this);
        setContentView(R.layout.activity_main);
        panelNote = findViewById(R.id.panelNote);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Even line lengths instead of a short last line. Layout's constant is
            // the API 23-28 name of LineBreaker.BREAK_STRATEGY_BALANCED (same value).
            //noinspection WrongConstant
            panelNote.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_BALANCED);
        }
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
        if (savedInstanceState != null && savedInstanceState.getBoolean(STATE_STORAGE_PROMPT, false)) {
            // Recreated while the files-access page was open: resolve it on resume.
            storagePromptOpen = true;
            leftForStoragePrompt = true;
            launchAfterStorage = savedInstanceState.getBoolean(STATE_LAUNCH_AFTER_STORAGE, false);
        }
        applyQaAction();
        // Files access is needed for the OBB copy. Sign in normally asks; if it
        // did not this run (or access is gone), ask here once so Launch isn't
        // the first surprise. Never blocks the screen.
        if (!preview && savedInstanceState == null && !StorageAccess.has(this)
                && !StorageAccess.askedThisSession()) {
            main.postDelayed(() -> {
                if (Ui.alive(this) && !StorageAccess.has(this) && !StorageAccess.askedThisSession()) {
                    requestStorage(false);
                }
            }, HOME_STORAGE_ASK_DELAY_MS);
        }
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
        if (launchPending) leftDuringLaunch = true;
        if (storagePromptOpen) leftForStoragePrompt = true;
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (primaryAction == null) return;
        refreshGameState();
        // A launch reported as failed, but the game is up after all: drop the stale error.
        if (launchErrorShown && containerRunning()) onGameLaunched(false);
        refreshLibrary();
        if (!preview) applyPanelStatus();
        reportLoaderError();
        maybeRecheckLibrary();
        // Back from the All-files-access page (some phones never send a result).
        if (storagePromptOpen && leftForStoragePrompt) finishStoragePrompt();
    }

    // ------------------------------------------------------- files access

    /**
     * Opens the system files-access prompt (All files access on Android 11+,
     * the storage dialog below). With {@code thenLaunch}, the existing
     * startLaunch() - OBB copy, then launch - continues once access is granted.
     */
    private void requestStorage(boolean thenLaunch) {
        if (storagePromptOpen) return;
        boolean shown;
        try {
            shown = StorageAccess.request(this);
        } catch (RuntimeException e) {
            Log.w(TAG, "Files-access prompt unavailable", e);
            shown = false;
        }
        if (!shown) {
            showMessage(getString(R.string.storage_permission_needed), true);
            return;
        }
        launchAfterStorage = thenLaunch;
        storagePromptOpen = true;
        leftForStoragePrompt = false;
    }

    /** The user answered the prompt: continue the launch, or explain and stop. */
    private void finishStoragePrompt() {
        boolean launch = launchAfterStorage;
        storagePromptOpen = false;
        leftForStoragePrompt = false;
        launchAfterStorage = false;
        if (!Ui.alive(this)) return;
        if (StorageAccess.has(this)) {
            if (launch) startLaunch();
        } else {
            showMessage(getString(R.string.storage_permission_needed), true);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_STORAGE_PROMPT, storagePromptOpen);
        outState.putBoolean(STATE_LAUNCH_AFTER_STORAGE, launchAfterStorage);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != StorageAccess.REQUEST_CODE || !storagePromptOpen) return;
        // Some phones report "cancelled" before the user has answered; only
        // decide once we have really been away or access is already there.
        if (leftForStoragePrompt || StorageAccess.has(this)) finishStoragePrompt();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == StorageAccess.REQUEST_CODE && storagePromptOpen) finishStoragePrompt();
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
            panelNote.setText(keepLastWordsTogether(note));
            panelNoteCard.setContentDescription(getString(R.string.notice_description, note));
            panelNoteCard.setVisibility(View.VISIBLE);
        }
    }

    /**
     * Panel note copy: emoji removed, ALL-CAPS sentences (3+ words) set in
     * sentence case, the panel's default line replaced by plain copy.
     */
    /** Joins the last two words with a no-break space so a short word ("ban.") never sits alone. */
    static String keepLastWordsTogether(String text) {
        if (text == null) return null;
        String t = text.trim();
        int last = t.lastIndexOf(' ');
        if (last <= 0 || t.indexOf(' ') == last) return t; // fewer than three words
        return t.substring(0, last) + '\u00A0' + t.substring(last + 1);
    }

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
        if ("obb_copy".equals(getIntent().getStringExtra(EXTRA_QA_ACTION))) {
            // The real copy-then-launch that follows the pre-checks (the emulator
            // has no activated container). qa_launch picks the stand-in game.
            main.postDelayed(this::prepareObbThenLaunch, 400L);
            return;
        }
        if (qaLaunch != null) {
            // The real flow after the pre-checks and OBB copy: "Launching…", then launch.
            main.postDelayed(() -> {
                setBusy(true, getString(R.string.launching));
                main.postDelayed(this::launchGame, 600L);
            }, 400L);
            return;
        }
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
            case "storage_launch":
                // Launch tapped after the activation / library checks, files access missing.
                requestStorage(true);
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
        gameInstalled = isInstalled(PKG_BGMI) || qaLaunch != null;
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
        if (!StorageAccess.has(this)) {
            // Ask for files access; startLaunch() runs again once it is granted.
            requestStorage(true);
            return;
        }
        prepareObbThenLaunch();
    }

    /**
     * Copies the game's OBB into the container with live percent on the Launch
     * button, and launches only once AppManager reports a verified copy (or a
     * truly current one). Any failure stops here: no launch.
     */
    private void prepareObbThenLaunch() {
        Log.d(TAG, "Launch: preparing game files");
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
                    Log.d(TAG, "Launch stopped: game files not ready (" + error + ")");
                    setBusy(false, null);
                    String reason = error == null || error.isEmpty() ? getString(R.string.net_generic) : error;
                    showMessage(getString(R.string.main_obb_not_found).equals(reason)
                            || getString(R.string.obb_folder_not_found).equals(reason)
                            ? getString(R.string.error_obb_missing)
                            : getString(R.string.error_obb_copy, reason), true);
                    return;
                }
                Log.d(TAG, "Launch: game files ready, launching");
                hideMessage(); // The pill itself reads "Launching…".
                primaryAction.setText(R.string.launching);
                main.postDelayed(MAct.this::launchGame, LAUNCH_DELAY_MS);
            }
        });
    }

    private void launchGame() {
        if (!Ui.alive(this)) return;
        qaLaunchAt = SystemClock.elapsedRealtime();
        // 1. Launch. Only this block decides whether the game started; the
        //    container call itself is unchanged.
        boolean started = false;
        Throwable failure = null;
        try {
            Intent intent = obtainLaunchIntent();
            if (intent != null) {
                startActivity(intent);
                started = true; // startActivity returned normally: the game is launching.
            }
        } catch (Throwable t) {
            failure = t;
        }
        if (started || containerRunning()) {
            onGameLaunched(true);
        } else {
            // No intent, or starting it threw: the container may still have
            // started the game itself. Confirm before calling it a failure.
            awaitContainerLaunch(failure);
        }
    }

    private Intent obtainLaunchIntent() {
        if (qaLaunch != null) {
            switch (qaLaunch) {
                case "started":
                case "started_polish_throws":
                    // Stand-in for the game: any activity that takes the screen.
                    return new Intent(android.provider.Settings.ACTION_SETTINGS);
                case "throws":
                    throw new IllegalStateException("QA: container refused the launch");
                default:
                    return null;
            }
        }
        return MetaActivityManager.obtainSplashLaunchIntent(PKG_BGMI, this);
    }

    /** True when the virtual container reports the game process running. */
    private boolean containerRunning() {
        if (qaLaunch != null) {
            return "null_running".equals(qaLaunch) && SystemClock.elapsedRealtime() - qaLaunchAt > 1200L;
        }
        try {
            return MetaActivityManager.isAppRunning(PKG_BGMI);
        } catch (Throwable t) {
            Log.w(TAG, "Container state unavailable", t);
            return false;
        }
    }

    private void awaitContainerLaunch(final Throwable cause) {
        launchPending = true;
        leftDuringLaunch = false;
        final long deadline = SystemClock.elapsedRealtime() + LAUNCH_CONFIRM_MS;
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!launchPending) return;
                if (leftDuringLaunch || containerRunning()) {
                    launchPending = false;
                    onGameLaunched(true);
                } else if (SystemClock.elapsedRealtime() < deadline) {
                    main.postDelayed(this, LAUNCH_POLL_MS);
                } else {
                    launchPending = false;
                    onLaunchFailed(cause);
                }
            }
        }, LAUNCH_POLL_MS);
    }

    /** 2. The game started. Nothing here can turn that into a launch error. */
    private void onGameLaunched(boolean haptic) {
        gameRunning = true;
        launchErrorShown = false;
        try {
            hideMessage();
            if (haptic) Ui.confirm(primaryAction);
            if ("started_polish_throws".equals(qaLaunch)) {
                throw new IllegalStateException("QA: post-launch UI failure");
            }
        } catch (Throwable t) {
            Log.w(TAG, "Post-launch UI update failed (the game is running)", t);
        }
        try {
            setBusy(false, null);
            refreshGameState();
        } catch (Throwable t) {
            busy = false;
            Log.w(TAG, "Post-launch state refresh failed (the game is running)", t);
        }
    }

    /** The game truly did not start: log the real cause and say so. */
    private void onLaunchFailed(Throwable cause) {
        Log.e(TAG, "Launch failed: the game did not start", cause != null ? cause
                : new IllegalStateException("No launch intent and the container reports the game is not running"));
        showMessage(getString(R.string.error_launch), true);
        launchErrorShown = true;
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
        launchErrorShown = false;
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
        launchErrorShown = false;
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
