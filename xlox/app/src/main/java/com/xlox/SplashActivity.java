package com.xlox;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Interpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.PathInterpolator;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.splashscreen.SplashScreen;
import androidx.core.splashscreen.SplashScreenViewProvider;

import com.xlox.ui.Ui;
import com.xlox.utils.AppUpdater;
import com.xlox.utils.UpdateException;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Launcher entry: a ~3 s typography film on true black, then sign in / the
 * update check exactly as before.
 *
 * <p>The system splash (core-splashscreen) shows the static Anoy wordmark
 * (res/drawable/splash_mark.xml, the launcher icon's outlines). This activity
 * starts on the same image in the same place, then:
 * <pre>
 *   0.00-0.50 s  arrive   "Welcome To / Anoy Loader" fades in and rises 12 dp
 *                         while the system wordmark cross-fades out
 *   0.50-1.15 s  read     hold
 *   1.15-1.67 s  leave    "Welcome To" fades out while drifting up 8 dp
 *   1.20-1.80 s  move     "Anoy Loader" glides up onto the true screen centre
 *   1.56-2.26 s  swap     "Loader" cross-dissolves into "Hax" in the same slot
 *                         while the pair re-centres. The fades overlap: "Hax"
 *                         is in by 70 % and "Loader" only leaves from 30 %, so
 *                         both words share the slot mid-way and it never looks
 *                         empty - no gap, no pop
 *   2.26-2.85 s  settle   hold "Anoy Hax"
 *   2.85 s       handoff  existing 320 ms cross-fade into LogAct (~3.17 s)
 * </pre>
 * 2.2.0: meanwhile the app (APK) update check runs and the bottom status line
 * reports it. The hand-off waits for BOTH the film and the check: up to date
 * goes on to sign in (whose library UpdateGate is unchanged); a newer release
 * opens {@link AppUpdateActivity}; a failed check shows the reason and Retry
 * and never continues on its own. If the check outlasts the film, "Anoy Hax"
 * breathes until it answers.
 * Every property is a pure function of time ({@link #applyTime}) driven by one
 * animator, so the moves stay locked together as one gesture. Text is only
 * faded and translated - never clipped and never scaled - on hardware layers.
 * Reduced motion: "Welcome To Anoy Loader" holds, then cuts straight to the
 * final "Anoy Hax" and hands off - no choreography.
 */
public class SplashActivity extends AppCompatActivity {

    // ---- Timeline (ms). Tuned together: change one, check the others. ----
    /** System-splash wordmark cross-fades out as the lockup arrives. */
    private static final long MARK_FADE_MS = 220L;
    /** Arrive: rise into place (emphasized decelerate) and fade in. */
    private static final long ARRIVE_MS = 500L;
    private static final long ARRIVE_FADE_MS = 400L;
    private static final float ARRIVE_RISE_DP = 12f;
    /** Leave: the greeting fades out and drifts up. Never clipped or scaled. */
    private static final long CUT_AT_MS = 1150L;
    private static final long CUT_MS = 520L;
    private static final float CUT_DRIFT_DP = 8f;
    /** Move: "Anoy Loader" glides into the space the greeting left. */
    private static final long MOVE_AT_MS = CUT_AT_MS + 50L;
    private static final long MOVE_MS = 600L;
    /**
     * Swap: "Loader" and "Hax" cross-dissolve in the same slot (start edges
     * aligned) while the pair re-centres. It starts as the move settles, so the
     * whole cut reads as one continuous gesture.
     */
    private static final long SWAP_AT_MS = MOVE_AT_MS + 360L;
    private static final long SWAP_MS = 700L;
    /**
     * Overlapping fade windows within the swap: "Hax" fades in over 0-70 % and
     * "Loader" fades out over 30-100 %. Mid-way both are 84 % visible, so the
     * words genuinely overlap - the slot never dims, empties or pops.
     */
    private static final float HAX_IN_END = 0.70f;
    private static final float LOADER_OUT_START = 0.30f;
    /** Version footer fades in early, as before. */
    private static final long VERSION_AT_MS = 260L;
    private static final long VERSION_MS = 320L;
    /** End of the film = hand-off (plus the 320 ms activity cross-fade). */
    private static final long FILM_MS = 2850L;
    /** Reduced motion: hold the welcome, cut to the final line, hold, hand off. */
    private static final long REDUCED_WELCOME_MS = 1000L;
    private static final long REDUCED_FINAL_MS = 700L;
    /** Startup slower than the film: very slow opacity breathe on "Anoy Hax". */
    private static final long BREATHE_MS = 2400L;
    private static final float BREATHE_MIN_ALPHA = 0.72f;
    /** Safety net if the first frame never arrives. */
    private static final long SAFETY_MS = FILM_MS + 2500L;

    /** Inter cap height (1490 / 2048 em): used to centre the line optically. */
    private static final float CAP_HEIGHT_EM = 1490f / 2048f;

    private static final Interpolator EMPHASIZED_DECELERATE = new PathInterpolator(0.05f, 0.7f, 0.1f, 1f);
    private static final Interpolator FAST_OUT_SLOW_IN = new PathInterpolator(0.4f, 0f, 0.2f, 1f);
    /**
     * Symmetric ease-in-out for the cross-dissolve and its slide: no
     * front-loaded jump, so the change is spread over the whole swap.
     */
    private static final Interpolator DISSOLVE = new PathInterpolator(0.45f, 0f, 0.55f, 1f);

    /** Debug builds only: hold the splash (used by the screenshot QA). */
    public static final String EXTRA_HOLD = "qa_hold";
    /** Debug builds only: freeze the film at a time in seconds for screenshots. */
    public static final String EXTRA_FRAME = "qa_frame";
    /**
     * Debug builds only: simulate the app update check without the network -
     * checking | up_to_date | available | mandatory | fail.
     */
    public static final String EXTRA_QA_APP_UPDATE = "qa_app_update";
    /** Status line: the check starts once the version and status have faded in. */
    private static final long CHECK_AT_MS = VERSION_AT_MS + VERSION_MS;
    /** "Proceeding..." / "Update available" stay readable this long before the hand-off. */
    private static final long PROCEED_MS = 400L;
    /** Debug simulation: how long the fake check takes. */
    private static final long QA_CHECK_MS = 900L;

    private enum Check { PENDING, CHECKING, CURRENT, AVAILABLE, FAILED }

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** Film finished (or never started): hand off as soon as the check allows. */
    private final Runnable next = this::onFilmDone;
    private final ExecutorService checker = Executors.newSingleThreadExecutor(r -> new Thread(r, "anoy-app-check"));
    private View root;
    private View mark;
    private View lockup;
    private TextView greeting;
    private View name;
    private TextView loader;
    private TextView hax;
    private TextView version;
    private View statusBar;
    private View statusSpinner;
    private TextView statusLine;
    private TextView statusDetail;
    private View retry;
    private Check check = Check.PENDING;
    private AppUpdater.Release release;
    /** Bumped on every (re)check so a stale answer is ignored. */
    private int checkRun;
    private boolean filmDone;
    private boolean handingOff;
    private String qaUpdate;
    private ValueAnimator film;
    private ValueAnimator breath;
    private float risePx;
    private float driftPx;
    /** Measured after layout: vertical move to the centre, horizontal re-centre. */
    private float moveDy;
    private float recentreDx;
    private boolean measured;
    private boolean opened;
    private boolean hold;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SplashScreen splash = SplashScreen.installSplashScreen(this);
        // Our first frame is the same wordmark in the same place, so drop the
        // system splash at once. Without a listener the platform fades it out
        // on its own and a cold start can show it dim, then an empty window,
        // then this activity fading in.
        splash.setOnExitAnimationListener(SplashScreenViewProvider::remove);
        super.onCreate(savedInstanceState);
        // Re-launch from the launcher while a task already exists: just finish.
        if (!isTaskRoot() && getIntent() != null && Intent.ACTION_MAIN.equals(getIntent().getAction())
                && getIntent().hasCategory(Intent.CATEGORY_LAUNCHER)) {
            finish();
            return;
        }
        hold = BuildConfig.DEBUG && getIntent() != null && getIntent().getBooleanExtra(EXTRA_HOLD, false);
        qaUpdate = BuildConfig.DEBUG && getIntent() != null ? getIntent().getStringExtra(EXTRA_QA_APP_UPDATE) : null;
        final float qaFrame = BuildConfig.DEBUG && getIntent() != null
                ? getIntent().getFloatExtra(EXTRA_FRAME, -1f) : -1f;
        try {
            Ui.edgeToEdge(this);
            setContentView(R.layout.activity_splash);
            root = findViewById(R.id.splashRoot);
            mark = findViewById(R.id.splashMark);
            lockup = findViewById(R.id.splashLockup);
            greeting = findViewById(R.id.splashGreeting);
            name = findViewById(R.id.splashName);
            loader = findViewById(R.id.splashLoader);
            hax = findViewById(R.id.splashHax);
            version = findViewById(R.id.splashVersion);
            version.setText(getString(R.string.splash_version, BuildConfig.VERSION_NAME));
            statusBar = findViewById(R.id.splashStatusBar);
            statusSpinner = findViewById(R.id.splashStatusSpinner);
            statusLine = findViewById(R.id.splashStatus);
            statusDetail = findViewById(R.id.splashStatusDetail);
            retry = findViewById(R.id.splashRetry);
            retry.setOnClickListener(v -> {
                Ui.tap(v);
                startCheck();
            });
            risePx = Ui.dp(this, ARRIVE_RISE_DP);
            driftPx = Ui.dp(this, CUT_DRIFT_DP);
            // One real word space between "Anoy" and the Loader/Hax slot.
            TextView anoy = findViewById(R.id.splashAnoy);
            View slotView = findViewById(R.id.splashSlot);
            ViewGroup.MarginLayoutParams slot = (ViewGroup.MarginLayoutParams) slotView.getLayoutParams();
            slot.setMarginStart(Math.round(anoy.getPaint().measureText(" ")));
            slotView.setLayoutParams(slot);
            final int footerMargin = getResources().getDimensionPixelSize(R.dimen.space_24);
            final int versionTop = getResources().getDimensionPixelSize(R.dimen.space_16);
            final int versionEnd = getResources().getDimensionPixelSize(R.dimen.screen_padding);
            Ui.onInsets(root, (bars, bottom, ime) -> {
                // Only re-lays out when an inset really changes - never per frame.
                ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) version.getLayoutParams();
                int top = versionTop + bars.top;
                int end = versionEnd + Math.max(bars.left, bars.right);
                if (lp.topMargin != top || lp.getMarginEnd() != end) {
                    lp.topMargin = top;
                    lp.setMarginEnd(end);
                    version.setLayoutParams(lp);
                }
                ViewGroup.MarginLayoutParams sp = (ViewGroup.MarginLayoutParams) statusBar.getLayoutParams();
                int margin = footerMargin + bars.bottom;
                if (sp.bottomMargin != margin) {
                    sp.bottomMargin = margin;
                    statusBar.setLayoutParams(sp);
                }
            });
        } catch (RuntimeException e) {
            // Never let a cosmetic screen block the app.
            Log.e("SplashActivity", "Splash layout failed", e);
            openLogin();
            return;
        }

        if (qaFrame >= 0f) {
            // QA: one still frame of the film, no navigation, no network.
            hold = true;
            if (qaUpdate == null) qaUpdate = "checking";
            root.post(() -> {
                if (!Ui.alive(this) || !measure()) return;
                applyTime(Math.round(qaFrame * 1000f));
            });
            startCheck();
            return;
        }
        // The hold screenshots never touch the network either.
        if (hold && qaUpdate == null) qaUpdate = "checking";
        handler.postDelayed(this::startCheck, CHECK_AT_MS);
        if (Ui.reducedMotion(this)) {
            playReduced();
            return;
        }
        // Start on the first drawn frame (after layout) so a slow cold start
        // can't eat into the film; until then the system wordmark stays put.
        root.post(() -> {
            if (!Ui.alive(this)) return;
            if (!measure()) {
                onFilmDone();
                return;
            }
            film = ValueAnimator.ofFloat(0f, FILM_MS);
            film.setDuration(FILM_MS);
            film.setInterpolator(new LinearInterpolator());
            film.addUpdateListener(a -> applyTime((long) (float) a.getAnimatedValue()));
            film.addListener(new AnimatorListenerAdapter() {
                private boolean done;

                @Override
                public void onAnimationEnd(Animator animation) {
                    if (done) return;
                    done = true;
                    applyTime(FILM_MS);
                    setTextLayers(false);
                    onFilmDone();
                }
            });
            setTextLayers(true);
            film.start();
        });
        handler.postDelayed(next, SAFETY_MS);
    }

    // ------------------------------------------------------ app update check

    /** The film is over (or could not run): go on once the check allows it. */
    private void onFilmDone() {
        if (filmDone || !Ui.alive(this)) return;
        filmDone = true;
        handler.removeCallbacks(next);
        if (!maybeProceed()) breathe();
    }

    /** (Re)runs the check; the status line follows its real state. */
    private void startCheck() {
        if (!Ui.alive(this) || handingOff) return;
        if (check == Check.CHECKING) return;
        final int run = ++checkRun;
        check = Check.CHECKING;
        release = null;
        setStatus(getString(R.string.splash_status_checking), 0, R.color.text_tertiary, true);
        hideFailure();
        if (qaUpdate != null) {
            simulateCheck(run);
            return;
        }
        Log.i("SplashActivity", "Checking for an app update");
        checker.execute(() -> {
            try {
                AppUpdater.Release found = AppUpdater.check(this);
                handler.post(() -> onChecked(run, found));
            } catch (UpdateException e) {
                Log.w("SplashActivity", "App update check failed: " + e.getMessage());
                handler.post(() -> onCheckFailed(run, e.getMessage()));
            } catch (RuntimeException e) {
                Log.e("SplashActivity", "App update check crashed", e);
                handler.post(() -> onCheckFailed(run, getString(R.string.update_unavailable)));
            }
        });
    }

    private void onChecked(int run, AppUpdater.Release found) {
        if (run != checkRun || !Ui.alive(this)) return;
        release = found;
        if (found == null) {
            check = Check.CURRENT;
            Log.i("SplashActivity", "App is up to date (" + BuildConfig.VERSION_CODE + ")");
            setStatus(getString(R.string.splash_status_current), R.drawable.ic_circle_check, R.color.success, false);
        } else {
            check = Check.AVAILABLE;
            Log.i("SplashActivity", "App update available: " + found.versionName + " (" + found.versionCode + ")");
            setStatus(getString(R.string.splash_status_available, found.versionName),
                    R.drawable.ic_arrow_down_to_line, R.color.blue, false);
        }
        if (maybeProceed() && breath != null) {
            breath.cancel();
            name.setAlpha(1f);
        }
    }

    private void onCheckFailed(int run, String reason) {
        if (run != checkRun || !Ui.alive(this)) return;
        check = Check.FAILED;
        setStatus(getString(R.string.splash_status_failed), R.drawable.ic_circle_alert, R.color.danger, false);
        statusDetail.setText(reason);
        statusDetail.setVisibility(reason == null || reason.isEmpty() ? View.GONE : View.VISIBLE);
        retry.setVisibility(View.VISIBLE);
        // Never left invisible behind a still-running film.
        statusBar.setAlpha(1f);
    }

    /**
     * Hands off when both the film and the check are done: up to date goes to
     * sign in, a newer release to the update screen. False while waiting (or
     * after a failure, which waits for Retry).
     */
    private boolean maybeProceed() {
        if (!filmDone || hold || handingOff) return false;
        if (check == Check.CURRENT) {
            handingOff = true;
            setStatus(getString(R.string.splash_status_proceeding), 0, R.color.text_tertiary, true);
            handler.postDelayed(this::openLogin, PROCEED_MS);
            return true;
        }
        if (check == Check.AVAILABLE && release != null) {
            handingOff = true;
            handler.postDelayed(this::openUpdate, PROCEED_MS);
            return true;
        }
        return false;
    }

    private void setStatus(String text, int iconRes, int colourRes, boolean spinner) {
        int colour = ContextCompat.getColor(this, colourRes);
        statusLine.setText(text);
        statusLine.setTextColor(colour);
        Drawable glyph = null;
        if (iconRes != 0) {
            glyph = ContextCompat.getDrawable(this, iconRes);
            if (glyph != null) {
                int size = getResources().getDimensionPixelSize(R.dimen.icon_chip);
                glyph = glyph.mutate();
                glyph.setBounds(0, 0, size, size);
            }
        }
        statusLine.setCompoundDrawablesRelative(glyph, null, null, null);
        androidx.core.widget.TextViewCompat.setCompoundDrawableTintList(statusLine, ColorStateList.valueOf(colour));
        statusSpinner.setVisibility(spinner ? View.VISIBLE : View.GONE);
    }

    private void hideFailure() {
        statusDetail.setVisibility(View.GONE);
        retry.setVisibility(View.GONE);
    }

    /** Debug only: the check's answers without the network. */
    private void simulateCheck(int run) {
        final String qa = qaUpdate.toLowerCase(Locale.US);
        if ("checking".equals(qa)) return;
        handler.postDelayed(() -> {
            switch (qa) {
                case "available":
                case "mandatory":
                    onChecked(run, sampleRelease("mandatory".equals(qa)));
                    break;
                case "fail":
                    onCheckFailed(run, getString(R.string.net_offline));
                    // A Retry in the sample then succeeds.
                    qaUpdate = "up_to_date";
                    break;
                default:
                    onChecked(run, null);
                    break;
            }
        }, QA_CHECK_MS);
    }

    private static AppUpdater.Release sampleRelease(boolean mandatory) {
        return new AppUpdater.Release("2.3.0", BuildConfig.VERSION_CODE + 1,
                "https://github.com/masterace572-prog/AnoyMundo/releases/download/sample/Anoy.apk",
                "Faster start-up\nClearer update messages\nFixes for the game files copy", mandatory);
    }

    private void openUpdate() {
        if (opened || !Ui.alive(this) || release == null) return;
        opened = true;
        Intent intent = new Intent(this, AppUpdateActivity.class);
        release.writeTo(intent);
        if (qaUpdate != null) intent.putExtra(AppUpdateActivity.EXTRA_QA_PREVIEW, true);
        startActivity(intent);
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
        finish();
    }

    /** Measures the moves from the laid-out views; false if layout is unusable. */
    private boolean measure() {
        if (measured) return true;
        if (root.getHeight() == 0 || name.getHeight() == 0) return false;
        TextView anoy = findViewById(R.id.splashAnoy);
        // Optical centre of the line = middle of the cap band above the baseline.
        float capCentre = lockup.getTop() + name.getTop() + anoy.getTop() + anoy.getBaseline()
                - CAP_HEIGHT_EM * anoy.getTextSize() / 2f;
        moveDy = root.getHeight() / 2f - capCentre;
        // "Hax" sits where "Loader" starts; shift the pair so "Anoy Hax" is centred.
        recentreDx = (loader.getWidth() - hax.getWidth()) / 2f;
        measured = true;
        return true;
    }

    /** Sets every view for film time t (ms). Pure: the same t gives the same frame. */
    private void applyTime(long t) {
        mark.setAlpha(1f - progress(t, 0, MARK_FADE_MS, FAST_OUT_SLOW_IN));

        lockup.setAlpha(progress(t, 0, ARRIVE_FADE_MS, FAST_OUT_SLOW_IN));
        lockup.setTranslationY((1f - progress(t, 0, ARRIVE_MS, EMPHASIZED_DECELERATE)) * risePx);

        // Greeting leaves: fade + gentle upward drift, sub-pixel. It stays
        // VISIBLE until its alpha is exactly 0.
        float leave = progress(t, CUT_AT_MS, CUT_MS, FAST_OUT_SLOW_IN);
        greeting.setAlpha(1f - leave);
        greeting.setTranslationY(-leave * driftPx);
        greeting.setVisibility(leave >= 1f ? View.INVISIBLE : View.VISIBLE);

        // The name glides into the space the greeting left.
        name.setTranslationY(progress(t, MOVE_AT_MS, MOVE_MS, FAST_OUT_SLOW_IN) * moveDy);

        // Loader -> Hax: overlapping fades plus one slide, all on one clock.
        float swap = linear(t, SWAP_AT_MS, SWAP_MS);
        name.setTranslationX(DISSOLVE.getInterpolation(swap) * recentreDx);
        hax.setAlpha(DISSOLVE.getInterpolation(window(swap, 0f, HAX_IN_END)));
        loader.setAlpha(1f - DISSOLVE.getInterpolation(window(swap, LOADER_OUT_START, 1f)));

        float footer = progress(t, VERSION_AT_MS, VERSION_MS, FAST_OUT_SLOW_IN);
        version.setAlpha(footer);
        // Once shown, the status line stays (it may carry an error and Retry).
        if (statusBar.getAlpha() < footer) statusBar.setAlpha(footer);
    }

    /**
     * Hardware layers for the moving text while the film runs, so alpha,
     * translation and scale are composited instead of re-rasterising the glyphs
     * every frame. Off again at the hand-off.
     */
    private void setTextLayers(boolean on) {
        int type = on ? View.LAYER_TYPE_HARDWARE : View.LAYER_TYPE_NONE;
        for (View v : new View[] {greeting, name, loader, hax}) {
            if (v != null && v.getLayerType() != type) v.setLayerType(type, null);
        }
    }

    /** Raw 0..1 progress of t through [start, start + duration]. */
    private static float linear(long t, long start, long duration) {
        if (t <= start) return 0f;
        if (t >= start + duration) return 1f;
        return (t - start) / (float) duration;
    }

    /** Re-maps p so it runs 0..1 across [from, to] and is clamped outside. */
    private static float window(float p, float from, float to) {
        if (p <= from) return 0f;
        if (p >= to) return 1f;
        return (p - from) / (to - from);
    }

    private static float progress(long t, long start, long duration, Interpolator easing) {
        if (t <= start) return 0f;
        if (t >= start + duration) return 1f;
        return easing.getInterpolation((t - start) / (float) duration);
    }

    /** Reduced motion: welcome, then the final line, then hand off. No movement. */
    private void playReduced() {
        root.post(() -> {
            if (!Ui.alive(this)) return;
            if (!measure()) {
                statusBar.setAlpha(1f);
                onFilmDone();
                return;
            }
            applyTime(CUT_AT_MS); // welcome lockup, settled, system mark gone
            handler.postDelayed(() -> {
                if (!Ui.alive(this)) return;
                applyTime(FILM_MS);
                handler.postDelayed(next, REDUCED_FINAL_MS);
            }, REDUCED_WELCOME_MS);
        });
        handler.postDelayed(next, REDUCED_WELCOME_MS + REDUCED_FINAL_MS + 2500L);
    }

    /** Startup outlasted the film: keep "Anoy Hax" with a very slow opacity breathe. */
    private void breathe() {
        if (!Ui.alive(this) || Ui.reducedMotion(this) || breath != null) return;
        breath = ValueAnimator.ofFloat(1f, BREATHE_MIN_ALPHA);
        breath.setDuration(BREATHE_MS / 2);
        breath.setInterpolator(FAST_OUT_SLOW_IN);
        breath.setRepeatMode(ValueAnimator.REVERSE);
        breath.setRepeatCount(ValueAnimator.INFINITE);
        breath.addUpdateListener(a -> name.setAlpha((float) a.getAnimatedValue()));
        breath.start();
    }

    private void openLogin() {
        if (opened || !Ui.alive(this)) return;
        opened = true;
        startActivity(new Intent(this, LogAct.class));
        // 320 ms crossfade + 8 dp drift (res/anim/fade_in, fade_out).
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
        finish();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (film != null) film.cancel();
        if (breath != null) breath.cancel();
        checker.shutdownNow();
        setTextLayers(false);
        super.onDestroy();
    }
}
