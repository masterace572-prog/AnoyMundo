package com.xlox.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.xlox.BuildConfig;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 2.3.5: "BGMI updated before the library" warning.
 *
 * The panel's library page stores the newest BGMI version the current library
 * works with; GET /api/lib/manifest returns it as {@code game_version}. This
 * class only reads that one field (a separate, read-only request - the library
 * update check in Downtwo/UpdateGate is untouched) and remembers the last
 * answer, so the warning also shows offline. Empty = no warning.
 *
 * It never blocks anything: Launch works exactly as before.
 */
public final class GameSupport {

    private static final String TAG = "GameSupport";
    private static final String PREFS = "anoy_game_support";
    private static final String KEY_VERSION = "supported_version";
    private static final int CONNECT_TIMEOUT_MS = 8_000;
    private static final int READ_TIMEOUT_MS = 10_000;
    private static final int MAX_BODY = 64 * 1024;

    private GameSupport() {
    }

    /** Last known supported BGMI version ("" when none is set or never fetched). */
    public static String cached(Context context) {
        return prefs(context).getString(KEY_VERSION, "");
    }

    /**
     * Fetches the supported BGMI version from the panel and stores it.
     * Blocking - call off the main thread. Any failure keeps the old value.
     */
    public static void refresh(Context context) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(Downtwo.manifestUrl()).openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "Anoy/" + BuildConfig.VERSION_NAME + " (Android)");
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) return;
            JSONObject json = new JSONObject(read(connection.getInputStream()));
            if (!json.optBoolean("ok", false)) return;
            String value = json.optString("game_version", "").trim();
            if (!value.isEmpty() && !value.matches("^\\d{1,4}(\\.\\d{1,6}){0,3}$")) value = "";
            prefs(context).edit().putString(KEY_VERSION, value).apply();
        } catch (Exception e) {
            Log.w(TAG, "Supported BGMI version check failed: " + e.getMessage());
        } finally {
            if (connection != null) connection.disconnect();
        }
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

    private static String read(InputStream in) throws java.io.IOException {
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = stream.read(buffer)) != -1) {
                out.write(buffer, 0, n);
                if (out.size() > MAX_BODY) throw new java.io.IOException("Response too large");
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
