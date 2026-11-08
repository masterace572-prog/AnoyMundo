package com.xlox.utils;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;

import androidx.core.app.ActivityCompat;

/**
 * Files access for the OBB copy - one place for sign in (LogAct) and home
 * (MAct) so the two never drift apart.
 *
 * <p>Android 11+ (API 30): "All files access" ({@code MANAGE_EXTERNAL_STORAGE},
 * opened as the system settings page). Android 5-10: the runtime storage
 * dialog (READ / WRITE_EXTERNAL_STORAGE). Nothing else is requested.
 */
public final class StorageAccess {

    private static final String TAG = "StorageAccess";

    /** Request code for both the settings page and the runtime dialog. */
    public static final int REQUEST_CODE = 100;

    /** Set once any screen has shown the prompt in this process (avoids asking twice in a row). */
    private static volatile boolean askedThisSession;

    private StorageAccess() {
    }

    /** True when the app may read the game's OBB and write the container copy. */
    public static boolean has(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return Environment.isExternalStorageManager();
        return ActivityCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** True once sign in or home has already shown the prompt since the app started. */
    public static boolean askedThisSession() {
        return askedThisSession;
    }

    /**
     * Opens the system prompt for files access. The answer arrives in the
     * activity's onActivityResult (settings page) or onRequestPermissionsResult
     * (runtime dialog) with {@link #REQUEST_CODE}.
     *
     * @return true if a prompt was shown, false if this device has no such page
     */
    public static boolean request(Activity activity) {
        askedThisSession = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.fromParts("package", activity.getPackageName(), null));
                activity.startActivityForResult(intent, REQUEST_CODE);
                return true;
            } catch (ActivityNotFoundException e) {
                try {
                    activity.startActivityForResult(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
                            REQUEST_CODE);
                    return true;
                } catch (RuntimeException nested) {
                    Log.w(TAG, "All-files-access settings unavailable", nested);
                    return false;
                }
            }
        }
        ActivityCompat.requestPermissions(activity,
                new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        Manifest.permission.READ_EXTERNAL_STORAGE}, REQUEST_CODE);
        return true;
    }
}
