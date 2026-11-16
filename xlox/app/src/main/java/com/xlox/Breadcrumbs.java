package com.xlox;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Small in-memory event trail included in local crash reports. */
public final class Breadcrumbs {

    private static final int CAPACITY = 50;
    private static final String[] ENTRIES = new String[CAPACITY];
    private static int next;
    private static int size;

    private Breadcrumbs() {
    }

    /**
     * Adds a fixed, non-sensitive event description. Do not pass user input,
     * licence keys, device IDs, tokens, or server responses.
     */
    public static synchronized void add(String event) {
        try {
            if (event == null) return;
            String line = event.replace('\r', ' ').replace('\n', ' ').trim();
            if (line.isEmpty()) return;
            if (line.length() > 240) line = line.substring(0, 240);
            ENTRIES[next] = timestamp(System.currentTimeMillis()) + " " + line;
            next = (next + 1) % CAPACITY;
            if (size < CAPACITY) size++;
        } catch (Throwable ignored) {
            // A diagnostic breadcrumb must never affect the app's normal path.
        }
    }

    static synchronized String dump() {
        try {
            StringBuilder out = new StringBuilder(size * 48);
            int first = (next - size + CAPACITY) % CAPACITY;
            for (int i = 0; i < size; i++) {
                String entry = ENTRIES[(first + i) % CAPACITY];
                if (entry == null) continue;
                if (out.length() > 0) out.append('\n');
                out.append(entry);
            }
            return out.toString();
        } catch (Throwable ignored) {
            return "Breadcrumb snapshot unavailable";
        }
    }

    private static String timestamp(long timeMillis) {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)
                .format(new Date(timeMillis));
    }
}
