package com.xlox;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.splashscreen.SplashScreen;
import androidx.core.splashscreen.SplashScreenViewProvider;

import com.airbnb.lottie.LottieAnimationView;
import com.xlox.ui.GlassLensView;
import com.xlox.ui.Liquid;
import com.xlox.ui.Motion;
import com.xlox.ui.Ui;
import com.xlox.utils.AppUpdater;
import com.xlox.utils.UpdateException;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A short, one-shot glass-lens reveal over the original brand artwork.
 * The real APK update check starts immediately and is independent of animation.
 * No simulated progress, extra 3-second hold or indefinite decorative breathing.
 * Remove animations = a static final frame; backgrounding stops all motion.
 */
public class SplashActivity extends AppCompatActivity {
    private static final long FILM_MS = 1100L;
    private static final long CURRENT_HOLD_MS = 700L;
    private static final long PROCEED_MS = 400L;
    private static final long DONE_OUT_MS = Ui.SWAP_OUT;
    private static final long DONE_IN_MS = Ui.SWAP_IN;
    private static final long SWAP_OUT_MS = Ui.SWAP_OUT;
    private static final long SWAP_IN_MS = Ui.SWAP_IN;
    private static final long SLOW_STATUS_MS = Ui.SLOW_MS;
    private static final long SLOW_DETAIL_MS = 15_000L;
    private static final long QA_CHECK_MS = 900L;

    public static final String EXTRA_HOLD = "qa_hold";
    public static final String EXTRA_FRAME = "qa_frame";
    public static final String EXTRA_QA_APP_UPDATE = "qa_app_update";
    public static final String EXTRA_QA_REAL_URL = "qa_real_update_url";
    public static final String EXTRA_QA_REAL_CODE = "qa_real_update_code";
    public static final String EXTRA_QA_REAL_NAME = "qa_real_update_name";

    private enum Check { PENDING, CHECKING, CURRENT, AVAILABLE, FAILED }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService checker = Executors.newSingleThreadExecutor(r -> new Thread(r, "anoy-app-check"));
    private View root;
    private View mark;
    private View name;
    private View tagline;
    private GlassLensView lens;
    private TextView version;
    private View statusBar;
    private View statusRow;
    private LottieAnimationView statusMark;
    private TextView statusLine;
    private TextView statusDetail;
    private View retry;
    private Check check = Check.PENDING;
    private AppUpdater.Release release;
    private AppUpdater.Release qaReal;
    private String qaUpdate;
    private int checkRun;
    private long statusShownAt;
    private ValueAnimator film;
    private boolean filmDone;
    private boolean handingOff;
    private boolean opened;
    private boolean hold;
    private boolean foreground;

    @Override protected void onCreate(Bundle savedInstanceState) {
        SplashScreen splash = SplashScreen.installSplashScreen(this);
        splash.setOnExitAnimationListener(SplashScreenViewProvider::remove);
        super.onCreate(savedInstanceState);
        if (!isTaskRoot() && getIntent() != null && Intent.ACTION_MAIN.equals(getIntent().getAction())
                && getIntent().hasCategory(Intent.CATEGORY_LAUNCHER)) {
            finish();
            return;
        }
        hold = BuildConfig.DEBUG && getIntent() != null && getIntent().getBooleanExtra(EXTRA_HOLD, false);
        qaUpdate = BuildConfig.DEBUG && getIntent() != null ? getIntent().getStringExtra(EXTRA_QA_APP_UPDATE) : null;
        String qaRealUrl = BuildConfig.DEBUG && getIntent() != null ? getIntent().getStringExtra(EXTRA_QA_REAL_URL) : null;
        if (qaRealUrl != null) {
            String qaRealName = getIntent().getStringExtra(EXTRA_QA_REAL_NAME);
            qaReal = new AppUpdater.Release(qaRealName != null ? qaRealName : "e2e",
                    getIntent().getIntExtra(EXTRA_QA_REAL_CODE, 0), qaRealUrl, "End-to-end update test", false);
        }
        float frame = BuildConfig.DEBUG && getIntent() != null ? getIntent().getFloatExtra(EXTRA_FRAME, -1f) : -1f;
        try {
            Ui.edgeToEdge(this);
            setContentView(R.layout.activity_splash);
            Liquid.attach(this);
            root = findViewById(R.id.splashRoot);
            mark = findViewById(R.id.splashMark);
            name = findViewById(R.id.splashName);
            tagline = findViewById(R.id.splashTagline);
            lens = findViewById(R.id.splashLens);
            version = findViewById(R.id.splashVersion);
            version.setText(getString(R.string.splash_version, BuildConfig.VERSION_NAME));
            statusBar = findViewById(R.id.splashStatusBar);
            statusRow = findViewById(R.id.splashStatusRow);
            statusMark = findViewById(R.id.splashStatusMark);
            statusLine = findViewById(R.id.splashStatus);
            statusDetail = findViewById(R.id.splashStatusDetail);
            retry = findViewById(R.id.splashRetry);
            retry.setOnClickListener(v -> { Ui.tap(v); startCheck(); });
            Ui.onInsets(root, (bars, bottom, ime) -> {
                ViewGroup.MarginLayoutParams vp = (ViewGroup.MarginLayoutParams) version.getLayoutParams();
                int top = Ui.dp(this, 24) + bars.top;
                int end = Ui.dp(this, 24) + Math.max(bars.left, bars.right);
                if (vp.topMargin != top || vp.getMarginEnd() != end) {
                    vp.topMargin = top;
                    vp.setMarginEnd(end);
                    version.setLayoutParams(vp);
                }
                ViewGroup.MarginLayoutParams sp = (ViewGroup.MarginLayoutParams) statusBar.getLayoutParams();
                int margin = Ui.dp(this, 24) + bars.bottom;
                if (sp.bottomMargin != margin || sp.leftMargin != bars.left || sp.rightMargin != bars.right) {
                    sp.bottomMargin = margin;
                    sp.leftMargin = bars.left;
                    sp.rightMargin = bars.right;
                    statusBar.setLayoutParams(sp);
                }
            });
        } catch (RuntimeException error) {
            Log.e("SplashActivity", "Splash layout failed", error);
            openLogin();
            return;
        }
        if (frame >= 0 || hold) {
            hold = true;
            if (qaUpdate == null) qaUpdate = "checking";
        }
        // Do not delay a real network operation to wait for a decorative reveal.
        startCheck();
        if (frame >= 0) {
            applyTime(Math.round(frame * 1000));
            filmDone = true;
        } else if (Ui.reducedMotion(this)) {
            applyTime(FILM_MS);
            filmDone = true;
        } else {
            applyTime(0);
            root.post(this::playIntro);
        }
    }

    private void playIntro() {
        if (!Ui.alive(this) || filmDone) return;
        film = ValueAnimator.ofFloat(0, FILM_MS);
        film.setDuration(FILM_MS);
        film.setInterpolator(new LinearInterpolator());
        film.addUpdateListener(a -> applyTime((long) (float) a.getAnimatedValue()));
        film.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;
            @Override public void onAnimationCancel(Animator animation) { cancelled = true; }
            @Override public void onAnimationEnd(Animator animation) {
                if (!cancelled) { applyTime(FILM_MS); onFilmDone(); }
            }
        });
        film.start();
    }

    /** Pure phase mapping lets screenshot QA inspect real animation frames. */
    private void applyTime(long millis) {
        float p = Math.max(0f, Math.min(1f, millis / (float) FILM_MS));
        float arrive = Ui.ENTER.getInterpolation(Math.min(1, p * 1.6f));
        mark.setAlpha(.55f + .45f * arrive);
        lens.setAlpha(arrive);
        lens.setPhase(p);
        lens.setScaleX(.94f + .06f * arrive);
        lens.setScaleY(.94f + .06f * arrive);
        name.setAlpha(arrive);
        name.setTranslationY(Ui.dp(this, 8) * (1 - arrive));
        tagline.setAlpha(Ui.ENTER.getInterpolation(Math.max(0, Math.min(1, (p - .2f) * 1.6f))));
    }

    // ------------------------------------------------------ app update check

    /** The film is over (or could not run): go on once the check allows it. */
    private void onFilmDone() {
        if (filmDone || !Ui.alive(this)) return;
        filmDone = true;
        maybeProceed();
    }

    /** (Re)runs the check; the status line follows its real state. */
    private void startCheck() {
        if (!Ui.alive(this) || handingOff) return;
        if (check == Check.CHECKING) return;
        final int run = ++checkRun;
        check = Check.CHECKING;
        release = null;
        setStatus(getString(R.string.splash_status_checking), 0, 0, true, 0, 0);
        hideFailure();
        handler.postDelayed(() -> {
            if (run != checkRun || check != Check.CHECKING || !Ui.alive(this)) return;
            Ui.swapText(statusLine, getString(R.string.splash_status_slow));
        }, SLOW_STATUS_MS);
        handler.postDelayed(() -> {
            if (run != checkRun || check != Check.CHECKING || !Ui.alive(this)) return;
            statusDetail.setText(R.string.splash_status_slow_detail);
            Ui.fadeReserved(statusDetail, true);
        }, SLOW_DETAIL_MS);
        if (qaUpdate != null) {
            simulateCheck(run);
            return;
        }
        if (qaReal != null) {
            Log.i("SplashActivity", "Debug: offering " + qaReal.versionName + " (" + qaReal.versionCode + ") from the intent");
            handler.postDelayed(() -> onChecked(run, qaReal), QA_CHECK_MS);
            return;
        }
        Log.i("SplashActivity", "Checking for an app update");
        checker.execute(() -> {
            try {
                // 2.3.3: up to date less than 10 minutes ago -> no network.
                if (AppUpdater.recentlyUpToDate(this)) {
                    Log.i("SplashActivity", "Up to date (checked within 10 minutes); skipping the network check");
                    handler.post(() -> onChecked(run, null));
                    return;
                }
                AppUpdater.Release found = AppUpdater.checkForSplash(this);
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
            setStatus(getString(R.string.splash_status_current), R.drawable.ic_check, R.color.success, false,
                    DONE_OUT_MS, DONE_IN_MS);
        } else {
            check = Check.AVAILABLE;
            Log.i("SplashActivity", "App update available: " + found.versionName + " (" + found.versionCode + ")");
            setStatus(getString(R.string.splash_status_available, found.versionName), 0, 0, false,
                    SWAP_OUT_MS, SWAP_IN_MS);
        }
        // The slow-connection detail (if it came up) leaves with the answer.
        Ui.fadeReserved(statusDetail, false);
        maybeProceed();
    }

    private void onCheckFailed(int run, String reason) {
        if (run != checkRun || !Ui.alive(this)) return;
        check = Check.FAILED;
        setStatus(getString(R.string.splash_status_failed), R.drawable.dot_status, R.color.warning, false,
                SWAP_OUT_MS, SWAP_IN_MS);
        // Try again sits right below, so the reason drops its own "Try again."
        String detail = Ui.dropTryAgain(reason);
        statusDetail.animate().cancel();
        statusDetail.setAlpha(1f);
        statusDetail.setText(detail);
        statusDetail.setVisibility(detail == null || detail.isEmpty() ? View.INVISIBLE : View.VISIBLE);
        retry.setVisibility(View.VISIBLE);
        // Never left invisible behind a still-running film.
        statusBar.setAlpha(1f);
    }

    /**
     * Hands off when both the film and the check are done: up to date goes to
     * sign in, a newer release to the update screen. False while waiting (or
     * after a failure, which waits for Try again).
     */
    private boolean maybeProceed() {
        if (!foreground || !filmDone || hold || handingOff) return false;
        long shown = android.os.SystemClock.uptimeMillis() - statusShownAt;
        if (check == Check.CURRENT) {
            // The green check + "Up to date" holds for at least 700 ms.
            handingOff = true;
            handler.postDelayed(this::openLogin, Math.max(0L, DONE_OUT_MS + CURRENT_HOLD_MS - shown));
            return true;
        }
        if (check == Check.AVAILABLE && release != null) {
            handingOff = true;
            handler.postDelayed(this::openUpdate, Math.max(0L, SWAP_OUT_MS + SWAP_IN_MS + PROCEED_MS - shown));
            return true;
        }
        return false;
    }

    /**
     * Status line: Caption text with the quiet arc, the check (2.3.7: Lottie,
     * one-shot), a tinted glyph (amber dot) or nothing. With durations it crossfades (opacity
     * only); before the line is visible, or with animations off, it is instant.
     */
    private void setStatus(String text, int iconRes, int colourRes, boolean spinner, long outMs, long inMs) {
        statusShownAt = android.os.SystemClock.uptimeMillis();
        boolean animate = outMs > 0 && statusBar.getAlpha() > 0f && !Ui.reducedMotion(this);
        statusRow.animate().cancel();
        if (!animate) {
            statusRow.setAlpha(1f);
            applyStatus(text, iconRes, colourRes, spinner);
            return;
        }
        statusRow.animate().alpha(0f).setDuration(outMs).setInterpolator(new LinearInterpolator())
                .withEndAction(() -> {
                    if (!Ui.alive(this)) return;
                    applyStatus(text, iconRes, colourRes, spinner);
                    statusRow.animate().alpha(1f).setDuration(inMs).setInterpolator(new LinearInterpolator()).start();
                }).start();
    }

    private void applyStatus(String text, int iconRes, int colourRes, boolean spinner) {
        // Drop a pending "Taking longer than usual…" swap: the real state wins.
        statusLine.animate().cancel();
        statusLine.setAlpha(1f);
        statusLine.setTag(R.id.tag_swap_text, null);
        statusLine.setText(text);
        int colour = ContextCompat.getColor(this, colourRes != 0 ? colourRes : R.color.text_secondary);
        // 2.3.7: "Up to date" is the Lottie check drawing in, not a glyph.
        boolean done = iconRes == R.drawable.ic_check;
        Drawable glyph = null;
        if (iconRes != 0 && !done) {
            glyph = ContextCompat.getDrawable(this, iconRes);
            if (glyph != null) {
                int size = getResources().getDimensionPixelSize(R.dimen.icon_chip);
                glyph = glyph.mutate();
                glyph.setBounds(0, 0, size, size);
            }
        }
        statusLine.setCompoundDrawablesRelative(glyph, null, null, null);
        androidx.core.widget.TextViewCompat.setCompoundDrawableTintList(statusLine, ColorStateList.valueOf(colour));
        if (spinner) Motion.arc(statusMark);
        else if (done) Motion.check(statusMark);
        else Motion.hide(statusMark);
        if (!foreground) statusMark.pauseAnimation();
    }

    /** The detail line keeps its space (INVISIBLE), so the status never jumps. */
    private void hideFailure() {
        statusDetail.animate().cancel();
        statusDetail.setAlpha(1f);
        statusDetail.setVisibility(View.INVISIBLE);
        statusDetail.setText("");
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
                    // A Try again in the sample then succeeds.
                    qaUpdate = "up_to_date";
                    break;
                default:
                    onChecked(run, null);
                    break;
            }
        }, QA_CHECK_MS);
    }

    private static AppUpdater.Release sampleRelease(boolean mandatory) {
        return new AppUpdater.Release("2.5.1", BuildConfig.VERSION_CODE + 1,
                "https://example.com/" + BuildConfig.DISPLAY_BRAND + ".apk",
                "Faster start-up\nClearer update messages\nFixes for the game files copy", mandatory);
    }

    private void openUpdate() {
        if (!foreground) { handingOff = false; return; }
        if (opened || !Ui.alive(this) || release == null) return;
        opened = true;
        Intent intent = new Intent(this, AppUpdateActivity.class);
        release.writeTo(intent);
        if (qaUpdate != null) intent.putExtra(AppUpdateActivity.EXTRA_QA_PREVIEW, true);
        startActivity(intent);
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
        finish();
    }


    private void openLogin() {
        if (root != null && !foreground) { handingOff = false; return; }
        if (opened || !Ui.alive(this)) return;
        opened = true;
        startActivity(new Intent(this, LogAct.class));
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
        finish();
    }

    @Override protected void onStart() {
        super.onStart();
        foreground = true;
        if (statusMark != null && (check == Check.PENDING || check == Check.CHECKING)) Motion.arc(statusMark);
        maybeProceed();
    }

    @Override protected void onStop() {
        foreground = false;
        if (film != null) film.cancel();
        if (mark != null) applyTime(FILM_MS);
        filmDone = true;
        if (statusMark != null) statusMark.pauseAnimation();
        super.onStop();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (film != null) film.cancel();
        if (statusMark != null) statusMark.cancelAnimation();
        checker.shutdownNow();
        super.onDestroy();
    }
}
