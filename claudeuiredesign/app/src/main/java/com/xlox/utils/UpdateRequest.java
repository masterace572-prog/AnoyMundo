package com.xlox.utils;

import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Cancellation and a wall-clock budget for one screen's update work.
 * Closing a screen closes its socket too, not just its UI callbacks. No Android
 * dependencies: the cancellation/deadline behaviour is exercised on the JVM.
 */
public final class UpdateRequest implements AutoCloseable {
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "update-deadline");
        thread.setDaemon(true);
        return thread;
    });
    private static final java.util.concurrent.ExecutorService CLOSER = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "update-close");
        thread.setDaemon(true);
        return thread;
    });

    private boolean cancelled;
    private boolean expired;
    private long deadlineNanos;
    private long generation;
    private HttpURLConnection active;
    private ScheduledFuture<?> alarm;

    /** Starts a new operation; checks and downloads may reuse this screen token sequentially. */
    public synchronized void begin(long timeoutMs) throws InterruptedIOException {
        check();
        if (active != null || alarm != null) throw new IllegalStateException("Update request already in use");
        expired = false;
        deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        long current = ++generation;
        alarm = TIMER.schedule(() -> expire(current), timeoutMs, TimeUnit.MILLISECONDS);
    }

    private void expire(long current) {
        HttpURLConnection connection;
        synchronized (this) {
            if (current != generation || alarm == null) return;
            expired = true;
            connection = active;
            notifyAll();
        }
        disconnectAsync(connection);
    }

    public void cancel() {
        HttpURLConnection connection;
        synchronized (this) {
            cancelled = true;
            connection = active;
            notifyAll();
        }
        // Some Android HTTP implementations close synchronously under a read
        // lock. Never let disconnect block the main/UI thread.
        disconnectAsync(connection);
    }

    public synchronized boolean isCancelled() {
        return cancelled;
    }

    public synchronized void check() throws InterruptedIOException {
        if (cancelled || Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Update request cancelled");
        }
        if (expired || (deadlineNanos != 0L && System.nanoTime() >= deadlineNanos)) {
            throw new SocketTimeoutException("Update request deadline exceeded");
        }
    }

    public synchronized int timeout(int maximumMs) throws InterruptedIOException {
        check();
        if (deadlineNanos == 0L) return maximumMs;
        long left = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
        return (int) Math.max(1L, Math.min(maximumMs, left));
    }

    /** Register BEFORE connect/getResponseCode so cancellation covers waiting for headers too. */
    public synchronized void attach(HttpURLConnection connection) throws InterruptedIOException {
        check();
        active = connection;
    }

    public synchronized void detach(HttpURLConnection connection) {
        if (active == connection) active = null;
    }

    /** Interruptible retry backoff; cancelling a screen wakes it immediately. */
    public synchronized void pause(long millis) throws InterruptedIOException {
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (System.nanoTime() < until) {
            check();
            long left = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(until - System.nanoTime()));
            try {
                wait(Math.min(left, timeout(Integer.MAX_VALUE)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Update retry interrupted");
            }
        }
        check();
    }

    /** Release this operation's timer/socket without cancelling the whole screen. */
    @Override
    public void close() {
        HttpURLConnection connection;
        synchronized (this) {
            ++generation;
            if (alarm != null) alarm.cancel(false);
            alarm = null;
            deadlineNanos = 0L;
            expired = false;
            connection = active;
            active = null;
        }
        if (connection != null) connection.disconnect();
    }

    private static void disconnectAsync(HttpURLConnection connection) {
        if (connection != null) CLOSER.execute(connection::disconnect);
    }
}
