package com.xlox.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.util.Log;

import net.lingala.zip4j.ZipFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Talks to the Anoy panel's library endpoints.
 *
 * Version()  -> GET /api/lib/version  (one plain-text line)
 * Link()     -> GET /api/lib/download (ZIP containing libbgmi.so)
 *
 * Fixes applied vs. the original:
 *  - HTTP status / ZIP magic / ELF validation (a 404 HTML page used to be saved
 *    as Xlox.zip and silently reported as success).
 *  - Extract to a staging directory, then install the library at the canonical
 *    files/loader/libbgmi.so path even when the ZIP wraps everything in a folder
 *    or uses the legacy lib.so name.
 *  - Only store the version in prefs after the library really is usable.
 *  - Real timeouts, redirects and a readable failure message for the UI.
 */
public class Downtwo extends AsyncTask<String, String, String> {

    private static final String TAG = "Downtwo";

    public static native String Version();

    public static native String Link();

    public interface Callback {
        void onComplete(boolean success, String result);
    }

    public interface ProgressListener {
        void onProgress(int percent);
    }

    public static final String RESULT_NO_UPDATE = "No Update Available";
    public static final String RESULT_UPDATE = "Update Available";
    public static final String RESULT_NOTIFIED = "Already Notified";
    private static final String RESULT_FAILED = "UPDATE_UNAVAILABLE";
    private static final String ZIP_NAME = "anoy-release.zip";

    // Fallbacks keep the app usable (with a clear error) if libXloX.so failed to
    // load on a device; the native panel_config.h values are the source of truth.
    private static final String FALLBACK_VERSION_URL =
            "https://anoymundo-panel.vercel.app/api/lib/version";
    private static final String FALLBACK_DOWNLOAD_URL =
            "https://anoymundo-panel.vercel.app/api/lib/download";

    /** Never let a missing native library crash the UI thread. */
    public static String versionUrl() {
        try {
            String value = Version();
            return value != null && value.startsWith("http") ? value : FALLBACK_VERSION_URL;
        } catch (Throwable t) {
            Log.e(TAG, "Native Version() unavailable", t);
            return FALLBACK_VERSION_URL;
        }
    }

    public static String downloadUrl() {
        try {
            String value = Link();
            return value != null && value.startsWith("http") ? value : FALLBACK_DOWNLOAD_URL;
        } catch (Throwable t) {
            Log.e(TAG, "Native Link() unavailable", t);
            return FALLBACK_DOWNLOAD_URL;
        }
    }

    private final Context context;
    private final Callback callback;
    private ProgressListener progressListener;
    private volatile String serverVersion = "0.0";
    private volatile String result = "";
    private volatile String failureMessage = "";

    public Downtwo(Context context, Callback callback) {
        this.context = context.getApplicationContext();
        this.callback = callback;
    }

    public void setProgressListener(ProgressListener listener) {
        this.progressListener = listener;
    }

    public String getFetchedServerVersion() {
        return serverVersion;
    }

    public String getResult() {
        return result;
    }

    public String getFailureMessage() {
        return failureMessage;
    }

    @Override
    protected String doInBackground(String... params) {
        try {
            if (params.length == 0 || params[0] == null) {
                return fail("Invalid download URL provided");
            }
            serverVersion = getServerVersion();
            if (serverVersion == null || serverVersion.trim().isEmpty()) {
                return fail("Cannot reach the panel version endpoint");
            }
            serverVersion = serverVersion.trim();

            if (params.length > 1 && "check_only".equals(params[1])) {
                SharedPreferences prefs = LibManager.prefs(context);
                String localVersion = prefs.getString(LibManager.PREF_VERSION_KEY, "0.0");
                String lastNotified = prefs.getString(LibManager.PREF_LAST_NOTIFIED, "0.0");
                // A missing or corrupt library always requires a download, even
                // when the stored version already matches the server.
                if (!LibManager.isUsableLib(context)) return RESULT_UPDATE;
                if (localVersion.equals(serverVersion)) return RESULT_NO_UPDATE;
                return serverVersion.equals(lastNotified) ? RESULT_NOTIFIED : RESULT_UPDATE;
            }

            return downloadAndExtract(params[0]);
        } catch (Exception e) {
            Log.e(TAG, "doInBackground error", e);
            return fail("Background error: " + e.getMessage());
        }
    }

    @Override
    protected void onPostExecute(String outcome) {
        super.onPostExecute(outcome);
        result = outcome == null ? "" : outcome;
        boolean success = outcome == null
                || RESULT_NO_UPDATE.equals(outcome)
                || RESULT_UPDATE.equals(outcome)
                || RESULT_NOTIFIED.equals(outcome);
        if (callback != null) callback.onComplete(success, result);
    }

    private String fail(String message) {
        failureMessage = message;
        Log.e(TAG, message);
        return RESULT_FAILED;
    }

    /** GET /api/lib/version — plain text, one line, e.g. "1.0". */
    public String getServerVersion() {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(versionUrl());
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "Anoy/1.0");
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "Version endpoint returned HTTP " + code);
                return null;
            }
            InputStream in = connection.getInputStream();
            byte[] buffer = new byte[256];
            int read = in.read(buffer);
            in.close();
            if (read <= 0) return null;
            String value = new String(buffer, 0, read, "UTF-8").trim();
            return value.isEmpty() ? "1.0" : value.split("[\\r\\n]")[0].trim();
        } catch (Exception e) {
            Log.e(TAG, "Error fetching server version", e);
            failureMessage = "Version check failed: " + e.getMessage();
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private String downloadAndExtract(String urlString) {
        HttpURLConnection connection = null;
        File zip = new File(context.getFilesDir(), ZIP_NAME);
        File staging = LibManager.stagingDir(context);
        try {
            URL url = new URL(urlString);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(60000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "Anoy/1.0");
            connection.setRequestProperty("Accept", "application/zip");
            connection.connect();

            int code = connection.getResponseCode();
            if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                return fail("No loader release uploaded in the panel yet (Admin -> Library)");
            }
            if (code != HttpURLConnection.HTTP_OK) {
                return fail("Loader download failed with HTTP " + code);
            }

            long totalSize = connection.getContentLength();
            long downloaded = 0L;
            InputStream input = connection.getInputStream();
            OutputStream output = new FileOutputStream(zip, false);
            byte[] data = new byte[8192];
            int count;
            while ((count = input.read(data)) != -1) {
                output.write(data, 0, count);
                downloaded += count;
                if (totalSize > 0 && progressListener != null) {
                    int percent = (int) ((downloaded * 100L) / totalSize);
                    progressListener.onProgress(Math.min(99, percent));
                }
            }
            output.flush();
            output.close();
            input.close();

            if (zip.length() < 64 || !isZip(zip)) {
                return fail("Panel did not return a valid loader ZIP (" + zip.length() + " bytes)");
            }

            LibManager.deleteRecursive(staging);
            if (!staging.mkdirs()) {
                return fail("Cannot create staging directory");
            }
            new ZipFile(zip).extractAll(staging.getAbsolutePath());

            File found = LibManager.findLib(staging);
            if (found == null) {
                return fail("Loader ZIP has no libbgmi.so / lib.so ELF library");
            }
            if (!LibManager.installLib(context, found)) {
                return fail("Could not install the loader library");
            }
            if (!LibManager.isUsableLib(context)) {
                return fail("Installed loader library is not usable");
            }

            LibManager.prefs(context).edit()
                    .putString(LibManager.PREF_VERSION_KEY, serverVersion)
                    .putString(LibManager.PREF_LAST_NOTIFIED, serverVersion)
                    .apply();
            if (progressListener != null) progressListener.onProgress(100);
            Log.i(TAG, "Loader release " + serverVersion + " installed");
            return null; // success
        } catch (Exception e) {
            Log.e(TAG, "Error downloading or extracting loader", e);
            return fail("Loader download failed: " + e.getMessage());
        } finally {
            if (connection != null) connection.disconnect();
            LibManager.deleteRecursive(staging);
            if (zip.exists()) zip.delete();
        }
    }

    private static boolean isZip(File file) {
        InputStream in = null;
        try {
            in = new java.io.FileInputStream(file);
            byte[] magic = new byte[4];
            if (in.read(magic) != 4) return false;
            return (magic[0] & 0xFF) == 0x50 && (magic[1] & 0xFF) == 0x4B
                    && (magic[2] & 0xFF) == 0x03 && (magic[3] & 0xFF) == 0x04;
        } catch (Exception e) {
            return false;
        } finally {
            if (in != null) try {
                in.close();
            } catch (Exception ignored) {
            }
        }
    }
}
