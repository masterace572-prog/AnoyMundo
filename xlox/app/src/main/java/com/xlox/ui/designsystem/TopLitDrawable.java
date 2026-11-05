package com.xlox.ui.designsystem;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.xlox.R;

/**
 * Bespoke matte surface drawn on Canvas: a filled rounded rectangle, a 1dp
 * hairline border, a brighter 1dp top-lit edge and a very soft radial pool of
 * light at the top. No drop shadows, no neon glows, no gradient colours in
 * Java — everything resolves from the token layer.
 */
public final class TopLitDrawable extends Drawable {

    public static final int CARD = 0;
    public static final int TILE = 1;
    public static final int RECESSED = 2;
    public static final int PILL = 3;
    public static final int BANNER = 4;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint topEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path clip = new Path();

    private final int variant;
    private final float radius;
    private final float hairlinePx;
    private final int topLight;
    private int alpha = 255;

    public TopLitDrawable(Context context, int variant) {
        this.variant = variant;
        this.hairlinePx = Math.max(1f, Tokens.px(context, R.dimen.divider));
        this.topLight = Tokens.topLight(context);
        this.radius = Tokens.px(context, variant == CARD ? R.dimen.radius_card
                : variant == TILE ? R.dimen.radius_tile : R.dimen.radius_input);

        fill.setStyle(Paint.Style.FILL);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(hairlinePx);
        topEdge.setStyle(Paint.Style.STROKE);
        topEdge.setStrokeWidth(hairlinePx);
        sheen.setStyle(Paint.Style.FILL);

        switch (variant) {
            case BANNER:
                fill.setColor(Tokens.warningWash(context));
                border.setColor(withAlpha(Tokens.warning(context), 110));
                topEdge.setColor(withAlpha(Tokens.warning(context), 150));
                break;
            case RECESSED:
            case PILL:
                fill.setColor(Tokens.surfaceRecessed(context));
                border.setColor(Tokens.hairline(context));
                topEdge.setColor(Tokens.hairlineStrong(context));
                break;
            default:
                fill.setColor(Tokens.surface(context));
                border.setColor(Tokens.hairline(context));
                topEdge.setColor(Tokens.hairlineStrong(context));
                break;
        }
    }

    private static int withAlpha(int colour, int a) {
        return Color.argb(a, Color.red(colour), Color.green(colour), Color.blue(colour));
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);
        float inset = hairlinePx;
        rect.set(bounds.left + inset, bounds.top + inset,
                bounds.right - inset, bounds.bottom - inset);
        clip.reset();
        float r = variant == PILL ? rect.height() / 2f : radius;
        clip.addRoundRect(rect, r, r, Path.Direction.CW);
        if (variant == CARD || variant == TILE || variant == BANNER) {
            sheen.setShader(new RadialGradient(rect.centerX(), rect.top,
                    Math.max(rect.width() * 0.85f, 1f), topLight, Color.TRANSPARENT,
                    Shader.TileMode.CLAMP));
        }
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        if (rect.isEmpty()) return;
        float r = variant == PILL ? rect.height() / 2f : radius;
        int saved = canvas.save();
        canvas.clipPath(clip);
        canvas.drawRect(rect, fill);
        if (variant == CARD || variant == TILE || variant == BANNER) {
            canvas.drawRect(rect, sheen);
        }
        canvas.restoreToCount(saved);
        canvas.drawRoundRect(rect, r, r, border);
        if (variant != PILL) {
            canvas.drawLine(rect.left + r, rect.top + hairlinePx,
                    rect.right - r, rect.top + hairlinePx, topEdge);
        }
    }

    @Override
    public void setAlpha(int a) { alpha = a; invalidateSelf(); }

    @Override
    public int getAlpha() { return alpha; }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        fill.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
