package com.xlox.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.xlox.R;

/** Static ambient light, not a bitmap, animation, network asset or expensive blur. */
final class LiquidBackdrop extends Drawable {
    private final Context context;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int base;
    private final int cool;
    private final int quiet;
    private final int clear;
    private Shader upper;
    private Shader lower;

    LiquidBackdrop(Context context) {
        this.context = context;
        base = ContextCompat.getColor(context, R.color.background);
        cool = ContextCompat.getColor(context, R.color.ambient_blue);
        quiet = ContextCompat.getColor(context, R.color.ambient_ice);
        clear = ContextCompat.getColor(context, R.color.transparent);
    }

    @Override
    protected void onBoundsChange(Rect b) {
        float width = Math.max(1, b.width());
        float height = Math.max(1, b.height());
        upper = new RadialGradient(b.left + width * .9f, b.top + height * .08f,
                width * 1.05f, cool, clear, Shader.TileMode.CLAMP);
        lower = new RadialGradient(b.left - width * .2f, b.top + height * .72f,
                width * .9f, quiet, clear, Shader.TileMode.CLAMP);
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        canvas.drawColor(base);
        if (!Liquid.effects(context)) return;
        paint.setShader(upper);
        canvas.drawRect(getBounds(), paint);
        paint.setShader(lower);
        canvas.drawRect(getBounds(), paint);
        paint.setShader(null);
    }

    @Override public void setAlpha(int alpha) { }
    @Override public void setColorFilter(@Nullable ColorFilter filter) { }
    @Override public int getOpacity() { return PixelFormat.OPAQUE; }
}
