package com.xlox.ui;

import static org.junit.Assert.*;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.xlox.BuildConfig;
import com.xlox.R;

import java.io.File;
import java.io.FileOutputStream;
import java.time.Duration;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;

/** Native Skia pixels of the REAL navigation over synthetic and copied-Home fixtures.
 * Never opens MAct, authenticates, touches a game, or changes FLAG_SECURE anywhere.
 */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 28, qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class LiquidGlassRenderTest {
    public static class Stage extends View {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        boolean stripes;
        boolean alternate;
        Stage(android.content.Context context) { super(context); }
        @Override protected void onDraw(Canvas canvas) {
            if (stripes) {
                paint.setShader(null);
                for (int x = 0; x < getWidth(); x += 4) {
                    paint.setColor((x / 4) % 2 == 0 ? Color.WHITE : Color.BLACK);
                    canvas.drawRect(x, 0, x + 4, getHeight(), paint);
                }
                return;
            }
            paint.setShader(new LinearGradient(0, 0, getWidth(), getHeight(),
                    alternate ? new int[]{0xff391a45, 0xff183b43, 0xff13151c}
                            : new int[]{0xff132037, 0xff152b44, 0xff302137}, null, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
            paint.setShader(null);
            paint.setColor(alternate ? 0xffa55b6a : 0xff316ba4);
            canvas.drawRoundRect(8, 36, 150, 148, 20, 20, paint);
            paint.setColor(alternate ? 0xff486c99 : 0xff765465);
            canvas.drawRoundRect(166, 68, 352, 176, 20, 20, paint);
            paint.setColor(0x6effffff);
            for (int x = 22; x < 330; x += 30) canvas.drawRect(x, 82, x + 9, 125, paint);
            paint.setColor(0x20ffffff);
            canvas.drawRect(24, 57, 112, 62, paint);
            canvas.drawRect(183, 143, 298, 148, paint);
        }
    }

    public static class Host extends AppCompatActivity {
        FrameLayout root;
        Stage stage;
        CapsuleNavigation capsule;
        @Override protected void onCreate(Bundle state) {
            setTheme(R.style.AppTheme);
            super.onCreate(state);
            root = new FrameLayout(this);
            stage = new Stage(this);
            root.addView(stage, new FrameLayout.LayoutParams(-1, -1));
            ViewGroup original = (ViewGroup) getLayoutInflater().inflate(R.layout.activity_main, null);
            View navHost = original.findViewById(R.id.bottomNavigationHost);
            original.removeView(navHost);
            root.addView(navHost);
            setContentView(root);
            capsule = root.findViewById(R.id.bottomCapsule);
            capsule.setBackdropSource(stage);
            ((TextView) root.findViewById(R.id.navigationHome)).setTextColor(ContextCompat.getColor(this, R.color.on_accent));
            ((TextView) root.findViewById(R.id.navigationSettings)).setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        }
    }

    /** Real copied Home layout for a human-reviewable preview; still no MAct/game/account. */
    public static class RealisticHost extends AppCompatActivity {
        ViewGroup root;
        CapsuleNavigation capsule;
        HomeSettingsNavigation navigation;
        @Override protected void onCreate(Bundle state) {
            setTheme(R.style.AppTheme);
            super.onCreate(state);
            setContentView(R.layout.activity_main);
            root = findViewById(R.id.mainRoot);
            navigation = new HomeSettingsNavigation(root, new HomeSettingsNavigation.Listener() {
                @Override public void onPageSelected(boolean settings) { }
                @Override public void onMotionChanged(boolean moving) { }
            });
            capsule = root.findViewById(R.id.bottomCapsule);
        }
    }

    private ActivityController<Host> controller;
    private ActivityController<RealisticHost> realisticController;
    private Host activity;
    private long time;

    @Before public void create() {
        controller = Robolectric.buildActivity(Host.class).setup().visible();
        activity = controller.get();
        activity.root.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(180, View.MeasureSpec.EXACTLY));
        activity.root.layout(0, 0, 360, 180);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        time = SystemClock.uptimeMillis() + 100;
    }

    @After public void destroy() {
        if (realisticController != null) realisticController.pause().stop().destroy();
        controller.pause().stop().destroy();
    }

    private Bitmap render() {
        activity.capsule.backdrop().capture(time += 64);
        Bitmap bitmap = Bitmap.createBitmap(360, 180, Bitmap.Config.ARGB_8888);
        activity.root.draw(new Canvas(bitmap));
        return bitmap;
    }

    @Test public void frostedLensActuallySoftensBackdropDetail() {
        activity.stage.stripes = true;
        Bitmap image = render();
        int[] location = new int[2];
        int[] rootLocation = new int[2];
        activity.capsule.getLocationInWindow(location);
        activity.root.getLocationInWindow(rootLocation);
        int left = location[0] - rootLocation[0];
        int y = location[1] - rootLocation[1] + 17;
        double selected = variation(image, left + 42, left + 104, y);
        double clear = variation(image, left + 177, left + 239, y);
        assertTrue("Clear shell must still show the live stripes", clear > 20);
        assertTrue("Actual frost must reduce detail, not merely tint it", selected < clear * .65);
    }

    @Test public void liveBackdropRespondsToContentButUnchangedContentSchedulesNoRedraw() {
        LiquidGlassBackdrop sampler = activity.capsule.backdrop();
        sampler.capture(time += 64);
        assertNotNull(sampler.shader());
        assertFalse(sampler.capture(time += 64));
        activity.stage.alternate = true;
        assertTrue(sampler.capture(time += 64));
        assertFalse(sampler.capture(time += 64));
    }

    @Test public void noBackdropFallbackRemainsTransparentRatherThanABlueOrGreyPill() {
        activity.capsule.setBackdropSource(null);
        Bitmap image = Bitmap.createBitmap(activity.capsule.getWidth(), activity.capsule.getHeight(), Bitmap.Config.ARGB_8888);
        activity.capsule.draw(new Canvas(image));
        assertTrue("Inactive glass has low-alpha fill", Color.alpha(image.getPixel(208, 16)) < 96);
        assertTrue("Selected fallback also stays translucent", Color.alpha(image.getPixel(48, 16)) < 128);
    }

    @Test public void nativeRendererExportsRestAndMovingReviewFramesOnlyFromSyntheticHost() throws Exception {
        Bitmap rest = render();
        save(rest, "nav-rest");
        for (int i = 1; i <= 6; i++) {
            activity.capsule.setProgress(i / 12f);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16));
        }
        Bitmap moving = render();
        save(moving, "nav-moving");
        assertFalse("Liquid lens has visibly moved", rest.sameAs(moving));
    }

    @Test public void nativeRendererExportsARealisticCopiedHomeNavigationPreview() throws Exception {
        realisticController = Robolectric.buildActivity(RealisticHost.class).setup().visible();
        RealisticHost realistic = realisticController.get();
        realistic.root.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY));
        realistic.root.layout(0, 0, 360, 640);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        realistic.capsule.backdrop().capture(time += 64);
        Bitmap full = Bitmap.createBitmap(360, 640, Bitmap.Config.ARGB_8888);
        Canvas fullCanvas = new Canvas(full);
        fullCanvas.drawColor(Color.BLACK);
        realistic.root.draw(fullCanvas);
        assertTrue("Realistic preview contains the opaque copied page", Color.alpha(full.getPixel(180, 420)) > 240);
        Bitmap bottom = Bitmap.createBitmap(full, 0, 360, 360, 280);
        Bitmap preview = Bitmap.createScaledBitmap(bottom, 320, 200, true);
        save(preview, "00-nav-realistic");
        realistic.navigation.dispose();
        realisticController.pause().stop().destroy();
        realisticController = null;
    }

    private static double variation(Bitmap bitmap, int left, int right, int y) {
        double sum = 0;
        for (int x = left; x < right; x++) {
            int a = bitmap.getPixel(x, y), b = bitmap.getPixel(x + 1, y);
            sum += Math.abs(Color.red(a) - Color.red(b)) + Math.abs(Color.green(a) - Color.green(b))
                    + Math.abs(Color.blue(a) - Color.blue(b));
        }
        return sum / ((right - left) * 3.0);
    }

    private static void save(Bitmap bitmap, String name) throws Exception {
        File directory = new File("build/liquid-preview");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        try (FileOutputStream output = new FileOutputStream(new File(directory, BuildConfig.RELEASE_CHANNEL + "-" + name + ".png"))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        }
    }
}
