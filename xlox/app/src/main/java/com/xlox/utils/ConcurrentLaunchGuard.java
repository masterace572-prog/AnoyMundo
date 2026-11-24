package com.xlox.utils;

/** A fresh, explicit confirmation after ten monotonic seconds; never auto-launches. */
public final class ConcurrentLaunchGuard {
    public static final long WAIT_MS = 10_000L;
    private final long startedAt;
    private boolean consumed;

    public ConcurrentLaunchGuard(long elapsedRealtime) {
        startedAt = elapsedRealtime;
    }

    public int secondsRemaining(long elapsedRealtime) {
        long elapsed = Math.max(0L, elapsedRealtime - startedAt);
        return (int) ((Math.max(0L, WAIT_MS - elapsed) + 999L) / 1000L);
    }

    public boolean confirm(long elapsedRealtime) {
        if (consumed || secondsRemaining(elapsedRealtime) != 0) return false;
        consumed = true;
        return true;
    }

    public void cancel() { consumed = true; }
}
