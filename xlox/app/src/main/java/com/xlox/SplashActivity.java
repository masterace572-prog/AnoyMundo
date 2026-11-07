package com.xlox;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.xlox.ui.Ui;

/** Launcher entry: a short, text-only splash, then the sign-in screen. */
public class SplashActivity extends AppCompatActivity {

    private static final long SPLASH_MS = 1100L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable next = this::openLogin;
    private boolean opened;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Re-launch from the launcher while a task already exists: just finish.
        if (!isTaskRoot() && getIntent() != null && Intent.ACTION_MAIN.equals(getIntent().getAction())
                && getIntent().hasCategory(Intent.CATEGORY_LAUNCHER)) {
            finish();
            return;
        }
        try {
            setContentView(R.layout.activity_splash);
            View content = findViewById(R.id.splashContent);
            View spinner = findViewById(R.id.splashSpinner);
            TextView version = findViewById(R.id.splashVersion);
            version.setText(getString(R.string.version_footer_app, BuildConfig.VERSION_NAME));

            content.setScaleX(0.96f);
            content.setScaleY(0.96f);
            content.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(520)
                    .setInterpolator(new DecelerateInterpolator(1.6f)).start();
            spinner.animate().alpha(1f).setStartDelay(420).setDuration(300).start();
            version.animate().alpha(1f).setStartDelay(260).setDuration(300).start();
        } catch (RuntimeException e) {
            // Never let a cosmetic screen block the app.
            Log.e("SplashActivity", "Splash layout failed", e);
            openLogin();
            return;
        }
        handler.postDelayed(next, SPLASH_MS);
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
        handler.removeCallbacks(next);
        super.onDestroy();
    }
}
