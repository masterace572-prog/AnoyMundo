package com.xlox.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.MotionEvent;

import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.xlox.R;

/** Material semantics, loading and states, with a clipped light-catching face. */
public final class GlassButton extends MaterialButton {
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF face = new RectF();
    private final Path clip = new Path();
    private Shader light;

    public GlassButton(Context context) { this(context, null); }
    public GlassButton(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        face.set(1, 1, w - 1, h - 1);
        clip.reset();
        clip.addRoundRect(face, getCornerRadius(), getCornerRadius(), Path.Direction.CW);
        light = new LinearGradient(0, 0, 0, Math.max(1, h),
                ContextCompat.getColor(getContext(), R.color.button_sheen),
                ContextCompat.getColor(getContext(), R.color.transparent), Shader.TileMode.CLAMP);
    }

    @Override protected void onDraw(Canvas canvas) {
        if (isEnabled() && Liquid.effects(getContext()) && light != null) {
            int save = canvas.save();
            canvas.clipPath(clip);
            sheen.setShader(light);
            sheen.setStyle(Paint.Style.FILL);
            canvas.drawRect(face, sheen);
            sheen.setShader(null);
            sheen.setStyle(Paint.Style.STROKE);
            sheen.setStrokeWidth(Ui.dp(getContext(), 1));
            sheen.setColor(ContextCompat.getColor(getContext(), R.color.button_edge));
            canvas.drawRoundRect(face, getCornerRadius(), getCornerRadius(), sheen);
            canvas.restoreToCount(save);
        }
        // Text and icons are drawn LAST, never blurred or scaled independently.
        super.onDraw(canvas);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (isEnabled() && isClickable() && !Ui.reducedMotion(getContext())) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                float scale = action == MotionEvent.ACTION_DOWN ? .985f : 1f;
                animate().scaleX(scale).scaleY(scale).setDuration(Ui.PRESS).setInterpolator(Ui.ENTER).start();
            }
        }
        return super.onTouchEvent(event);
    }

    @Override public boolean performClick() {
        return super.performClick();
    }

    @Override protected void onDetachedFromWindow() {
        animate().cancel();
        setScaleX(1f);
        setScaleY(1f);
        super.onDetachedFromWindow();
    }
}
