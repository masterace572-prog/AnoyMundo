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
        if (screen == null) return;
        try {
            screen.setOnExitAnimationListener(Splash::animateExit);
        } catch (Throwable t) {
            Log.d("Splash", "Exit animation unavailable", t);
        }
    }

    private static void animateExit(SplashScreenViewProvider provider) {
        View icon = provider.getIconView();
        View target = icon != null ? icon : provider.getView();
        if (!DsMotion.animationsEnabled(target.getContext())) {
            provider.remove();
            return;
        }
        target.animate().cancel();
        target.animate()
                .alpha(0f)
                .scaleX(1.12f)
                .scaleY(1.12f)
                .setDuration(DsMotion.SHEET)
                .setInterpolator(DsMotion.interpolator())
                .withEndAction(provider::remove)
                .start();
    }
}
