package com.xlox.ui.designsystem;

import android.app.Activity;
import android.util.Log;
import android.view.View;

import androidx.core.splashscreen.SplashScreen;
import androidx.core.splashscreen.SplashScreenViewProvider;

/**
 * AndroidX SplashScreen bridge. install() must be called before
 * Activity.onCreate(); exit() attaches the custom spring-like fade/scale that
 * hands over to the staggered screen entrance.
 */
public final class Splash {

    private Splash() {}

    /** Call before super.onCreate(). */
    public static SplashScreen install(Activity activity) {
        return SplashScreen.installSplashScreen(activity);
    }

    public static void exit(SplashScreen screen) {
        exit(screen, null);
    }

    /**
     * The platform splash screen owns the system-bar appearance until its view
     * is removed. [afterExit] runs immediately afterwards so the activity can
     * restore its own theme-aware light/dark status and navigation bar icons.
     */
    public static void exit(SplashScreen screen, Runnable afterExit) {
        if (screen == null) {
            if (afterExit != null) afterExit.run();
            return;
        }
        try {
            screen.setOnExitAnimationListener(provider -> animateExit(provider, afterExit));
        } catch (Throwable t) {
            Log.d("Splash", "Exit animation unavailable", t);
            if (afterExit != null) afterExit.run();
        }
    }

    private static void animateExit(SplashScreenViewProvider provider, Runnable afterExit) {
        Runnable finish = () -> {
            if (afterExit != null) afterExit.run();
        };
        View icon = provider.getIconView();
        View target = icon != null ? icon : provider.getView();
        if (!DsMotion.animationsEnabled(target.getContext())) {
            provider.remove();
            finish.run();
            return;
        }
        target.animate().cancel();
        target.animate()
                .alpha(0f)
                .scaleX(1.12f)
                .scaleY(1.12f)
                .setDuration(DsMotion.SHEET)
                .setInterpolator(DsMotion.interpolator())
                .withEndAction(() -> {
                    provider.remove();
                    finish.run();
                })
                .start();
    }
}
