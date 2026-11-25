package com.xlox.ui;

/** Small, bounded spring model for the glass shape, never for the page/touch position. */
final class LiquidGlassMotion {
    private float previousProgress;
    private float targetFlow;
    private float flow;
    private float flowVelocity;
    private float pressure;
    private float pressureVelocity;
    private boolean pressed;
    private boolean hasProgress;
    private long lastProgressTime;
    private long lastFrameTime;

    void progress(float position, long time) {
        if (Float.isNaN(position) || Float.isInfinite(position)) return;
        position = clamp(position, 0f, 1f);
        if (hasProgress && time > lastProgressTime && Math.abs(position - previousProgress) > .0001f) {
            float seconds = Math.max(.008f, Math.min(.08f, (time - lastProgressTime) / 1000f));
            targetFlow = clamp((position - previousProgress) / (seconds * 3.5f), -1f, 1f);
        }
        if (!hasProgress || position != previousProgress) lastProgressTime = time;
        hasProgress = true;
        previousProgress = position;
    }

    void press(boolean value) {
        pressed = value;
    }

    /** Returns false at rest. There is no perpetual shimmer/breathing animation. */
    boolean advance(long time, boolean reducedMotion) {
        if (reducedMotion) {
            snap(previousProgress);
            return false;
        }
        float dt = lastFrameTime == 0 ? 1f / 60f
                : clamp((time - lastFrameTime) / 1000f, 0f, .032f);
        lastFrameTime = time;
        if (time - lastProgressTime > 32L) targetFlow *= (float) Math.exp(-13f * dt);
        // Semi-implicit integration at bounded steps; modest damping, no page overshoot.
        int steps = Math.max(1, (int) Math.ceil(dt / .008f));
        float step = dt / steps;
        for (int i = 0; i < steps; i++) {
            flowVelocity += (260f * (targetFlow - flow) - 27f * flowVelocity) * step;
            flow = clamp(flow + flowVelocity * step, -1.12f, 1.12f);
            pressureVelocity += (360f * ((pressed ? 1f : 0f) - pressure)
                    - 32f * pressureVelocity) * step;
            pressure = clamp(pressure + pressureVelocity * step, 0f, 1.04f);
        }
        if (Math.abs(targetFlow) < .001f) targetFlow = 0f;
        if (Math.abs(flow) < .001f && Math.abs(flowVelocity) < .012f && targetFlow == 0f) {
            flow = flowVelocity = 0f;
        }
        float targetPressure = pressed ? 1f : 0f;
        if (Math.abs(pressure - targetPressure) < .001f && Math.abs(pressureVelocity) < .012f) {
            pressure = targetPressure;
            pressureVelocity = 0f;
        }
        return flow != 0f || targetFlow != 0f || flowVelocity != 0f
                || pressure != targetPressure || pressureVelocity != 0f;
    }

    void snap(float progress) {
        previousProgress = Float.isNaN(progress) || Float.isInfinite(progress)
                ? 0f : clamp(progress, 0f, 1f);
        hasProgress = true;
        targetFlow = flow = flowVelocity = pressure = pressureVelocity = 0f;
        pressed = false;
        lastFrameTime = 0L;
        lastProgressTime = 0L;
    }

    float flow() { return flow; }
    float pressure() { return pressure; }

    static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
