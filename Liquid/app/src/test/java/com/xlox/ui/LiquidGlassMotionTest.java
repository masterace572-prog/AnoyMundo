package com.xlox.ui;

import static org.junit.Assert.*;
import org.junit.Test;

/** Real production spring model, deterministic clock, no Android/native SDK. */
public class LiquidGlassMotionTest {
    @Test public void restingGlassDoesNotRequestAnAnimation() {
        LiquidGlassMotion motion = new LiquidGlassMotion();
        motion.snap(0f);
        assertFalse(motion.advance(1000, false));
        assertEquals(0f, motion.flow(), 0f);
    }

    @Test public void travelDevelopsDirectionalLiquidFlow() {
        LiquidGlassMotion motion = moving();
        assertTrue(motion.flow() > .1f);
        assertTrue(motion.flow() <= 1.12f);
    }

    @Test public void reversingTravelReversesTheLiquidTail() {
        LiquidGlassMotion motion = moving();
        for (int i = 1; i <= 12; i++) {
            motion.progress(.6f - i * .045f, 1200 + i * 16);
            motion.advance(1200 + i * 16, false);
        }
        assertTrue(motion.flow() < -.1f);
    }

    @Test public void releaseSettlesCompletelyInsteadOfBreathingForever() {
        LiquidGlassMotion motion = moving();
        boolean active = true;
        for (int i = 1; i <= 240; i++) active = motion.advance(1400 + i * 16, false);
        assertFalse(active);
        assertEquals(0f, motion.flow(), 0f);
    }

    @Test public void touchPressureHasItsOwnSoftSpring() {
        LiquidGlassMotion motion = new LiquidGlassMotion();
        motion.snap(0f);
        motion.press(true);
        motion.advance(1000, false);
        assertTrue(motion.pressure() > 0f && motion.pressure() < .5f);
        for (int i = 1; i < 100; i++) motion.advance(1000 + i * 16, false);
        assertEquals(1f, motion.pressure(), 0f);
        assertFalse(motion.advance(2700, false));
    }

    @Test public void cancelledTouchReturnsPressureToZero() {
        LiquidGlassMotion motion = moving();
        motion.press(true);
        for (int i = 0; i < 20; i++) motion.advance(1300 + i * 16, false);
        motion.press(false);
        for (int i = 0; i < 160; i++) motion.advance(1700 + i * 16, false);
        assertEquals(0f, motion.pressure(), 0f);
        assertFalse(motion.advance(4400, false));
    }

    @Test public void reducedMotionSnapsWithoutWaterWobble() {
        LiquidGlassMotion motion = moving();
        motion.press(true);
        assertFalse(motion.advance(1500, true));
        assertEquals(0f, motion.flow(), 0f);
        assertEquals(0f, motion.pressure(), 0f);
    }

    @Test public void frameGapsAndClockRepetitionsRemainFiniteAndBounded() {
        LiquidGlassMotion motion = moving();
        long[] frames = {1200, 1200, 1100, 1500, 100000, 100016};
        for (long frame : frames) {
            motion.advance(frame, false);
            assertTrue(Float.isFinite(motion.flow()));
            assertTrue(Math.abs(motion.flow()) <= 1.12f);
            assertTrue(motion.pressure() >= 0f && motion.pressure() <= 1.04f);
        }
    }

    @Test public void invalidPositionsCannotPoisonTheSpring() {
        LiquidGlassMotion motion = moving();
        motion.progress(Float.NaN, 1400);
        motion.progress(Float.POSITIVE_INFINITY, 1416);
        motion.advance(1432, false);
        assertTrue(Float.isFinite(motion.flow()));
        motion.snap(Float.NaN);
        assertFalse(motion.advance(1500, false));
    }

    @Test public void largePageJumpsAndLongSequencesStayBounded() {
        LiquidGlassMotion motion = new LiquidGlassMotion();
        motion.snap(0f);
        for (int i = 1; i <= 1000; i++) {
            motion.progress(i % 2 == 0 ? -10f : 10f, 1000 + i * 8);
            motion.press(i % 3 == 0);
            motion.advance(1000 + i * 8, false);
            assertTrue(Math.abs(motion.flow()) <= 1.12f);
            assertTrue(motion.pressure() >= 0f && motion.pressure() <= 1.04f);
        }
    }

    private static LiquidGlassMotion moving() {
        LiquidGlassMotion motion = new LiquidGlassMotion();
        motion.progress(0f, 1000);
        for (int i = 1; i <= 12; i++) {
            motion.progress(i * .05f, 1000 + i * 16);
            motion.advance(1000 + i * 16, false);
        }
        return motion;
    }
}
