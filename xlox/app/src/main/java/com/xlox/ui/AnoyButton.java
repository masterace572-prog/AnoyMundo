package com.xlox.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;

import androidx.appcompat.widget.AppCompatButton;

/**
 * iOS-style action button.
 *
 * Three states:
 *  - idle      : the normal label
 *  - dots      : three hovering dots, used the moment any action is tapped
 *  - progress  : a fill that grows inside the button with a compact
 *                "Updating 42%" / "Copying 42%" label, used while a release is
 *                downloading or the OBB is being copied.
 *
 * The dots and the fill are drawn by the button itself, so nothing has to be
 * added to the layout and there is no separate dialog to dismiss.
 */
public class AnoyButton extends AppCompatButton {

    private static final int IDLE = 0;
    private static final int DOTS = 1;
    private static final int PROGRESS = 2;

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clipPath = new Path();
    private final RectF rect = new RectF();

    private int mode = IDLE;
    private String idleLabel;
    private int progress = 0;
    private float animatedProgress = 0f;
    private float phase = 0f;
    private ValueAnimator dotsAnimator;
    private ValueAnimator progressAnimator;
    private Runnable pendingReset;

    public AnoyButton(Context context) {
        super(context);
        init();
    }

    public AnoyButton(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public AnoyButton(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        fillPaint.setColor(Color.argb(72, 255, 255, 255));
        fillPaint.setStyle(Paint.Style.FILL);
        dotPaint.setColor(Color.WHITE);
        dotPaint.setStyle(Paint.Style.FILL);
    }

    /** Colour of the three dots (blue for secondary, text-only buttons). */
    public void setDotColor(int color) {
        dotPaint.setColor(color);
        invalidate();
    }

    /** Colour of the progress fill that grows across the button. */
    public void setFillColor(int color) {
        fillPaint.setColor(color);
        invalidate();
    }

    /** Change the resting label without disturbing an in-flight animation. */
    public void setIdleLabel(String label) {
        idleLabel = label;
        if (mode == IDLE) {
            setText(label);
        }
    }

    /** Show three hovering dots until reset() is called. */
    public void showDots() {
        cancelPendingReset();
        captureLabel();
        stopProgressAnimator();
        mode = DOTS;
        progress = 0;
        setText("");
        setClickable(false);
        startDotsAnimator();
        invalidate();
    }

    /** Brief hovering dots for a quick action, then back to the label. */
    public void pulseDots(long millis) {
        showDots();
        pendingReset = this::reset;
        postDelayed(pendingReset, Math.max(250L, millis));
    }

    /** Grow a fill inside the button and show a short label with the percent. */
    public void showProgress(int percent, String label) {
        cancelPendingReset();
        captureLabel();
        stopDotsAnimator();
        mode = PROGRESS;
        progress = Math.max(0, Math.min(100, percent));
        setClickable(false);
        if (label != null) {
            setText(label);
        }
        animateProgressTo(progress);
        invalidate();
    }

    /** Back to the normal label. Safe to call more than once. */
    public void reset() {
        cancelPendingReset();
        stopDotsAnimator();
        stopProgressAnimator();
        mode = IDLE;
        progress = 0;
        animatedProgress = 0f;
        setClickable(true);
        if (idleLabel != null) {
            setText(idleLabel);
        }
        invalidate();
    }

    public boolean isBusy() {
        return mode != IDLE;
    }

    private void captureLabel() {
        if (idleLabel == null) {
            CharSequence current = getText();
            idleLabel = current == null ? "" : current.toString();
        }
    }

    private void cancelPendingReset() {
        if (pendingReset != null) {
            removeCallbacks(pendingReset);
            pendingReset = null;
        }
    }

    private void startDotsAnimator() {
        if (dotsAnimator != null && dotsAnimator.isRunning()) return;
        dotsAnimator = ValueAnimator.ofFloat(0f, 1f);
        dotsAnimator.setDuration(900L);
        dotsAnimator.setRepeatCount(ValueAnimator.INFINITE);
        dotsAnimator.setInterpolator(new LinearInterpolator());
        dotsAnimator.addUpdateListener(animation -> {
            phase = (Float) animation.getAnimatedValue();
            invalidate();
        });
        dotsAnimator.start();
    }

    private void stopDotsAnimator() {
        if (dotsAnimator != null) {
            dotsAnimator.cancel();
            dotsAnimator = null;
        }
    }

    private void animateProgressTo(int target) {
        if (progressAnimator != null) {
            progressAnimator.cancel();
        }
        float from = animatedProgress;
        progressAnimator = ValueAnimator.ofFloat(from, (float) target);
        progressAnimator.setDuration(220L);
        progressAnimator.setInterpolator(new DecelerateInterpolator());
        progressAnimator.addUpdateListener(animation -> {
            animatedProgress = (Float) animation.getAnimatedValue();
            invalidate();
        });
        progressAnimator.start();
    }

    private void stopProgressAnimator() {
        if (progressAnimator != null) {
            progressAnimator.cancel();
            progressAnimator = null;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mode == PROGRESS) {
            float radius = getHeight() / 2f;
            rect.set(0f, 0f, getWidth(), getHeight());
            clipPath.reset();
            clipPath.addRoundRect(rect, radius, radius, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(clipPath);
            canvas.drawRect(0f, 0f, getWidth() * (animatedProgress / 100f), getHeight(), fillPaint);
            canvas.restore();
        }
        // TextView draws the label (empty in dots mode) on top of the fill.
        super.onDraw(canvas);
        if (mode == DOTS) {
            drawDots(canvas);
        }
    }

    /** Three dots that hover up one after another, like an iOS typing indicator. */
    private void drawDots(Canvas canvas) {
        float density = getResources().getDisplayMetrics().density;
        float gap = 11f * density;
        float radius = 3.6f * density;
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        for (int i = 0; i < 3; i++) {
            float t = (phase + i * 0.28f) % 1f;
            float wave = (float) Math.sin(t * Math.PI);
            float lift = wave * 5f * density;
            dotPaint.setAlpha(90 + (int) (165 * wave));
            canvas.drawCircle(cx + (i - 1) * gap, cy - lift, radius, dotPaint);
        }
        dotPaint.setAlpha(255);
    }

    /** Subtle iOS press response; also works on devices without a ripple. */
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                animate().cancel();
                animate().scaleX(0.975f).scaleY(0.975f).setDuration(110)
                        .setInterpolator(new DecelerateInterpolator()).start();
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                animate().scaleX(1f).scaleY(1f).setDuration(180)
                        .setInterpolator(new DecelerateInterpolator()).start();
                break;
            default:
                break;
        }
        return super.onTouchEvent(event);
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelPendingReset();
        stopDotsAnimator();
        stopProgressAnimator();
        super.onDetachedFromWindow();
    }
}
