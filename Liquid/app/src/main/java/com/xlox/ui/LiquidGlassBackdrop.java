package com.xlox.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewParent;
import android.view.ViewTreeObserver;

/**
 * A tiny, in-memory crop of the pager ONLY, used to frost the selected glass lens.
 * Never draws the root/window (which would include this nav), captures a screen,
 * uses PixelCopy, stores a file, or requests a permission. The shell stays clear.
 */
final class LiquidGlassBackdrop implements ViewTreeObserver.OnPreDrawListener {
    static final int MAX_PIXELS = 12288;
    private static final long FRAME_INTERVAL_MS = 32L;
    private final View owner;
    private final int[] ownerLocation = new int[2];
    private final int[] sourceLocation = new int[2];
    private View source;
    private ViewTreeObserver observer;
    private Bitmap sample;
    private Bitmap frost;
    private Canvas sampleCanvas;
    private BitmapShader shader;
    private int[] pixels;
    private int[] work;
    private int ownerWidth;
    private int ownerHeight;
    private long previousHash;
    private long lastCapture = Long.MIN_VALUE;
    private boolean hasFrame;
    private boolean disabled;
    private int captureCount;

    LiquidGlassBackdrop(View owner) { this.owner = owner; }

    void source(View view) {
        if (view == owner) throw new IllegalArgumentException("Glass cannot sample itself");
        for (ViewParent parent = owner.getParent(); parent instanceof View; parent = parent.getParent()) {
            if (parent == view) throw new IllegalArgumentException("Glass source must not contain navigation");
        }
        detach();
        source = view;
        disabled = false;
        attach();
    }

    void attach() {
        if (source == null || !owner.isAttachedToWindow() || observer != null) return;
        observer = owner.getViewTreeObserver();
        if (observer.isAlive()) observer.addOnPreDrawListener(this);
    }

    void detach() {
        if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(this);
        observer = null;
        releaseBuffers();
    }

    @Override
    public boolean onPreDraw() {
        if (capture(SystemClock.uptimeMillis())) owner.invalidate();
        // No timed sampler/idle invalidate loop. Unchanged content schedules no frame.
        return true;
    }

    boolean capture(long now) {
        if (source == null || disabled || !owner.isShown() || !source.isShown()
                || owner.getWidth() <= 0 || owner.getHeight() <= 0) return false;
        if (lastCapture != Long.MIN_VALUE && now - lastCapture < FRAME_INTERVAL_MS) return false;
        lastCapture = now;
        try {
            ensureBuffers();
            lastCapture = now;
            owner.getLocationInWindow(ownerLocation);
            source.getLocationInWindow(sourceLocation);
            sample.eraseColor(Color.TRANSPARENT);
            int save = sampleCanvas.save();
            try {
                sampleCanvas.scale(sample.getWidth() / (float) owner.getWidth(),
                        sample.getHeight() / (float) owner.getHeight());
                sampleCanvas.translate(sourceLocation[0] - ownerLocation[0],
                        sourceLocation[1] - ownerLocation[1]);
                source.draw(sampleCanvas);
            } finally {
                sampleCanvas.restoreToCount(save);
            }
            captureCount++;
            sample.getPixels(pixels, 0, sample.getWidth(), 0, 0, sample.getWidth(), sample.getHeight());
            long hash = 1469598103934665603L;
            for (int value : pixels) hash = (hash ^ value) * 1099511628211L;
            if (hasFrame && hash == previousHash) return false;
            previousHash = hash;
            blur(pixels, work, sample.getWidth(), sample.getHeight());
            blur(pixels, work, sample.getWidth(), sample.getHeight());
            frost.setPixels(pixels, 0, frost.getWidth(), 0, 0, frost.getWidth(), frost.getHeight());
            hasFrame = true;
            return true;
        } catch (RuntimeException | OutOfMemoryError unavailable) {
            // Hardware-only child content / allocation failure: retain transparent
            // optics, never replace the bar with an opaque fallback or crash Home.
            disabled = true;
            releaseBuffers();
            return true;
        }
    }

    private void ensureBuffers() {
        if (sample != null && ownerWidth == owner.getWidth() && ownerHeight == owner.getHeight()) return;
        releaseBuffers();
        ownerWidth = owner.getWidth();
        ownerHeight = owner.getHeight();
        float scale = Math.min(.125f, (float) Math.sqrt(MAX_PIXELS / (double) (ownerWidth * (long) ownerHeight)));
        int width = Math.max(1, (int) (ownerWidth * scale));
        int height = Math.max(1, (int) (ownerHeight * scale));
        sample = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        frost = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        sampleCanvas = new Canvas(sample);
        shader = new BitmapShader(frost, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        pixels = new int[width * height];
        work = new int[pixels.length];
    }

    /** Separable [1,2,1] kernel. Eight downsampled pixels ~= a few dp of soft frost. */
    static void blur(int[] values, int[] scratch, int width, int height) {
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) scratch[row + x] = average(
                    values[row + Math.max(0, x - 1)], values[row + x],
                    values[row + Math.min(width - 1, x + 1)]);
        }
        for (int y = 0; y < height; y++) {
            int above = Math.max(0, y - 1) * width;
            int row = y * width;
            int below = Math.min(height - 1, y + 1) * width;
            for (int x = 0; x < width; x++) values[row + x] = average(scratch[above + x],
                    scratch[row + x], scratch[below + x]);
        }
    }

    private static int average(int a, int b, int c) {
        int alpha = ((a >>> 24) + 2 * (b >>> 24) + (c >>> 24)) / 4;
        int red = (((a >> 16) & 255) + 2 * ((b >> 16) & 255) + ((c >> 16) & 255)) / 4;
        int green = (((a >> 8) & 255) + 2 * ((b >> 8) & 255) + ((c >> 8) & 255)) / 4;
        int blue = ((a & 255) + 2 * (b & 255) + (c & 255)) / 4;
        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }

    private void releaseBuffers() {
        // Let Android release bitmap references safely; never recycle a bitmap
        // while a hardware display list may still refer to its previous frame.
        shader = null;
        sampleCanvas = null;
        sample = frost = null;
        pixels = work = null;
        hasFrame = false;
        lastCapture = Long.MIN_VALUE;
    }

    BitmapShader shader() { return hasFrame ? shader : null; }
    int width() { return frost == null ? 0 : frost.getWidth(); }
    int height() { return frost == null ? 0 : frost.getHeight(); }
    int pixelCount() { return pixels == null ? 0 : pixels.length; }
    int captureCount() { return captureCount; }
    boolean listening() { return observer != null && observer.isAlive(); }
}
