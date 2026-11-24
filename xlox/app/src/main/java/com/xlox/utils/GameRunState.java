package com.xlox.utils;

/** One game's running reconciliation. Never shared between game packages. */
public final class GameRunState {
    private static final long SETTLE_MS = 2000L;
    private static final long FIRST_SEEN_MS = 30_000L;
    private boolean running;
    private int misses;
    private int hits;
    private long settleUntil;
    private long launchedAt;
    private boolean seenSinceLaunch;

    public boolean isRunning() { return running; }

    public void launched(long now) {
        running = true;
        launchedAt = now;
        settleUntil = now + SETTLE_MS;
        seenSinceLaunch = false;
        resetObservations();
    }

    public void stopped(long now) {
        running = false;
        settleUntil = now + SETTLE_MS;
        resetObservations();
    }

    public void resetObservations() {
        misses = 0;
        hits = 0;
    }

    /** Two hits/misses suppress transient container blips. Returns true on a UI change. */
    public boolean observe(boolean containerRunning, long now) {
        if (now < settleUntil) return false;
        if (containerRunning) {
            misses = 0;
            seenSinceLaunch = true;
            if (!running && ++hits >= 2) {
                running = true;
                hits = 0;
                return true;
            }
            return false;
        }
        hits = 0;
        if (!running) {
            misses = 0;
            return false;
        }
        if (!seenSinceLaunch && now - launchedAt < FIRST_SEEN_MS) return false;
        if (++misses >= 2) {
            running = false;
            misses = 0;
            return true;
        }
        return false;
    }
}
