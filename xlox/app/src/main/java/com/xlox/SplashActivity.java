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
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.splashscreen.SplashScreen;

import com.xlox.ui.Ui;

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
 *   0.50-1.20 s  read     hold
 *   1.20 s       leave    "Welcome To" dissolves: fades out while drifting up
 *                         10 dp (420 ms, no clipping, no squash)
 *   1.28 s       move     "Anoy Loader" eases up onto the true screen centre
 *   1.36 s       swap     "Loader" cross-dissolves into "Hax" in the same slot
 *                         while the pair slides to centre - one 400 ms motion,
 *                         one easing, so "Anoy" glides sideways exactly once
 *   ~1.80 s      settle   hold "Anoy Hax"
 *   2.85 s       handoff  existing 320 ms cross-fade into LogAct (~3.17 s)
 * </pre>
 * Every property is a pure function of time ({@link #applyTime}) driven by one
 * animator, so the three cut moves stay locked together as one gesture.
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
    /** Leave: the greeting fades out, drifts up and eases to 98 %. Never clipped. */
    private static final long CUT_AT_MS = 1200L;
    private static final long CUT_MS = 420L;
    private static final float CUT_DRIFT_DP = 10f;
    private static final float CUT_END_SCALE = 0.98f;
    /** Move: "Anoy Loader" eases into the space the greeting left. */
    private static final long MOVE_AT_MS = CUT_AT_MS + 80L;
    private static final long MOVE_MS = 460L;
    /**
     * Swap: "Loader" and "Hax" cross-dissolve in the same slot (start edges
     * aligned) and the pair re-centres - one progress value, one easing, so the
     * fade and the slide are a single motion. Both words stay at full size apart
     * from a 1.0 <-> 0.98 breath.
     */
    private static final long SWAP_AT_MS = MOVE_AT_MS + 80L;
    private static final long SWAP_MS = 400L;
    private static final float SWAP_SCALE = 0.98f;
    /**
     * Below 1 so the two fades sum to more than 100 % mid-way (0.57 + 0.57 at
     * the midpoint): the slot never dims or looks empty.
     */
    private static final float SWAP_ALPHA_POWER = 0.8f;
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
     * Material 3 "emphasized" ease (single-curve form): starts gently - 16 % at a
     * tenth of the time, half-way at a fifth - then a long soft settle. Used for
     * every text change so nothing snaps.
     */
    private static final Interpolator SMOOTH = new PathInterpolator(0.2f, 0f, 0f, 1f);

    /** Debug builds only: hold the splash (used by the screenshot QA). */
    public static final String EXTRA_HOLD = "qa_hold";
    /** Debug builds only: freeze the film at a time in seconds for screenshots. */
    public static final String EXTRA_FRAME = "qa_frame";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable next = this::openLogin;
    private View root;
    private View mark;
    private View lockup;
    private TextView greeting;
    private View name;
    private TextView loader;
    private TextView hax;
    private TextView version;
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
        SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);
        // Re-launch from the launcher while a task already exists: just finish.
        if (!isTaskRoot() && getIntent() != null && Intent.ACTION_MAIN.equals(getIntent().getAction())
                && getIntent().hasCategory(Intent.CATEGORY_LAUNCHER)) {
            finish();
            return;
        }
        hold = BuildConfig.DEBUG && getIntent() != null && getIntent().getBooleanExtra(EXTRA_HOLD, false);
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
            version.setText(getString(R.string.version_footer_app, BuildConfig.VERSION_NAME));
            risePx = Ui.dp(this, ARRIVE_RISE_DP);
            driftPx = Ui.dp(this, CUT_DRIFT_DP);
            // One real word space between "Anoy" and the Loader/Hax slot.
            TextView anoy = findViewById(R.id.splashAnoy);
            View slotView = findViewById(R.id.splashSlot);
            ViewGroup.MarginLayoutParams slot = (ViewGroup.MarginLayoutParams) slotView.getLayoutParams();
            slot.setMarginStart(Math.round(anoy.getPaint().measureText(" ")));
            slotView.setLayoutParams(slot);
            final int footerMargin = getResources().getDimensionPixelSize(R.dimen.space_24);
            Ui.onInsets(root, (bars, bottom, ime) -> {
                // Only re-lays out when the inset really changes - never per frame.
                ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) version.getLayoutParams();
                int margin = footerMargin + bars.bottom;
                if (lp.bottomMargin != margin) {
                    lp.bottomMargin = margin;
                    version.setLayoutParams(lp);
                }
            });
        } catch (RuntimeException e) {
            // Never let a cosmetic screen block the app.
            Log.e("SplashActivity", "Splash layout failed", e);
            openLogin();
            return;
        }

        if (qaFrame >= 0f) {
            // QA: one still frame of the film, no navigation.
            root.post(() -> {
                if (!Ui.alive(this) || !measure()) return;
                applyTime(Math.round(qaFrame * 1000f));
            });
            return;
        }
        if (Ui.reducedMotion(this)) {
            playReduced();
            return;
        }
        // Start on the first drawn frame (after layout) so a slow cold start
        // can't eat into the film; until then the system wordmark stays put.
        root.post(() -> {
            if (!Ui.alive(this)) return;
            if (!measure()) {
                openLogin();
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
                    if (hold) {
                        breathe();
                    } else {
                        setTextLayers(false);
                        openLogin();
                    }
                }
            });
            setTextLayers(true);
            film.start();
        });
        if (!hold) handler.postDelayed(next, SAFETY_MS);
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
        // The 0.98 breath scales about the shared start edge and cap centre, so
        // "Hax" grows out of exactly where "Loader" began.
        float pivot = loader.getBaseline() - CAP_HEIGHT_EM * loader.getTextSize() / 2f;
        loader.setPivotX(0f);
        loader.setPivotY(pivot);
        hax.setPivotX(0f);
        hax.setPivotY(pivot);
        greeting.setPivotX(greeting.getWidth() / 2f);
        greeting.setPivotY(greeting.getHeight());
        measured = true;
        return true;
    }

    /** Sets every view for film time t (ms). Pure: the same t gives the same frame. */
    private void applyTime(long t) {
        mark.setAlpha(1f - progress(t, 0, MARK_FADE_MS, FAST_OUT_SLOW_IN));

        lockup.setAlpha(progress(t, 0, ARRIVE_FADE_MS, FAST_OUT_SLOW_IN));
        lockup.setTranslationY((1f - progress(t, 0, ARRIVE_MS, EMPHASIZED_DECELERATE)) * risePx);

        // Greeting leaves: fade + drift + slight scale, all sub-pixel. It stays
        // VISIBLE until its alpha is exactly 0.
        float leave = progress(t, CUT_AT_MS, CUT_MS, SMOOTH);
        greeting.setAlpha(1f - leave);
        greeting.setTranslationY(-leave * driftPx);
        float greetScale = 1f - leave * (1f - CUT_END_SCALE);
        greeting.setScaleX(greetScale);
        greeting.setScaleY(greetScale);
        greeting.setVisibility(leave >= 1f ? View.INVISIBLE : View.VISIBLE);

        // The name rises into the space the greeting left (unchanged ease).
        name.setTranslationY(progress(t, MOVE_AT_MS, MOVE_MS, EMPHASIZED_DECELERATE) * moveDy);

        // Loader -> Hax: one progress drives the cross-dissolve and the slide.
        float swap = progress(t, SWAP_AT_MS, SWAP_MS, SMOOTH);
        name.setTranslationX(swap * recentreDx);
        loader.setAlpha((float) Math.pow(1f - swap, SWAP_ALPHA_POWER));
        hax.setAlpha((float) Math.pow(swap, SWAP_ALPHA_POWER));
        float loaderScale = 1f - swap * (1f - SWAP_SCALE);
        float haxScale = SWAP_SCALE + swap * (1f - SWAP_SCALE);
        loader.setScaleX(loaderScale);
        loader.setScaleY(loaderScale);
        hax.setScaleX(haxScale);
        hax.setScaleY(haxScale);

        version.setAlpha(progress(t, VERSION_AT_MS, VERSION_MS, FAST_OUT_SLOW_IN));
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
                if (!hold) openLogin();
                return;
            }
            applyTime(CUT_AT_MS); // welcome lockup, settled, system mark gone
            handler.postDelayed(() -> {
                if (!Ui.alive(this)) return;
                applyTime(FILM_MS);
                if (!hold) handler.postDelayed(next, REDUCED_FINAL_MS);
            }, REDUCED_WELCOME_MS);
        });
        if (!hold) handler.postDelayed(next, REDUCED_WELCOME_MS + REDUCED_FINAL_MS + 2500L);
    }

    /** Startup outlasted the film: keep "Anoy Hax" with a very slow opacity breathe. */
    private void breathe() {
        if (!Ui.alive(this) || Ui.reducedMotion(this)) return;
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
        setTextLayers(false);
        super.onDestroy();
    }
}
