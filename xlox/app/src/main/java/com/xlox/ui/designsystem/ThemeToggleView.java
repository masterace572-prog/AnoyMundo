package com.xlox.ui.designsystem;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import com.xlox.R;

/**
 * Animated pill-shaped appearance switch. A sliding thumb travels with a
 * critically damped spring while the sun and moon glyphs crossfade and rotate
 * into one another. Drawn entirely on Canvas — no default widgets.
 */
public class ThemeToggleView extends View {

    private static final FloatPropertyCompat<ThemeToggleView> PROGRESS =
            new FloatPropertyCompat<ThemeToggleView>("dsProgress") {
                @Override public float getValue(ThemeToggleView v) { return v.progress; }
                @Override public void setValue(ThemeToggleView v, float value) {
                    v.progress = value;
                    v.invalidate();
                }
            };

    private final Paint background = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumb = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final RectF thumbRect = new RectF();
    private final Path moon = new Path();
    private final Path cut = new Path();
    private final Paint solid = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float progress = 0f; // 0 = light (sun), 1 = dark (moon)
    private boolean dark;
    private int accent;
    private int iconColour;
    private float stroke;
    private SpringAnimation spring;

    public ThemeToggleView(Context context) {
        super(context);
        init(context, null);
    }

    public ThemeToggleView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context, attrs);
    }

    private void init(Context context, AttributeSet attrs) {
        accent = Tokens.accent(context);
        iconColour = Tokens.icon(context);
        stroke = Math.max(1f, Tokens.px(context, R.dimen.divider));
        background.setColor(Tokens.surfaceRecessed(context));
        background.setStyle(Paint.Style.FILL);
        border.setColor(Tokens.hairlineStrong(context));
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(stroke);
        thumb.setColor(Tokens.accentWash(context));
        thumb.setStyle(Paint.Style.FILL);
        solid.setStyle(Paint.Style.FILL);
        thumbEdge.setColor(accent);
        thumbEdge.setStyle(Paint.Style.STROKE);
        thumbEdge.setStrokeWidth(stroke);
        glyph.setStyle(Paint.Style.STROKE);
        glyph.setStrokeWidth(Math.max(1f, Tokens.px(context, R.dimen.divider) * 1.4f));
        glyph.setStrokeCap(Paint.Cap.ROUND);
        setClickable(true);
        setFocusable(true);
    }

    @Override
    public boolean performClick() {
        Haptics.toggle(this);
        return super.performClick();
    }

    public boolean isDark() { return dark; }

    /** Set the switch state, optionally with the spring transition. */
    public void setDark(boolean value, boolean animate) {
        dark = value;
        float target = value ? 1f : 0f;
        if (!animate || !DsMotion.animationsEnabled(getContext())) {
            progress = target;
            invalidate();
            return;
        }
        if (spring != null) spring.cancel();
        // A bare SpringForce has an UNSET final position; installing it makes
        // SpringAnimation.sanityCheck() throw at start(). Keep the rest
        // position on the force and never let a cosmetic spring abort a screen.
        SpringForce force = new SpringForce(target)
                .setStiffness(SpringForce.STIFFNESS_MEDIUM)
                .setDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY);
        try {
            spring = new SpringAnimation(this, PROGRESS);
            spring.setSpring(force);
            spring.start();
        } catch (RuntimeException e) {
            progress = target;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float pad = stroke;
        rect.set(pad, pad, getWidth() - pad, getHeight() - pad);
        float r = rect.height() / 2f;
        canvas.drawRoundRect(rect, r, r, background);
        canvas.drawRoundRect(rect, r, r, border);

        float inner = stroke * 3f;
        float diameter = rect.height() - inner * 2f;
        float travel = rect.width() - inner * 2f - diameter;
        float left = rect.left + inner + travel * progress;
        thumbRect.set(left, rect.top + inner, left + diameter, rect.bottom - inner);
        canvas.drawOval(thumbRect, thumb);
        canvas.drawOval(thumbRect, thumbEdge);

        float cx = thumbRect.centerX();
        float cy = thumbRect.centerY();
        float gr = diameter * 0.26f;

        // Sun: core plus eight rays, fading and rotating out as the moon arrives.
        glyph.setColor(blend(accent, iconColour, progress));
        glyph.setAlpha((int) (255 * (1f - progress)));
        if (glyph.getAlpha() > 0) {
            int saved = canvas.save();
            canvas.rotate(-90f * progress, cx, cy);
            canvas.drawCircle(cx, cy, gr * 0.52f, glyph);
            for (int i = 0; i < 8; i++) {
                double a = Math.PI * 2 * i / 8;
                float x1 = (float) (cx + Math.cos(a) * gr * 0.92f);
                float y1 = (float) (cy + Math.sin(a) * gr * 0.92f);
                float x2 = (float) (cx + Math.cos(a) * gr * 1.42f);
                float y2 = (float) (cy + Math.sin(a) * gr * 1.42f);
                canvas.drawLine(x1, y1, x2, y2, glyph);
            }
            canvas.restoreToCount(saved);
        }

        // Moon: crescent carved with a path difference, rotating in.
        glyph.setColor(blend(iconColour, accent, progress));
        glyph.setAlpha((int) (255 * progress));
        if (glyph.getAlpha() > 0) {
            int saved = canvas.save();
            canvas.rotate(90f * (1f - progress), cx, cy);
            solid.setColor(glyph.getColor());
            solid.setAlpha(glyph.getAlpha());
            moon.reset();
            moon.addCircle(cx, cy, gr * 1.25f, Path.Direction.CW);
            cut.reset();
            cut.addCircle(cx + gr * 0.62f, cy - gr * 0.28f, gr * 1.12f, Path.Direction.CW);
            moon.op(cut, Path.Op.DIFFERENCE);
            canvas.drawPath(moon, solid);
            canvas.restoreToCount(saved);
        }
    }

    private static int blend(int from, int to, float t) {
        t = Math.max(0f, Math.min(1f, t));
        return Color.rgb(
                (int) (Color.red(from) + (Color.red(to) - Color.red(from)) * t),
                (int) (Color.green(from) + (Color.green(to) - Color.green(from)) * t),
                (int) (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t));
    }
}
