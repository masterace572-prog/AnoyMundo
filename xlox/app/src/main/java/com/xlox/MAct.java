package com.xlox;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
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

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;

import com.xlox.ui.Ui;
import com.xlox.ui.PermissionSheet;
import com.xlox.ui.UpdateGate;
import com.xlox.utils.AppManager;
import com.xlox.utils.AppUpdater;
import com.xlox.utils.LibManager;
import com.xlox.utils.Net;
import com.xlox.utils.Prefs;
import com.xlox.utils.StorageAccess;
import com.xlox.utils.UpdateException;

import java.io.File;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
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
    /** The saved licence key (written by sign in, LogAct.PREF_USER). */
    private static final String PREF_USER = "USER";
    /** Debug-only: lets CI screenshot this screen without a live session. */
    public static final String EXTRA_PREVIEW = "qa_preview";
    private static final long RECHECK_INTERVAL_MS = 60_000L;
    private static final long LAUNCH_DELAY_MS = 1200L;
    private static final String STATE_STORAGE_PROMPT = "storage_prompt_open";
    private static final String STATE_LAUNCH_AFTER_STORAGE = "launch_after_storage";
    private static final String STATE_PENDING_NEED = "pending_permission";
    private static final String STATE_ASKED_ROUND = "asked_permissions";
    /** Home asks for missing files access after the content has settled. */
    private static final long HOME_STORAGE_ASK_DELAY_MS = 700L;
    /** Status text kept in the support info, at most this long. */
    private static final int SUPPORT_STATUS_MAX = 120;
    private static final long LAUNCH_RESUME_DELAY_MS = 700L;
    /** Long enough to read "Restarting Anoy…" before the restart. */
    private static final long RESTART_DELAY_MS = 1500L;
    /**
     * When the container gives no launch intent (or starting it throws), it may
     * still have started the game itself: watch this long before reporting a
     * failure.
     */
    private static final long LAUNCH_CONFIRM_MS = 4000L;
    private static final long LAUNCH_POLL_MS = 400L;
    /** While home is visible, the container is asked this often whether the game runs. */
    private static final long RUNNING_CHECK_MS = 3000L;
    /** After Launch / Stop, the running check waits this long before acting. */
    private static final long RUNNING_SETTLE_MS = 2000L;
    /** A launched game that the container never reports is dropped after this. */
    private static final long RUNNING_FIRST_SEEN_MS = 30_000L;
    /** Skeletons pulse for at most this long, then stay static. */
    /** Settings > Check for app updates: the row's states. */
    private static final int APP_CHECK_IDLE = 0;
    private static final int APP_CHECK_CHECKING = 1;
    private static final int APP_CHECK_CURRENT = 2;
    private static final int APP_CHECK_FAILED = 3;
    /** "Up to date" stays this long; "Couldn't check" a little longer. */
    private static final long APP_CHECK_CURRENT_MS = 3000L;
    private static final long APP_CHECK_FAILED_MS = 5000L;
    /** The licence key row shows "Copied" this long after a tap. */
    private static final long KEY_COPIED_MS = 1200L;
    /** Debug: Settings > Check for app updates answers current | available | failed. */
    static final String EXTRA_QA_APP_CHECK = "qa_app_check";
    /** The countdown line follows the font scale up to 1.3x. */
    private static final float COUNTDOWN_MAX_FONT_SCALE = 1.3f;
    /** Licence refresh: on return to home at most this often... */
    private static final long LICENCE_REFRESH_MIN_MS = 60_000L;
    /** ...and quietly this often while home stays open. */
    private static final long LICENCE_REFRESH_EVERY_MS = 5 * 60_000L;
    /** Debug runs refresh on every return after this short pause. */
    private static final long QA_LICENCE_REFRESH_MIN_MS = 2000L;
    /** The panel's answer for a key that has run out. */
    private static final String REASON_EXPIRED = "EXPIRED KEY";
    /** Duration segment of a licence key: ANOY-7D-XXXXX, ANOY-2H-XXXXX. */
    private static final Pattern KEY_DURATION = Pattern.compile("^[A-Z0-9]+-(\\d{1,4})([HD])-", Pattern.CASE_INSENSITIVE);

    /** Debug builds only (screenshot QA) - see docs/ui-redesign/README.md. */
    public static final String EXTRA_QA_EXPIRY = "qa_expiry";
    public static final String EXTRA_QA_STATUS = "qa_status";
    public static final String EXTRA_QA_NOTE = "qa_note";
    public static final String EXTRA_QA_LOADING = "qa_loading";
    public static final String EXTRA_QA_ACTION = "qa_action";
    /**
     * Debug builds only: simulates the panel's answer to the licence refresh
     * on return to home - extend | soon | expired | fail.
     */
    public static final String EXTRA_QA_REFRESH = "qa_refresh";
    /** Debug builds only: stores this as the saved licence key (sign-out checks). */
    public static final String EXTRA_QA_SAVED_KEY = "qa_saved_key";
    /** Debug builds only: open the sample screen with Settings showing. */
    public static final String EXTRA_QA_SETTINGS = "qa_settings";
    /**
     * Debug builds only (with qa_preview): draw the countdown once and stop the
     * per-second tick, so the UI goes idle for uiautomator dumps in QA.
     */
    public static final String EXTRA_QA_FREEZE_CLOCK = "qa_freeze_clock";
    /**
     * Debug builds only: simulates the container's answer to a launch so CI can
     * check the result handling without BGMI - started | started_polish_throws
     * | null_running | null_dead | throws.
     */
    public static final String EXTRA_QA_LAUNCH = "qa_launch";
    /** Debug builds only: the stand-in game closes this many ms after the launch. */
    public static final String EXTRA_QA_GAME_MS = "qa_game_ms";

    private final Handler main = new Handler(Looper.getMainLooper());

    private TextView panelNote;
    private View panelNoteCard;
    private View licenceCard;
    private int statusLook = -1;
    private TextView statusValue;
    private TextView expiresValue;
    /** Countdown (2.3.1): one line, "29d 23h 59m 58s", "Expired" or "—". */
    private TextView timeLeftValue;
    private TextView gameVersionText;
    private TextView gameStateValue;
    /** Settings page (2.2.0): device, access + OBB, report, version, sign out. */
    private View settingsPage;
    private View actionBarView;
    private TextView gameTitle;
    private OnBackPressedCallback settingsBack;
    /** OBB state and the game icon are read off the main thread. */
    private final ExecutorService ioExecutor =
            Executors.newSingleThreadExecutor(r -> new Thread(r, "anoy-home-io"));
    /** Settings > Licence (2.3.1). */
    private TextView licenceKeyValue;
    private android.widget.ImageButton licenceKeyEye;
    private TextView licenceStatusValue;
    private TextView licenceExpiresValue;
    private boolean keyRevealed;
    private final Runnable restoreKey = this::renderLicenceKey;
    /** Settings > Updates (2.3.1). */
    private TextView libraryVersionValue;
    private View checkUpdateSpinner;
    private View checkUpdateIcon;
    private TextView checkUpdateValue;
    private View checkUpdateGlyph;
    /** 2.3.4: "Last checked today, 14:32" (IST) under the row label, or "Not checked yet". */
    private TextView checkUpdateCaption;
    private final Runnable slowAppCheck = () -> {
        if (this.appCheckRunning && this.checkUpdateValue != null) {
            Ui.swapText(this.checkUpdateValue, getString(R.string.settings_still_checking));
        }
    };
    private int appCheckRun;
    private boolean appCheckRunning;
    private final Runnable clearAppCheck = () -> showAppCheck(APP_CHECK_IDLE, null);
    private ExecutorService appCheckExecutor;
    private UpdateGate redownloadGate;
    private BottomSheetDialog helpSheet;
    private TextView libraryValue;
    private TextView serverValue;
    private TextView statusMessage;
    private LinearProgressIndicator taskProgress;
    private MaterialButton primaryAction;

    private long expiryMillis = -1L;
    private SimpleDateFormat expiryFormat;
    private TextView licenceChecked;
    /** Licence refresh (see maybeRefreshLicence). */
    private final ExecutorService licenceExecutor =
            Executors.newSingleThreadExecutor(r -> new Thread(r, "anoy-licence"));
    private boolean licenceRefreshing;
    private long lastLicenceAttempt;
    /** Wall-clock time of the last answer from the server. */
    private long lastLicenceCheckedAt;
    /** The latest refresh could not reach the server: show "Last checked". */
    private boolean licenceStale;
    private String qaRefreshExpiry;
    /** Full licence length from the key's duration segment, or -1 if unknown. */
    private long totalMillis = -1L;
    private long lastA11yMinute = -1L;
    private boolean preview;
    private boolean qaFreezeClock;
    private SimpleDateFormat longDate;
    private boolean expired;
    private boolean gameInstalled;
    private boolean gameRunning;
    /** A launch is waiting for the container to confirm the game started. */
    private boolean launchPending;
    /** Anoy went to the background while a launch was pending (the game came up). */
    private boolean leftDuringLaunch;
    /** Running reconcile (see reconcileRunning). */
    private int runningMisses;
    private int runningHits;
    private long runningSettleUntil;
    private long gameLaunchedAt;
    private boolean seenRunningSinceLaunch;
    /** Debug: the stand-in game "closes" this long after the launch (0 = stays open). */
    private long qaGameMs;
    /** The "Couldn't launch" message is on screen. */
    private boolean launchErrorShown;
    private String qaLaunch;
    /** Files-access prompt (for the OBB copy) is open; resolve it when we are back. */
    private boolean storagePromptOpen;
    private boolean leftForStoragePrompt;
    /** The prompt came from Launch: continue with startLaunch() once access is granted. */
    private boolean launchAfterStorage;
    /** The permission whose prompt is open, and every one asked in this round (bit per ordinal). */
    private StorageAccess.Need pendingNeed;
    private int askedThisRound;
    /** The explanation sheet shown before a settings page, while it is open. */
    private BottomSheetDialog permissionSheet;
    /** Debug only: after the permissions, run the OBB copy directly (no container activation on the emulator). */
    private boolean qaPermissionsThenCopy;
    private long qaLaunchAt;
    private boolean busy;
    private UpdateGate gate;
    private long lastRecheck;

    /** Preparing game files: the delayed line, and "Still preparing…" after 15 s without progress. */
    private static final long PREP_SLOW_MS = 15_000L;
    /** Set once a game-files copy has finished on this phone (only changes the wording). */
    private static final String PREF_OBB_PREPARED = "obb_prepared_once";
    private static final String OBB_PREPARED_YES = "1";
    private Runnable prepShow;
    private final Runnable prepSlow = () -> {
        if (!Ui.alive(this) || !this.busy) return;
        if (this.statusMessage.getVisibility() == View.VISIBLE) {
            Ui.swapText(this.statusMessage, getString(R.string.preparing_slow));
        } else {
            showMessage(getString(R.string.preparing_slow), false);
        }
    };

    private final Runnable runningCheck = new Runnable() {
        @Override
        public void run() {
            try {
                reconcileRunning();
            } catch (Throwable t) {
                Log.w(TAG, "Running check failed", t);
            }
            main.postDelayed(this, RUNNING_CHECK_MS);
        }
    };

    private final Runnable licenceTimer = new Runnable() {
        @Override
        public void run() {
            maybeRefreshLicence(true);
            main.postDelayed(this, LICENCE_REFRESH_EVERY_MS);
        }
    };

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            renderCountdown();
            // Paused while Settings covers home (closeSettings restarts it).
            boolean hidden = settingsPage != null && settingsPage.getVisibility() == View.VISIBLE;
            if (!expired && expiryMillis > 0 && !qaFreezeClock && !hidden) {
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
        qaFreezeClock = preview && getIntent().getBooleanExtra(EXTRA_QA_FREEZE_CLOCK, false);
        qaGameMs = preview ? getIntent().getLongExtra(EXTRA_QA_GAME_MS, 0L) : 0L;
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
        expiresValue = findViewById(R.id.expiresValue);
        timeLeftValue = findViewById(R.id.timeLeftValue);
        // One countdown line: it follows the font scale up to 1.3x, then holds (it can't wrap).
        Ui.capFontScale(timeLeftValue, COUNTDOWN_MAX_FONT_SCALE);
        gameVersionText = findViewById(R.id.gameVersion);
        gameStateValue = findViewById(R.id.gameStateValue);
        libraryValue = findViewById(R.id.libraryValue);
        serverValue = findViewById(R.id.serverValue);
        statusMessage = findViewById(R.id.statusMessage);
        taskProgress = findViewById(R.id.taskProgress);
        primaryAction = findViewById(R.id.primaryAction);
        setupInsets();

        gameTitle = findViewById(R.id.gameValue);
        gameTitle.setText(R.string.game_name);
        setupHeader();
        setupSettings();
        ((TextView) findViewById(R.id.modelValue)).setText(deviceName());
        ((TextView) findViewById(R.id.androidValue)).setText(getString(R.string.android_version,
                Build.VERSION.RELEASE, Build.VERSION.SDK_INT));

        findViewById(R.id.signOut).setOnClickListener(v -> {
            Ui.tap(v);
            confirmSignOut();
        });
        // Help opens only from the "?" button - never on its own.
        findViewById(R.id.helpButton).setOnClickListener(v -> {
            Ui.tap(v);
            showHelp();
        });
        findViewById(R.id.settingsButton).setOnClickListener(v -> {
            Ui.tap(v);
            openSettings();
        });
        primaryAction.setOnClickListener(v -> onPrimaryAction());

        if (preview && getIntent().hasExtra(EXTRA_QA_SAVED_KEY)) {
            // Debug: a saved key for the Sign out / Forget this key checks.
            new Prefs(this).setSt(PREF_USER, getIntent().getStringExtra(EXTRA_QA_SAVED_KEY));
        }
        String savedKey = new Prefs(this).getSt(PREF_USER, "");
        if (preview) {
            expiry = qaExpiry();
            savedKey = "ANOY-30D-QA7K2";
        }
        totalMillis = keyDurationMillis(savedKey);
        // Expiry is shown in Indian Standard Time, like the panel, whatever
        // zone the phone is set to ("14 Nov 2026, 10:30 PM IST").
        longDate = istFormat("d MMMM yyyy");
        expiryFormat = istFormat(EXPIRY_PATTERN);
        setExpiry(expiry);
        // Sign in just checked the licence: the first refresh waits its turn.
        lastLicenceAttempt = SystemClock.elapsedRealtime();
        lastLicenceCheckedAt = System.currentTimeMillis();
        licenceChecked = findViewById(R.id.licenceChecked);
        applyPanelStatus();
        refreshLibrary();
        refreshGameState();
        lastRecheck = SystemClock.elapsedRealtime(); // LogAct just ran the gate.
        if (savedInstanceState != null && savedInstanceState.getBoolean(STATE_STORAGE_PROMPT, false)) {
            // Recreated while the files-access page was open: resolve it on resume.
            storagePromptOpen = true;
            leftForStoragePrompt = true;
            launchAfterStorage = savedInstanceState.getBoolean(STATE_LAUNCH_AFTER_STORAGE, false);
            int pending = savedInstanceState.getInt(STATE_PENDING_NEED, -1);
            pendingNeed = pending >= 0 && pending < StorageAccess.Need.values().length
                    ? StorageAccess.Need.values()[pending] : null;
            askedThisRound = savedInstanceState.getInt(STATE_ASKED_ROUND, 0);
        }
        if (savedInstanceState == null && StorageAccess.consumeLaunchAfterRestart(this)) {
            // Anoy restarted itself for the game files: carry on with that Launch.
            Log.d(TAG, "Launch: resuming after the restart");
            main.postDelayed(preview ? this::prepareObbThenLaunch : this::startLaunch, LAUNCH_RESUME_DELAY_MS);
        } else {
            applyQaAction();
        }
        // All files access, Files and Install unknown apps are needed for the
        // OBB copy. Ask here for whichever is missing (unless sign in already
        // asked for it this run) so Launch isn't the first surprise. Never
        // blocks the screen.
        if (!preview && savedInstanceState == null && shouldAskOnHome()) {
            main.postDelayed(() -> {
                if (Ui.alive(this) && shouldAskOnHome()) requestStorage(false);
            }, HOME_STORAGE_ASK_DELAY_MS);
        }
        if (preview && getIntent().getBooleanExtra(EXTRA_QA_SETTINGS, false)) openSettings();
        // Login -> home is the activity transition (anim/fade_in: 280 ms, 8 dp up).
        setupActionDivider();
    }

    /** The pinned Launch bar gets its hairline only while content is under it. */
    private void setupActionDivider() {
        View scroll = findViewById(R.id.mainScroll);
        View divider = findViewById(R.id.actionDivider);
        if (scroll == null || divider == null) return;
        Runnable update = () -> divider.setVisibility(
                scroll.canScrollVertically(1) ? View.VISIBLE : View.INVISIBLE);
        scroll.getViewTreeObserver().addOnScrollChangedListener(update::run);
        scroll.getViewTreeObserver().addOnGlobalLayoutListener(update::run);
    }

    /** Status bar above the content; navigation bar under the action bar. */
    private void setupInsets() {
        View root = findViewById(R.id.mainRoot);
        View content = findViewById(R.id.mainContent);
        View actionBar = findViewById(R.id.actionBar);
        View actionPanel = findViewById(R.id.actionPanel);
        Ui.maxWidth(content, 600);
        Ui.maxWidth(actionPanel, 600);
        View settingsContent = findViewById(R.id.settingsContent);
        Ui.onInsets(root, (bars, bottom, ime) -> {
            root.setPadding(bars.left, bars.top, bars.right, 0);
            Ui.padBottom(actionPanel, bars.bottom);
            Ui.padBottom(settingsContent, bars.bottom);
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

    /**
     * Sign out asks first, in a bottom sheet. "Sign out" keeps the saved key
     * (sign in opens with it filled in); "Forget this key" also removes it from
     * this phone, so sign in opens empty. Nothing is deleted on the server.
     */
    private void confirmSignOut() {
        View sheetView = LayoutInflater.from(this).inflate(R.layout.sheet_sign_out, null, false);
        BottomSheetDialog sheet = Ui.sheet(this, sheetView, true);
        sheetView.findViewById(R.id.signOutConfirm).setOnClickListener(v -> {
            Ui.tap(v);
            sheet.dismiss();
            Log.d(TAG, "Sign out: keeping the saved key");
            openLogin();
        });
        sheetView.findViewById(R.id.signOutForget).setOnClickListener(v -> {
            Ui.tap(v);
            sheet.dismiss();
            new Prefs(this).remove(PREF_USER);
            Log.d(TAG, "Sign out: saved key forgotten");
            openLogin();
        });
        sheetView.findViewById(R.id.signOutCancel).setOnClickListener(v -> sheet.dismiss());
        sheet.show();
    }

    @Override
    protected void onStart() {
        super.onStart();
        main.removeCallbacks(ticker);
        main.removeCallbacks(runningCheck);
        main.removeCallbacks(licenceTimer);
        if (primaryAction != null) {
            main.post(ticker);
            main.postDelayed(runningCheck, 1000L);
            main.postDelayed(licenceTimer, LICENCE_REFRESH_EVERY_MS);
        }
    }

    @Override
    protected void onStop() {
        main.removeCallbacks(ticker);
        main.removeCallbacks(runningCheck);
        main.removeCallbacks(licenceTimer);
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
        maybeRefreshLicence(false);
        // Back from the All-files-access page (some phones never send a result).
        if (storagePromptOpen && leftForStoragePrompt) finishStoragePrompt();
    }

    // ------------------------------------------------------- files access

    /**
     * Asks for whichever of All files access, Files and Install unknown apps
     * is missing, one after another (see {@link StorageAccess}). With
     * {@code thenLaunch}, the existing startLaunch() - OBB copy, then launch -
     * continues once all three are granted.
     */
    private void requestStorage(boolean thenLaunch) {
        if (storagePromptOpen || (permissionSheet != null && permissionSheet.isShowing())) return;
        launchAfterStorage = thenLaunch;
        askedThisRound = 0;
        askNextPermission();
    }

    /** Home asks only for a missing permission sign in has not asked for this run. */
    private boolean shouldAskOnHome() {
        StorageAccess.Need next = StorageAccess.firstMissing(this);
        return next != null && !StorageAccess.askedThisSession(next);
    }

    private void askNextPermission() {
        StorageAccess.Need need = StorageAccess.firstMissing(this);
        if (need == null) {
            permissionsGranted();
            return;
        }
        int bit = 1 << need.ordinal();
        if ((askedThisRound & bit) != 0) {
            // Asked in this round and still missing: the user said no.
            permissionRefused(need);
            return;
        }
        if (PermissionSheet.explains(need)) {
            // Settings pages get a short explanation first; the page opens
            // only after "Continue to Settings".
            Log.d(TAG, "Permission explained: " + need);
            permissionSheet = PermissionSheet.show(this, need, new PermissionSheet.Callback() {
                @Override
                public void onContinue() {
                    permissionSheet = null;
                    if (Ui.alive(MAct.this)) openPermissionPrompt(need);
                }

                @Override
                public void onNotNow() {
                    permissionSheet = null;
                    if (Ui.alive(MAct.this)) permissionNotNow(need);
                }
            });
            return;
        }
        openPermissionPrompt(need);
    }

    /** Opens the settings page / runtime dialog for {@code need} (unchanged prompt logic). */
    private void openPermissionPrompt(StorageAccess.Need need) {
        int bit = 1 << need.ordinal();
        boolean shown;
        try {
            shown = StorageAccess.request(this, need);
        } catch (RuntimeException e) {
            Log.w(TAG, "Permission prompt unavailable: " + need, e);
            shown = false;
        }
        if (!shown) {
            permissionRefused(need);
            return;
        }
        Log.d(TAG, "Permission prompt: " + need);
        askedThisRound |= bit;
        pendingNeed = need;
        storagePromptOpen = true;
        leftForStoragePrompt = false;
    }

    /** The user answered a prompt: ask for the next missing one, or carry on. */
    private void finishStoragePrompt() {
        storagePromptOpen = false;
        leftForStoragePrompt = false;
        pendingNeed = null;
        if (!Ui.alive(this)) return;
        askNextPermission();
    }

    private void permissionsGranted() {
        boolean launch = launchAfterStorage;
        launchAfterStorage = false;
        askedThisRound = 0;
        Log.d(TAG, "Permissions granted" + (launch ? ": continuing the launch" : ""));
        if (!launch) return;
        if (qaPermissionsThenCopy) {
            prepareObbThenLaunch();
        } else {
            startLaunch();
        }
    }

    /**
     * "Not now" on the explanation sheet. From Launch it is a refusal (the
     * existing message, no copy, no launch); when home asked on its own it
     * just closes and is not asked again this run.
     */
    private void permissionNotNow(StorageAccess.Need need) {
        Log.d(TAG, "Permission not now: " + need);
        StorageAccess.markAsked(need);
        if (launchAfterStorage) {
            permissionRefused(need);
        } else {
            askedThisRound = 0;
        }
    }

    /** Refused (or no prompt on this phone): explain, no copy, no launch. */
    private void permissionRefused(StorageAccess.Need need) {
        launchAfterStorage = false;
        askedThisRound = 0;
        Log.d(TAG, "Permission refused: " + need);
        int message;
        switch (need) {
            case FILES:
                message = R.string.permission_files_needed;
                break;
            case INSTALL:
                message = R.string.permission_install_needed;
                break;
            default:
                message = R.string.storage_permission_needed;
                break;
        }
        showMessage(getString(message), true);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_STORAGE_PROMPT, storagePromptOpen);
        outState.putBoolean(STATE_LAUNCH_AFTER_STORAGE, launchAfterStorage);
        outState.putInt(STATE_PENDING_NEED, pendingNeed != null ? pendingNeed.ordinal() : -1);
        outState.putInt(STATE_ASKED_ROUND, askedThisRound);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != StorageAccess.REQUEST_CODE || !storagePromptOpen) return;
        // Some phones report "cancelled" before the user has answered; only
        // decide once we have really been away or access is already there.
        if (leftForStoragePrompt || (pendingNeed != null && StorageAccess.has(this, pendingNeed))) {
            finishStoragePrompt();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == StorageAccess.REQUEST_CODE && storagePromptOpen) finishStoragePrompt();
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        licenceExecutor.shutdown();
        ioExecutor.shutdownNow();
        if (permissionSheet != null) {
            permissionSheet.setOnDismissListener(null);
            permissionSheet.dismiss();
            permissionSheet = null;
        }
        if (gate != null) gate.dispose();
        if (redownloadGate != null) redownloadGate.dispose();
        if (appCheckExecutor != null) appCheckExecutor.shutdownNow();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- native

    private enum NativeField { EXPIRY, STATUS, CREDIT }

    // The last good session, so a failed quiet refresh never signs anyone out.
    // Check (LogAct) resets the native values before it asks the server; when
    // home's refresh fails those stay reset, and these stand in until the next
    // successful check. A process restart clears both, as before.
    private static final Object SESSION = new Object();
    private static String sessionExpiry;
    private static String sessionStatus;
    private static String sessionCredit;
    private static boolean sessionKept;

    /** Called by LogAct.checkLicence right after Check, with CHECK_LOCK held. */
    static void onLicenceChecked(boolean ok, boolean quietRefresh) {
        String expiry = ok ? rawNative(NativeField.EXPIRY) : null;
        synchronized (SESSION) {
            if (ok && hasExpiry(expiry)) {
                sessionExpiry = expiry;
                sessionStatus = rawNative(NativeField.STATUS);
                sessionCredit = rawNative(NativeField.CREDIT);
                sessionKept = false;
            } else if (quietRefresh && hasExpiry(sessionExpiry)) {
                sessionKept = true;
            } else {
                // Sign in failed: exactly as before, no session at all.
                sessionExpiry = null;
                sessionStatus = null;
                sessionCredit = null;
                sessionKept = false;
            }
        }
    }

    private static boolean hasExpiry(String expiry) {
        return expiry != null && !expiry.trim().isEmpty() && !"NULL".equalsIgnoreCase(expiry.trim());
    }

    private static String sessionValue(NativeField field) {
        switch (field) {
            case EXPIRY:
                return sessionExpiry;
            case STATUS:
                return sessionStatus;
            default:
                return sessionCredit;
        }
    }

    /**
     * The native session value - or the last good one while a refresh is
     * running (never read while Check rewrites it) or after one failed.
     */
    private static String readNative(NativeField field) {
        synchronized (SESSION) {
            if (sessionKept) return sessionValue(field);
        }
        if (!LogAct.CHECK_LOCK.tryLock()) {
            synchronized (SESSION) {
                return sessionValue(field);
            }
        }
        try {
            String value = rawNative(field);
            synchronized (SESSION) {
                if (field == NativeField.EXPIRY) {
                    if (hasExpiry(value)) sessionExpiry = value;
                } else if (value != null) {
                    if (field == NativeField.STATUS) sessionStatus = value;
                    else sessionCredit = value;
                }
            }
            return value;
        } finally {
            LogAct.CHECK_LOCK.unlock();
        }
    }

    private static String rawNative(NativeField field) {
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
            // Unknown (or still loading): a tertiary dash, no dot.
            setDot(serverValue, getString(R.string.dash), 0, R.color.text_tertiary);
        } else {
            String lower = status.toLowerCase(Locale.US);
            boolean safe = lower.contains("safe") && !lower.contains("unsafe") && !lower.contains("not safe");
            setDot(serverValue, status, safe ? R.color.success : R.color.warning, R.color.text_primary);
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

    /** Joins the last two words with a no-break space so a short word ("ban.") never sits alone. */
    static String keepLastWordsTogether(String text) {
        if (text == null) return null;
        String t = text.trim();
        int last = t.lastIndexOf(' ');
        if (last <= 0 || t.indexOf(' ') == last) return t; // fewer than three words
        return t.substring(0, last) + '\u00A0' + t.substring(last + 1);
    }

    /**
     * Panel note copy: emoji removed and ALL-CAPS sentences (3+ words) set in
     * sentence case - otherwise exactly the panel's floating text (2.2.0: the
     * default "play safe else flag ban" is no longer swapped for app copy).
     * Empty hides the card.
     */
    private String tidyNote(String raw) {
        String text = tidyPanelText(raw);
        return text == null ? "" : text;
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

    // ------------------------------------------------------- licence refresh

    /**
     * Re-checks the licence with the panel (the same check sign in runs) so a
     * changed expiry shows up. On return to home and every few minutes while
     * it stays open. Offline, a failure or an odd answer keeps the last known
     * time with a quiet "Last checked" line: never signs out, never stops the
     * game. Only the panel's "expired" answer changes the state.
     */
    private void maybeRefreshLicence(boolean fromTimer) {
        if (primaryAction == null || licenceRefreshing) return;
        final String qa = preview ? getIntent().getStringExtra(EXTRA_QA_REFRESH) : null;
        if (preview && qa == null) return;
        long now = SystemClock.elapsedRealtime();
        long wait = preview ? QA_LICENCE_REFRESH_MIN_MS
                : fromTimer ? LICENCE_REFRESH_EVERY_MS - 5000L : LICENCE_REFRESH_MIN_MS;
        if (now - lastLicenceAttempt < wait) return;
        final String key = preview ? "ANOY-30D-QA7K2" : new Prefs(this).getSt(PREF_USER, "");
        if (key == null || key.trim().isEmpty()) return;
        lastLicenceAttempt = now;
        if (!preview && (!Net.isOnline(this) || Net.isVpn(this))) {
            // No network (or a VPN, which sign in refuses too): don't even try,
            // so the session is never touched.
            onLicenceRefreshed("offline");
            return;
        }
        licenceRefreshing = true;
        final Context app = getApplicationContext();
        try {
            licenceExecutor.execute(() -> {
                String result;
                try {
                    result = preview ? qaRefreshResult(qa) : LogAct.checkLicence(app, key.trim(), true);
                } catch (Throwable t) {
                    Log.w(TAG, "Licence refresh unavailable", t);
                    result = null;
                }
                final String answer = result;
                main.post(() -> onLicenceRefreshed(answer));
            });
        } catch (RejectedExecutionException e) {
            licenceRefreshing = false;
        }
    }

    private void onLicenceRefreshed(String result) {
        licenceRefreshing = false;
        if (!Ui.alive(this) || primaryAction == null) return;
        long now = System.currentTimeMillis();
        String expiry = "OK".equals(result) ? (preview ? qaRefreshExpiry : readNative(NativeField.EXPIRY)) : null;
        if ("OK".equals(result) && hasExpiry(expiry)) {
            long before = expiryMillis;
            setExpiry(expiry);
            lastLicenceCheckedAt = now;
            licenceStale = false;
            Log.d(TAG, "Licence refreshed" + (expiryMillis != before ? ": expiry changed" : ": no change"));
            if (!preview) applyPanelStatus();
        } else if (result != null && result.toUpperCase(Locale.US).contains(REASON_EXPIRED)) {
            Log.d(TAG, "Licence refreshed: the panel reports the key expired");
            lastLicenceCheckedAt = now;
            licenceStale = false;
            if (expiryMillis <= 0 || expiryMillis > now) setExpiry(String.valueOf(now));
        } else {
            Log.d(TAG, "Licence refresh failed, keeping the last known time: " + result);
            licenceStale = true;
        }
        renderLicenceChecked();
        main.removeCallbacks(ticker);
        main.post(ticker);
    }

    /** New expiry from the panel: the card, the countdown and Launch follow it. */
    private void setExpiry(String expiry) {
        expiryMillis = parseExpiryMillis(expiry);
        if (expiryMillis > 0) {
            expiresValue.setTag(expiryFormat.format(new Date(expiryMillis)));
        } else if (hasExpiry(expiry)) {
            expiresValue.setTag(expiry);
        }
        lastA11yMinute = Long.MIN_VALUE;
        if (expired && expiryMillis > System.currentTimeMillis()) {
            // Renewed on the panel: active again.
            expired = false;
            refreshPrimary();
        }
    }

    /**
     * "Offline · last checked 12 min ago" (IST, see Ui.lastChecked) - only after
     * a refresh could not reach the panel.
     */
    private void renderLicenceChecked() {
        if (licenceChecked == null) return;
        if (!licenceStale || lastLicenceCheckedAt <= 0) {
            licenceChecked.setVisibility(View.GONE);
            return;
        }
        licenceChecked.setText(getString(R.string.licence_offline_checked,
                Ui.lastChecked(this, lastLicenceCheckedAt)));
        licenceChecked.setVisibility(View.VISIBLE);
    }

    /** Debug: the panel's simulated answer (the refresh itself runs for real). */
    private String qaRefreshResult(String qa) {
        SystemClock.sleep(300L);
        long now = System.currentTimeMillis();
        switch (qa) {
            case "extend":
                qaRefreshExpiry = String.valueOf(now + 60L * 86_400_000L);
                return "OK";
            case "soon":
                qaRefreshExpiry = String.valueOf(now + 3L * 3_600_000L + 30_000L);
                return "OK";
            case "expired":
                return REASON_EXPIRED;
            default:
                return "Couldn't resolve host name";
        }
    }

    /** "Expires soon" from a day before the end (a quarter of short keys). */
    private long lowTimeMillis() {
        long day = 86_400_000L;
        if (totalMillis > 0 && totalMillis < 4 * day) return Math.max(totalMillis / 4, 10 * 60_000L);
        return day;
    }

    // ------------------------------------------------------------- countdown

    private void renderCountdown() {
        if (timeLeftValue == null) return;
        boolean loading = preview && getIntent().getBooleanExtra(EXTRA_QA_LOADING, false);
        Object raw = expiresValue.getTag();
        // Row 3: the IST expiry date alone ("12 Dec 2026, 6:30 PM IST").
        expiresValue.setText(raw != null ? String.valueOf(raw) : "");
        expiresValue.setVisibility(raw != null ? View.VISIBLE : View.GONE);
        if (licenceExpiresValue != null) {
            String settingsExpiry = raw != null ? String.valueOf(raw) : getString(R.string.dash);
            if (!settingsExpiry.contentEquals(licenceExpiresValue.getText())) licenceExpiresValue.setText(settingsExpiry);
        }
        if (expiryMillis <= 0 || loading) {
            // Unknown expiry: a tertiary dash, no dot.
            showCountdownText(getString(R.string.dash), R.color.text_tertiary);
            setStatusLook(LOOK_UNKNOWN);
            announce(getString(R.string.licence_a11y_unknown), -2L);
            return;
        }
        long remaining = expiryMillis - System.currentTimeMillis();
        if (remaining <= 0) {
            if (!expired) {
                expired = true;
                refreshPrimary();
            }
            // Expired: the word in danger on the countdown line; the dot says Expired too.
            setStatusLook(LOOK_EXPIRED);
            showCountdownText(getString(R.string.expired), R.color.danger);
            announce(getString(R.string.licence_a11y_expired, longDate.format(new Date(expiryMillis))), -1L);
            return;
        }
        boolean soon = remaining <= lowTimeMillis();
        setStatusLook(soon ? LOOK_SOON : LOOK_ACTIVE);
        long total = remaining / 1000L;
        long days = total / 86400L;
        long hours = (total % 86400L) / 3600L;
        long minutes = (total % 3600L) / 60L;
        long seconds = total % 60L;
        showCountdownText(formatCountdown(days, hours, minutes, seconds), R.color.text_primary);
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
            announce(getString(soon ? R.string.licence_a11y_soon : R.string.licence_a11y_active,
                    left, longDate.format(new Date(expiryMillis))), minute);
        }
    }

    /** "29d 23h 59m 58s"; under a day "23h 59m 58s". Always ticking. */
    static String formatCountdown(long days, long hours, long minutes, long seconds) {
        return days > 0
                ? String.format(Locale.US, "%dd %02dh %02dm %02ds", days, hours, minutes, seconds)
                : String.format(Locale.US, "%dh %02dm %02ds", hours, minutes, seconds);
    }

    private void showCountdownText(String text, int colourRes) {
        if (!text.contentEquals(timeLeftValue.getText())) timeLeftValue.setText(text);
        timeLeftValue.setTextColor(ContextCompat.getColor(this, colourRes));
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
    private static final int LOOK_SOON = 3;

    /** Licence status: a 6 dp dot + word (Caption 500); nothing but a dash when unknown. */
    private void setStatusLook(int look) {
        if (look == statusLook) return;
        statusLook = look;
        applyLook(statusValue, look);
        if (licenceStatusValue != null) applyLook(licenceStatusValue, look);
    }

    /** Home's status and Settings > Licence > Status show the same dot + word. */
    private void applyLook(TextView view, int look) {
        if (look == LOOK_ACTIVE) {
            setDot(view, getString(R.string.active), R.color.success, R.color.text_secondary);
        } else if (look == LOOK_SOON) {
            setDot(view, getString(R.string.expires_soon), R.color.warning, R.color.text_secondary);
        } else if (look == LOOK_EXPIRED) {
            setDot(view, getString(R.string.expired), R.color.danger, R.color.text_secondary);
        } else {
            setDot(view, getString(R.string.dash), 0, R.color.text_tertiary);
        }
    }

    /** Licence status in words, for the support info. */
    private String lookWord() {
        if (statusLook == LOOK_ACTIVE) return getString(R.string.active);
        if (statusLook == LOOK_SOON) return getString(R.string.expires_soon);
        if (statusLook == LOOK_EXPIRED) return getString(R.string.expired);
        return getString(R.string.support_unknown);
    }

    /**
     * Status = a 6 dp dot in the state colour + plain text (spec 2.6: no pills,
     * no chips, at most three dots per screen). {@code dotColour} 0 = no dot.
     */
    private void setDot(TextView view, CharSequence text, int dotColour, int textColour) {
        view.setVisibility(View.VISIBLE);
        view.setText(text);
        view.setTextColor(ContextCompat.getColor(this, textColour));
        android.graphics.drawable.Drawable dot = null;
        if (dotColour != 0) {
            dot = ContextCompat.getDrawable(this, R.drawable.dot);
            if (dot != null) {
                dot = androidx.core.graphics.drawable.DrawableCompat.wrap(dot.mutate());
                androidx.core.graphics.drawable.DrawableCompat.setTint(dot, ContextCompat.getColor(this, dotColour));
                int size = getResources().getDimensionPixelSize(R.dimen.dot);
                dot.setBounds(0, 0, size, size);
            }
        }
        view.setCompoundDrawablesRelative(dot, null, null, null);
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
        String qaAction = getIntent().getStringExtra(EXTRA_QA_ACTION);
        if ("obb_copy".equals(qaAction)) {
            // The real copy-then-launch that follows the pre-checks (the emulator
            // has no activated container). qa_launch picks the stand-in game.
            main.postDelayed(this::prepareObbThenLaunch, 400L);
            return;
        }
        if ("permissions_copy".equals(qaAction)) {
            // Launch with permissions missing: ask for them, then the real copy
            // and launch (qa_launch picks the stand-in game).
            qaPermissionsThenCopy = true;
            main.postDelayed(() -> requestStorage(true), 400L);
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
                // Launch tapped after the activation / library checks, permissions missing.
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

    /** Indian Standard Time (UTC+5:30, no daylight saving): the zone of every shown expiry. */
    static final TimeZone IST = TimeZone.getTimeZone("Asia/Kolkata");
    /** Same text as the panel's humanDate(): "14 Nov 2026, 10:30 PM IST". */
    static final String EXPIRY_PATTERN = "d MMM yyyy, h:mm a 'IST'";

    /** A formatter that shows Indian Standard Time in English, like the panel. */
    static SimpleDateFormat istFormat(String pattern) {
        SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.US);
        format.setTimeZone(IST);
        return format;
    }

    /**
     * The panel sends the expiry instant as ISO-8601 UTC ("2026-11-14T17:00:00.000Z").
     * Parsing gives the real instant - the countdown uses it as is; only the
     * text is shown in IST. A zone-less "yyyy-MM-dd HH:mm:ss" is UTC, the
     * panel's storage rule.
     */
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

    /**
     * Game chip: Not installed, Running, Ready (everything Launch needs is in
     * place) or Installed (BGMI is there, but the library, a permission or the
     * container is still missing - Launch sorts those out or explains).
     */
    private void refreshGameState() {
        gameInstalled = isInstalled(PKG_BGMI) || qaLaunch != null;
        if (!gameInstalled) gameRunning = false;
        String gameVersion = installedVersionName(PKG_BGMI);
        gameTitle.setText(R.string.game_name);
        gameTitle.setContentDescription(gameVersion != null ? getString(R.string.game_title_version, gameVersion) : null);
        if (gameVersion != null) {
            gameVersionText.setText(getString(R.string.splash_version, gameVersion));
            gameVersionText.setVisibility(View.VISIBLE);
        } else {
            gameVersionText.setVisibility(View.GONE);
        }
        if (!gameInstalled) {
            setDot(gameStateValue, getString(R.string.state_not_installed), R.color.warning, R.color.text_primary);
        } else if (gameRunning) {
            setDot(gameStateValue, getString(R.string.state_running), R.color.success, R.color.text_primary);
        } else if (readyToLaunch()) {
            setDot(gameStateValue, getString(R.string.state_ready), R.color.success, R.color.text_primary);
        } else {
            // Installed, but the library, a permission or the container isn't ready.
            setDot(gameStateValue, getString(R.string.state_installed), R.color.warning, R.color.text_primary);
        }
        refreshPrimary();
    }

    // ------------------------------------------------------------------ help

    /** Help sheet: three short tips, Copy support info (preview first) and the Telegram link. */
    private void showHelp() {
        if (helpSheet != null && helpSheet.isShowing()) return;
        View sheetView = LayoutInflater.from(this).inflate(R.layout.sheet_help, null, false);
        BottomSheetDialog sheet = Ui.sheet(this, sheetView, true);
        helpSheet = sheet;
        sheet.setOnDismissListener(d -> {
            if (helpSheet == sheet) helpSheet = null;
        });
        sheetView.findViewById(R.id.helpCopySupport).setOnClickListener(v -> {
            Ui.tap(v);
            sheet.dismiss();
            showSupportInfo();
        });
        sheetView.findViewById(R.id.helpTelegram).setOnClickListener(v -> {
            Ui.tap(v);
            sheet.dismiss();
            Ui.openUrl(this, getString(R.string.telegram_url));
        });
        sheetView.findViewById(R.id.helpGotIt).setOnClickListener(v -> {
            Ui.tap(v);
            sheet.dismiss();
        });
        Log.d(TAG, "Help: sheet shown");
        sheet.show();
    }

    /** Shows exactly what "Copy support info" copies; nothing leaves the phone unless the user pastes it. */
    private void showSupportInfo() {
        String info = supportInfo();
        View sheetView = LayoutInflater.from(this).inflate(R.layout.sheet_support_info, null, false);
        ((TextView) sheetView.findViewById(R.id.supportInfoText)).setText(dimKeys(info));
        // The block scrolls inside once it passes ~45 % of the screen height.
        View scroll = sheetView.findViewById(R.id.supportInfoScroll);
        int maxHeight = Math.round(getResources().getDisplayMetrics().heightPixels * 0.45f);
        scroll.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (v.getHeight() > maxHeight) {
                v.getLayoutParams().height = maxHeight;
                v.post(v::requestLayout);
            }
        });
        BottomSheetDialog sheet = Ui.sheet(this, sheetView, true);
        MaterialButton copy = sheetView.findViewById(R.id.supportInfoCopy);
        copy.setOnClickListener(v -> {
            Ui.tap(v);
            boolean copied = false;
            try {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.support_info_title), info));
                    copied = true;
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "Help: clipboard unavailable", e);
            }
            if (!copied) {
                sheet.dismiss();
                return;
            }
            // The button itself confirms: check + "Copied" for ~800 ms, then close.
            Log.d(TAG, "Help: support info copied");
            Ui.setSuccess(copy, getString(R.string.support_info_copied_short));
            copy.announceForAccessibility(getString(R.string.support_info_copied));
            main.postDelayed(() -> {
                if (sheet.isShowing()) sheet.dismiss();
            }, preview && getIntent().getBooleanExtra(EXTRA_QA_FREEZE_CLOCK, false) ? 4000L : 800L);
        });
        sheetView.findViewById(R.id.supportInfoCancel).setOnClickListener(v -> sheet.dismiss());
        sheet.show();
    }

    /** Support block: the "Key:" part of each line in secondary, values in primary. */
    private CharSequence dimKeys(String info) {
        android.text.SpannableString text = new android.text.SpannableString(info);
        int secondary = ContextCompat.getColor(this, R.color.text_secondary);
        int start = 0;
        for (String line : info.split("\n", -1)) {
            int colon = line.indexOf(": ");
            if (colon > 0) {
                text.setSpan(new android.text.style.ForegroundColorSpan(secondary), start, start + colon + 1,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            start += line.length() + 1;
        }
        return text;
    }

    /**
     * Device and app facts for support. Since 2.3.1 it includes the licence
     * key, its status and the IST expiry (the sheet says so); never the
     * username, file paths, secrets or anything else from the panel.
     */
    private String supportInfo() {
        StringBuilder b = new StringBuilder();
        b.append("Anoy ").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(")\n");
        String library = null;
        try {
            library = LibManager.installedVersion(this);
        } catch (RuntimeException ignored) {
            // Unknown is fine.
        }
        b.append("Library: ").append(library != null && !library.isEmpty() ? library : "not installed").append('\n');
        b.append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        b.append("Device: ").append(deviceName()).append('\n');
        b.append("Game: ").append(gameStateValue.getText()).append('\n');
        StringBuilder missing = new StringBuilder();
        for (StorageAccess.Need need : StorageAccess.Need.values()) {
            if (StorageAccess.has(this, need)) continue;
            if (missing.length() > 0) missing.append(", ");
            missing.append(need == StorageAccess.Need.ALL_FILES ? "All files access"
                    : need == StorageAccess.Need.FILES ? "Files" : "Install unknown apps");
        }
        b.append("Access: ").append(missing.length() == 0 ? "allowed" : "missing " + missing).append('\n');
        // 2.3.1: the key, its status and expiry are included (support asks for them).
        String key = licenceKey();
        b.append("Licence key: ").append(key.isEmpty() ? "none" : key).append('\n');
        b.append("Licence status: ").append(lookWord()).append('\n');
        b.append("Expires (IST): ").append(expiryMillis > 0
                ? istFormat("d MMM yyyy, h:mm a").format(new Date(expiryMillis)) : "unknown");
        if (statusMessage.getVisibility() == View.VISIBLE && statusMessage.getText().length() > 0) {
            b.append("\nStatus: ").append(redactStatus(statusMessage.getText().toString()));
        }
        return b.toString();
    }

    /** Drops keys, file paths, links and e-mail addresses from a status line and shortens it. */
    static String redactStatus(String text) {
        if (text == null) return "";
        String s = text.replaceAll("(?i)\\bANOY-[A-Z0-9]+-[A-Z0-9]+\\b", "[key]")
                .replaceAll("(?i)\\b[a-z][a-z0-9+.-]*://\\S+", "[link]")
                .replaceAll("\\S+@\\S+\\.\\S+", "[email]")
                .replaceAll("(?:/[^\\s/]+){2,}/?", "[path]")
                .replaceAll("\\s+", " ")
                .trim();
        if (s.length() > SUPPORT_STATUS_MAX) s = s.substring(0, SUPPORT_STATUS_MAX - 1).trim() + "\u2026";
        return s;
    }

    /** Library installed, the three permissions granted and the container activated. */
    private boolean readyToLaunch() {
        if (LibManager.installedVersion(this) == null) return false;
        if (StorageAccess.firstMissing(this) != null) return false;
        if (preview) return true; // Sample screen: the emulator has no activated container.
        try {
            return MetaActivationManager.getActivationStatus();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Keeps Launch / Stop honest while home is visible: the game can be closed
     * (or started) outside Anoy. Two misses in a row are needed before
     * "Running" is dropped, so a brief blip from the container changes nothing,
     * and a pending launch is left to its own confirmation. Never shows an error.
     */
    private void reconcileRunning() {
        if (primaryAction == null || busy || launchPending || !gameInstalled || expired) {
            runningMisses = 0;
            runningHits = 0;
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now < runningSettleUntil) return; // Just launched / stopped: let the container catch up.
        boolean running = containerRunning();
        if (running) {
            runningMisses = 0;
            seenRunningSinceLaunch = true;
            if (!gameRunning && ++runningHits >= 2) {
                runningHits = 0;
                Log.d(TAG, "Game: running outside Anoy, showing Stop");
                gameRunning = true;
                if (launchErrorShown) hideMessage();
                refreshGameState();
            }
            return;
        }
        runningHits = 0;
        if (!gameRunning) {
            runningMisses = 0;
            return;
        }
        // Until the container has reported the game once, give it time to start.
        if (!seenRunningSinceLaunch && now - gameLaunchedAt < RUNNING_FIRST_SEEN_MS) return;
        if (++runningMisses >= 2) {
            runningMisses = 0;
            Log.d(TAG, "Game: closed outside Anoy, back to Launch");
            gameRunning = false;
            refreshGameState();
        }
    }

    private void refreshLibrary() {
        String version = LibManager.installedVersion(this);
        libraryValue.setText(version != null ? getString(R.string.library_version, version)
                : getString(R.string.library_not_installed));
        libraryValue.setTextColor(ContextCompat.getColor(this,
                version != null ? R.color.text_tertiary : R.color.warning));
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
        if (expired) {
            label = R.string.sign_in_again;
        } else if (!gameInstalled) {
            label = R.string.install_game;
        } else if (gameRunning) {
            label = R.string.stop;
        } else {
            label = R.string.launch;
        }
        Ui.setLoading(primaryAction, false, getString(label));
        // Running: Stop is surface-2 with danger text - never a red slab.
        boolean stop = gameRunning && !expired && gameInstalled;
        primaryAction.setBackgroundTintList(ContextCompat.getColorStateList(this,
                stop ? R.color.tonal_bg_on_black : R.color.button_bg));
        primaryAction.setTextColor(ContextCompat.getColorStateList(this, stop ? R.color.danger_text : R.color.button_text));
        // 2.3.2: a play / stop glyph before Launch / Stop (Lucide, 20 dp, 8 dp gap,
        // tinted like the label). The icon and label stay centred as one group
        // (iconGravity textStart); other labels have none, busy shows the spinner.
        if (stop || label == R.string.launch) {
            primaryAction.setIconResource(stop ? R.drawable.ic_stop : R.drawable.ic_play);
            primaryAction.setIconSize(getResources().getDimensionPixelSize(R.dimen.icon_inline));
            primaryAction.setIconPadding(Ui.dp(this, 8));
            primaryAction.setIconTint(ContextCompat.getColorStateList(this,
                    stop ? R.color.danger_text : R.color.button_text));
        }
    }

    private void setBusy(boolean value, CharSequence label) {
        busy = value;
        if (!value) {
            cancelPrepTimers();
            // Idle: a hidden status line gives its reserved space back.
            if (statusMessage.getVisibility() == View.INVISIBLE) statusMessage.setVisibility(View.GONE);
        }
        if (value) {
            primaryAction.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.button_bg));
            primaryAction.setTextColor(ContextCompat.getColorStateList(this, R.color.button_text));
            // Back to the spinner's own size and tint (Launch / Stop use a 20 dp glyph).
            primaryAction.setIconSize(getResources().getDimensionPixelSize(R.dimen.button_progress));
            primaryAction.setIconTint(ContextCompat.getColorStateList(this, R.color.button_text));
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
        // 2.3.4 (text only; the copy itself is unchanged): nothing new for the
        // first 400 ms, then the line. The very first copy explains itself;
        // 15 s without a new percent says it is still going.
        cancelPrepTimers();
        statusMessage.setVisibility(View.INVISIBLE);
        final boolean firstCopy = !OBB_PREPARED_YES.equals(new Prefs(this).getSt(PREF_OBB_PREPARED, ""));
        main.postDelayed(prepShow = () -> {
            if (!Ui.alive(MAct.this) || !busy) return;
            showMessage(getString(firstCopy ? R.string.preparing_first : R.string.preparing), false);
        }, Ui.QUIET_MS);
        main.postDelayed(prepSlow, PREP_SLOW_MS);
        new AppManager(this).copyObbAsync(PKG_BGMI, new AppManager.CopyCallback() {
            private int lastPercent = -1;

            @Override
            public void onProgressUpdate(int percent) {
                if (!Ui.alive(MAct.this)) return;
                showProgress(percent, false);
                primaryAction.setText(getString(R.string.action_preparing_progress, percent));
                if (percent != lastPercent) {
                    lastPercent = percent;
                    main.removeCallbacks(prepSlow);
                    main.postDelayed(prepSlow, PREP_SLOW_MS);
                }
            }

            @Override
            public void onCopyCompleted(boolean success, String error) {
                if (!Ui.alive(MAct.this)) return;
                cancelPrepTimers();
                if (success) new Prefs(MAct.this).setSt(PREF_OBB_PREPARED, OBB_PREPARED_YES);
                hideProgress();
                if (!success && getString(R.string.obb_folder_blocked).equals(error)
                        && StorageAccess.installGrantedThisRun(MAct.this)) {
                    // Install unknown apps was just granted: Android opens the
                    // game-files folder only to a freshly started app.
                    restartForGameFiles();
                    return;
                }
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

    /** Stops the "Preparing" line's 400 ms and 15 s timers. */
    private void cancelPrepTimers() {
        if (prepShow != null) main.removeCallbacks(prepShow);
        prepShow = null;
        main.removeCallbacks(prepSlow);
    }

    /**
     * Restarts Anoy once so the new process can read the game-files folder,
     * then Launch continues by itself when home opens again (after sign in).
     */
    private void restartForGameFiles() {
        Log.d(TAG, "Launch: restarting to finish setting up game files");
        StorageAccess.markLaunchAfterRestart(this);
        setBusy(true, getString(R.string.action_preparing));
        showMessage(getString(R.string.restarting_for_game_files), false);
        main.postDelayed(() -> {
            Intent restart = Intent.makeRestartActivityTask(new ComponentName(this, SplashActivity.class));
            startActivity(restart);
            Runtime.getRuntime().exit(0);
        }, RESTART_DELAY_MS);
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
            // Debug stand-in: nothing runs before Launch; a started game runs
            // until qa_game_ms (if given) has passed.
            if (qaLaunchAt <= 0) return false;
            long since = SystemClock.elapsedRealtime() - qaLaunchAt;
            if (qaGameMs > 0 && since >= qaGameMs) return false;
            if ("null_running".equals(qaLaunch)) return since > 1200L;
            return "started".equals(qaLaunch) || "started_polish_throws".equals(qaLaunch);
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
        gameLaunchedAt = SystemClock.elapsedRealtime();
        runningSettleUntil = gameLaunchedAt + RUNNING_SETTLE_MS;
        seenRunningSinceLaunch = false;
        runningMisses = 0;
        runningHits = 0;
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
            runningSettleUntil = SystemClock.elapsedRealtime() + RUNNING_SETTLE_MS;
            runningMisses = 0;
            runningHits = 0;
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
        if (busy || gameRunning || (gate != null && gate.isShowing())
                || (redownloadGate != null && redownloadGate.isShowing())) return;
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

    /** Caption above Launch; errors are danger text with a REJECT haptic. */
    private void showMessage(String text, boolean error) {
        launchErrorShown = false;
        statusMessage.animate().cancel();
        statusMessage.setAlpha(1f);
        statusMessage.setTag(R.id.tag_swap_text, null);
        statusMessage.setText(text);
        statusMessage.setTextColor(ContextCompat.getColor(this, error ? R.color.danger : R.color.text_secondary));
        if (error) Ui.rejectHaptic(statusMessage);
        Ui.fade(statusMessage, true);
    }

    /** While a task runs the line keeps its space (INVISIBLE), so nothing jumps. */
    private void hideMessage() {
        launchErrorShown = false;
        cancelPrepTimers();
        if (busy) {
            Ui.fadeReserved(statusMessage, false);
        } else {
            Ui.fade(statusMessage, false);
        }
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

    /** The installed version name of a package, or null. */
    private String installedVersionName(String pkg) {
        try {
            String name = getPackageManager().getPackageInfo(pkg, 0).versionName;
            return name == null || name.trim().isEmpty() ? null : name.trim();
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        } catch (RuntimeException e) {
            Log.w(TAG, "Package lookup failed", e);
            return null;
        }
    }

    // --------------------------------------------------------- header + icon

    /**
     * "Welcome back" kicker over the user's display name when the panel sends
     * one. The /connect payload has no name today (only expiry, status and the
     * note), so the title itself reads "Welcome back" and the kicker stays
     * hidden - never "Anoy", never the key, never an invented name.
     */
    private void setupHeader() {
        // 2.3.0: the kicker is the product name (ANOY HAX), the title "Welcome back".
        String name = displayName();
        TextView title = findViewById(R.id.homeTitle);
        title.setText(name != null ? name : getString(R.string.home_title));
    }

    /** No name field in the session payload yet: see setupHeader. */
    private String displayName() {
        return null;
    }

    // ---------------------------------------------------------------- settings

    private void setupSettings() {
        settingsPage = findViewById(R.id.settingsPage);
        actionBarView = findViewById(R.id.actionBar);
        licenceKeyValue = findViewById(R.id.licenceKeyValue);
        licenceKeyEye = findViewById(R.id.licenceKeyEye);
        licenceStatusValue = findViewById(R.id.licenceStatusValue);
        licenceExpiresValue = findViewById(R.id.licenceExpiresValue);
        libraryVersionValue = findViewById(R.id.libraryVersionValue);
        checkUpdateSpinner = findViewById(R.id.checkUpdateSpinner);
        checkUpdateIcon = findViewById(R.id.checkUpdateIcon);
        checkUpdateValue = findViewById(R.id.checkUpdateValue);
        checkUpdateGlyph = findViewById(R.id.checkUpdateGlyph);
        checkUpdateCaption = findViewById(R.id.checkUpdateCaption);
        renderAppCheckCaption();
        if (statusLook >= 0) applyLook(licenceStatusValue, statusLook);
        licenceKeyEye.setOnClickListener(v -> {
            Ui.tap(v);
            keyRevealed = !keyRevealed;
            main.removeCallbacks(restoreKey);
            renderLicenceKey();
        });
        findViewById(R.id.licenceKeyRow).setOnClickListener(v -> {
            Ui.tap(v);
            copyLicenceKey();
        });
        findViewById(R.id.checkUpdateRow).setOnClickListener(v -> {
            Ui.tap(v);
            checkAppUpdate();
        });
        findViewById(R.id.redownloadRow).setOnClickListener(v -> {
            Ui.tap(v);
            confirmRedownload();
        });
        ((TextView) findViewById(R.id.appVersionValue)).setText(getString(R.string.settings_version_value,
                BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE));
        // Insets: mainRoot pads the status bar and sides; setupInsets pads the bottom.
        Ui.maxWidth(findViewById(R.id.settingsContent), 600);
        findViewById(R.id.settingsBack).setOnClickListener(v -> {
            Ui.tap(v);
            closeSettings();
        });
        findViewById(R.id.settingsTelegram).setOnClickListener(v -> {
            Ui.tap(v);
            Ui.openUrl(this, getString(R.string.telegram_url));
        });
        findViewById(R.id.settingsCopySupport).setOnClickListener(v -> {
            Ui.tap(v);
            showSupportInfo();
        });
        settingsBack = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                closeSettings();
            }
        };
        getOnBackPressedDispatcher().addCallback(this, settingsBack);
    }

    // ------------------------------------------------------ settings: licence

    /** The saved key (debug preview: the QA key when none is saved). */
    private String licenceKey() {
        String key = new Prefs(this).getSt(PREF_USER, "");
        if ((key == null || key.trim().isEmpty()) && preview) key = "ANOY-30D-QA7K2";
        return key == null ? "" : key.trim();
    }

    /** "ANOY••••••A7K2": the first and last four characters, a bullet for each one between. */
    static String maskKey(String key) {
        if (key == null || key.isEmpty()) return "";
        int n = key.length();
        StringBuilder b = new StringBuilder(n);
        if (n <= 8) {
            for (int i = 0; i < n; i++) b.append('\u2022');
            return b.toString();
        }
        b.append(key, 0, 4);
        for (int i = 4; i < n - 4; i++) b.append('\u2022');
        b.append(key, n - 4, n);
        return b.toString();
    }

    /** Licence key row: masked (or revealed by the eye) in Mono; the eye hides with no key. */
    private void renderLicenceKey() {
        if (licenceKeyValue == null) return;
        String key = licenceKey();
        boolean has = !key.isEmpty();
        licenceKeyValue.setText(has ? (keyRevealed ? key : maskKey(key)) : getString(R.string.dash));
        licenceKeyValue.setTextColor(ContextCompat.getColor(this, has ? R.color.text_secondary : R.color.text_tertiary));
        licenceKeyEye.setVisibility(has ? View.VISIBLE : View.INVISIBLE);
        licenceKeyEye.setImageResource(keyRevealed ? R.drawable.ic_eye_off : R.drawable.ic_eye);
        licenceKeyEye.setContentDescription(getString(keyRevealed ? R.string.licence_key_hide : R.string.licence_key_show));
        View row = findViewById(R.id.licenceKeyRow);
        row.setEnabled(has);
        row.setContentDescription(!has ? getString(R.string.licence_key)
                : keyRevealed ? getString(R.string.licence_key_shown_description, key)
                : getString(R.string.licence_key_hidden_description));
    }

    /** Tapping the row copies the full key; the value says "Copied" for ~1.2 s. */
    private void copyLicenceKey() {
        String key = licenceKey();
        if (key.isEmpty()) return;
        try {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null) return;
            ClipData clip = ClipData.newPlainText(getString(R.string.licence_key), key);
            if (Build.VERSION.SDK_INT >= 33) {
                // Android 13+: the system's clipboard preview shows dots, not the key.
                android.os.PersistableBundle extras = new android.os.PersistableBundle();
                extras.putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true);
                clip.getDescription().setExtras(extras);
            }
            clipboard.setPrimaryClip(clip);
        } catch (RuntimeException e) {
            Log.w(TAG, "Settings: clipboard unavailable", e);
            return;
        }
        Log.d(TAG, "Settings: licence key copied");
        licenceKeyValue.setText(R.string.licence_key_copied);
        licenceKeyValue.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        licenceKeyValue.announceForAccessibility(getString(R.string.licence_key_copied));
        main.removeCallbacks(restoreKey);
        main.postDelayed(restoreKey, preview && getIntent().getBooleanExtra(EXTRA_QA_FREEZE_CLOCK, false)
                ? 6000L : KEY_COPIED_MS);
    }

    // ------------------------------------------------------ settings: updates

    private void refreshLibraryVersion() {
        if (libraryVersionValue == null) return;
        String version = null;
        try {
            version = LibManager.installedVersion(this);
        } catch (RuntimeException ignored) {
            // Unknown: shown as not installed.
        }
        boolean has = version != null && !version.isEmpty();
        libraryVersionValue.setText(has ? version : getString(R.string.settings_library_none));
        libraryVersionValue.setTextColor(ContextCompat.getColor(this, has ? R.color.text_secondary : R.color.warning));
    }

    /**
     * Check for app updates: the splash's check (AppUpdater.check). Newer
     * versionCode - the usual update screen with its rules (mandatory = no
     * skip); none - "Up to date" with a check for a few seconds; any failure -
     * "Couldn't check" (never treated as up to date).
     */
    private void checkAppUpdate() {
        if (appCheckRunning) return;
        appCheckRunning = true;
        final int run = ++appCheckRun;
        main.removeCallbacks(clearAppCheck);
        showAppCheck(APP_CHECK_CHECKING, null);
        main.removeCallbacks(slowAppCheck);
        main.postDelayed(slowAppCheck, Ui.SLOW_MS);
        Log.i(TAG, "Settings: checking for an app update");
        final String qa = preview ? getIntent().getStringExtra(EXTRA_QA_APP_CHECK) : null;
        if (appCheckExecutor == null) {
            appCheckExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "anoy-app-check"));
        }
        try {
            appCheckExecutor.execute(() -> {
                AppUpdater.Release found = null;
                String error = null;
                try {
                    if (qa != null) {
                        SystemClock.sleep(600L);
                        if ("failed".equals(qa)) throw new UpdateException(getString(R.string.net_offline), true);
                        if ("available".equals(qa)) {
                            found = new AppUpdater.Release("2.4.0", BuildConfig.VERSION_CODE + 1,
                                    "https://github.com/masterace572-prog/AnoyMundo/releases/download/sample/Anoy.apk",
                                    "Faster start-up\nClearer update messages", false);
                        }
                    } else {
                        found = AppUpdater.check(this);
                    }
                } catch (UpdateException e) {
                    Log.w(TAG, "Settings: app update check failed: " + e.getMessage());
                    error = e.getMessage() == null ? "" : e.getMessage();
                } catch (RuntimeException e) {
                    Log.e(TAG, "Settings: app update check crashed", e);
                    error = "";
                }
                final AppUpdater.Release release = found;
                final String failure = error;
                main.post(() -> onAppChecked(run, release, failure));
            });
        } catch (RejectedExecutionException e) {
            appCheckRunning = false;
            showAppCheck(APP_CHECK_IDLE, null);
        }
    }

    /** Debug QA (frozen clock): transient row states stay long enough for a uiautomator dump. */
    private long qaHold(long normalMs) {
        return preview && getIntent().getBooleanExtra(EXTRA_QA_FREEZE_CLOCK, false) ? Math.max(normalMs, 8000L) : normalMs;
    }

    private void onAppChecked(int run, AppUpdater.Release release, String failure) {
        if (run != appCheckRun || !Ui.alive(this)) return;
        appCheckRunning = false;
        main.removeCallbacks(slowAppCheck);
        // The caption keeps the last good time; a failure doesn't change it.
        renderAppCheckCaption();
        if (failure != null) {
            showAppCheck(APP_CHECK_FAILED, failure);
            main.postDelayed(clearAppCheck, qaHold(APP_CHECK_FAILED_MS));
            return;
        }
        if (release == null) {
            Log.i(TAG, "Settings: app is up to date (" + BuildConfig.VERSION_CODE + ")");
            showAppCheck(APP_CHECK_CURRENT, null);
            main.postDelayed(clearAppCheck, qaHold(APP_CHECK_CURRENT_MS));
            return;
        }
        Log.i(TAG, "Settings: app update available: " + release.versionName + " (" + release.versionCode + ")");
        showAppCheck(APP_CHECK_IDLE, null);
        Intent intent = new Intent(this, AppUpdateActivity.class);
        release.writeTo(intent);
        intent.putExtra(AppUpdateActivity.EXTRA_FROM_SETTINGS, true);
        if (preview) intent.putExtra(AppUpdateActivity.EXTRA_QA_PREVIEW, true);
        startActivity(intent);
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
    }

    /** The check row's trailing part: chevron, spinner + "Checking…", check + "Up to date", or "Couldn't check". */
    private void showAppCheck(int state, String reason) {
        if (checkUpdateValue == null) return;
        checkUpdateValue.animate().cancel();
        checkUpdateValue.setAlpha(1f);
        checkUpdateValue.setTag(R.id.tag_swap_text, null);
        checkUpdateSpinner.setVisibility(state == APP_CHECK_CHECKING ? View.VISIBLE : View.GONE);
        checkUpdateIcon.setVisibility(state == APP_CHECK_CURRENT ? View.VISIBLE : View.GONE);
        checkUpdateGlyph.setVisibility(state == APP_CHECK_IDLE ? View.VISIBLE : View.GONE);
        if (state == APP_CHECK_IDLE) {
            checkUpdateValue.setVisibility(View.GONE);
            checkUpdateValue.setText("");
            findViewById(R.id.checkUpdateRow).setContentDescription(null);
            return;
        }
        int text = state == APP_CHECK_CHECKING ? R.string.settings_checking
                : state == APP_CHECK_CURRENT ? R.string.settings_up_to_date : R.string.settings_check_failed;
        checkUpdateValue.setText(text);
        checkUpdateValue.setTextColor(ContextCompat.getColor(this, state == APP_CHECK_FAILED ? R.color.warning
                : state == APP_CHECK_CHECKING ? R.color.text_tertiary : R.color.text_secondary));
        checkUpdateValue.setVisibility(View.VISIBLE);
        String spoken = getString(R.string.settings_row_description, getString(R.string.settings_check_updates),
                getString(text));
        if (checkUpdateCaption != null) spoken += ". " + checkUpdateCaption.getText();
        if (state == APP_CHECK_FAILED && reason != null && !reason.isEmpty()) spoken += ". " + reason;
        findViewById(R.id.checkUpdateRow).setContentDescription(spoken);
    }

    /** "Last checked 12 min ago" from the last successful check (splash or Settings), IST. */
    private void renderAppCheckCaption() {
        if (checkUpdateCaption == null) return;
        long at = AppUpdater.lastCheckedAt(this);
        checkUpdateCaption.setText(at > 0 ? getString(R.string.licence_last_checked, Ui.lastChecked(this, at))
                : getString(R.string.settings_not_checked));
    }

    /** Re-download library: a confirmation first, then the usual library sheet. */
    private void confirmRedownload() {
        if (busy || launchPending || (redownloadGate != null && redownloadGate.isShowing())
                || (gate != null && gate.isShowing())) {
            return;
        }
        View sheetView = LayoutInflater.from(this).inflate(R.layout.sheet_redownload, null, false);
        BottomSheetDialog sheet = Ui.sheet(this, sheetView, true);
        sheetView.findViewById(R.id.redownloadConfirm).setOnClickListener(v -> {
            Ui.tap(v);
            sheet.dismiss();
            startRedownload();
        });
        sheetView.findViewById(R.id.redownloadCancel).setOnClickListener(v -> sheet.dismiss());
        sheet.show();
    }

    private void startRedownload() {
        if (busy || launchPending) return;
        Log.i(TAG, "Settings: re-downloading the library");
        if (redownloadGate != null) redownloadGate.dispose();
        redownloadGate = UpdateGate.redownload(this, () -> {
            // Installed or not (a failure keeps the old library): show what is there now.
            refreshLibraryVersion();
            refreshLibrary();
            refreshGameState();
        });
    }

    private void openSettings() {
        if (settingsPage == null || settingsPage.getVisibility() == View.VISIBLE) return;
        Log.d(TAG, "Settings: open");
        // The key is always masked again when Settings opens.
        keyRevealed = false;
        main.removeCallbacks(restoreKey);
        renderLicenceKey();
        refreshLibraryVersion();
        renderAppCheckCaption();
        ((android.widget.ScrollView) findViewById(R.id.settingsScroll)).scrollTo(0, 0);
        // 12 dp horizontal + fade, 280 ms decelerate; instant with animations off.
        settingsPage.animate().cancel();
        settingsPage.setVisibility(View.VISIBLE);
        if (Ui.reducedMotion(this)) {
            settingsPage.setAlpha(1f);
            settingsPage.setTranslationX(0f);
        } else {
            settingsPage.setAlpha(0f);
            settingsPage.setTranslationX(Ui.dp(this, 12));
            settingsPage.animate().alpha(1f).translationX(0f).setDuration(Ui.MEDIUM)
                    .setInterpolator(Ui.ENTER).start();
        }
        // Launch / Stop stay on home only.
        actionBarView.setVisibility(View.INVISIBLE);
        settingsBack.setEnabled(true);
    }

    private void closeSettings() {
        if (settingsPage == null || settingsPage.getVisibility() != View.VISIBLE) return;
        Log.d(TAG, "Settings: close");
        settingsBack.setEnabled(false);
        settingsPage.animate().cancel();
        actionBarView.setVisibility(View.VISIBLE);
        if (Ui.reducedMotion(this)) {
            settingsPage.setVisibility(View.GONE);
        } else {
            settingsPage.animate().alpha(0f).translationX(Ui.dp(this, 12)).setDuration(Ui.SHORT)
                    .setInterpolator(Ui.EXIT).withEndAction(() -> {
                        settingsPage.setVisibility(View.GONE);
                        settingsPage.setAlpha(1f);
                        settingsPage.setTranslationX(0f);
                    }).start();
        }
        main.removeCallbacks(ticker);
        main.post(ticker);
    }
}
