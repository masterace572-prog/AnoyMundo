#!/usr/bin/env python3
"""Compile the actual Android-independent blur class and check it against a slow oracle."""
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
if not shutil.which('javac'):
    raise SystemExit('JDK 17 is required; this check is mandatory in the Liquid build workflow')

HARNESS = r'''
import com.xlox.ui.BackdropBlur;
import java.util.Arrays;
import java.util.Random;

public final class BlurRegression {
    static int[] reference(int[] input, int w, int h, int radius) {
        int[] a = input.clone();
        if (radius < 1) return a;
        radius = Math.min(radius, 16);
        for (int pass = 0; pass < 2; pass++) {
            for (boolean horizontal : new boolean[] {true, false}) {
                int[] b = new int[a.length];
                for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                    int red = 0, green = 0, blue = 0;
                    for (int d = -radius; d <= radius; d++) {
                        int xx = horizontal ? Math.max(0, Math.min(w - 1, x + d)) : x;
                        int yy = horizontal ? y : Math.max(0, Math.min(h - 1, y + d));
                        int c = a[yy * w + xx];
                        red += (c >> 16) & 255;
                        green += (c >> 8) & 255;
                        blue += c & 255;
                    }
                    int n = radius * 2 + 1;
                    b[y * w + x] = (255 << 24) | ((red / n) << 16) | ((green / n) << 8) | (blue / n);
                }
                a = b;
            }
        }
        return a;
    }
    public static void main(String[] args) {
        int checks = 0;
        Random random = new Random(75);
        for (int w : new int[] {1, 2, 9, 64}) for (int h : new int[] {1, 3, 17, 28}) {
            for (int radius : new int[] {-1, 0, 1, 4, 16, 30}) {
                int[] a = new int[w * h];
                for (int i = 0; i < a.length; i++) a[i] = random.nextInt();
                int[] expected = reference(a, w, h, radius);
                BackdropBlur.blur(a, w, h, radius);
                if (!Arrays.equals(a, expected)) throw new AssertionError(w + "x" + h + " r=" + radius);
                checks++;
            }
        }
        for (int[] dimensions : new int[][] {{0, 1}, {1, 0}, {-1, 2}, {1, 2}}) {
            try {
                BackdropBlur.blur(new int[1], dimensions[0], dimensions[1], 3);
                throw new AssertionError("Invalid dimensions accepted");
            } catch (IllegalArgumentException expected) { checks++; }
        }
        System.out.println("PASS " + checks + " backdrop blur oracle / edge / radius / dimension regressions");
    }
}
'''
with tempfile.TemporaryDirectory(prefix='liquid-blur-') as directory:
    directory = Path(directory)
    harness = directory / 'BlurRegression.java'
    harness.write_text(HARNESS)
    source = ROOT / 'app/src/main/java/com/xlox/ui/BackdropBlur.java'
    subprocess.run(['javac', '-d', str(directory), str(source), str(harness)], check=True)
    subprocess.run(['java', '-cp', str(directory), 'BlurRegression'], check=True)
