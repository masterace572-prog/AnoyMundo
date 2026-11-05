package com.xlox.ui.designsystem;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/** A live emerald status dot with a soft, calm expanding ring. */
public class LiveDotView extends View {

    private final Paint core = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float phase = 0f;
    private ValueAnimator animator;

    public LiveDotView(Context context) {
        super(context);
        init(context);
    }

    public LiveDotView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        int colour = Tokens.success(context);
        core.setColor(colour);
        core.setStyle(Paint.Style.FILL);
        ring.setColor(colour);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(Math.max(1f, Tokens.px(context, com.xlox.R.dimen.divider)));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float r = Math.min(cx, cy) * 0.42f;
        ring.setAlpha((int) (150 * (1f - phase)));
        canvas.drawCircle(cx, cy, r * (1f + 0.85f * phase), ring);
        core.setAlpha(255);
        canvas.drawCircle(cx, cy, r, core);
    }

    private void tick(float value) {
        phase = value;
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (DsMotion.animationsEnabled(getContext())) {
            animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(1600L);
            animator.setRepeatCount(ValueAnimator.INFINITE);
            animator.setRepeatMode(ValueAnimator.RESTART);
            animator.addUpdateListener(a -> tick((Float) a.getAnimatedValue()));
            animator.start();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        super.onDetachedFromWindow();
    }
}
