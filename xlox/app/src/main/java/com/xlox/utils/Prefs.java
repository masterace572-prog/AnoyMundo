package com.xlox.utils;

import android.content.Context;
import android.content.SharedPreferences;

/** Tiny wrapper around the "settings" preferences file (kept for existing installs). */
public class Prefs {
    private final SharedPreferences sp;

    public Prefs(Context context) {
        sp = context.getApplicationContext().getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    public String getSt(String key, String fallback) {
        try {
            return sp.getString(key, fallback);
        } catch (ClassCastException e) {
            return fallback;
        }
    }

    public void setSt(String key, String value) {
        sp.edit().putString(key, value).apply();
    }

    public void remove(String key) {
        sp.edit().remove(key).apply();
    }
}
