package com.xlox.ui.designsystem;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.animation.LinearInterpolator;

import androidx.appcompat.widget.AppCompatButton;

import com.xlox.R;

/**
 * The flagship primary action. Fixed height, press-scale physics, a haptic
 * tap, and a crossfade from the label to a centred 20dp spinner. The label is
 * never removed from the view, so the measured size cannot change and there
 * is zero layout shift while an operation is in flight.
 */
public class PrimaryButton extends AppCompatButton {

    private ColorStateList idleColors;
    private boolean busy;
    private float phase;            // 0 = label, 1 = spinner
    private float spin;
    private ValueAnimator fade;
    private ValueAnimator spinner;

    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();

    public PrimaryButton(Context context) {
        super(context);
        init();
    }

    public PrimaryButton(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public PrimaryButton(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        idleColors = getTextColors();
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeWidth(Math.max(1f, Tokens.px(getContext(), R.dimen.divider) * 2f));
        arc.setColor(Tokens.onAccent(getContext()));
        arc.setStrokeCap(Paint.Cap.ROUND);
        DsMotion.attachPressScale(this, 0.97f);
    }

    public void setLabel(CharSequence label) {
        setText(label);
    }

    public boolean isBusy() { return busy; }

    /** Crossfade label out / spinner in without touching the measured size. */
    public void setBusy(boolean active) {
        if (active == busy) return;
        busy = active;
        setClickable(!active);
        if (fade != null) fade.cancel();
        if (!DsMotion.animationsEnabled(getContext())) {
            phase = active ? 1f : 0f;
            applyLabelAlpha();
            if (active) startSpinner(); else stopSpinner();
            invalidate();
            return;
        }
        fade = ValueAnimator.ofFloat(phase, active ? 1f : 0f);
        fade.setDuration(DsMotion.STATE);
        fade.setInterpolator(DsMotion.interpolator());
        fade.addUpdateListener(a -> {
            phase = (Float) a.getAnimatedValue();
            applyLabelAlpha();
            invalidate();
        });
        if (!active) {
            fade.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator animation) {
                    restoreLabelColor();
                }
            });
        }
        fade.start();
        if (active) startSpinner(); else stopSpinner();
    }

    public void reset() {
        setBusy(false);
        setEnabled(true);
        setClickable(true);
    }

    private void applyLabelAlpha() {
        int base = idleColors == null
                ? Tokens.onAccent(getContext())
                : idleColors.getColorForState(getDrawableState(), idleColors.getDefaultColor());
        int a = (int) (Color.alpha(base) * (1f - phase));
        setTextColor(Color.argb(a, Color.red(base), Color.green(base), Color.blue(base)));
    }

    private void restoreLabelColor() {
        if (idleColors != null) setTextColor(idleColors);
    }

    private void startSpinner() {
        stopSpinner();
        if (!DsMotion.animationsEnabled(getContext())) {
            spin = 0f;
            invalidate();
            return;
        }
        spinner = ValueAnimator.ofFloat(0f, 360f);
        spinner.setDuration(900L);
        spinner.setRepeatCount(ValueAnimator.INFINITE);
        spinner.setInterpolator(new LinearInterpolator());
        spinner.addUpdateListener(a -> {
            spin = (Float) a.getAnimatedValue();
            invalidate();
        });
        spinner.start();
    }

    private void stopSpinner() {
        if (spinner != null) {
            spinner.cancel();
            spinner = null;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (phase <= 0.01f) return;
        float size = Tokens.px(getContext(), R.dimen.progress_circular);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float r = size / 2f - arc.getStrokeWidth() / 2f;
        bounds.set(cx - r, cy - r, cx + r, cy + r);
        arc.setAlpha((int) (255 * phase));
        canvas.drawArc(bounds, -90f + spin, 270f, false, arc);
    }

    @Override
    protected void onDetachedFromWindow() {
        stopSpinner();
        if (fade != null) fade.cancel();
        super.onDetachedFromWindow();
    }
}
