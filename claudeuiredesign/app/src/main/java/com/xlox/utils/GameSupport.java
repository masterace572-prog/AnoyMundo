package com.xlox.utils;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Remembers the supported BGMI version from the SAME verified library manifest
 * request as Downtwo. No duplicate HTTP request on every new home screen. The
 * last good answer remains available offline; this warning never blocks Launch.
 */
public final class GameSupport {

    private static final String PREFS = "anoy_game_support";
    private static final String KEY_VERSION = "supported_version";

    private GameSupport() {
    }

    /** Last known supported BGMI version ("" when none is set or never fetched). */
    public static String cached(Context context) {
        return prefs(context).getString(KEY_VERSION, "");
    }

    /** Called only after a successful, valid library manifest response. */
    static void remember(Context context, String version) {
        String value = version == null ? "" : version.trim();
        if (!value.isEmpty() && !value.matches("^\\d{1,4}(\\.\\d{1,6}){0,3}$")) value = "";
        prefs(context).edit().putString(KEY_VERSION, value).apply();
    }

    /**
     * True when the installed BGMI is newer than the supported version.
     * Compares number by number, only as far as the panel's value goes:
     * supported "3.9" accepts 3.9.1; supported "3.9.0" does not.
     */
    public static boolean isNewer(String installed, String supported) {
        if (installed == null || supported == null) return false;
        String[] have = installed.trim().split("\\.");
        String[] want = supported.trim().split("\\.");
        if (supported.trim().isEmpty()) return false;
        for (int i = 0; i < want.length; i++) {
            long a = i < have.length ? number(have[i]) : 0L;
            long b = number(want[i]);
            if (a < 0 || b < 0) return false; // not a number: never warn by mistake
            if (a != b) return a > b;
        }
        return false;
    }

    /** Leading digits of a version part ("0-beta" -> 0), or -1. */
    private static long number(String part) {
        int end = 0;
        while (end < part.length() && end < 9 && Character.isDigit(part.charAt(end))) end++;
        if (end == 0) return -1L;
        return Long.parseLong(part.substring(0, end));
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
