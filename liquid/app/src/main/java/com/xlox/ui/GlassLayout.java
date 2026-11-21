package com.xlox.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;

import androidx.core.content.ContextCompat;

import com.xlox.R;

/**
 * Frosted control/sheet layer. Only the sampled BACKDROP is blurred; children
 * (text, icons, progress and touch targets) are always rendered sharply.
 * Captures a cropped, 1/8-resolution sibling/other-window source on scroll or
 * layout only, at <= 30 fps. No continuous animator, hidden APIs or SDK change.
 */
public final class GlassLayout extends LinearLayout {
    private static final float SAMPLE = 8f;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path shape = new Path();
    private final RectF bounds = new RectF();
    private final int[] sourcePosition = new int[2];
    private final int[] targetPosition = new int[2];
    private final ViewTreeObserver.OnScrollChangedListener scrollListener = this::scheduleCapture;
    private final OnLayoutChangeListener layoutListener = (v, l, t, r, b, ol, ot, or, ob) -> scheduleCapture();
    private final Runnable capture = this::captureBackdrop;
    private View source;
    private ViewTreeObserver observed;
    private Bitmap backdrop;
    private int[] pixels;
    private Shader light;
    private Shader edge;
    private boolean sheet;
    private boolean pending;
    private long lastCapture;

    public GlassLayout(Context context) { this(context, null); }
    public GlassLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);
        setBackground(null);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                if (!shape.isEmpty()) outline.setConvexPath(shape);
            }
        });
        setClipToOutline(true);
    }

    public void setSheet(boolean sheet) {
        this.sheet = sheet;
        geometry(getWidth(), getHeight());
    }

    public void setBackdrop(View view) {
        unobserve();
        source = view;
        // A capture of our own ancestor would recursively draw us into us.
        for (android.view.ViewParent p = getParent(); p instanceof View; p = p.getParent()) {
            if (p == source) { source = null; break; }
        }
        if (source == this) source = null;
        if (isAttachedToWindow()) observe();
    }

    public void refreshEffects() {
        if (!Liquid.effects(getContext())) releaseBitmap();
        else scheduleCapture();
        invalidate();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        observe();
    }

    @Override protected void onDetachedFromWindow() {
        unobserve();
        removeCallbacks(capture);
        pending = false;
        releaseBitmap();
        super.onDetachedFromWindow();
    }

    private void observe() {
        if (source == null || observed != null) return;
        observed = source.getViewTreeObserver();
        observed.addOnScrollChangedListener(scrollListener);
        source.addOnLayoutChangeListener(layoutListener);
        scheduleCapture();
    }

    private void unobserve() {
        if (observed != null && observed.isAlive()) observed.removeOnScrollChangedListener(scrollListener);
        if (source != null) source.removeOnLayoutChangeListener(layoutListener);
        observed = null;
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        geometry(w, h);
        releaseBitmap();
        scheduleCapture();
    }

    private void geometry(int width, int height) {
        float radius = Ui.dp(getContext(), sheet ? 32 : 28);
        bounds.set(.5f, .5f, Math.max(.5f, width - .5f), Math.max(.5f, height - .5f));
        shape.reset();
        float bottom = sheet ? 0 : radius;
        shape.addRoundRect(bounds, new float[] {radius, radius, radius, radius,
                bottom, bottom, bottom, bottom}, Path.Direction.CW);
        int clear = colour(R.color.transparent);
        light = new LinearGradient(0, 0, width, Math.max(1, height),
                new int[] {colour(R.color.glass_light), clear, colour(R.color.glass_cool)},
                new float[] {0, .52f, 1}, Shader.TileMode.CLAMP);
        edge = new LinearGradient(0, 0, width * .7f, Math.max(1, height),
                new int[] {colour(R.color.glass_edge), colour(R.color.glass_edge_soft),
                        colour(R.color.glass_edge)}, null, Shader.TileMode.CLAMP);
        invalidateOutline();
        invalidate();
    }

    private int colour(int id) { return ContextCompat.getColor(getContext(), id); }

    private void scheduleCapture() {
        if (pending || source == null || !isAttachedToWindow() || !Liquid.effects(getContext())) return;
        pending = true;
        postDelayed(capture, Math.max(0, 34 - (SystemClock.uptimeMillis() - lastCapture)));
    }

    private void captureBackdrop() {
        pending = false;
        if (source == null || !isShown() || !source.isShown() || !Liquid.effects(getContext())) return;
        int width = (int) Math.ceil(getWidth() / SAMPLE);
        int height = (int) Math.ceil(getHeight() / SAMPLE);
        if (width < 1 || height < 1) return;
        try {
            if (backdrop == null || backdrop.getWidth() != width || backdrop.getHeight() != height) {
                releaseBitmap();
                backdrop = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                pixels = new int[width * height];
            }
            backdrop.eraseColor(colour(R.color.surface_2));
            source.getLocationOnScreen(sourcePosition);
            getLocationOnScreen(targetPosition);
            Canvas sample = new Canvas(backdrop);
            sample.scale(1 / SAMPLE, 1 / SAMPLE);
            sample.translate(sourcePosition[0] - targetPosition[0], sourcePosition[1] - targetPosition[1]);
            source.draw(sample);
            backdrop.getPixels(pixels, 0, width, 0, 0, width, height);
            BackdropBlur.blur(pixels, width, height, Math.max(1, Math.round(Ui.dp(getContext(), 18) / SAMPLE)));
            backdrop.setPixels(pixels, 0, width, 0, 0, width, height);
            lastCapture = SystemClock.uptimeMillis();
            invalidate();
        } catch (RuntimeException | OutOfMemoryError ignored) {
            // Effects must never block sign-in, permission prompts or a launch.
            releaseBitmap();
        }
    }

    private void releaseBitmap() {
        // Do not recycle a bitmap the RenderThread may still be displaying.
        backdrop = null;
        pixels = null;
    }

    @Override protected void onDraw(Canvas canvas) {
        int save = canvas.save();
        canvas.clipPath(shape);
        boolean effects = Liquid.effects(getContext());
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(colour(R.color.surface_2));
        canvas.drawPath(shape, paint);
        if (effects) {
            if (backdrop != null) canvas.drawBitmap(backdrop, null, bounds, paint);
            paint.setColor(colour(sheet ? R.color.glass_sheet_tint : R.color.glass_tint));
            canvas.drawPath(shape, paint);
            paint.setShader(light);
            canvas.drawPath(shape, paint);
            paint.setShader(edge);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Ui.dp(getContext(), 1));
            canvas.drawPath(shape, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(null);
        }
        canvas.restoreToCount(save);
        super.onDraw(canvas);
    }
}
