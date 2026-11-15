package com.xlox.utils;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * The three permissions Anoy asks for, in one place for sign in (LogAct) and
 * home (MAct) so the two never drift apart. They are what the game-files (OBB)
 * copy and launch need on current phones:
 *
 * <ol>
 *   <li>{@link Need#ALL_FILES} - "All files access" (MANAGE_EXTERNAL_STORAGE,
 *       Android 11+; opened as its system settings page).</li>
 *   <li>{@link Need#FILES} - "Files" / "Files and media", the runtime storage
 *       permission (READ / WRITE_EXTERNAL_STORAGE; on Android 13+ the
 *       READ_MEDIA_* permissions are asked in the same dialog). All files
 *       access is not a substitute for it.</li>
 *   <li>{@link Need#INSTALL} - "Install unknown apps" (REQUEST_INSTALL_PACKAGES,
 *       Android 8+; opened as its system settings page).</li>
 * </ol>
 *
 * Nothing else is ever requested here: microphone, nearby devices, appear on
 * top, picture-in-picture, location and notifications are only declared in
 * the manifest for the game / later features.
 */
public final class StorageAccess {

    private static final String TAG = "StorageAccess";

    /** Request code for every settings page and the runtime dialog. */
    public static final int REQUEST_CODE = 100;

    /** Order matters: requested one after another in this order. */
    public enum Need { ALL_FILES, FILES, INSTALL }

    private static final String PREFS = "anoy_permissions";
    private static final String KEY_FILES_ASKED = "files_asked";
    private static final String KEY_LAUNCH_AFTER_RESTART = "launch_after_restart_at";
    /** A Launch interrupted by the restart is continued only if home opens within this time. */
    private static final long LAUNCH_AFTER_RESTART_MS = 10L * 60L * 1000L;

    /** Install unknown apps was seen missing in this process (so a grant came during it). */
    private static volatile boolean installMissingThisRun;

    /** Needs whose prompt has been shown in this process (bit per ordinal). */
    private static volatile int askedThisSession;

    private StorageAccess() {
    }

    // ---------------------------------------------------------------- checks

    /** True when all three are granted. */
    public static boolean has(Context context) {
        return firstMissing(context) == null;
    }

    public static boolean has(Context context, Need need) {
        switch (need) {
            case ALL_FILES:
                // The permission only exists on Android 11+; below it the Files
                // runtime permission gives the same access.
                return Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager();
            case FILES:
                return granted(context, Manifest.permission.READ_EXTERNAL_STORAGE)
                        && granted(context, Manifest.permission.WRITE_EXTERNAL_STORAGE);
            case INSTALL:
                // Per-app "Install unknown apps" exists from Android 8; before
                // that it is a single device-wide switch, not an app permission.
                boolean install = Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                        || context.getPackageManager().canRequestPackageInstalls();
                if (!install) installMissingThisRun = true;
                return install;
            default:
                return true;
        }
    }

    /** The first missing permission in request order, or null when all are granted. */
    @Nullable
    public static Need firstMissing(Context context) {
        for (Need need : Need.values()) {
            if (!has(context, need)) return need;
        }
        return null;
    }

    /** True once this need's prompt has been shown since the app started. */
    public static boolean askedThisSession(Need need) {
        return (askedThisSession & (1 << need.ordinal())) != 0;
    }

    /** "Not now" on the explanation sheet counts as asked: home won't ask again this run. */
    public static void markAsked(Need need) {
        askedThisSession |= 1 << need.ordinal();
    }

    /** True once any prompt has been shown since the app started. */
    public static boolean askedThisSession() {
        return askedThisSession != 0;
    }

    /**
     * True when Install unknown apps was granted while this process was
     * running. On Android 11+ the game-files folder only becomes readable for
     * a process started after that grant, so the app has to restart once.
     */
    public static boolean installGrantedThisRun(Context context) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                && installMissingThisRun && has(context, Need.INSTALL);
    }

    /** Remember that Launch should continue once home opens after the restart. */
    public static void markLaunchAfterRestart(Context context) {
        prefs(context).edit().putLong(KEY_LAUNCH_AFTER_RESTART, System.currentTimeMillis()).commit();
    }

    /** True (once) when a Launch interrupted by the restart should continue now. */
    public static boolean consumeLaunchAfterRestart(Context context) {
        SharedPreferences prefs = prefs(context);
        long at = prefs.getLong(KEY_LAUNCH_AFTER_RESTART, 0L);
        if (at == 0L) return false;
        prefs.edit().remove(KEY_LAUNCH_AFTER_RESTART).apply();
        long age = System.currentTimeMillis() - at;
        return age >= 0 && age < LAUNCH_AFTER_RESTART_MS;
    }

    // -------------------------------------------------------------- requests

    /**
     * Opens the prompt for the first missing permission. The answer arrives in
     * the activity's onActivityResult (settings pages) or
     * onRequestPermissionsResult (runtime dialog) with {@link #REQUEST_CODE}.
     *
     * @return the need that was prompted, or null if nothing is missing or no
     *         prompt could be opened on this phone
     */
    @Nullable
    public static Need request(Activity activity) {
        Need need = firstMissing(activity);
        if (need == null) return null;
        return request(activity, need) ? need : null;
    }

    /** Opens the prompt for {@code need}. Returns true if a prompt was shown. */
    public static boolean request(Activity activity, Need need) {
        askedThisSession |= 1 << need.ordinal();
        switch (need) {
            case ALL_FILES:
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false;
                return open(activity, new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri(activity)))
                        || open(activity, new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            case FILES:
                return requestFiles(activity);
            case INSTALL:
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false;
                return open(activity, new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, packageUri(activity)))
                        || open(activity, new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES));
            default:
                return false;
        }
    }

    /**
     * The runtime Files dialog. Once the user has refused it for good ("Don't
     * ask again", or refused twice on Android 11+) the dialog no longer
     * appears, so Anoy's own App info page is opened instead of leaving the
     * user to find it.
     */
    private static boolean requestFiles(Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false; // Granted at install.
        SharedPreferences prefs = prefs(activity);
        boolean askedBefore = prefs.getBoolean(KEY_FILES_ASKED, false);
        boolean canShowDialog = !askedBefore
                || ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.READ_EXTERNAL_STORAGE)
                || ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE);
        if (!canShowDialog) {
            Log.d(TAG, "Files permission refused for good: opening App info");
            if (open(activity, new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(activity)))) {
                return true;
            }
        }
        prefs.edit().putBoolean(KEY_FILES_ASKED, true).apply();
        ActivityCompat.requestPermissions(activity, filesPermissions(), REQUEST_CODE);
        return true;
    }

    /** READ / WRITE external storage, plus the Android 13+ media permissions. */
    private static String[] filesPermissions() {
        List<String> list = new ArrayList<>(5);
        list.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        list.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        if (Build.VERSION.SDK_INT >= 33) {
            list.add("android.permission.READ_MEDIA_IMAGES");
            list.add("android.permission.READ_MEDIA_VIDEO");
            list.add("android.permission.READ_MEDIA_AUDIO");
        }
        return list.toArray(new String[0]);
    }

    // --------------------------------------------------------------- helpers

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static boolean granted(Context context, String permission) {
        return ActivityCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED;
    }

    private static Uri packageUri(Context context) {
        return Uri.fromParts("package", context.getPackageName(), null);
    }

    private static boolean open(Activity activity, Intent intent) {
        try {
            activity.startActivityForResult(intent, REQUEST_CODE);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(TAG, "Settings page unavailable: " + intent.getAction(), e);
            return false;
        }
    }
}
