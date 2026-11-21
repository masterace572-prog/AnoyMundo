package com.xlox.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import androidx.core.content.ContextCompat;

import com.xlox.R;

/** Tiny procedural lens illustration. Its one-shot phase is driven by the splash. */
public final class GlassLensView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF lens = new RectF();
    private final RectF inner = new RectF();
    private Shader halo;
    private Shader fill;
    private Shader rim;
    private float phase = 1f;

    public GlassLensView(Context context) { this(context, null); }
    public GlassLensView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    public void setPhase(float phase) {
        this.phase = Math.max(0f, Math.min(1f, phase));
        invalidate();
    }

    private int colour(int id) { return ContextCompat.getColor(getContext(), id); }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float size = Math.min(w, h);
        float cx = w / 2f, cy = h / 2f;
        lens.set(cx - size * .37f, cy - size * .37f, cx + size * .37f, cy + size * .37f);
        inner.set(lens);
        inner.inset(size * .052f, size * .052f);
        halo = new RadialGradient(cx, cy, Math.max(1, size / 2),
                new int[] {colour(R.color.lens_halo), colour(R.color.transparent)}, null, Shader.TileMode.CLAMP);
        fill = new LinearGradient(lens.left, lens.top, lens.right, lens.bottom,
                new int[] {colour(R.color.lens_fill), colour(R.color.lens_dark), colour(R.color.lens_inner)},
                new float[] {0, .58f, 1}, Shader.TileMode.CLAMP);
        rim = new LinearGradient(lens.left, lens.top, lens.right, lens.bottom,
                new int[] {colour(R.color.lens_glint), colour(R.color.lens_edge_soft), colour(R.color.lens_edge)},
                new float[] {0, .5f, 1}, Shader.TileMode.CLAMP);
    }

    @Override protected void onDraw(Canvas canvas) {
        float size = Math.min(getWidth(), getHeight());
        if (!Liquid.effects(getContext())) {
            paint.setShader(null);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(colour(R.color.surface_2));
            canvas.drawOval(lens, paint);
            return;
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(halo);
        canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
        paint.setShader(fill);
        canvas.drawOval(lens, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1, size * .0045f));
        paint.setShader(rim);
        canvas.drawOval(lens, paint);
        paint.setStrokeWidth(Math.max(1, size * .0025f));
        paint.setAlpha(85);
        canvas.drawOval(inner, paint);
        paint.setAlpha(255);
        paint.setShader(null);
        paint.setColor(colour(R.color.lens_glint));
        paint.setStrokeWidth(size * .009f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawArc(lens, 206 + 28 * phase, 42, false, paint);
        paint.setColor(colour(R.color.lens_edge));
        paint.setStrokeWidth(size * .014f);
        canvas.drawArc(inner, 26 + 18 * phase, 38, false, paint);
        paint.setStyle(Paint.Style.FILL);
    }
}
