package com.xlox.utils;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.os.StatFs;
import android.util.Log;

import androidx.core.content.pm.PackageInfoCompat;

import com.xlox.BuildConfig;
import com.xlox.R;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * In-app update of the Anoy app itself (the APK). New code that sits next to
 * the library updater ({@link Downtwo} / {@link LibManager}) and shares
 * nothing with it except the panel address:
 * <ol>
 *   <li>{@link #check} asks the panel's {@code GET /api/app/update} which app
 *       release is current and compares its versionCode with this build.</li>
 *   <li>{@link #download} fetches the APK over HTTPS (every redirect hop too)
 *       into the app's private cache, with progress, a free-space check and a
 *       quick validity check (ZIP header, package name, version code).</li>
 *   <li>{@link #install} streams it into a {@link PackageInstaller} session.
 *       Android shows its own confirmation; no browser, no file:// URI, no
 *       FileProvider. Android itself refuses an APK signed with another key.</li>
 * </ol>
 * Every method that touches the network or disk blocks: background threads
 * only. Failures are {@link UpdateException}s with a message to show as-is.
 */
public final class AppUpdater {

    private static final String TAG = "AppUpdater";
    private static final String FALLBACK_BASE = "https://anoymundo-panel.vercel.app";
    private static final String LIB_VERSION_PATH = "/api/lib/version";
    private static final String CHECK_PATH = "/api/app/update";
    private static final String USER_AGENT = "Anoy/" + BuildConfig.VERSION_NAME + " (Android)";
    /** Broadcast the PackageInstaller session reports its status to. */
    public static final String ACTION_INSTALL_STATUS = BuildConfig.APPLICATION_ID + ".APP_INSTALL_STATUS";

    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    /** 2.3.3: the splash check is one quick attempt; Settings keeps the patient one above. */
    private static final int SPLASH_CONNECT_TIMEOUT_MS = 8_000;
    private static final int SPLASH_READ_TIMEOUT_MS = 10_000;
    /** A successful "no newer version" lets the splash skip the network for this long. */
    static final long UP_TO_DATE_CACHE_MS = 10L * 60L * 1000L;
    /** Prefs keys (UTC epoch milliseconds). Only successful checks are ever written. */
    private static final String PREF_LAST_OK = "app_update_last_ok";
    private static final String PREF_NONE_AT = "app_update_none_at";
    private static final String PREF_NONE_CODE = "app_update_none_code";
    private static final int MAX_REDIRECTS = 6;
    private static final int MAX_ATTEMPTS = 3;
    private static final long MAX_APK_BYTES = 200L * 1024L * 1024L;
    private static final long MIN_APK_BYTES = 64L * 1024L;
    /** Head-room kept free on top of the APK itself. */
    private static final long SPACE_MARGIN_BYTES = 16L * 1024L * 1024L;
    private static final String DIR = "app-update";

    private AppUpdater() {
    }

    /** One published app release, as the panel describes it. */
    public static final class Release {
        public final String versionName;
        public final int versionCode;
        public final String apkUrl;
        public final String changelog;
        public final boolean mandatory;

        public Release(String versionName, int versionCode, String apkUrl, String changelog, boolean mandatory) {
            this.versionName = versionName;
            this.versionCode = versionCode;
            this.apkUrl = apkUrl;
            this.changelog = changelog == null ? "" : changelog;
            this.mandatory = mandatory;
        }

        /** True when this release is newer than the running build (compared by versionCode). */
        public boolean isNewer() {
            return versionCode > BuildConfig.VERSION_CODE;
        }

        public void writeTo(Intent intent) {
            intent.putExtra(EXTRA_NAME, versionName);
            intent.putExtra(EXTRA_CODE, versionCode);
            intent.putExtra(EXTRA_URL, apkUrl);
            intent.putExtra(EXTRA_CHANGELOG, changelog);
            intent.putExtra(EXTRA_MANDATORY, mandatory);
        }

        /** Null when the intent carries no (valid) release. */
        public static Release from(Intent intent) {
            if (intent == null) return null;
            String url = intent.getStringExtra(EXTRA_URL);
            int code = intent.getIntExtra(EXTRA_CODE, 0);
            if (url == null || code <= 0) return null;
            return new Release(intent.getStringExtra(EXTRA_NAME), code, url,
                    intent.getStringExtra(EXTRA_CHANGELOG), intent.getBooleanExtra(EXTRA_MANDATORY, false));
        }
    }

    private static final String EXTRA_NAME = "app_update_name";
    private static final String EXTRA_CODE = "app_update_code";
    private static final String EXTRA_URL = "app_update_url";
    private static final String EXTRA_CHANGELOG = "app_update_changelog";
    private static final String EXTRA_MANDATORY = "app_update_mandatory";

    public interface Progress {
        /** total is -1 when the server sends no length. */
        void onProgress(long done, long total);
    }

    // ------------------------------------------------------------------ check

    /** The panel this build talks to, from the same native config the library check uses. */
    static String panelBase() {
        String url = Downtwo.versionUrl();
        if (url != null && url.startsWith("https://") && url.endsWith(LIB_VERSION_PATH)) {
            return url.substring(0, url.length() - LIB_VERSION_PATH.length());
        }
        return FALLBACK_BASE;
    }

    public static String checkUrl() {
        return panelBase() + CHECK_PATH + "?code=" + BuildConfig.VERSION_CODE;
    }

    /**
     * Asks the panel for the current app release. Returns it when its
     * versionCode is higher than this build's, null when the app is up to date
     * (or nothing is published). Any failure throws: the caller must never
     * treat "couldn't check" as "up to date". Used by Settings: two attempts,
     * 15 s connect / 30 s read.
     */
    public static Release check(Context context) throws UpdateException {
        return check(context, 2, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS);
    }

    /** The splash's check: one attempt, 8 s connect / 10 s read, same rules otherwise. */
    public static Release checkForSplash(Context context) throws UpdateException {
        return check(context, 1, SPLASH_CONNECT_TIMEOUT_MS, SPLASH_READ_TIMEOUT_MS);
    }

    /**
     * True when a successful check for this build found no newer version less
     * than 10 minutes ago. Only "up to date" is remembered: a found update is
     * never cached away and a failed check never counts.
     */
    public static boolean recentlyUpToDate(Context context) {
        Prefs prefs = new Prefs(context);
        long at = prefs.getLong(PREF_NONE_AT, 0L);
        if (at <= 0L || prefs.getLong(PREF_NONE_CODE, 0L) != BuildConfig.VERSION_CODE) return false;
        long age = System.currentTimeMillis() - at;
        // A clock set backwards makes the age negative: check again.
        return age >= 0L && age < UP_TO_DATE_CACHE_MS;
    }

    /** UTC epoch milliseconds of the last successful check (splash or Settings), 0 if none yet. */
    public static long lastCheckedAt(Context context) {
        return new Prefs(context).getLong(PREF_LAST_OK, 0L);
    }

    private static void remember(Context app, Release found) {
        Prefs prefs = new Prefs(app);
        long now = System.currentTimeMillis();
        prefs.setLong(PREF_LAST_OK, now);
        if (found == null) {
            prefs.setLong(PREF_NONE_AT, now);
            prefs.setLong(PREF_NONE_CODE, BuildConfig.VERSION_CODE);
        } else {
            prefs.remove(PREF_NONE_AT);
            prefs.remove(PREF_NONE_CODE);
        }
    }

    private static Release check(Context context, int attempts, int connectMs, int readMs) throws UpdateException {
        Context app = context.getApplicationContext();
        if (!Net.isOnline(app)) throw new UpdateException(app.getString(R.string.net_offline), true);
        IOException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            HttpURLConnection connection = null;
            try {
                // No "Cache-Control: no-cache" here: the panel answers from its
                // CDN for up to a minute, which is what keeps this fast.
                connection = open(checkUrl(), "application/json", connectMs, readMs, false);
                int code = connection.getResponseCode();
                if (code != 200) {
                    Log.w(TAG, "Update check answered HTTP " + code);
                    throw new UpdateException(code >= 500
                            ? app.getString(R.string.update_unavailable)
                            : app.getString(R.string.net_http, code), true);
                }
                Release found = parse(app, readBody(connection, 32 * 1024));
                remember(app, found);
                return found;
            } catch (IOException e) {
                last = e;
                Log.w(TAG, "Update check attempt " + attempt + " failed: " + e);
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
        throw new UpdateException(Net.friendly(app, last), true, last);
    }

    static Release parse(Context app, String body) throws UpdateException {
        try {
            JSONObject json = new JSONObject(body);
            if (!json.optBoolean("ok", false)) throw new UpdateException(app.getString(R.string.update_unavailable), true);
            if (!json.optBoolean("available", false)) return null;
            int code = json.optInt("version_code", 0);
            // 2.3.2: not newer means up to date, whatever else the row holds, so
            // an old or odd published row can never hold up the splash.
            if (code > 0 && code <= BuildConfig.VERSION_CODE) return null;
            String url = json.optString("apk_url", "");
            String name = json.optString("version_name", "");
            if (code <= 0 || !url.toLowerCase(Locale.US).startsWith("https://") || name.isEmpty()) {
                Log.w(TAG, "Update check: malformed release");
                throw new UpdateException(app.getString(R.string.app_update_bad_release), true);
            }
            Release release = new Release(name, code, url, json.optString("changelog", ""),
                    json.optBoolean("mandatory", false));
            return release.isNewer() ? release : null;
        } catch (JSONException e) {
            throw new UpdateException(app.getString(R.string.update_unavailable), true, e);
        }
    }

    // --------------------------------------------------------------- download

    /** The APK for this release in the private cache (may not exist yet). */
    public static File apkFile(Context context, Release release) {
        return new File(new File(context.getCacheDir(), DIR), "anoy-" + release.versionCode + ".apk");
    }

    /**
     * Downloads the APK (or reuses an already verified copy) and returns it.
     * {@code cancel} stops the transfer between reads.
     */
    public static File download(Context context, Release release, Progress progress, AtomicBoolean cancel)
            throws UpdateException {
        Context app = context.getApplicationContext();
        File dir = new File(app.getCacheDir(), DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new UpdateException(app.getString(R.string.app_update_storage), false);
        }
        File target = apkFile(app, release);
        // Leftovers from older releases only waste space.
        File[] old = dir.listFiles();
        if (old != null) {
            for (File f : old) {
                if (!f.equals(target) && !f.delete()) Log.w(TAG, "Could not delete " + f.getName());
            }
        }
        if (target.isFile()) {
            try {
                verify(app, target, release);
                Log.i(TAG, "Reusing the downloaded APK " + target.getName());
                if (progress != null) progress.onProgress(target.length(), target.length());
                return target;
            } catch (UpdateException e) {
                if (!target.delete()) Log.w(TAG, "Could not delete a stale APK");
            }
        }
        if (!Net.isOnline(app)) throw new UpdateException(app.getString(R.string.net_offline), true);

        UpdateException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                downloadOnce(app, release, target, progress, cancel);
                verify(app, target, release);
                return target;
            } catch (UpdateException e) {
                last = e;
                if (cancel != null && cancel.get()) throw e;
                if (!e.retryable || attempt == MAX_ATTEMPTS) break;
                Log.w(TAG, "APK download attempt " + attempt + " failed: " + e.getMessage());
                try {
                    Thread.sleep(800L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw last;
    }

    private static void downloadOnce(Context app, Release release, File target, Progress progress,
                                     AtomicBoolean cancel) throws UpdateException {
        File part = new File(target.getPath() + ".part");
        HttpURLConnection connection = null;
        try {
            connection = open(release.apkUrl, "application/vnd.android.package-archive, application/octet-stream, */*");
            int code = connection.getResponseCode();
            if (code != 200) {
                Log.w(TAG, "APK download answered HTTP " + code);
                // 2.3.2: "Couldn't download the update (HTTP 504). Try again later."
                // Server-side trouble (5xx, 408, 429) is retried; 4xx is final.
                throw new UpdateException(app.getString(R.string.app_update_download_http, code),
                        code >= 500 || code == 408 || code == 429);
            }
            String type = connection.getContentType();
            if (type != null && type.toLowerCase(Locale.US).startsWith("text/html")) {
                // A web page (release page, Drive, Telegram...) instead of the file.
                Log.w(TAG, "APK link returned a web page, not an APK");
                throw new UpdateException(app.getString(R.string.app_update_mismatch), false);
            }
            long total = connection.getContentLength();
            if (total > MAX_APK_BYTES) throw new UpdateException(app.getString(R.string.app_update_invalid), false);
            long needed = (total > 0 ? total : 64L * 1024L * 1024L) + SPACE_MARGIN_BYTES;
            long free = freeBytes(target.getParentFile());
            if (free >= 0 && free < needed) {
                throw new UpdateException(app.getString(R.string.app_update_no_space,
                        android.text.format.Formatter.formatShortFileSize(app, needed)), false);
            }
            long done = 0;
            long lastReported = -1;
            try (InputStream in = connection.getInputStream();
                 FileOutputStream out = new FileOutputStream(part)) {
                byte[] buffer = new byte[32 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    if (cancel != null && cancel.get()) {
                        throw new UpdateException(app.getString(R.string.app_update_cancelled), true);
                    }
                    out.write(buffer, 0, read);
                    done += read;
                    if (done > MAX_APK_BYTES) throw new UpdateException(app.getString(R.string.app_update_invalid), false);
                    if (progress != null && (done - lastReported >= 64 * 1024 || done == total)) {
                        lastReported = done;
                        progress.onProgress(done, total);
                    }
                }
                out.flush();
                try {
                    out.getFD().sync();
                } catch (IOException ignored) {
                    // Best effort; the size and package checks still apply.
                }
            }
            if (total > 0 && done != total) throw new UpdateException(app.getString(R.string.net_interrupted), true);
            if (target.exists() && !target.delete()) Log.w(TAG, "Could not replace the old APK");
            if (!part.renameTo(target)) throw new UpdateException(app.getString(R.string.app_update_storage), false);
            Log.i(TAG, "APK " + release.versionName + " (" + release.versionCode + ") downloaded, " + done + " bytes");
        } catch (IOException e) {
            if (Net.isNoSpace(e)) {
                throw new UpdateException(app.getString(R.string.app_update_storage), false, e);
            }
            throw new UpdateException(Net.friendly(app, e), Net.isRetryable(e), e);
        } finally {
            if (connection != null) connection.disconnect();
            if (part.exists() && !part.delete()) Log.w(TAG, "Could not delete the partial APK");
        }
    }

    /**
     * Cheap checks before Android's own: a ZIP file that parses as an app with
     * our package name and the announced version code. The signature is left
     * to the installer, which refuses any other key.
     */
    static void verify(Context app, File file, Release release) throws UpdateException {
        // 2.3.2: anything that isn't this app at a higher, announced version code
        // is refused before PackageInstaller with one message.
        if (file.length() < MIN_APK_BYTES || !isZip(file)) {
            Log.w(TAG, "Downloaded file is not an APK");
            throw new UpdateException(app.getString(R.string.app_update_mismatch), false);
        }
        PackageInfo info = null;
        try {
            info = app.getPackageManager().getPackageArchiveInfo(file.getPath(), 0);
        } catch (RuntimeException e) {
            Log.w(TAG, "APK could not be parsed", e);
        }
        if (info == null) throw new UpdateException(app.getString(R.string.app_update_mismatch), false);
        if (!app.getPackageName().equals(info.packageName)) {
            Log.w(TAG, "APK is for another package");
            throw new UpdateException(app.getString(R.string.app_update_mismatch), false);
        }
        long code = PackageInfoCompat.getLongVersionCode(info);
        if (code != release.versionCode) {
            Log.w(TAG, "APK version code " + code + " does not match the release " + release.versionCode);
            throw new UpdateException(app.getString(R.string.app_update_mismatch), false);
        }
        if (code <= BuildConfig.VERSION_CODE) {
            Log.w(TAG, "APK version code " + code + " is not higher than the installed " + BuildConfig.VERSION_CODE);
            throw new UpdateException(app.getString(R.string.app_update_mismatch), false);
        }
    }

    private static boolean isZip(File file) {
        byte[] magic = new byte[4];
        try (InputStream in = new FileInputStream(file)) {
            if (in.read(magic) != 4) return false;
        } catch (IOException e) {
            return false;
        }
        return magic[0] == 0x50 && magic[1] == 0x4B && magic[2] == 0x03 && magic[3] == 0x04;
    }

    private static long freeBytes(File dir) {
        try {
            StatFs stat = new StatFs(dir.getPath());
            return stat.getAvailableBytes();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    // ---------------------------------------------------------------- install

    /**
     * Streams the APK into a PackageInstaller session and commits it. The
     * result arrives as an {@link #ACTION_INSTALL_STATUS} broadcast (first
     * STATUS_PENDING_USER_ACTION with the system confirmation to start).
     * Returns the session id.
     */
    public static int install(Context context, File apk) throws UpdateException {
        Context app = context.getApplicationContext();
        PackageInstaller installer = app.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(app.getPackageName());
        params.setSize(apk.length());
        int sessionId = -1;
        PackageInstaller.Session session = null;
        try {
            sessionId = installer.createSession(params);
            session = installer.openSession(sessionId);
            try (InputStream in = new FileInputStream(apk);
                 OutputStream out = session.openWrite("anoy.apk", 0, apk.length())) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                session.fsync(out);
            }
            Intent status = new Intent(ACTION_INSTALL_STATUS).setPackage(app.getPackageName());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            // The installer fills in the status extras, so the intent must stay mutable.
            if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
            PendingIntent pending = PendingIntent.getBroadcast(app, sessionId, status, flags);
            session.commit(pending.getIntentSender());
            Log.i(TAG, "Install session " + sessionId + " committed");
            return sessionId;
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Install session failed", e);
            if (session != null) {
                try {
                    session.abandon();
                } catch (RuntimeException ignored) {
                    // Already gone.
                }
            }
            boolean space = e instanceof IOException && Net.isNoSpace(e);
            throw new UpdateException(app.getString(space ? R.string.app_update_storage
                    : R.string.app_update_install_failed), true, e);
        } finally {
            if (session != null) session.close();
        }
    }

    /** User-facing text for a PackageInstaller failure status. */
    public static String statusMessage(Context context, int status, String detail) {
        switch (status) {
            case PackageInstaller.STATUS_FAILURE_ABORTED:
                return context.getString(R.string.app_update_install_cancelled);
            case PackageInstaller.STATUS_FAILURE_STORAGE:
                return context.getString(R.string.app_update_storage);
            case PackageInstaller.STATUS_FAILURE_INVALID:
                return context.getString(R.string.app_update_invalid);
            case PackageInstaller.STATUS_FAILURE_CONFLICT:
            case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE:
                return context.getString(R.string.app_update_signature);
            case PackageInstaller.STATUS_FAILURE_BLOCKED:
                return context.getString(R.string.app_update_blocked);
            default:
                Log.w(TAG, "Install failed: status " + status + " " + detail);
                return context.getString(R.string.app_update_install_failed);
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Opens a connection, following redirects by hand: every hop must stay on https. */
    private static HttpURLConnection open(String start, String accept) throws IOException {
        return open(start, accept, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS, true);
    }

    private static HttpURLConnection open(String start, String accept, int connectMs, int readMs,
                                          boolean noCache) throws IOException {
        String current = start;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            URL url = new URL(current);
            if (!"https".equalsIgnoreCase(url.getProtocol())) {
                throw new java.net.ProtocolException("Refusing a non-https address");
            }
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(connectMs);
            connection.setReadTimeout(readMs);
            connection.setUseCaches(false);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Accept", accept);
            connection.setRequestProperty("Accept-Encoding", "identity");
            if (noCache) connection.setRequestProperty("Cache-Control", "no-cache");
            int code = connection.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null || location.isEmpty()) throw new java.net.ProtocolException("Redirect without location");
                current = new URL(url, location).toString();
                continue;
            }
            return connection;
        }
        throw new java.net.ProtocolException("Too many redirects");
    }

    private static String readBody(HttpURLConnection connection, int limit) throws IOException {
        try (InputStream in = connection.getInputStream();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                if (out.size() > limit) throw new java.net.ProtocolException("Response too large");
            }
            return out.toString("UTF-8");
        }
    }
}
