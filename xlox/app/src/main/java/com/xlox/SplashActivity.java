package com.xlox;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.splashscreen.SplashScreen;

import com.airbnb.lottie.LottieAnimationView;
import com.airbnb.lottie.LottieComposition;
import com.airbnb.lottie.LottieCompositionFactory;
import com.airbnb.lottie.LottieDrawable;
import com.airbnb.lottie.LottieResult;
import com.airbnb.lottie.RenderMode;
import com.xlox.ui.Ui;

/**
 * Launcher entry. The system splash (core-splashscreen) shows the Anoy
 * monogram on black - frame 0 of res/raw/anoy_mark.json, a plain white A - so
 * the hand-off to this activity is invisible. The blue crossbar then draws
 * through the A and past its right leg, the mark lifts and the wordmark fades
 * in under it (~1.9 s), then sign in / the update check. The monogram is the
 * same artwork as the launcher icon (scripts/brand_mark.py).
 */
public class SplashActivity extends AppCompatActivity {

    /** Hand off at 1.7 s of the 2.0 s mark, plus a 200 ms hold (~1.9 s). */
    private static final float HANDOFF_PROGRESS = 102f / 120f;
    private static final long HOLD_MS = 200L;
    /** Wordmark fades in while the mark lifts (frame 54 = 0.9 s). */
    private static final long WORDMARK_DELAY_MS = 900L;
    private static final float WORDMARK_PROGRESS = 54f / 120f;
    /** Final 0.6 s breathing segment (opacity only), looped only if the hand-off is late. */
    private static final int BREATH_START = 84;
    private static final int BREATH_END = 120;
    private static final long REDUCED_MOTION_MS = 700L;

    /** Debug builds only: hold the splash (used by the screenshot QA). */
    public static final String EXTRA_HOLD = "qa_hold";
    /** Debug builds only: freeze the mark at a progress 0..1 for screenshots. */
    public static final String EXTRA_FRAME = "qa_frame";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable next = this::openLogin;
    private LottieAnimationView mark;
    private View wordmark;
    private TextView version;
    private boolean opened;
    private boolean handoffScheduled;
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
        float qaFrame = BuildConfig.DEBUG && getIntent() != null ? getIntent().getFloatExtra(EXTRA_FRAME, -1f) : -1f;
        boolean hasMark;
        try {
            Ui.edgeToEdge(this);
            setContentView(R.layout.activity_splash);
            mark = findViewById(R.id.splashMark);
            wordmark = findViewById(R.id.splashContent);
            version = findViewById(R.id.splashVersion);
            version.setText(getString(R.string.version_footer_app, BuildConfig.VERSION_NAME));
            final int footerMargin = getResources().getDimensionPixelSize(R.dimen.space_24);
            Ui.onInsets(findViewById(R.id.splashRoot), (bars, bottom, ime) -> {
                ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) version.getLayoutParams();
                lp.bottomMargin = footerMargin + bars.bottom;
                version.setLayoutParams(lp);
            });
            hasMark = loadMark();
        } catch (RuntimeException e) {
            // Never let a cosmetic screen block the app.
            Log.e("SplashActivity", "Splash layout failed", e);
            openLogin();
            return;
        }

        if (qaFrame >= 0f) {
            // QA: a still frame of the sequence, no navigation.
            if (hasMark) mark.setProgress(Math.min(1f, qaFrame));
            boolean words = qaFrame >= WORDMARK_PROGRESS;
            wordmark.setAlpha(words ? Math.min(1f, (qaFrame - WORDMARK_PROGRESS) / 0.18f) : 0f);
            version.setAlpha(1f);
            return;
        }
        if (!hasMark || Ui.reducedMotion(this)) {
            // Reduced motion: final frame, instant text, short hold.
            if (hasMark) mark.setProgress(1f);
            wordmark.setAlpha(1f);
            version.setAlpha(1f);
            if (!hold) handler.postDelayed(next, REDUCED_MOTION_MS);
            return;
        }

        mark.addAnimatorUpdateListener(a -> {
            if (!handoffScheduled && mark.getProgress() >= HANDOFF_PROGRESS) {
                handoffScheduled = true;
                if (!hold) handler.postDelayed(next, HOLD_MS);
            }
        });
        mark.addAnimatorListener(new AnimatorListenerAdapter() {
            private boolean done;

            @Override
            public void onAnimationEnd(Animator animation) {
                if (done) return;
                done = true;
                breathe();
            }
        });
        // Start on the first drawn frame so a slow cold start can't eat into it.
        getWindow().getDecorView().post(() -> {
            if (!Ui.alive(this)) return;
            mark.playAnimation();
            handler.postDelayed(this::showWordmark, WORDMARK_DELAY_MS);
            version.animate().alpha(1f).setStartDelay(260).setDuration(Ui.SLOW).setInterpolator(Ui.ENTER).start();
        });
        // Safety net in case the frame callback never runs.
        if (!hold) handler.postDelayed(next, 2000L + HOLD_MS + 2500L);
    }

    /** Loads the mark synchronously so frame 0 is on screen in the very first frame. */
    private boolean loadMark() {
        LottieResult<LottieComposition> result = LottieCompositionFactory.fromRawResSync(this, R.raw.anoy_mark);
        if (result.getValue() == null) {
            Log.w("SplashActivity", "Splash mark unavailable", result.getException());
            mark.setVisibility(View.GONE);
            return false;
        }
        mark.setRenderMode(RenderMode.HARDWARE);
        mark.setComposition(result.getValue());
        mark.setProgress(0f);
        return true;
    }

    private void showWordmark() {
        if (!Ui.alive(this)) return;
        wordmark.setTranslationY(Ui.dp(this, 8));
        wordmark.animate().alpha(1f).translationY(0f).setDuration(Ui.SLOW).setInterpolator(Ui.ENTER).start();
    }

    /** Startup outlasted the mark: loop only the final 0.6 s breathing segment. */
    private void breathe() {
        if (!Ui.alive(this) || mark == null) return;
        mark.setMinAndMaxFrame(BREATH_START, BREATH_END);
        mark.setRepeatMode(LottieDrawable.RESTART);
        mark.setRepeatCount(LottieDrawable.INFINITE);
        mark.playAnimation();
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
        if (mark != null) mark.cancelAnimation();
        super.onDestroy();
    }
}
