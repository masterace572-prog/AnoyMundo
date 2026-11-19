package com.xlox;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
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

import com.xlox.ui.Motion;
import com.xlox.ui.Ui;
import com.xlox.utils.AppUpdater;
import com.xlox.utils.UpdateException;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Launcher entry: a local, full-screen Lottie brand film on true black followed
 * by the existing app-update gate. The bundled animation is an offline,
 * black-and-white rebrand of the owner-selected Techpost motion; its existing
 * vector glyph paths are replaced in-place with {@code AnoyHax} and {@code A}.
 * See assets/licenses/LOTTIE.md.
 *
 * <p>The system splash is removed at once so the app controls a seamless black
 * hand-off. The version and app-update status retain their prior timing and
 * behaviour: a current app continues to sign-in, a newer app opens the update
 * screen, and a failure stays on Splash with a retry action. The Lottie ending
 * is the visual hand-off point. With reduced motion enabled, its final branded
 * frame is shown without playing the film.</p>
 */
public class SplashActivity extends AppCompatActivity {

    /** The source's final branded `A` pose: frame 400 of the 60 fps Lottie. */
    private static final int SPLASH_FINAL_FRAME = 400;
    private static final long FILM_MS = 6_667L;
    private static final long VERSION_AT_MS = 260L;
    private static final long VERSION_MS = 320L;
    private static final long STATUS_AT_MS = 600L;
    private static final long STATUS_MS = 280L;
    private static final long REDUCED_FINAL_MS = 700L;
    /** Safety net if a renderer cannot start the animation. */
    private static final long SAFETY_MS = FILM_MS + 2_500L;

    /** Debug builds only: hold the splash (used by the screenshot QA). */
    public static final String EXTRA_HOLD = "qa_hold";
    /** Debug builds only: freeze the film at a time in seconds for screenshots. */
    public static final String EXTRA_FRAME = "qa_frame";
    /** Debug builds only: simulate the app update check without the network. */
    public static final String EXTRA_QA_APP_UPDATE = "qa_app_update";
    /** Debug builds only: offer this real release instead of asking the panel. */
    public static final String EXTRA_QA_REAL_URL = "qa_real_update_url";
    public static final String EXTRA_QA_REAL_CODE = "qa_real_update_code";
    public static final String EXTRA_QA_REAL_NAME = "qa_real_update_name";

    /** Status line: the check starts after the version and status have faded in. */
    private static final long CHECK_AT_MS = VERSION_AT_MS + VERSION_MS;
    /** "Up to date" stays on screen before the hand-off. */
    private static final long CURRENT_HOLD_MS = 700L;
    /** "Update available" stays readable after its crossfade. */
    private static final long PROCEED_MS = 400L;
    private static final long DONE_OUT_MS = Ui.SWAP_OUT;
    private static final long DONE_IN_MS = Ui.SWAP_IN;
    private static final long SWAP_OUT_MS = Ui.SWAP_OUT;
    private static final long SWAP_IN_MS = Ui.SWAP_IN;
    private static final long SLOW_STATUS_MS = Ui.SLOW_MS;
    private static final long SLOW_DETAIL_MS = 15_000L;
    private static final long QA_CHECK_MS = 900L;

    private enum Check { PENDING, CHECKING, CURRENT, AVAILABLE, FAILED }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable next = this::onFilmDone;
    private final ExecutorService checker = Executors.newSingleThreadExecutor(
            r -> new Thread(r, "anoy-app-check"));

    private View root;
    private LottieAnimationView splashAnimation;
    private TextView version;
    private View statusBar;
    private View statusRow;
    private LottieAnimationView statusMark;
    private TextView statusLine;
    private TextView statusDetail;
    private View retry;
    /** Uptime when the current status text appeared (for the current-state hold). */
    private long statusShownAt;
    private Check check = Check.PENDING;
    private AppUpdater.Release release;
    private int checkRun;
    private boolean filmDone;
    private boolean handingOff;
    private boolean opened;
    private boolean hold;
    private String qaUpdate;
    private AppUpdater.Release qaReal;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SplashScreen splash = SplashScreen.installSplashScreen(this);
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
        String qaRealUrl = BuildConfig.DEBUG && getIntent() != null ? getIntent().getStringExtra(EXTRA_QA_REAL_URL) : null;
        if (qaRealUrl != null) {
            String qaRealName = getIntent().getStringExtra(EXTRA_QA_REAL_NAME);
            qaReal = new AppUpdater.Release(qaRealName != null ? qaRealName : "e2e",
                    getIntent().getIntExtra(EXTRA_QA_REAL_CODE, 0), qaRealUrl,
                    "End-to-end update test", false);
        }
        final float qaFrame = BuildConfig.DEBUG && getIntent() != null
                ? getIntent().getFloatExtra(EXTRA_FRAME, -1f) : -1f;

        try {
            Ui.edgeToEdge(this);
            setContentView(R.layout.activity_splash);
            root = findViewById(R.id.splashRoot);
            splashAnimation = findViewById(R.id.splashAnimation);
            configureSplashAnimation();

            version = findViewById(R.id.splashVersion);
            version.setText(getString(R.string.splash_version, BuildConfig.VERSION_NAME));
            statusBar = findViewById(R.id.splashStatusBar);
            statusRow = findViewById(R.id.splashStatusRow);
            statusMark = findViewById(R.id.splashStatusMark);
            Motion.arc(statusMark);
            statusLine = findViewById(R.id.splashStatus);
            statusDetail = findViewById(R.id.splashStatusDetail);
            retry = findViewById(R.id.splashRetry);
            retry.setOnClickListener(v -> {
                Ui.tap(v);
                startCheck();
            });

            final int footerMargin = getResources().getDimensionPixelSize(R.dimen.space_32);
            final int versionTop = getResources().getDimensionPixelSize(R.dimen.gutter_wide);
            final int versionEnd = getResources().getDimensionPixelSize(R.dimen.gutter_wide);
            Ui.onInsets(root, (bars, bottom, ime) -> {
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
                if (sp.bottomMargin != margin || sp.leftMargin != bars.left || sp.rightMargin != bars.right) {
                    sp.bottomMargin = margin;
                    sp.leftMargin = bars.left;
                    sp.rightMargin = bars.right;
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
            // QA: one deterministic Lottie frame, no navigation and no network.
            hold = true;
            if (qaUpdate == null) qaUpdate = "checking";
            root.post(() -> showQaFrame(qaFrame));
            startCheck();
            return;
        }

        if (hold && qaUpdate == null) qaUpdate = "checking";
        handler.postDelayed(this::startCheck, CHECK_AT_MS);
        revealChrome();
        if (Ui.reducedMotion(this)) {
            root.post(() -> {
                if (!Ui.alive(this)) return;
                splashAnimation.cancelAnimation();
                splashAnimation.setFrame(SPLASH_FINAL_FRAME);
                handler.postDelayed(next, REDUCED_FINAL_MS);
            });
        } else {
            root.post(() -> {
                if (Ui.alive(this)) splashAnimation.playAnimation();
            });
        }
        handler.postDelayed(next, SAFETY_MS);
    }

    private void configureSplashAnimation() {
        splashAnimation.setAnimation(R.raw.anoy_splash);
        // Stop on the source animation's edited final A pose instead of its
        // blank tail. The word and A are vector paths inside this one Lottie.
        splashAnimation.setMaxFrame(SPLASH_FINAL_FRAME);
        splashAnimation.setRepeatCount(0);
        splashAnimation.addAnimatorListener(new AnimatorListenerAdapter() {
            private boolean completed;

            @Override
            public void onAnimationEnd(Animator animation) {
                // Reduced motion and deterministic QA frames set progress directly;
                // their explicit callbacks own the hand-off timing.
                if (completed || Ui.reducedMotion(SplashActivity.this) || !Ui.alive(SplashActivity.this)) return;
                completed = true;
                onFilmDone();
            }
        });
    }

    private void revealChrome() {
        version.animate().alpha(1f).setStartDelay(VERSION_AT_MS).setDuration(VERSION_MS)
                .setInterpolator(Ui.ENTER).start();
        statusBar.animate().alpha(1f).setStartDelay(STATUS_AT_MS).setDuration(STATUS_MS)
                .setInterpolator(Ui.ENTER).start();
    }

    private void showQaFrame(float seconds) {
        if (!Ui.alive(this)) return;
        version.setAlpha(1f);
        statusBar.setAlpha(1f);
        splashAnimation.cancelAnimation();
        splashAnimation.setFrame(Math.max(0, Math.min(SPLASH_FINAL_FRAME,
                Math.round(seconds * 60f))));
    }

    // ------------------------------------------------------ app update check

    /** The visual film is over (or could not render): proceed when the check allows it. */
    private void onFilmDone() {
        if (filmDone || !Ui.alive(this)) return;
        filmDone = true;
        handler.removeCallbacks(next);
        maybeProceed();
    }

    /** (Re)runs the check; the status line follows its real state. */
    private void startCheck() {
        if (!Ui.alive(this) || handingOff || check == Check.CHECKING) return;
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
        Ui.fadeReserved(statusDetail, false);
        maybeProceed();
    }

    private void onCheckFailed(int run, String reason) {
        if (run != checkRun || !Ui.alive(this)) return;
        check = Check.FAILED;
        setStatus(getString(R.string.splash_status_failed), R.drawable.dot_status, R.color.warning, false,
                SWAP_OUT_MS, SWAP_IN_MS);
        String detail = Ui.dropTryAgain(reason);
        statusDetail.animate().cancel();
        statusDetail.setAlpha(1f);
        statusDetail.setText(detail);
        statusDetail.setVisibility(detail == null || detail.isEmpty() ? View.INVISIBLE : View.VISIBLE);
        retry.setVisibility(View.VISIBLE);
        statusBar.setAlpha(1f);
    }

    /** Hands off only once both the animation and update answer have completed. */
    private boolean maybeProceed() {
        if (!filmDone || hold || handingOff) return false;
        long shown = android.os.SystemClock.uptimeMillis() - statusShownAt;
        if (check == Check.CURRENT) {
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

    /** Status line with the existing small Lottie arc/check and semantic fallback glyphs. */
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
                    statusRow.animate().alpha(1f).setDuration(inMs)
                            .setInterpolator(new LinearInterpolator()).start();
                }).start();
    }

    private void applyStatus(String text, int iconRes, int colourRes, boolean spinner) {
        statusLine.animate().cancel();
        statusLine.setAlpha(1f);
        statusLine.setTag(R.id.tag_swap_text, null);
        statusLine.setText(text);
        int colour = ContextCompat.getColor(this, colourRes != 0 ? colourRes : R.color.text_secondary);
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
    }

    /** The detail line keeps its space (INVISIBLE), so the status never jumps. */
    private void hideFailure() {
        statusDetail.animate().cancel();
        statusDetail.setAlpha(1f);
        statusDetail.setVisibility(View.INVISIBLE);
        statusDetail.setText("");
        retry.setVisibility(View.GONE);
    }

    /** Debug only: check answers without network access. */
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
                    qaUpdate = "up_to_date";
                    break;
                default:
                    onChecked(run, null);
                    break;
            }
        }, QA_CHECK_MS);
    }

    private static AppUpdater.Release sampleRelease(boolean mandatory) {
        return new AppUpdater.Release("2.4.0", BuildConfig.VERSION_CODE + 1,
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

    private void openLogin() {
        if (opened || !Ui.alive(this)) return;
        opened = true;
        startActivity(new Intent(this, LogAct.class));
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
        finish();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (splashAnimation != null) splashAnimation.cancelAnimation();
        checker.shutdownNow();
        super.onDestroy();
    }
}
