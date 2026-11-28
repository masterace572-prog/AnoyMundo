package com.xlox;

import android.annotation.SuppressLint;
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
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;

import com.xlox.ui.Ui;
import com.xlox.ui.StatusText;
import com.xlox.ui.HomeSettingsPager;
import com.xlox.ui.HomeSettingsNavigation;
import com.xlox.ui.PermissionSheet;
import com.xlox.ui.UpdateGate;
import com.xlox.utils.AppManager;
import com.xlox.utils.AppUpdater;
import com.xlox.utils.GameSupport;
import com.xlox.utils.GameTarget;
import com.xlox.utils.GameLoginRouter;
import com.xlox.utils.GameRunState;
import com.xlox.utils.ConcurrentLaunchGuard;
import com.xlox.utils.ConfigGuard;
import com.xlox.utils.CrashLogger;
import com.xlox.utils.SplitDepsFix;
import com.xlox.utils.GoogleServicesInstaller;
import com.xlox.utils.LibManager;
import com.xlox.utils.Net;
import com.xlox.utils.AccountTrace;
import com.xlox.utils.PmCompatShim;
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
import net_62v.external.MetaPackageManager;

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
    private static final String PKG_BGMI = GameTarget.BGMI.packageName;
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
    private static final String STATE_LAUNCH_GAME = "launch_game_package";
    private static final String STATE_PAGE = "home_settings_page";
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
    /** Licence refresh: on return to home at most this often... */
    private static final long LICENCE_REFRESH_MIN_MS = 60_000L;
    /** ...and quietly this often while home stays open. */
    private static final long LICENCE_REFRESH_EVERY_MS = 5 * 60_000L;
    /** Debug runs refresh on every return after this short pause. */
    private static final long QA_LICENCE_REFRESH_MIN_MS = 2000L;
    /** The panel's answer for a key that has run out. */
    private static final String REASON_EXPIRED = "EXPIRED KEY";
    /** Panel reason for a banned key ("USER BLOCKED"): signs the phone out. */
    private static final String REASON_BLOCKED = "BLOCKED";
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
    /** Debug launch stand-in target; full package name, BGMI by default. */
    public static final String EXTRA_QA_GAME = "qa_game";

    private final Handler main = new Handler(Looper.getMainLooper());

    private TextView panelNote;
    private View panelNoteCard;
    /** 2.3.5: "BGMI updated" warning (installed BGMI newer than the library supports). */
    private View gameUpdateCard;
    private TextView gameUpdateText;
    private View licenceSummary;
    private int statusLook = -1;
    private TextView statusValue;
    private String formattedExpiry;
    /** Compact header expiry; the exact IST date remains in Settings and TalkBack. */
    private TextView timeLeftValue;
    private TextView gameVersionText;
    private TextView gameStateValue;
    /** Retained Home / Settings pages; only Home owns the two game controls. */
    private HomeSettingsNavigation navigation;
    private boolean homeStarted;
    private TextView gameTitle;
    private OnBackPressedCallback settingsBack;
    /** Read-only OBB inspection never blocks touch/scroll dispatch. */
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
    private TextView serverValue;
    private MaterialButton primaryAction;

    private static final class GameSession {
        final GameTarget target;
        final GameRunState runtime = new GameRunState();
        TextView title;
        TextView version;
        TextView state;
        TextView message;
        LinearProgressIndicator progress;
        AppManager.ObbState obbState = AppManager.ObbState.UNKNOWN;
        boolean checkingObb;
        int obbRequest;
        MaterialButton action;
        boolean installed;
        long qaLaunchAt;

        GameSession(GameTarget target) { this.target = target; }
    }

    private final GameSession bgmi = new GameSession(GameTarget.BGMI);
    private final GameSession air = new GameSession(GameTarget.BGMI_AIR);
    private final GameSession[] games = {bgmi, air};
    /** Pinned for the whole copy/permission/launch transaction; controls cannot switch it mid-flight. */
    private GameSession launchTarget = bgmi;
    private GameSession qaLaunchTarget = bgmi;
    private BottomSheetDialog concurrentSheet;
    private Runnable concurrentTick;
    private boolean concurrentLaunchApproved;

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
    /** A launch is waiting for the container to confirm the game started. */
    private boolean launchPending;
    /** Anoy went to the background while a launch was pending (the game came up). */
    private boolean leftDuringLaunch;
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
        if (launchTarget.message.getVisibility() == View.VISIBLE) {
            Ui.swapText(launchTarget.message, getString(R.string.preparing_slow));
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
            // Both pager pages remain VISIBLE. Pause by selection / motion, not visibility.
            boolean onHome = navigation == null || navigation.homeIsSettled();
            if (homeStarted && !expired && expiryMillis > 0 && !qaFreezeClock && onHome) {
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
        gameUpdateCard = findViewById(R.id.gameUpdateCard);
        gameUpdateText = findViewById(R.id.gameUpdateText);
        licenceSummary = findViewById(R.id.licenceSummary);
        statusValue = findViewById(R.id.statusValue);
        timeLeftValue = findViewById(R.id.timeLeftValue);
        gameVersionText = findViewById(R.id.gameVersion);
        gameStateValue = findViewById(R.id.gameStateValue);
        serverValue = findViewById(R.id.serverValue);
        bgmi.message = findViewById(R.id.statusMessage);
        bgmi.progress = findViewById(R.id.taskProgress);
        air.message = findViewById(R.id.airStatusMessage);
        air.progress = findViewById(R.id.airTaskProgress);
        primaryAction = findViewById(R.id.primaryAction);
        bgmi.action = primaryAction;
        bgmi.title = findViewById(R.id.gameValue);
        bgmi.version = gameVersionText;
        bgmi.state = gameStateValue;
        air.action = findViewById(R.id.airPrimaryAction);
        air.title = findViewById(R.id.airGameValue);
        air.version = findViewById(R.id.airGameVersion);
        air.state = findViewById(R.id.airGameStateValue);
        if (preview) {
            qaLaunchTarget = sessionFor(GameTarget.fromPackage(getIntent().getStringExtra(EXTRA_QA_GAME)));
            launchTarget = qaLaunchTarget;
        }
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
        primaryAction.setOnClickListener(v -> onPrimaryAction(bgmi));
        air.action.setOnClickListener(v -> onPrimaryAction(air));

        if (preview && getIntent().hasExtra(EXTRA_QA_SAVED_KEY)) {
            // Debug: a saved key for the Sign out / Forget this key checks.
            new Prefs(this).setSt(PREF_USER, getIntent().getStringExtra(EXTRA_QA_SAVED_KEY));
        }
        String savedKey = new Prefs(this).getSt(PREF_USER, "");
        if (preview) {
            expiry = qaExpiry();
            savedKey = (BuildConfig.DISPLAY_BRAND.toUpperCase(Locale.US) + "-30D-QA7K2");
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
            launchTarget = sessionFor(GameTarget.fromPackage(savedInstanceState.getString(STATE_LAUNCH_GAME)));
        }
        GameTarget restartedGame = savedInstanceState == null ? StorageAccess.consumeGameAfterRestart(this) : null;
        if (restartedGame != null) {
            launchTarget = sessionFor(restartedGame);
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
        setupNavigation(savedInstanceState);
    }

    /** Status bar above both pages; only navigation owns the bottom inset. */
    private void setupInsets() {
        View root = findViewById(R.id.mainRoot);
        Ui.maxWidth(findViewById(R.id.mainContent), 600);
        View bottomNavigation = findViewById(R.id.bottomNavigationHost);
        Ui.onInsets(root, (bars, bottom, ime) -> {
            root.setPadding(bars.left, bars.top, bars.right, 0);
            Ui.padBottom(bottomNavigation, bars.bottom);
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
        homeStarted = true;
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
        homeStarted = false;
        maskLicenceKey();
        main.removeCallbacks(ticker);
        main.removeCallbacks(runningCheck);
        main.removeCallbacks(licenceTimer);
        if (launchPending) leftDuringLaunch = true;
        if (storagePromptOpen) leftForStoragePrompt = true;
        // Leaving the warning cancels it. A background timer can never authorize a launch.
        if (concurrentSheet != null) concurrentSheet.dismiss();
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
        refreshGameFiles();
        if (!preview) applyPanelStatus();
        reportLoaderError();
        maybeRecheckLibrary();
        maybeRefreshLicence(false);
        showCrashReport();
        showAirCrashIfPending();
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
        refreshPrimary();
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
        refreshGameFiles();
        refreshPrimary();
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
            refreshPrimary();
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
        refreshPrimary();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_STORAGE_PROMPT, storagePromptOpen);
        outState.putBoolean(STATE_LAUNCH_AFTER_STORAGE, launchAfterStorage);
        outState.putInt(STATE_PENDING_NEED, pendingNeed != null ? pendingNeed.ordinal() : -1);
        outState.putInt(STATE_ASKED_ROUND, askedThisRound);
        outState.putString(STATE_LAUNCH_GAME, launchTarget.target.packageName);
        if (navigation != null) outState.putInt(STATE_PAGE, navigation.currentPage());
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
        if (concurrentSheet != null) concurrentSheet.dismiss();
        if (permissionSheet != null) {
            permissionSheet.setOnDismissListener(null);
            permissionSheet.dismiss();
            permissionSheet = null;
        }
        if (gate != null) gate.dispose();
        if (redownloadGate != null) redownloadGate.dispose();
        if (appCheckExecutor != null) appCheckExecutor.shutdownNow();
        if (navigation != null) navigation.dispose();
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
        final String key = preview ? (BuildConfig.DISPLAY_BRAND.toUpperCase(Locale.US) + "-30D-QA7K2") : new Prefs(this).getSt(PREF_USER, "");
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
        } else if (!preview && result != null && result.toUpperCase(Locale.US).contains(REASON_BLOCKED)) {
            // Banned in the panel: sign out now and say why on the sign in page.
            Log.d(TAG, "Licence refreshed: the panel reports the key banned");
            openLogin(result);
            return;
        } else {
            Log.d(TAG, "Licence refresh failed, keeping the last known time: " + result);
            licenceStale = true;
        }
        renderLicenceChecked();
        main.removeCallbacks(ticker);
        main.post(ticker);
    }

    /** New expiry from the panel: header, Settings and Launch follow it. */
    private void setExpiry(String expiry) {
        expiryMillis = parseExpiryMillis(expiry);
        if (expiryMillis > 0) {
            formattedExpiry = expiryFormat.format(new Date(expiryMillis));
        } else if (hasExpiry(expiry)) {
            formattedExpiry = expiry;
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
        if (licenceExpiresValue != null) {
            String settingsExpiry = formattedExpiry != null ? formattedExpiry : getString(R.string.dash);
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

    /** Two useful units fit the header; exact expiry is available in Settings. */
    static String formatCountdown(long days, long hours, long minutes, long seconds) {
        if (days > 0) return String.format(Locale.US, "%dd %02dh", days, hours);
        if (hours > 0) return String.format(Locale.US, "%dh %02dm", hours, minutes);
        return String.format(Locale.US, "%dm %02ds", minutes, seconds);
    }

    private void showCountdownText(String text, int colourRes) {
        if (!text.contentEquals(timeLeftValue.getText())) timeLeftValue.setText(text);
        int colour = ContextCompat.getColor(this, colourRes);
        if (timeLeftValue.getCurrentTextColor() != colour) timeLeftValue.setTextColor(colour);
    }

    /** TalkBack reads the compact expiry summary as one sentence, once a minute. */
    private void announce(String sentence, long minuteKey) {
        if (minuteKey == lastA11yMinute) return;
        lastA11yMinute = minuteKey;
        licenceSummary.setContentDescription(sentence);
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
        StatusText.set(view, text, dotColour, textColour);
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

    /** One concise package-specific state; no server/library metadata in game cards. */
    private void refreshGameState() {
        for (GameSession game : games) {
            game.installed = isInstalled(game.target.packageName) || (qaLaunch != null && game == qaLaunchTarget);
            if (!game.installed && game.runtime.isRunning()) game.runtime.stopped(SystemClock.elapsedRealtime());
            String version = installedVersionName(game.target.packageName);
            game.title.setText(gameName(game));
            game.title.setContentDescription(version != null
                    ? getString(R.string.game_version_named, gameName(game), version) : null);
            game.version.setVisibility(version != null ? View.VISIBLE : View.GONE);
            if (version != null) game.version.setText(getString(R.string.splash_version, version));
            boolean available = libraryAvailable(game);
            if (game.runtime.isRunning()) {
                setDot(game.state, getString(R.string.state_running), R.color.success, R.color.text_primary);
            } else if (airUnsupportedOs(game)) {
                setDot(game.state, getString(R.string.game_air_os_unsupported), R.color.warning, R.color.text_secondary);
            } else if (game == air && !available) {
                // Its disabled button is the ONLY "Coming soon" copy on Home.
                game.state.setText("");
                game.state.setVisibility(View.GONE);
            } else if (!game.installed) {
                setDot(game.state, getString(R.string.state_not_installed), 0, R.color.text_secondary);
            } else if (!available) {
                setDot(game.state, getString(R.string.state_library_needed), R.color.warning, R.color.text_secondary);
            } else if (StorageAccess.firstMissing(this) != null
                    || game.obbState == AppManager.ObbState.ACCESS_REQUIRED) {
                setDot(game.state, getString(R.string.state_files_access), R.color.warning, R.color.text_secondary);
            } else if (game.obbState == AppManager.ObbState.MISSING) {
                setDot(game.state, getString(R.string.state_obb_missing), R.color.warning, R.color.text_secondary);
            } else if (readyToLaunch(game)) {
                setDot(game.state, getString(R.string.state_ready), R.color.success, R.color.text_primary);
            } else if (game.checkingObb) {
                setDot(game.state, getString(R.string.state_checking_files), 0, R.color.text_secondary);
            } else if (game.obbState == AppManager.ObbState.UNKNOWN) {
                setDot(game.state, getString(R.string.state_check_files), 0, R.color.text_secondary);
            } else {
                setDot(game.state, getString(R.string.state_installed), 0, R.color.text_secondary);
            }
        }
        showGameSupport(installedVersionName(PKG_BGMI));
        refreshPrimary();
    }

    /** Refresh on resume/access/library changes, never on a countdown tick or touch event. */
    private void refreshGameFiles() {
        for (GameSession game : games) {
            final int request = ++game.obbRequest;
            game.checkingObb = false;
            game.obbState = AppManager.ObbState.UNKNOWN;
            if (!game.installed || !libraryAvailable(game) || StorageAccess.firstMissing(this) != null) continue;
            if (preview) {
                game.obbState = AppManager.ObbState.AVAILABLE;
                continue;
            }
            if (!game.target.needsObb) {
                // BGMI Air carries no game files: nothing to inspect or copy.
                game.obbState = AppManager.ObbState.AVAILABLE;
                continue;
            }
            game.checkingObb = true;
            try {
                ioExecutor.execute(() -> {
                    AppManager.ObbState state = new AppManager(MAct.this).inspectObb(game.target.packageName);
                    main.post(() -> {
                        if (!Ui.alive(MAct.this) || game.obbRequest != request) return;
                        game.checkingObb = false;
                        game.obbState = state;
                        refreshGameState();
                    });
                });
            } catch (RejectedExecutionException ignored) {
                game.checkingObb = false; // Activity is shutting down; do not claim readiness.
            }
        }
        refreshGameState();
    }

    private GameSession sessionFor(GameTarget game) {
        return game == GameTarget.BGMI_AIR ? air : bgmi;
    }

    private String gameName(GameSession game) {
        return getString(game == air ? R.string.game_air_name : R.string.game_name);
    }

    private boolean libraryAvailable(GameSession game) {
        return (preview && qaLaunch != null && game == qaLaunchTarget)
                || LibManager.isUsableLib(this, game.target);
    }

    /**
     * BGMI Air's closed-source native container core breaks inside its own game
     * startup on Android 16+ (library loads and the container initialises, then
     * the game process faults). There is no compat switch in the engine API, so
     * the launch is gated to Android 15 and below instead of crashing. The
     * button, the state dot and every launch entry point all check this.
     */
    private boolean airUnsupportedOs(GameSession game) {
        return game == air && Build.VERSION.SDK_INT >= 36;
    }

    private boolean controlsBlocked() {
        return busy || launchPending || launchAfterStorage || storagePromptOpen || permissionSheet != null
                || concurrentSheet != null || (gate != null && gate.isShowing())
                || (redownloadGate != null && redownloadGate.isShowing());
    }

    private boolean anyGameRunning() {
        for (GameSession game : games) {
            if (game.runtime.isRunning() || containerRunning(game)) return true;
        }
        return false;
    }

    private GameSession otherRunningGame() {
        for (GameSession game : games) {
            if (game != launchTarget && (game.runtime.isRunning() || containerRunning(game))) return game;
        }
        return null;
    }

    // ---------------------------------------- BGMI newer than the library

    /** Shows the warning card when BGMI is newer than the library supports. Only a notice: Launch is unchanged. */
    private void showGameSupport(String installed) {
        if (gameUpdateCard == null) return;
        String supported = preview ? null : GameSupport.cached(this);
        if (installed == null || !GameSupport.isNewer(installed, supported)) {
            gameUpdateCard.setVisibility(View.GONE);
            return;
        }
        String text = getString(R.string.game_update_text, installed, supported);
        gameUpdateText.setText(keepLastWordsTogether(text));
        gameUpdateCard.setContentDescription(getString(R.string.game_update_kicker) + ". " + text);
        gameUpdateCard.setVisibility(View.VISIBLE);
    }

    // ------------------------------------------------------------------ help

    /** Help sheet: three short tips, Copy support info (preview first) and the Telegram link. */
    @SuppressLint("InflateParams") // a sheet has no parent view until it is shown
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
        b.append(BuildConfig.DISPLAY_BRAND).append(" ").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(")\n");
        String library = null;
        try {
            library = LibManager.installedVersion(this);
        } catch (RuntimeException ignored) {
            // Unknown is fine.
        }
        b.append("Library: ").append(library != null && !library.isEmpty() ? library : "not installed").append('\n');
        b.append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        b.append("Device: ").append(deviceName()).append('\n');
        for (GameSession game : games) {
            b.append(game.target.displayName).append(": ").append(game.state.getVisibility() == View.GONE
                            ? getString(R.string.coming_soon) : game.state.getText())
                    .append("; ").append(game.target.libraryName).append(" ")
                    .append(LibManager.isUsableLib(this, game.target) ? "installed" : "not installed").append('\n');
        }
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
        for (GameSession game : games) {
            if (game.message.getVisibility() == View.VISIBLE && game.message.getText().length() > 0) {
                b.append("\n").append(gameName(game)).append(" status: ")
                        .append(redactStatus(game.message.getText().toString()));
            }
        }
        // Virtual account calls (method names and outcomes only, no secrets):
        // the Play Store's in-container sign-in fails silently without this.
        String accountTrace;
        try {
            accountTrace = AccountTrace.read(this);
        } catch (Throwable t) {
            accountTrace = null;
        }
        if (accountTrace != null && !accountTrace.trim().isEmpty()) {
            b.append("\nAccount calls in Mundo:\n").append(accountTrace.trim());
        }
        return b.toString();
    }

    /** Drops keys, file paths, links and e-mail addresses from a status line and shortens it. */
    static String redactStatus(String text) {
        if (text == null) return "";
        String s = text.replaceAll("(?i)\\b[A-Z0-9]+-[0-9]+[HD]-[A-Z0-9]+\\b", "[key]")
                .replaceAll("(?i)\\b[a-z][a-z0-9+.-]*://\\S+", "[link]")
                .replaceAll("\\S+@\\S+\\.\\S+", "[email]")
                .replaceAll("(?:/[^\\s/]+){2,}/?", "[path]")
                .replaceAll("\\s+", " ")
                .trim();
        if (s.length() > SUPPORT_STATUS_MAX) s = s.substring(0, SUPPORT_STATUS_MAX - 1).trim() + "\u2026";
        return s;
    }

    /** Read-only readiness hint. Launch still performs the full, verified OBB preparation. */
    private boolean readyToLaunch(GameSession game) {
        if (!libraryAvailable(game) || game.obbState != AppManager.ObbState.AVAILABLE) return false;
        if (StorageAccess.firstMissing(this) != null) return false;
        if (preview) return true; // The preview is not a native/container integration test.
        try {
            return MetaActivationManager.getActivationStatus();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Reconcile each package independently; a transient miss never stops the other game. */
    private void reconcileRunning() {
        boolean changed = false;
        for (GameSession game : games) {
            if (primaryAction == null || busy || launchPending || !game.installed) {
                game.runtime.resetObservations();
                continue;
            }
            if (game.runtime.observe(containerRunning(game), SystemClock.elapsedRealtime())) {
                changed = true;
                Log.d(TAG, "Container state changed: " + game.target.packageName);
                if (game == launchTarget && game.runtime.isRunning() && launchErrorShown) hideMessage();
            }
        }
        if (changed) refreshGameState();
    }

    private void refreshLibrary() {
        // Detailed library metadata belongs in Settings, not the game cards.
        refreshLibraryVersion();
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
        boolean blocked = controlsBlocked();
        for (GameSession game : games) {
            if (game.action == null) continue;
            if (busy && game == launchTarget) continue; // Keep its live copy/launch label and spinner.
            boolean stop = game.runtime.isRunning(); // Stopping is always allowed, including after expiry.
            boolean comingSoon = game == air && !libraryAvailable(game) && !stop;
            boolean osBlocked = airUnsupportedOs(game) && !stop;
            CharSequence label = stop ? getString(R.string.game_stop_named, gameName(game))
                    : comingSoon ? getString(R.string.game_air_coming_soon_action)
                    : expired ? getString(R.string.sign_in_again)
                    : !game.installed ? getString(R.string.game_install_named, gameName(game))
                    : getString(R.string.game_launch_named, gameName(game));
            Ui.setLoading(game.action, false, label);
            game.action.setEnabled(!blocked && !comingSoon && !osBlocked);
            game.action.setBackgroundTintList(ContextCompat.getColorStateList(this,
                    stop ? R.color.tonal_bg_on_black : R.color.button_bg));
            game.action.setTextColor(ContextCompat.getColorStateList(this,
                    stop ? R.color.danger_text : R.color.button_text));
            boolean play = !comingSoon && !expired && game.installed;
            if (stop || play) {
                game.action.setIconResource(stop ? R.drawable.ic_stop : R.drawable.ic_play);
                game.action.setIconSize(getResources().getDimensionPixelSize(R.dimen.icon_inline));
                game.action.setIconPadding(Ui.dp(this, 8));
                game.action.setIconTint(ContextCompat.getColorStateList(this,
                        stop ? R.color.danger_text : R.color.button_text));
            } else {
                game.action.setIcon(null);
            }
        }
    }

    private void setBusy(boolean value, CharSequence label) {
        busy = value;
        if (!value) {
            cancelPrepTimers();
            if (launchTarget.message.getVisibility() == View.INVISIBLE) launchTarget.message.setVisibility(View.GONE);
        } else {
            MaterialButton action = launchTarget.action;
            action.setEnabled(true);
            action.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.button_bg));
            action.setTextColor(ContextCompat.getColorStateList(this, R.color.button_text));
            action.setIconSize(getResources().getDimensionPixelSize(R.dimen.button_progress));
            action.setIconTint(ContextCompat.getColorStateList(this, R.color.button_text));
            Ui.setLoading(action, true, label);
        }
        refreshPrimary();
    }

    // --------------------------------------------------------------- actions

    private void onPrimaryAction(GameSession game) {
        if (controlsBlocked()) return;
        Ui.tap(game.action);
        // A silent metadata response must not open an update gate over a new launch.
        if (gate != null) {
            gate.dispose();
            gate = null;
        }
        launchTarget = game;
        concurrentLaunchApproved = false;
        refreshGameState();
        if (game.runtime.isRunning() || containerRunning(game)) {
            stopGame();
        } else if (airUnsupportedOs(game)) {
            showMessage(getString(R.string.game_air_os_unsupported), true);
            refreshPrimary();
        } else if (game == air && !libraryAvailable(game)) {
            refreshPrimary();
        } else if (expired) {
            openLogin();
        } else if (!game.installed) {
            openStore();
        } else {
            startLaunch();
        }
    }

    private void startLaunch() {
        if (!Ui.alive(this) || busy || launchPending) return;
        hideMessage();
        if (expired || (expiryMillis > 0 && expiryMillis <= System.currentTimeMillis())) {
            openLogin();
            return;
        }
        if (airUnsupportedOs(launchTarget)) {
            showMessage(getString(R.string.game_air_os_unsupported), true);
            refreshGameState();
            return;
        }
        if (launchTarget == air && !libraryAvailable(air)) {
            refreshGameState();
            return;
        }
        if (!isInstalled(launchTarget.target.packageName)) {
            refreshGameState();
            openStore();
            return;
        }
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
        if (!LibManager.isUsableLib(this, launchTarget.target)) {
            // BGMI's existing mandatory gate remains; Air never borrows libbgmi.so.
            if (launchTarget == bgmi) openGate(false);
            else refreshGameState();
            return;
        }
        if (!StorageAccess.has(this)) {
            requestStorage(true);
            return;
        }
        GameSession other = otherRunningGame();
        if (other != null && !concurrentLaunchApproved) {
            showConcurrentLaunchWarning(other);
            return;
        }
        prepareObbThenLaunch();
    }

    private void showConcurrentLaunchWarning(GameSession other) {
        if (concurrentSheet != null || !Ui.alive(this)) return;
        View content = LayoutInflater.from(this).inflate(R.layout.sheet_concurrent_games, null, false);
        ((TextView) content.findViewById(R.id.concurrentGamesMessage)).setText(
                getString(R.string.concurrent_games_message, gameName(other), gameName(launchTarget)));
        BottomSheetDialog sheet = Ui.sheet(this, content, true);
        concurrentSheet = sheet;
        ConcurrentLaunchGuard confirmation = new ConcurrentLaunchGuard(SystemClock.elapsedRealtime());
        MaterialButton confirm = content.findViewById(R.id.concurrentGamesConfirm);
        confirm.setEnabled(false);
        confirm.setOnClickListener(v -> {
            if (!Ui.alive(this) || concurrentSheet != sheet || !sheet.isShowing()
                    || !confirmation.confirm(SystemClock.elapsedRealtime())) return;
            Ui.tap(v);
            concurrentLaunchApproved = true;
            sheet.dismiss();
            startLaunch(); // Recheck licence, package, library and permissions after the wait.
        });
        content.findViewById(R.id.concurrentGamesCancel).setOnClickListener(v -> {
            Ui.tap(v);
            sheet.dismiss();
        });
        sheet.setOnDismissListener(d -> {
            confirmation.cancel();
            if (concurrentSheet == sheet) {
                if (concurrentTick != null) main.removeCallbacks(concurrentTick);
                concurrentTick = null;
                concurrentSheet = null;
                if (Ui.alive(this)) refreshPrimary();
            }
        });
        concurrentTick = new Runnable() {
            @Override public void run() {
                if (concurrentSheet != sheet || !sheet.isShowing()) return;
                int seconds = confirmation.secondsRemaining(SystemClock.elapsedRealtime());
                boolean wasEnabled = confirm.isEnabled();
                confirm.setEnabled(seconds == 0);
                confirm.setText(seconds == 0 ? getString(R.string.concurrent_games_confirm)
                        : getString(R.string.concurrent_games_wait, seconds));
                if (seconds > 0) main.postDelayed(this, 250L);
                else if (!wasEnabled) confirm.announceForAccessibility(getString(R.string.concurrent_games_confirm));
            }
        };
        refreshPrimary();
        sheet.show();
        concurrentTick.run();
    }

    /**
     * Copies the game's OBB into the container with live percent on the Launch
     * button, and launches only once AppManager reports a verified copy (or a
     * truly current one). Any failure stops here: no launch.
     */
    private void prepareObbThenLaunch() {
        Log.d(TAG, "Launch: preparing game files");
        if (!launchTarget.target.needsObb) {
            // BGMI Air has no game files: the copy step is skipped entirely.
            Log.d(TAG, "Launch: " + gameName(launchTarget) + " needs no game files, launching");
            ensureGameInContainerThenLaunch();
            return;
        }
        setBusy(true, getString(R.string.action_preparing));
        showProgress(0, true);
        // 2.3.4 (text only; the copy itself is unchanged): nothing new for the
        // first 400 ms, then the line. The very first copy explains itself;
        // 15 s without a new percent says it is still going.
        cancelPrepTimers();
        launchTarget.message.setVisibility(View.INVISIBLE);
        final GameSession preparingGame = launchTarget;
        final String preparedKey = preparingGame == bgmi ? PREF_OBB_PREPARED
                : PREF_OBB_PREPARED + "_" + preparingGame.target.packageName;
        final boolean firstCopy = !OBB_PREPARED_YES.equals(new Prefs(this).getSt(preparedKey, ""));
        main.postDelayed(prepShow = () -> {
            if (!Ui.alive(MAct.this) || !busy) return;
            showMessage(getString(firstCopy ? R.string.preparing_first : R.string.preparing), false);
        }, Ui.QUIET_MS);
        main.postDelayed(prepSlow, PREP_SLOW_MS);
        new AppManager(this).copyObbAsync(preparingGame.target.packageName, new AppManager.CopyCallback() {
            private int lastPercent = -1;

            @Override
            public void onProgressUpdate(int percent) {
                if (!Ui.alive(MAct.this) || launchTarget != preparingGame) return;
                showProgress(percent, false);
                preparingGame.action.setText(getString(R.string.action_preparing_progress, percent));
                if (percent != lastPercent) {
                    lastPercent = percent;
                    main.removeCallbacks(prepSlow);
                    main.postDelayed(prepSlow, PREP_SLOW_MS);
                }
            }

            @Override
            public void onCopyCompleted(boolean success, String error) {
                if (!Ui.alive(MAct.this) || launchTarget != preparingGame) return;
                cancelPrepTimers();
                preparingGame.obbRequest++;
                preparingGame.checkingObb = false;
                preparingGame.obbState = success ? AppManager.ObbState.AVAILABLE
                        : getString(R.string.main_obb_not_found).equals(error)
                                || getString(R.string.obb_folder_not_found).equals(error)
                                ? AppManager.ObbState.MISSING
                                : getString(R.string.obb_folder_blocked).equals(error)
                                        ? AppManager.ObbState.ACCESS_REQUIRED : AppManager.ObbState.UNKNOWN;
                refreshGameState();
                if (success) new Prefs(MAct.this).setSt(preparedKey, OBB_PREPARED_YES);
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
                            ? getString(R.string.game_error_obb_missing, gameName(preparingGame))
                            : getString(R.string.error_obb_copy, reason), true);
                    return;
                }
                Log.d(TAG, "Launch: game files ready, launching");
                hideMessage(); // The pill itself reads "Launching…".
                preparingGame.action.setText(R.string.launching);
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
        StorageAccess.markLaunchAfterRestart(this, launchTarget.target);
        setBusy(true, getString(R.string.action_preparing));
        showMessage(getString(R.string.restarting_for_game_files), false);
        main.postDelayed(() -> {
            Intent restart = Intent.makeRestartActivityTask(new ComponentName(this, SplashActivity.class));
            startActivity(restart);
            Runtime.getRuntime().exit(0);
        }, RESTART_DELAY_MS);
    }

    /**
     * Air has no game files, but the container still needs the game itself. The
     * SDK's implicit first-launch install is where Air died natively on Android
     * 17, so the game goes in through the staged base+splits path that reliably
     * installs GMS. Already installed: straight to launch.
     */
    private void ensureGameInContainerThenLaunch() {
        if (!Ui.alive(this)) return;
        hideMessage();
        setBusy(true, getString(R.string.launching));
        final GameSession launching = launchTarget;
        final Context app = getApplicationContext();
        new Thread(() -> {
            boolean ok = true;
            try {
                if (!MetaPackageManager.isInnerAppInstalled(launching.target.packageName)) {
                    // A native death inside the container installer leaves no
                    // Java trace; the breadcrumb names the step on next start.
                    CrashLogger.noteAction(app, "container install: " + launching.target.packageName);
                    ok = GoogleServicesInstaller.installFromPhone(app, launching.target.packageName);
                    CrashLogger.clearAction(app);
                }
            } catch (Throwable t) {
                Log.e(TAG, "Container game install failed", t);
                ok = false;
            }
            final boolean installed = ok;
            main.post(() -> {
                if (!Ui.alive(MAct.this) || launchTarget != launching) return;
                if (!installed) {
                    setBusy(false, null);
                    hideProgress();
                    showMessage(getString(R.string.game_container_install_failed,
                            gameName(launching)), true);
                    return;
                }
                main.postDelayed(MAct.this::launchGame, LAUNCH_DELAY_MS);
            });
        }, "anoy-game-install").start();
    }

    private void launchGame() {
        if (!Ui.alive(this)) return;
        if (!preview && (expired || (expiryMillis > 0 && expiryMillis <= System.currentTimeMillis()))) {
            setBusy(false, null);
            hideProgress();
            showMessage(getString(R.string.licence_expired_message), true);
            refreshGameState();
            return;
        }
        if (airUnsupportedOs(launchTarget)) {
            setBusy(false, null);
            hideProgress();
            showMessage(getString(R.string.game_air_os_unsupported), true);
            refreshGameState();
            return;
        }
        if (launchTarget == air && !libraryAvailable(air)) {
            setBusy(false, null);
            hideProgress();
            refreshGameState();
            return;
        }
        // Another package can start while a large OBB is being copied.
        GameSession other = otherRunningGame();
        if (other != null && !concurrentLaunchApproved) {
            setBusy(false, null);
            hideProgress();
            showConcurrentLaunchWarning(other);
            return;
        }
        launchTarget.qaLaunchAt = SystemClock.elapsedRealtime();
        // 1. Launch. Only this block decides whether the game started; the
        //    container call itself is unchanged.
        guardContainerLaunch();
        boolean started = false;
        Throwable failure = null;
        try {
            // A native container death during launch leaves no Java trace; the
            // breadcrumb names the game on the next start. Cleared on success.
            CrashLogger.noteAction(this, "launch: " + launchTarget.target.packageName);
            Intent intent = obtainLaunchIntent();
            if (intent != null) {
                started = dispatchLaunch(intent);
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
        return MetaActivityManager.obtainSplashLaunchIntent(launchTarget.target.packageName, this);
    }

    /**
     * Sends a game launch into the container. The splash intent's explicit
     * component goes through the SDK's in-container launcher with an explicit
     * user (the recipe GameLoginCallbackActivity proves out) — the host
     * startActivity path is what NPEs on Android 16+'s isolated-split
     * transaction. Host startActivity remains as the fallback for containers
     * where launchIntent cannot take the component.
     */
    private boolean dispatchLaunch(Intent intent) {
        try {
            ComponentName component = intent.getComponent();
            if (component != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                MetaActivityManager.launchIntent(intent, GameLoginRouter.USER);
                return true; // dispatch returned: the container accepted the launch.
            }
        } catch (Throwable t) {
            Log.w(TAG, "In-container launch dispatch failed, trying host startActivity", t);
        }
        startActivity(intent);
        return true; // startActivity returned normally: the game is launching.
    }

    /** True when the virtual container reports the game process running. */
    private boolean containerRunning() {
        return containerRunning(launchTarget);
    }

    private boolean containerRunning(GameSession game) {
        if (qaLaunch != null && game == qaLaunchTarget) {
            // Debug stand-in: nothing runs before Launch; a started game runs
            // until qa_game_ms (if given) has passed.
            if (game.qaLaunchAt <= 0) return false;
            long since = SystemClock.elapsedRealtime() - game.qaLaunchAt;
            if (qaGameMs > 0 && since >= qaGameMs) return false;
            if ("null_running".equals(qaLaunch)) return since > 1200L;
            return "started".equals(qaLaunch) || "started_polish_throws".equals(qaLaunch);
        }
        try {
            return MetaActivityManager.isAppRunning(game.target.packageName);
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
        launchTarget.runtime.launched(SystemClock.elapsedRealtime());
        concurrentLaunchApproved = false;
        launchErrorShown = false;
        // The game is up: the launch breadcrumb has served its purpose. A short
        // delay keeps it alive through the container's fragile first seconds.
        crashHandler.postDelayed(() -> CrashLogger.clearAction(getApplicationContext()), 15000L);
        try {
            hideMessage();
            if (haptic) Ui.confirm(launchTarget.action);
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
        concurrentLaunchApproved = false;
        CrashLogger.clearAction(this);
        Log.e(TAG, "Launch failed: the game did not start", cause != null ? cause
                : new IllegalStateException("No launch intent and the container reports the game is not running"));
        showMessage(getString(R.string.error_launch), true);
        launchErrorShown = true;
        setBusy(false, null);
        refreshGameState();
    }

    private void stopGame() {
        try {
            MetaActivityManager.killAppByPkg(launchTarget.target.packageName);
            launchTarget.runtime.stopped(SystemClock.elapsedRealtime());
            launchTarget.qaLaunchAt = 0L;
            concurrentLaunchApproved = false;
            showMessage(getString(R.string.game_stopped_named, gameName(launchTarget)), false);
        } catch (Throwable t) {
            Log.e(TAG, "Stop failed", t);
            showMessage(getString(R.string.error_stop), true);
        }
        refreshGameState();
    }

    private void openStore() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + launchTarget.target.packageName)));
        } catch (ActivityNotFoundException e) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=" + launchTarget.target.packageName)));
            } catch (ActivityNotFoundException nested) {
                showMessage(getString(R.string.error_store), true);
            }
        }
    }

    private void openLogin() {
        openLogin(null);
    }

    private void openLogin(String signedOutReason) {
        Intent intent = new Intent(this, LogAct.class);
        if (signedOutReason != null) intent.putExtra(LogAct.EXTRA_SIGNED_OUT, signedOutReason);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
        finish();
    }

    // ----------------------------------------------------- library updates

    private void maybeRecheckLibrary() {
        if (preview || controlsBlocked() || anyGameRunning()) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastRecheck < RECHECK_INTERVAL_MS) return;
        lastRecheck = now;
        openGate(true);
    }

    private void openGate(boolean silent) {
        if (gate != null) gate.dispose();
        UpdateGate.Listener onReady = () -> {
            refreshLibrary();
            refreshLibraryVersion();
            refreshGameFiles();
            if (!silent) hideMessage();
        };
        gate = silent ? UpdateGate.recheck(this, onReady) : UpdateGate.start(this, onReady);
    }

    /** After a crash, home offers the saved report once: copy it for support, or dismiss. */
    private boolean crashReportShown;
    /** Survives onDestroy (unlike `main`): clears the action breadcrumb on a live process. */
    private final Handler crashHandler = new Handler(Looper.getMainLooper());

    private void showCrashReport() {
        if (crashReportShown || preview) return;
        crashReportShown = true;
        String report;
        try {
            report = CrashLogger.read(this);
        } catch (Throwable t) {
            report = null;
        }
        if (report != null && !report.trim().isEmpty()) {
            CrashLogger.clearAction(this); // the Java trace supersedes the breadcrumb
            showCrashDialog(getString(R.string.crash_report_title), withSplitFixState(report));
            return;
        }
        // No Java trace: a native container crash leaves no exception behind,
        // only the breadcrumb written before the risky call.
        String action;
        try {
            action = CrashLogger.pendingAction(this);
        } catch (Throwable t) {
            return;
        }
        if (action == null || action.trim().isEmpty()) return;
        CrashLogger.clearAction(this);
        showCrashDialog(getString(R.string.crash_action_title),
                withSplitFixState("Anoy " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")\n"
                        + "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n"
                        + "Device: " + deviceName() + "\n\n"
                        + "The process died without a Java error (a native crash inside the container).\n\n"
                        + "Last action: " + action + "\n\n" + nativeExitSection()));
    }

    /** The system-recorded native fault (API 30+), or a note that none was found. */
    private String nativeExitSection() {
        try {
            String exit = CrashLogger.readNativeExit(this);
            return exit != null ? exit : "Native exit: none recorded (the container may run as a system/root process Android does not attribute to this app)";
        } catch (Throwable t) {
            return "Native exit: unreadable";
        }
    }

    /** Appends the split-repair bookkeeping so a report shows whether the fix fired. */
    private String withSplitFixState(String report) {
        try {
            String diag = SplitDepsFix.readDiag(this);
            if (!diag.isEmpty()) report += "\n\nSplit bind diag:\n" + diag;
            String state = SplitDepsFix.readState(this);
            if (state.isEmpty()) return report + "\n\nSplit repair: never fired";
            return report + "\n\nSplit repair fired for:\n" + state;
        } catch (Throwable t) {
            return report;
        }
    }

    /**
     * Persistent Air crash line on the Air card. A native container death can
     * restart the app at the splash without ever re-showing this activity, so the
     * crash dialog never appears. Mirror the report here — it survives restarts and
     * is always visible on Home. Tap-to-copy stays available via the crash dialog
     * whenever MAct does come back.
     */
    private void showAirCrashIfPending() {
        try {
            if (air == null || air.message == null) return;
            String action = CrashLogger.pendingAction(this);
            if (action == null || !action.contains(GameTarget.BGMI_AIR.packageName)) return;
            StringBuilder msg = new StringBuilder(getString(R.string.crash_action_title));
            // Air is NOT isolated-split (the diag proved it), so the crash is native:
            // the vprocess System.load(libbgmilite.so) or Air's own .so. Surface the
            // ELF page/alignment/writable state and the recorded load error — Android
            // 16/17 reject a 4KB-aligned or writable library that Android 15 tolerated.
            try {
                msg.append("\nLib: ").append(LibManager.diagnostics(this, GameTarget.BGMI_AIR));
            } catch (Throwable ignored) {
            }
            // Which startup step the game process last reached before dying.
            try {
                String steps = LibManager.milestones(this, GameTarget.BGMI_AIR);
                if (!steps.isEmpty()) msg.append("\nReached: ").append(steps);
            } catch (Throwable ignored) {
            }
            String diag = SplitDepsFix.readDiag(this);
            if (!diag.isEmpty()) msg.append("\n").append(diag);
            air.message.setText(msg.toString());
            air.message.setVisibility(View.VISIBLE);
        } catch (Throwable t) {
            Log.w(TAG, "Air crash status unavailable", t);
        }
    }

    private void showCrashDialog(String title, final String report) {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(report)
                .setPositiveButton(R.string.crash_report_copy, (d, w) -> {
                    try {
                        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        if (clipboard != null) {
                            clipboard.setPrimaryClip(ClipData.newPlainText(
                                    getString(R.string.crash_report_title), report));
                        }
                    } catch (RuntimeException e) {
                        Log.w(TAG, "Crash report: clipboard unavailable", e);
                    }
                    CrashLogger.clear(this);
                })
                .setNegativeButton(R.string.crash_report_dismiss, (d, w) -> CrashLogger.clear(this))
                .show();
    }

    private void reportLoaderError() {
        for (GameSession game : games) {
            String error = LibManager.lastLoadError(this, game.target);
            if (error != null && !error.isEmpty()) {
                showMessage(game, getString(R.string.game_library_error, gameName(game), error), true);
                LibManager.clearLoadError(this, game.target);
                return;
            }
            File lib = LibManager.libFile(this, game.target);
            if (lib.isFile()) {
                String problem = LibManager.alignmentProblem(lib);
                if (problem != null) {
                    showMessage(game, getString(R.string.game_library_error, gameName(game), problem), true);
                    return;
                }
            }
        }
    }

    // ---------------------------------------------------------------- status

    /** Each game keeps its own inline feedback; errors retain the REJECT haptic. */
    private void showMessage(String text, boolean error) {
        showMessage(launchTarget, text, error);
    }

    private void showMessage(GameSession game, String text, boolean error) {
        launchErrorShown = false;
        game.message.animate().cancel();
        game.message.setAlpha(1f);
        game.message.setTag(R.id.tag_swap_text, null);
        game.message.setText(text);
        game.message.setTextColor(ContextCompat.getColor(this, error ? R.color.danger : R.color.text_secondary));
        if (error) Ui.rejectHaptic(game.message);
        Ui.fade(game.message, true);
    }

    /** While a task runs the line keeps its space (INVISIBLE), so nothing jumps. */
    private void hideMessage() {
        launchErrorShown = false;
        cancelPrepTimers();
        if (busy) {
            Ui.fadeReserved(launchTarget.message, false);
        } else {
            Ui.fade(launchTarget.message, false);
        }
    }

    private void showProgress(int percent, boolean indeterminate) {
        if (launchTarget.progress.isIndeterminate() != indeterminate) {
            launchTarget.progress.setVisibility(View.INVISIBLE);
            launchTarget.progress.setIndeterminate(indeterminate);
        }
        launchTarget.progress.setVisibility(View.VISIBLE);
        if (!indeterminate) launchTarget.progress.setProgressCompat(percent, !Ui.reducedMotion(this));
    }

    private void hideProgress() {
        launchTarget.progress.setVisibility(View.GONE);
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

    /** A smaller greeting; the kicker retains each flavor's wordmark. */
    private void setupHeader() {
        ((TextView) findViewById(R.id.homeTitle)).setText(R.string.home_title);
    }

    // ---------------------------------------------------------------- settings

    private void setupSettings() {
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
        if ((key == null || key.trim().isEmpty()) && preview) key = (BuildConfig.DISPLAY_BRAND.toUpperCase(Locale.US) + "-30D-QA7K2");
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
                                    "https://example.com/" + BuildConfig.DISPLAY_BRAND + ".apk",
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
        if (anyGameRunning()) {
            showMessage(getString(R.string.game_libraries_in_use), false);
            return;
        }
        if (controlsBlocked() || busy || launchPending || (redownloadGate != null && redownloadGate.isShowing())
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
        if (anyGameRunning()) {
            showMessage(getString(R.string.game_libraries_in_use), false);
            return;
        }
        if (controlsBlocked()) return;
        Log.i(TAG, "Settings: re-downloading the library");
        if (redownloadGate != null) redownloadGate.dispose();
        redownloadGate = UpdateGate.redownload(this, () -> {
            // Installed or not (a failure keeps the old library): show what is there now.
            refreshLibraryVersion();
            refreshLibrary();
            refreshGameFiles();
        });
    }

    /** Android 16+ split-APK launch guard: the framework NPEs when the
     *  container leaves no current Application across the vprocess handoff. */
    private void guardContainerLaunch() {
        try {
            BoxApplication app = BoxApplication.get();
            if (app == null) return;
            ConfigGuard.install(app);
            ConfigGuard.repairNow(app);
            PmCompatShim.install();
            SplitDepsFix.install(this);
        } catch (Throwable ignored) {
        }
    }

    private void setupNavigation(Bundle savedInstanceState) {
        navigation = new HomeSettingsNavigation(findViewById(R.id.mainRoot),
                new HomeSettingsNavigation.Listener() {
                    @Override
                    public void onPageSelected(boolean settings) {
                        Log.d(TAG, settings ? "Settings: open" : "Home: open");
                        maskLicenceKey();
                        settingsBack.setEnabled(settings);
                        if (settings) {
                            refreshLibraryVersion();
                            renderAppCheckCaption();
                        }
                        resumeHomeCountdown();
                    }

                    @Override
                    public void onMotionChanged(boolean moving) {
                        // Never expose a revealed account key in a partial page preview.
                        if (moving) maskLicenceKey();
                        resumeHomeCountdown();
                    }
                });
        int page = savedInstanceState != null
                ? savedInstanceState.getInt(STATE_PAGE, HomeSettingsPager.HOME)
                : preview && getIntent().getBooleanExtra(EXTRA_QA_SETTINGS, false)
                ? HomeSettingsPager.SETTINGS : HomeSettingsPager.HOME;
        navigation.select(page == HomeSettingsPager.SETTINGS
                ? HomeSettingsPager.SETTINGS : HomeSettingsPager.HOME, false);
        settingsBack.setEnabled(navigation.currentPage() == HomeSettingsPager.SETTINGS);
        maskLicenceKey();
        refreshLibraryVersion();
    }

    private void maskLicenceKey() {
        keyRevealed = false;
        main.removeCallbacks(restoreKey);
        renderLicenceKey();
    }

    private void resumeHomeCountdown() {
        main.removeCallbacks(ticker);
        if (homeStarted && navigation != null && navigation.homeIsSettled()) main.post(ticker);
    }

    private void closeSettings() {
        if (navigation != null) navigation.select(HomeSettingsPager.HOME, true);
    }
}
