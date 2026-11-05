package com.xlox.ui.designsystem;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.animation.LinearInterpolator;

import androidx.appcompat.widget.AppCompatButton;

import com.xlox.R;

/**
 * The single implementation of the Primary button (B.6): 48dp, 10dp radius,
 * disabled at 38%, and loading swaps the label for a 20dp monochrome circular
 * indicator without changing the measured width. No press-scale, no bouncing
 * dots, no progress fill sweep.
 */
public class PrimaryButton extends AppCompatButton {

    private CharSequence idleLabel;
    private boolean loading;
    private float phase;
    private ValueAnimator spinner;

    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arcRect = new RectF();

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
        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeWidth(getResources().getDimension(R.dimen.divider) * 2f);
        arcPaint.setColor(Tokens.onAccent(getContext()));
        arcPaint.setStrokeCap(Paint.Cap.ROUND);
    }

    /** Resting label; safe to call while an action is in flight. */
    public void setLabel(String label) {
        idleLabel = label;
        if (!loading) setText(label);
    }

    /** Swap the label for the indicator without changing the measured width. */
    public void setLoading(boolean active) {
        if (active == loading) return;
        loading = active;
        setClickable(!active);
        if (active) {
            captureLabel();
            setText("");
            startSpinner();
        } else {
            stopSpinner();
            if (idleLabel != null) setText(idleLabel);
        }
        invalidate();
    }

    public void reset() {
        setLoading(false);
        setEnabled(true);
    }

    public boolean isBusy() {
        return loading;
    }

    private void captureLabel() {
        if (idleLabel == null) {
            CharSequence current = getText();
            idleLabel = current == null ? "" : current;
        }
    }

    private void startSpinner() {
        stopSpinner();
        if (!DsMotion.animationsEnabled(getContext())) {
            phase = 0f;
            invalidate();
            return;
        }
        spinner = ValueAnimator.ofFloat(0f, 360f);
        spinner.setDuration(900L);
        spinner.setRepeatCount(ValueAnimator.INFINITE);
        spinner.setInterpolator(new LinearInterpolator());
        spinner.addUpdateListener(a -> {
            phase = (Float) a.getAnimatedValue();
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
        if (!loading) return;
        float size = getResources().getDimension(R.dimen.progress_circular);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float r = size / 2f - arcPaint.getStrokeWidth() / 2f;
        arcRect.set(cx - r, cy - r, cx + r, cy + r);
        canvas.drawArc(arcRect, -90f + phase, 270f, false, arcPaint);
    }

    @Override
    protected void onDetachedFromWindow() {
        stopSpinner();
        super.onDetachedFromWindow();
    }
}
