package com.xlox.utils;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

/**
 * Per-game availability set on the panel's Server page, cached from the SAME
 * verified library manifest request as Downtwo (no extra HTTP). The last good
 * answer remains available offline; a missing or unknown value always means
 * "available", so a panel hiccup never blocks Launch.
 *
 * <p>Statuses: {@code available} launches normally; {@code unavailable} and
 * {@code maintenance} disable Launch and show the panel's message (or a short
 * default) on the game card.
 */
public final class GameStatus {

    public static final String AVAILABLE = "available";
    public static final String UNAVAILABLE = "unavailable";
    public static final String MAINTENANCE = "maintenance";

    private static final String PREFS = "anoy_game_status";
    private static final int MESSAGE_MAX = 200;

    private GameStatus() {
    }

    /** Panel key for a game ("bgmi" / "bgmi_air") — the manifest's `games` keys. */
    private static String key(GameTarget game) {
        return game == GameTarget.BGMI_AIR ? "bgmi_air" : "bgmi";
    }

    /** Last known status for the game; never null, always a known value. */
    public static String status(Context context, GameTarget game) {
        String value = prefs(context).getString(key(game) + "_status", AVAILABLE);
        return isKnown(value) ? value : AVAILABLE;
    }

    /** The panel's message for the game ("" when none). Only shown while not available. */
    public static String message(Context context, GameTarget game) {
        return prefs(context).getString(key(game) + "_message", "");
    }

    /** True only for {@code available}: anything else disables Launch. */
    public static boolean isPlayable(Context context, GameTarget game) {
        return AVAILABLE.equals(status(context, game));
    }

    /** Called only after a successful, valid library manifest response. */
    static void remember(Context context, JSONObject games) {
        if (games == null) return; // Older panel without per-game status: keep the last answer.
        SharedPreferences.Editor edit = prefs(context).edit();
        for (GameTarget game : GameTarget.values()) {
            JSONObject entry = games.optJSONObject(key(game));
            if (entry == null) continue;
            String status = entry.optString("status", AVAILABLE).trim().toLowerCase(java.util.Locale.US);
            if (!isKnown(status)) status = AVAILABLE;
            String message = entry.optString("message", "").trim();
            if (message.length() > MESSAGE_MAX) message = message.substring(0, MESSAGE_MAX).trim();
            edit.putString(key(game) + "_status", status).putString(key(game) + "_message", message);
        }
        edit.apply();
    }

    private static boolean isKnown(String value) {
        return AVAILABLE.equals(value) || UNAVAILABLE.equals(value) || MAINTENANCE.equals(value);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
