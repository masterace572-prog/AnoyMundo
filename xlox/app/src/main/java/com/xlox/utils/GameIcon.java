package com.xlox.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import com.xlox.BuildConfig;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Home's game icon (2.2.0).
 *
 * <p>{@link #GAME_ICON_URL} is the ONE place to set the picture: an https link
 * to a square PNG / JPEG / WebP (512 x 512 is plenty, under 1 MB). It is
 * downloaded once, kept in the app's private files and refreshed weekly;
 * offline the cached copy is used. While it loads, when the constant is
 * empty, or when anything fails, home shows the built-in vector placeholder
 * (res/drawable/game_icon_placeholder.xml) - never a broken image.
 */
public final class GameIcon {

    /** The owner's game icon link (https). Empty = always the placeholder. */
    public static final String GAME_ICON_URL = "";

    private static final String TAG = "GameIcon";
    private static final long MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L;
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    /** Decoded no larger than this (px): plenty for a 64 dp icon at xxxhdpi. */
    private static final int TARGET_PX = 256;

    private GameIcon() {
    }

    /**
     * Blocking (background thread): the icon from the cache or the network,
     * or null for the placeholder.
     */
    public static Bitmap load(Context context) {
        String url = GAME_ICON_URL == null ? "" : GAME_ICON_URL.trim();
        if (url.isEmpty() || !url.toLowerCase(java.util.Locale.US).startsWith("https://")) return null;
        File cache = new File(context.getFilesDir(), "game_icon_" + Integer.toHexString(url.hashCode()) + ".img");
        boolean fresh = cache.isFile() && System.currentTimeMillis() - cache.lastModified() < MAX_AGE_MS;
        if (fresh) {
            Bitmap cached = decode(cache);
            if (cached != null) return cached;
        }
        try {
            byte[] data = download(url);
            Bitmap bitmap = decode(data);
            if (bitmap == null) throw new IOException("Not an image");
            File tmp = new File(cache.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(data);
            }
            if (!tmp.renameTo(cache)) Log.w(TAG, "Could not cache the icon");
            deleteOthers(context, cache);
            return bitmap;
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Game icon unavailable: " + e);
            // Offline or a bad link: an older copy still beats the placeholder.
            return cache.isFile() ? decode(cache) : null;
        }
    }

    private static byte[] download(String start) throws IOException {
        String current = start;
        for (int hop = 0; hop <= 5; hop++) {
            URL url = new URL(current);
            if (!"https".equalsIgnoreCase(url.getProtocol())) throw new IOException("Not https");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            try {
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(10_000);
                connection.setReadTimeout(15_000);
                connection.setRequestProperty("User-Agent", "Anoy/" + BuildConfig.VERSION_NAME + " (Android)");
                int code = connection.getResponseCode();
                if (code >= 300 && code < 400) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("Redirect without location");
                    current = new URL(url, location).toString();
                    continue;
                }
                if (code != 200) throw new IOException("HTTP " + code);
                try (InputStream in = connection.getInputStream();
                     ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[16 * 1024];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                        if (out.size() > MAX_BYTES) throw new IOException("Icon too large");
                    }
                    return out.toByteArray();
                }
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("Too many redirects");
    }

    private static Bitmap decode(File file) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), bounds);
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight);
            return BitmapFactory.decodeFile(file.getPath(), options);
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    private static Bitmap decode(byte[] data) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight);
            return BitmapFactory.decodeByteArray(data, 0, data.length, options);
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    private static int sampleSize(int width, int height) {
        int sample = 1;
        while (width / (sample * 2) >= TARGET_PX && height / (sample * 2) >= TARGET_PX) sample *= 2;
        return sample;
    }

    private static void deleteOthers(Context context, File keep) {
        File[] files = context.getFilesDir().listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.getName().startsWith("game_icon_") && !f.equals(keep) && !f.delete()) {
                Log.w(TAG, "Could not delete " + f.getName());
            }
        }
    }
}
