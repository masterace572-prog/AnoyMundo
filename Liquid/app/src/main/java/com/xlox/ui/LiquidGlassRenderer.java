package com.xlox.ui;

import android.content.Context;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

import androidx.core.content.ContextCompat;

import com.xlox.R;

/** Clear rim + refractive, slightly frosted liquid lens. Text is drawn later, never blurred. */
final class LiquidGlassRenderer {
    private final float density;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF shell = new RectF();
    private final RectF lens = new RectF();
    private final RectF glow = new RectF();
    private final Path shellPath = new Path();
    private final Path lensPath = new Path();
    private final Path highlightPath = new Path();
    private final Matrix matrix = new Matrix();
    private final int clearTint;
    private final int frostTint;
    private final int shade;
    private final LinearGradient shellRim;
    private final LinearGradient shellLight;
    private final LinearGradient lensRim;
    private final LinearGradient lensLight;
    private final LinearGradient glint;
    private final RadialGradient caustic;

    LiquidGlassRenderer(Context context) {
        density = context.getResources().getDisplayMetrics().density;
        clearTint = color(context, R.color.liquid_clear_tint);
        frostTint = color(context, R.color.liquid_frost_tint);
        shade = color(context, R.color.liquid_contact_shadow);
        int transparent = color(context, R.color.transparent);
        int edge = color(context, R.color.liquid_edge);
        int edgeSoft = color(context, R.color.liquid_edge_soft);
        int edgeBright = color(context, R.color.liquid_edge_bright);
        int blue = color(context, R.color.liquid_edge_cool);
        int light = color(context, R.color.liquid_reflection);
        shellRim = new LinearGradient(0, 0, 1, 1,
                new int[]{edgeBright, edgeSoft, edgeSoft, edge},
                new float[]{0f, .40f, .66f, 1f}, Shader.TileMode.CLAMP);
        shellLight = new LinearGradient(0, 0, 0, 1,
                new int[]{light, transparent, transparent, edgeSoft},
                new float[]{0f, .35f, .68f, 1f}, Shader.TileMode.CLAMP);
        lensRim = new LinearGradient(0, 0, 1, 1,
                new int[]{edgeBright, edge, edgeSoft, blue, edge},
                new float[]{0f, .28f, .5f, .78f, 1f}, Shader.TileMode.CLAMP);
        lensLight = new LinearGradient(0, 0, 0, 1,
                new int[]{light, transparent, transparent, light},
                new float[]{0f, .38f, .72f, 1f}, Shader.TileMode.CLAMP);
        glint = new LinearGradient(0, 0, 1, 0,
                new int[]{transparent, edgeBright, edge, transparent},
                new float[]{0f, .22f, .65f, 1f}, Shader.TileMode.CLAMP);
        caustic = new RadialGradient(0, 0, 1,
                new int[]{light, transparent}, null, Shader.TileMode.CLAMP);
    }

    private static int color(Context context, int resource) {
        return ContextCompat.getColor(context, resource);
    }

    void draw(Canvas canvas, int width, int height, RectF anchor, float flow, float pressure,
              float focusX, float focusY, LiquidGlassBackdrop backdrop) {
        if (width < 2 || height < 2) return;
        shell.set(dp(.8f), dp(.8f), width - dp(.8f), height - dp(.8f));
        float radius = shell.height() / 2f;
        shellPath.reset();
        shellPath.addRoundRect(shell, radius, radius, Path.Direction.CW);
        fill(clearTint);
        canvas.drawPath(shellPath, paint);
        map(shellLight, shell, 0, 0);
        fill(shellLight);
        canvas.drawPath(shellPath, paint);
        stroke(shellRim, shell, dp(.8f), focusX * dp(2), 0);
        canvas.drawPath(shellPath, paint);
        if (anchor.width() <= 0 || anchor.height() <= 0) return;

        flow = LiquidGlassMotion.clamp(flow, -1f, 1f);
        pressure = LiquidGlassMotion.clamp(pressure, 0f, 1f);
        float stretch = dp(7f) * Math.abs(flow);
        float squeeze = dp(1.5f) * Math.abs(flow);
        lens.set(Math.max(shell.left + dp(2), anchor.left - Math.max(flow, 0) * stretch - pressure * dp(1.2f)),
                Math.max(shell.top + dp(2), anchor.top + squeeze - pressure * dp(.7f)),
                Math.min(shell.right - dp(2), anchor.right + Math.max(-flow, 0) * stretch + pressure * dp(1.2f)),
                Math.min(shell.bottom - dp(2), anchor.bottom - squeeze + pressure * dp(.7f)));
        buildLens(flow);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(shade);
        paint.setStrokeWidth(dp(2.4f));
        canvas.drawPath(lensPath, paint);

        int save = canvas.save();
        canvas.clipPath(lensPath);
        BitmapShader image = backdrop.shader();
        if (image != null) {
            // Local magnification bends the real page beneath the lens; it is
            // not a blurred copy of the labels or a static gradient pretending to blur.
            matrix.setScale(width / (float) backdrop.width(), height / (float) backdrop.height());
            matrix.postScale(1.045f + pressure * .012f, 1.035f, lens.centerX(), lens.centerY());
            matrix.postTranslate(flow * dp(1.8f) + focusX * dp(.65f), focusY * dp(.6f));
            image.setLocalMatrix(matrix);
            fill(image);
            paint.setAlpha(205);
            canvas.drawPath(lensPath, paint);
        }
        fill(frostTint);
        canvas.drawPath(lensPath, paint);
        map(lensLight, lens, -focusX * dp(2), -focusY * dp(1));
        fill(lensLight);
        canvas.drawPath(lensPath, paint);
        matrix.setScale(lens.width() * .65f, lens.height() * 1.3f);
        matrix.postTranslate(lens.centerX() + focusX * lens.width() * .22f,
                lens.top - lens.height() * .3f + focusY * dp(2));
        caustic.setLocalMatrix(matrix);
        fill(caustic);
        glow.set(lens.left, lens.top, lens.right, lens.bottom);
        canvas.drawOval(glow, paint);
        canvas.restoreToCount(save);

        // Fresnel-like edge light, a thin lower rim, and a soft travelling meniscus.
        stroke(lensRim, lens, dp(.85f), focusX * dp(2), focusY * dp(1));
        canvas.drawPath(lensPath, paint);
        int inner = canvas.save();
        canvas.scale(.987f, .958f, lens.centerX(), lens.centerY());
        paint.setStrokeWidth(dp(.45f));
        paint.setAlpha(85);
        canvas.drawPath(lensPath, paint);
        canvas.restoreToCount(inner);
        highlightPath.reset();
        highlightPath.moveTo(lens.left + lens.height() * .40f, lens.top + dp(1.7f));
        highlightPath.cubicTo(lens.left + lens.width() * .32f, lens.top + dp(1.0f) - flow * dp(.55f),
                lens.right - lens.width() * .32f, lens.top + dp(1.0f) + flow * dp(.55f),
                lens.right - lens.height() * .40f, lens.top + dp(1.7f));
        stroke(glint, lens, dp(.65f), focusX * dp(3), 0);
        canvas.drawPath(highlightPath, paint);
        paint.setShader(null);
    }

    private void buildLens(float flow) {
        float r = lens.height() / 2f;
        float k = .55228475f * r;
        float wave = flow * dp(.9f);
        float leftMid = lens.centerY() + wave * .35f;
        float rightMid = lens.centerY() - wave * .35f;
        lensPath.reset();
        lensPath.moveTo(lens.left + r, lens.top);
        lensPath.cubicTo(lens.left + r + lens.width() * .15f, lens.top - wave,
                lens.right - r - lens.width() * .15f, lens.top + wave, lens.right - r, lens.top);
        lensPath.cubicTo(lens.right - r + k, lens.top, lens.right, rightMid - k, lens.right, rightMid);
        lensPath.cubicTo(lens.right, rightMid + k, lens.right - r + k, lens.bottom, lens.right - r, lens.bottom);
        lensPath.cubicTo(lens.right - r - lens.width() * .15f, lens.bottom + wave,
                lens.left + r + lens.width() * .15f, lens.bottom - wave, lens.left + r, lens.bottom);
        lensPath.cubicTo(lens.left + r - k, lens.bottom, lens.left, leftMid + k, lens.left, leftMid);
        lensPath.cubicTo(lens.left, leftMid - k, lens.left + r - k, lens.top, lens.left + r, lens.top);
        lensPath.close();
    }

    private void map(Shader shader, RectF bounds, float x, float y) {
        matrix.setScale(bounds.width(), bounds.height());
        matrix.postTranslate(bounds.left + x, bounds.top + y);
        shader.setLocalMatrix(matrix);
    }

    private void fill(int color) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
    }

    private void fill(Shader shader) {
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(shader);
        paint.setAlpha(255);
    }

    private void stroke(Shader shader, RectF bounds, float width, float x, float y) {
        map(shader, bounds, x, y);
        paint.setStyle(Paint.Style.STROKE);
        paint.setShader(shader);
        paint.setAlpha(255);
        paint.setStrokeWidth(width);
    }

    private float dp(float value) { return value * density; }
}
