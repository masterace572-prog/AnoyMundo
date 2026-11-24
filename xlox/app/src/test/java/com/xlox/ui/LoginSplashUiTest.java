package com.xlox.ui;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Looper;
import android.provider.Settings;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.core.view.ViewCompat;

import com.xlox.R;
import com.xlox.SplashActivity;

import java.lang.reflect.Method;
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
import org.robolectric.annotation.LooperMode;

/** Real loader layouts and presentation only. No live panel, JNI login or game launch. */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = {21, 28}, qualifiers = "w360dp-h800dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public class LoginSplashUiTest {

    public static class HostActivity extends AppCompatActivity {
        LicenceKeyVisibility visibility;

        @Override
        protected void onCreate(Bundle state) {
            setTheme(R.style.AppTheme);
            super.onCreate(state);
            setContentView(R.layout.activity_login);
            visibility = new LicenceKeyVisibility(findViewById(R.id.userkey), findViewById(R.id.toggleKey));
        }
    }

    private ActivityController<HostActivity> controller;
    private HostActivity activity;
    private ViewGroup root;

    @Before
    public void create() {
        controller = Robolectric.buildActivity(HostActivity.class).setup().visible();
        activity = controller.get();
        root = activity.findViewById(R.id.loginRoot);
        Settings.Global.putFloat(activity.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
        layout(root, 360, 800);
    }

    @After
    public void destroy() {
        controller.pause().stop().destroy();
    }

    private void layout(View view, int width, int height) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private Rect bounds(ViewGroup parent, View view) {
        Rect rect = new Rect();
        view.getDrawingRect(rect);
        parent.offsetDescendantRectToMyCoords(view, rect);
        return rect;
    }

    @Test
    public void loginSharesHomeTypographyAndPasteIsAnIconWithAFullTouchTarget() {
        TextView title = activity.findViewById(R.id.loginTitle);
        assertEquals(ResourcesCompat.getFont(activity, R.font.manrope_semibold), title.getTypeface());
        assertEquals(28f, title.getTextSize(), .1f);
        assertTrue(activity.findViewById(R.id.pasteKey) instanceof ImageButton);
        ImageButton paste = activity.findViewById(R.id.pasteKey);
        ImageButton eye = activity.findViewById(R.id.toggleKey);
        assertNotNull(paste.getDrawable());
        assertEquals(activity.getString(R.string.paste_key_description), paste.getContentDescription());
        for (View target : new View[] {paste, eye}) {
            assertTrue(target.getWidth() >= Ui.dp(activity, 48));
            assertTrue(target.getHeight() >= Ui.dp(activity, 48));
        }
        EditText key = activity.findViewById(R.id.userkey);
        assertTrue(key.getWidth() > Ui.dp(activity, 150));
        assertTrue(bounds(root, key).right <= bounds(root, eye).left);
        assertTrue(bounds(root, eye).right <= bounds(root, paste).left);
    }

    @Test
    public void visibilityChangesPresentationWithoutResettingTextSelectionTypefaceOrIme() {
        EditText key = activity.findViewById(R.id.userkey);
        ImageButton eye = activity.findViewById(R.id.toggleKey);
        String value = "EXAMPLE-7D-ABC_123";
        key.setText(value);
        key.setSelection(3, 9);
        int inputType = key.getInputType();
        Typeface face = key.getTypeface();
        assertTrue(key.getTransformationMethod() instanceof PasswordTransformationMethod);
        assertEquals(activity.getString(R.string.show_key_description), eye.getContentDescription());
        eye.performClick();
        assertTrue(key.getTransformationMethod() instanceof HideReturnsTransformationMethod);
        assertEquals(value, key.getText().toString());
        assertEquals(3, key.getSelectionStart());
        assertEquals(9, key.getSelectionEnd());
        assertEquals(inputType, key.getInputType());
        assertSame(face, key.getTypeface());
        assertTrue(eye.isSelected());
        assertEquals(activity.getString(R.string.hide_key_description), eye.getContentDescription());
        assertEquals(activity.getString(R.string.key_state_visible), ViewCompat.getStateDescription(eye));
        eye.performClick();
        assertTrue(key.getTransformationMethod() instanceof PasswordTransformationMethod);
        assertFalse(eye.isSelected());
        assertEquals(activity.getString(R.string.show_key_description), eye.getContentDescription());
        assertEquals(activity.getString(R.string.key_state_hidden), ViewCompat.getStateDescription(eye));
        assertEquals(3, key.getSelectionStart());
        assertEquals(9, key.getSelectionEnd());
        assertEquals(inputType, key.getInputType());
        assertSame(face, key.getTypeface());
    }

    @Test
    public void maskingAgainKeepsTheKeyAndCursorRatherThanClearingTheForm() {
        EditText key = activity.findViewById(R.id.userkey);
        key.setText("EXAMPLE-30D-SAVED");
        key.setSelection(key.length());
        activity.findViewById(R.id.toggleKey).performClick();
        activity.visibility.hide();
        activity.visibility.hide();
        assertTrue(key.getTransformationMethod() instanceof PasswordTransformationMethod);
        assertEquals("EXAMPLE-30D-SAVED", key.getText().toString());
        assertEquals(key.length(), key.getSelectionEnd());
        assertFalse(activity.findViewById(R.id.toggleKey).isSelected());
    }

    @Test
    public void longKeysAtTwoTimesSystemFontSizeKeepAllControlsInsideTheField() {
        Configuration large = new Configuration(activity.getResources().getConfiguration());
        large.fontScale = 2f;
        Context themed = new ContextThemeWrapper(activity.createConfigurationContext(large), R.style.AppTheme);
        ViewGroup largeRoot = (ViewGroup) LayoutInflater.from(themed).inflate(R.layout.activity_login, null);
        EditText key = largeRoot.findViewById(R.id.userkey);
        Ui.capFontScale(key, 1.5f);
        key.setText("LONG-KEY-0123456789-ABCDEFGHIJKLMNOPQRSTUVWXYZ-0123456789-ABCDEFGH");
        new LicenceKeyVisibility(key, largeRoot.findViewById(R.id.toggleKey));
        layout(largeRoot, 320, 640);
        Rect field = bounds(largeRoot, largeRoot.findViewById(R.id.keyField));
        for (int id : new int[] {R.id.userkey, R.id.toggleKey, R.id.pasteKey}) {
            Rect child = bounds(largeRoot, largeRoot.findViewById(id));
            assertTrue(field.contains(child));
            assertTrue(child.height() >= Ui.dp(themed, 48));
        }
        assertEquals(1, key.getMaxLines());
        assertTrue(key.getWidth() > Ui.dp(themed, 120));
        assertTrue(key.getTextSize() <= Ui.dp(themed, 15 * 1.5f) + 1);
        assertTrue(bounds(largeRoot, largeRoot.findViewById(R.id.login)).right <= 320);
        assertTrue(bounds(largeRoot, largeRoot.findViewById(R.id.getKey)).right <= 320);
    }

    @Test
    public void showingAFieldErrorDoesNotMoveTheInputOrCoverItsIconControls() {
        EditText key = activity.findViewById(R.id.userkey);
        Rect before = bounds(root, key);
        TextView error = activity.findViewById(R.id.keyError);
        error.setText(R.string.error_key_unknown);
        error.setVisibility(View.VISIBLE);
        activity.findViewById(R.id.keyField).setActivated(true);
        layout(root, 360, 800);
        assertEquals(before, bounds(root, key));
        assertTrue(bounds(root, error).top >= bounds(root, activity.findViewById(R.id.keyField)).bottom);
        assertTrue(bounds(root, activity.findViewById(R.id.login)).top >= bounds(root, error).bottom);
    }

    @Test
    public void unchangedStatusRefreshesReuseTextAndTheTintedDot() {
        TextView status = new TextView(activity);
        StatusText.set(status, "Active", R.color.success, R.color.text_secondary);
        Drawable dot = status.getCompoundDrawablesRelative()[0];
        CharSequence text = status.getText();
        assertNotNull(dot);
        for (int i = 0; i < 20; i++) {
            StatusText.set(status, "Active", R.color.success, R.color.text_secondary);
            assertSame(dot, status.getCompoundDrawablesRelative()[0]);
            assertSame(text, status.getText());
        }
        StatusText.set(status, "Safe", R.color.success, R.color.text_primary);
        assertSame(dot, status.getCompoundDrawablesRelative()[0]);
        StatusText.set(status, "Expires soon", R.color.warning, R.color.text_secondary);
        assertNotSame(dot, status.getCompoundDrawablesRelative()[0]);
        StatusText.set(status, "—", 0, R.color.text_tertiary);
        assertNull(status.getCompoundDrawablesRelative()[0]);
    }

    private ActivityController<SplashActivity> splash(boolean freeze, float time) {
        Intent intent = new Intent(activity, SplashActivity.class)
                .putExtra(SplashActivity.EXTRA_HOLD, true)
                .putExtra(SplashActivity.EXTRA_QA_APP_UPDATE, "checking");
        if (freeze) intent.putExtra(SplashActivity.EXTRA_FRAME, time);
        ActivityController<SplashActivity> result = Robolectric.buildActivity(SplashActivity.class, intent)
                .setup().visible();
        layout(result.get().findViewById(R.id.splashRoot), 360, 800);
        return result;
    }

    @Test
    public void splashWordExchangeDoesNotSuperimposeTwoStrongWordsOrRelayoutEveryFrame() throws Exception {
        ActivityController<SplashActivity> splashController = splash(true, 1.785f);
        try {
            SplashActivity splash = splashController.get();
            TextView loader = splash.findViewById(R.id.splashLoader);
            TextView hax = splash.findViewById(R.id.splashHax);
            View name = splash.findViewById(R.id.splashName);
            assertTrue(loader.getAlpha() < .25f);
            assertTrue(hax.getAlpha() < .4f);
            assertTrue(loader.getTranslationY() < 0f);
            assertTrue(hax.getTranslationY() > 0f);
            assertEquals(ContextCompat.getColor(splash, R.color.accent), hax.getCurrentTextColor());
            assertEquals(ResourcesCompat.getFont(splash, R.font.manrope_semibold), loader.getTypeface());
            int width = name.getWidth();
            int height = name.getHeight();
            // The debug frame uses this same pure function. Exercise a frame
            // sweep without running the network or calling the navigation gate.
            Method frame = SplashActivity.class.getDeclaredMethod("applyTime", long.class);
            frame.setAccessible(true);
            for (long t = 0; t <= 2850; t += 16) {
                frame.invoke(splash, t);
                assertEquals(width, name.getWidth());
                assertEquals(height, name.getHeight());
                assertFalse("Only render properties may change per frame", name.isLayoutRequested());
                assertFalse(loader.getAlpha() > .4f && hax.getAlpha() > .4f);
            }
            frame.invoke(splash, 2850L);
            assertEquals(0f, loader.getAlpha(), 0f);
            assertEquals(1f, hax.getAlpha(), 0f);
            assertEquals(0f, hax.getTranslationY(), 0f);
        } finally {
            splashController.pause().stop().destroy();
        }
    }

    @Test
    public void splashLockupFitsANarrowPhoneForBothBrandsWithoutClippingTheWordExchange() {
        ViewGroup splash = (ViewGroup) LayoutInflater.from(activity).inflate(R.layout.activity_splash, null);
        layout(splash, 320, 640);
        Rect lockup = bounds(splash, splash.findViewById(R.id.splashLockup));
        Rect name = bounds(splash, splash.findViewById(R.id.splashName));
        assertTrue(lockup.left >= 0 && lockup.right <= 320);
        assertTrue(name.left >= 0 && name.right <= 320);
        assertFalse(splash.<ViewGroup>findViewById(R.id.splashSlot).getClipChildren());
        assertFalse(splash.<ViewGroup>findViewById(R.id.splashName).getClipChildren());
    }

    @Test
    public void reducedMotionSplashHoldsThenCutsToTheFinalWordWithoutContinuousMotion() {
        Settings.Global.putFloat(activity.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 0f);
        ActivityController<SplashActivity> splashController = splash(false, 0f);
        try {
            SplashActivity splash = splashController.get();
            TextView loader = splash.findViewById(R.id.splashLoader);
            TextView hax = splash.findViewById(R.id.splashHax);
            assertEquals(1f, loader.getAlpha(), 0f);
            assertEquals(0f, hax.getAlpha(), 0f);
            assertEquals(0f, splash.findViewById(R.id.splashName).getTranslationY(), 0f);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1000));
            assertEquals(0f, loader.getAlpha(), 0f);
            assertEquals(1f, hax.getAlpha(), 0f);
            float x = splash.findViewById(R.id.splashName).getTranslationX();
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2000));
            assertEquals(x, splash.findViewById(R.id.splashName).getTranslationX(), 0f);
            assertEquals(1f, splash.findViewById(R.id.splashName).getAlpha(), 0f);
            assertFalse(splash.isFinishing());
        } finally {
            splashController.pause().stop().destroy();
        }
    }
}
