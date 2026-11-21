package com.xlox.ui;

/**
 * Small separable box blur for a DOWNSAMPLED backdrop, on API 21+.
 * Two passes approximate a soft Gaussian. O(width * height), clamped edges,
 * opaque output; no RenderScript, native library or full-screen allocation.
 * This class has no Android dependencies and is covered by JVM regressions.
 */
public final class BackdropBlur {
    private BackdropBlur() { }

    public static void blur(int[] pixels, int width, int height, int radius) {
        if (width <= 0 || height <= 0 || pixels.length != width * height) {
            throw new IllegalArgumentException("Invalid backdrop dimensions");
        }
        if (radius < 1) return;
        radius = Math.min(radius, 16);
        int[] scratch = new int[pixels.length];
        for (int pass = 0; pass < 2; pass++) {
            axis(pixels, scratch, width, height, radius, true);
            axis(scratch, pixels, width, height, radius, false);
        }
    }

    private static void axis(int[] in, int[] out, int width, int height,
                             int radius, boolean horizontal) {
        int lines = horizontal ? height : width;
        int length = horizontal ? width : height;
        int stride = horizontal ? 1 : width;
        int window = radius * 2 + 1;
        for (int line = 0; line < lines; line++) {
            int start = horizontal ? line * width : line;
            int r = 0, g = 0, b = 0;
            for (int k = -radius; k <= radius; k++) {
                int colour = in[start + clamp(k, length) * stride];
                r += (colour >> 16) & 255;
                g += (colour >> 8) & 255;
                b += colour & 255;
            }
            for (int x = 0; x < length; x++) {
                out[start + x * stride] = (255 << 24) | ((r / window) << 16)
                        | ((g / window) << 8) | (b / window);
                int old = in[start + clamp(x - radius, length) * stride];
                int next = in[start + clamp(x + radius + 1, length) * stride];
                r += ((next >> 16) & 255) - ((old >> 16) & 255);
                g += ((next >> 8) & 255) - ((old >> 8) & 255);
                b += (next & 255) - (old & 255);
            }
        }
    }

    private static int clamp(int value, int length) {
        return Math.max(0, Math.min(length - 1, value));
    }
}
