package com.xlox.utils;

import android.content.Context;
import android.os.StatFs;
import android.util.Log;

import com.xlox.BuildConfig;
import com.xlox.R;
import com.xlox.ui.Ui;

import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.exception.ZipException;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Library update client for the Anoy panel.
 *
 * <ul>
 *   <li>{@link #check(Context)}  GET /api/lib/manifest (falls back to /api/lib/version)</li>
 *   <li>{@link #download}        GET /api/lib/download, verified and installed atomically</li>
 * </ul>
 *
 * Both methods block and must run on a background thread. Every failure is
 * reported as an {@link UpdateException} whose message can be shown directly.
 * The native {@code Version()} / {@code Link()} names are JNI entry points and
 * must not be renamed.
 */
public final class Downtwo {

    private static final String TAG = "Downtwo";

    public static native String Version();

    public static native String Link();

    private static final String FALLBACK_BASE = BuildConfig.PANEL_BASE_URL;
    private static final String USER_AGENT = BuildConfig.DISPLAY_BRAND + "/" + BuildConfig.VERSION_NAME + " (Android)";
    private static final String PART_NAME = "library-release.part";

    private static final int MAX_ATTEMPTS = 2;
    private static final long CHECK_BUDGET_MS = 20_000L;
    private static final long DOWNLOAD_BUDGET_MS = 90_000L;
    private static final long MAX_DOWNLOAD_BYTES = 64L * 1024L * 1024L;
    private static final long MIN_FREE_BYTES = 8L * 1024L * 1024L;
    private static final Pattern VERSION_PATTERN = Pattern.compile("^[A-Za-z0-9._+-]{1,32}$");
    private static final Pattern SHA_PATTERN = Pattern.compile("^[a-fA-F0-9]{64}$");

    private Downtwo() {
    }

    // ------------------------------------------------------------------ URLs

    /** Never let a missing native library break the update check. */
    public static String versionUrl() {
        try {
            String value = Version();
            if (value != null && value.startsWith("http")) return channelUrl(value);
        } catch (Throwable t) {
            Log.e(TAG, "Native Version() unavailable", t);
        }
        return channelUrl(FALLBACK_BASE + "/api/lib/version");
    }

    public static String downloadUrl() {
        try {
            String value = Link();
            if (value != null && value.startsWith("http")) return channelUrl(value);
        } catch (Throwable t) {
            Log.e(TAG, "Native Link() unavailable", t);
        }
        return channelUrl(FALLBACK_BASE + "/api/lib/download");
    }

    private static String channelUrl(String url) {
        android.net.Uri parsed = android.net.Uri.parse(url);
        android.net.Uri.Builder builder = parsed.buildUpon().clearQuery();
        for (String key : parsed.getQueryParameterNames()) {
            if ("channel".equals(key)) continue;
            for (String value : parsed.getQueryParameters(key)) builder.appendQueryParameter(key, value);
        }
        return builder.appendQueryParameter("channel", BuildConfig.RELEASE_CHANNEL).build().toString();
    }

    public static String manifestUrl() {
        String version = versionUrl();
        int suffix = version.indexOf("/api/lib/version");
        String base = suffix >= 0 ? version.substring(0, suffix) : FALLBACK_BASE;
        return channelUrl(base + "/api/lib/manifest");
    }

    // ----------------------------------------------------------------- types

    /** What the panel currently publishes. */
    public static final class Release {
        public final String version;
        public final boolean available;
        public final long size;
        public final String sha256;

        Release(String version, boolean available, long size, String sha256) {
            this.version = version;
            this.available = available;
            this.size = Math.max(0L, size);
            this.sha256 = sha256 == null ? "" : sha256;
        }
    }

    /** Result of comparing the published release with the installed one. */
    public static final class Status {
        public final Release release;
        public final String localVersion;
        public final boolean libUsable;
        public final boolean updateRequired;

        Status(Release release, String localVersion, boolean libUsable, boolean updateRequired) {
            this.release = release;
            this.localVersion = localVersion;
            this.libUsable = libUsable;
            this.updateRequired = updateRequired;
        }
    }

    public interface Progress {
        /** {@code total} is -1 when the server did not send a length. */
        void onProgress(long done, long total);

        default void onConnecting() {
        }

        default void onRetry(int attempt, int maximum) {
        }

        default void onInstalling() {
        }
    }

    // ----------------------------------------------------------------- check

    public static Status check(Context context) throws UpdateException {
        return check(context, new UpdateRequest());
    }

    public static Status check(Context context, UpdateRequest request) throws UpdateException {
        Context app = context.getApplicationContext();
        if (!Net.isOnline(app)) throw new UpdateException(app.getString(R.string.net_offline), true);
        Release release;
        long started = android.os.SystemClock.elapsedRealtime();
        try {
            request.begin(CHECK_BUDGET_MS);
            release = fetchManifestWithRetry(app, request);
            request.check();
        } catch (IOException e) {
            throw networkError(app, request, e);
        } finally {
            request.close();
            Log.i(TAG, "Library metadata request finished in "
                    + (android.os.SystemClock.elapsedRealtime() - started) + " ms");
        }
        boolean usable = LibManager.isUsableLib(app);
        String local = LibManager.localVersion(app);
        String localSha = LibManager.localSha(app);

        boolean required = release.available && (!usable
                || !release.version.equals(local)
                || (!release.sha256.isEmpty() && !localSha.isEmpty()
                && !release.sha256.equalsIgnoreCase(localSha)));
        Log.i(TAG, "check: server=" + release.version + " available=" + release.available
                + " local=" + local + " usable=" + usable + " required=" + required);
        return new Status(release, local, usable, required);
    }

    private static Release fetchManifestWithRetry(Context app, UpdateRequest request) throws UpdateException, IOException {
        UpdateException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                request.check();
                return fetchManifest(app, request);
            } catch (UpdateException e) {
                last = e;
                if (!e.retryable || attempt == MAX_ATTEMPTS || request.isCancelled()
                        || Thread.currentThread().isInterrupted()) break;
                request.pause(350L * attempt);
            }
        }
        throw last;
    }

    private static Release fetchManifest(Context app, UpdateRequest request) throws UpdateException {
        HttpURLConnection connection = null;
        try {
            connection = LibraryHttp.open(manifestUrl(), "application/json", USER_AGENT, true, request);
            int code = connection.getResponseCode();
            if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                // Older panel without the manifest endpoint.
                request.detach(connection);
                connection.disconnect();
                connection = null;
                return fetchLegacyVersion(app, request);
            }
            if (code >= 500) throw new UpdateException(app.getString(R.string.update_unavailable), true);
            if (code != HttpURLConnection.HTTP_OK) {
                throw new UpdateException(app.getString(R.string.net_http, code), false);
            }
            JSONObject json = new JSONObject(readBody(connection, 64 * 1024, request));
            if (!json.optBoolean("ok", false)) {
                throw new UpdateException(app.getString(R.string.update_unavailable), true);
            }
            String version = json.optString("version", "").trim();
            if (!VERSION_PATTERN.matcher(version).matches()) {
                throw new UpdateException(app.getString(R.string.error_bad_response), true);
            }
            String sha = json.optString("sha256", "").trim();
            if (!SHA_PATTERN.matcher(sha).matches()) sha = "";
            GameSupport.remember(app, json.optString("game_version", ""));
            return new Release(version, json.optBoolean("available", false),
                    json.optLong("size", 0L), sha.toLowerCase(Locale.US));
        } catch (JSONException e) {
            Log.w(TAG, "Manifest is not JSON", e);
            throw new UpdateException(app.getString(R.string.error_bad_response), true, e);
        } catch (IOException e) {
            Log.w(TAG, "Manifest request failed", e);
            throw networkError(app, request, e);
        } finally {
            if (connection != null) {
                request.detach(connection);
                connection.disconnect();
            }
        }
    }

    private static Release fetchLegacyVersion(Context app, UpdateRequest request) throws UpdateException {
        HttpURLConnection connection = null;
        try {
            connection = LibraryHttp.open(versionUrl(), "text/plain", USER_AGENT, true, request);
            int code = connection.getResponseCode();
            if (code >= 500) throw new UpdateException(app.getString(R.string.update_unavailable), true);
            if (code != HttpURLConnection.HTTP_OK) {
                throw new UpdateException(app.getString(R.string.net_http, code), false);
            }
            String body = readBody(connection, 1024, request).trim();
            String version = body.isEmpty() ? "" : body.split("[\\r\\n]")[0].trim();
            if (!VERSION_PATTERN.matcher(version).matches()) {
                throw new UpdateException(app.getString(R.string.error_bad_response), true);
            }
            return new Release(version, true, 0L, "");
        } catch (IOException e) {
            throw networkError(app, request, e);
        } finally {
            if (connection != null) {
                request.detach(connection);
                connection.disconnect();
            }
        }
    }

    // -------------------------------------------------------------- download

    public static void download(Context context, Release release, Progress progress,
                                UpdateRequest request) throws UpdateException {
        Context app = context.getApplicationContext();
        if (!Net.isOnline(app)) throw new UpdateException(app.getString(R.string.net_offline), true);
        ensureSpace(app, release.size);
        try {
            request.begin(DOWNLOAD_BUDGET_MS);
            UpdateException last = null;
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                request.check();
                try {
                    downloadOnce(app, release, progress, request);
                    return;
                } catch (UpdateException e) {
                    last = e;
                    // Neither signed URLs nor response bodies/exception text
                    // belong in diagnostics. Some providers embed credentials.
                    Log.w(TAG, "Library download attempt " + attempt + " failed");
                    if (!e.retryable || attempt == MAX_ATTEMPTS || request.isCancelled()
                            || Thread.currentThread().isInterrupted()) break;
                    if (progress != null) progress.onRetry(attempt + 1, MAX_ATTEMPTS);
                    request.pause(700L * attempt);
                }
            }
            throw last;
        } catch (IOException e) {
            throw networkError(app, request, e);
        } finally {
            request.close();
        }
    }

    private static void downloadOnce(Context app, Release release, Progress progress,
                                     UpdateRequest request) throws UpdateException {
        File part = new File(app.getFilesDir(), PART_NAME);
        File staging = LibManager.stagingDir(app);
        HttpURLConnection connection = null;
        try {
            if (progress != null) progress.onConnecting();
            long connectedAt = android.os.SystemClock.elapsedRealtime();
            // Only a pinned manifest checksum makes cached bytes safe. Legacy
            // link-only releases without a checksum retain their fresh fetch.
            connection = LibraryHttp.open(downloadUrl(), "application/zip, application/octet-stream",
                    USER_AGENT, release.sha256.isEmpty(), request);
            Log.i(TAG, "Library response headers received after "
                    + (android.os.SystemClock.elapsedRealtime() - connectedAt) + " ms");
            int code = connection.getResponseCode();
            if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                throw new UpdateException(app.getString(R.string.update_no_release), false);
            }
            if (code >= 500) throw new UpdateException(app.getString(R.string.net_http, code), true);
            if (code != HttpURLConnection.HTTP_OK) {
                throw new UpdateException(app.getString(R.string.net_http, code), false);
            }

            long headerLength = parseLong(connection.getHeaderField("Content-Length"));
            long total = headerLength > 0 ? headerLength : (release.size > 0 ? release.size : -1L);
            if (total > MAX_DOWNLOAD_BYTES) {
                throw new UpdateException(app.getString(R.string.update_invalid_file), false);
            }
            String headerSha = connection.getHeaderField("X-Content-SHA256");

            MessageDigest digest = sha256();
            long done = 0L;
            if (progress != null) progress.onProgress(0L, total);
            try (InputStream in = new BufferedInputStream(connection.getInputStream());
                 FileOutputStream out = new FileOutputStream(part, false)) {
                byte[] buffer = new byte[32 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    request.check();
                    out.write(buffer, 0, read);
                    digest.update(buffer, 0, read);
                    done += read;
                    if (done > MAX_DOWNLOAD_BYTES) {
                        throw new UpdateException(app.getString(R.string.update_invalid_file), false);
                    }
                    // The UI throttles drawing, but every received chunk
                    // counts as movement, including less than one percent.
                    if (progress != null) progress.onProgress(done, total);
                }
                request.check();
                out.flush();
                try {
                    out.getFD().sync();
                } catch (IOException ignored) {
                    // Best effort; the size + hash checks below still apply.
                }
            }

            if (headerLength > 0 && done != headerLength) {
                throw new UpdateException(app.getString(R.string.net_interrupted), true);
            }
            if (done < 64) throw new UpdateException(app.getString(R.string.update_invalid_file), false);

            String actualSha = hex(digest.digest());
            String expectedSha = !release.sha256.isEmpty() ? release.sha256
                    : (headerSha != null && SHA_PATTERN.matcher(headerSha.trim()).matches()
                    ? headerSha.trim() : "");
            if (!expectedSha.isEmpty() && !expectedSha.equalsIgnoreCase(actualSha)) {
                // The current release may have changed since the manifest.
                // Repeating this same stale manifest downloads the wrong file
                // again. Let the retry action fetch fresh metadata instead.
                Log.w(TAG, "Library checksum did not match the announced release");
                throw new UpdateException(app.getString(R.string.update_corrupt), false);
            }

            request.check();
            if (progress != null) progress.onInstalling();
            long installingAt = android.os.SystemClock.elapsedRealtime();
            install(app, part, staging);
            Log.i(TAG, "Library verification/install finished in "
                    + (android.os.SystemClock.elapsedRealtime() - installingAt) + " ms");

            String version = release.version;
            String headerVersion = connection.getHeaderField("X-Release-Version");
            if (headerVersion != null && VERSION_PATTERN.matcher(headerVersion.trim()).matches()) {
                version = headerVersion.trim();
            }
            LibManager.saveInstalled(app, version, actualSha);
            Log.i(TAG, "Library " + version + " installed (" + done + " bytes, sha256 " + actualSha + ")");
        } catch (IOException e) {
            throw networkError(app, request, e);
        } finally {
            if (connection != null) {
                request.detach(connection);
                connection.disconnect();
            }
            if (part.exists() && !part.delete()) Log.w(TAG, "Could not delete " + part);
            LibManager.deleteRecursive(staging);
        }
    }

    /** Extracts a ZIP (or accepts a raw ELF) and installs the library atomically. */
    private static void install(Context app, File file, File staging) throws UpdateException {
        LibManager.deleteRecursive(staging);
        if (!staging.mkdirs() && !staging.isDirectory()) {
            throw new UpdateException(app.getString(R.string.update_install_failed), false);
        }
        File found;
        byte[] magic = readMagic(file);
        if (magic[0] == 0x50 && magic[1] == 0x4B && magic[2] == 0x03 && magic[3] == 0x04) {
            try {
                new ZipFile(file).extractAll(staging.getAbsolutePath());
            } catch (ZipException e) {
                Log.w(TAG, "Release ZIP could not be extracted", e);
                if (Net.isNoSpace(e)) {
                    throw new UpdateException(app.getString(R.string.update_install_failed), false, e);
                }
                throw new UpdateException(app.getString(R.string.update_invalid_file), false, e);
            }
            found = LibManager.findLib(staging);
        } else if (magic[0] == 0x7F && magic[1] == 'E' && magic[2] == 'L' && magic[3] == 'F') {
            File raw = new File(staging, LibManager.LIB_NAME);
            if (!file.renameTo(raw)) {
                throw new UpdateException(app.getString(R.string.update_install_failed), false);
            }
            found = raw;
        } else {
            throw new UpdateException(app.getString(R.string.update_invalid_file), false);
        }
        if (found == null) throw new UpdateException(app.getString(R.string.update_no_library), false);
        if (!LibManager.installLib(app, found) || !LibManager.isUsableLib(app)) {
            throw new UpdateException(app.getString(R.string.update_install_failed), false);
        }
    }

    // --------------------------------------------------------------- helpers

    private static UpdateException networkError(Context app, UpdateRequest request, IOException error) {
        try {
            request.check();
        } catch (IOException stop) {
            error = stop;
        }
        return new UpdateException(Net.friendly(app, error),
                !request.isCancelled() && !Thread.currentThread().isInterrupted() && Net.isRetryable(error), error);
    }

    private static String readBody(HttpURLConnection connection, int limit, UpdateRequest request) throws IOException {
        try (InputStream in = connection.getInputStream();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                request.check();
                out.write(buffer, 0, read);
                if (out.size() > limit) throw new java.net.ProtocolException("Response too large");
            }
            return out.toString("UTF-8");
        }
    }

    private static void ensureSpace(Context app, long size) throws UpdateException {
        try {
            StatFs stat = new StatFs(app.getFilesDir().getAbsolutePath());
            long free = stat.getAvailableBytes();
            long need = Math.max(MIN_FREE_BYTES, size * 3L);
            if (free < need) {
                throw new UpdateException(app.getString(R.string.update_no_space, Ui.bytes(need)), false);
            }
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "StatFs unavailable", e);
        }
    }

    private static byte[] readMagic(File file) {
        byte[] magic = new byte[4];
        try (InputStream in = new FileInputStream(file)) {
            int read = in.read(magic);
            if (read != 4) return new byte[4];
        } catch (IOException e) {
            return new byte[4];
        }
        return magic;
    }

    private static MessageDigest sha256() throws UpdateException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new UpdateException("SHA-256 unavailable", false, e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format(Locale.US, "%02x", b & 0xFF));
        return sb.toString();
    }

    private static long parseLong(String value) {
        if (value == null) return -1L;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

}
