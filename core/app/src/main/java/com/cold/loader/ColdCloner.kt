package com.cold.loader

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import com.ccore.CCoreCore
import com.ccore.entity.pm.InstallResult

/**
 * High-speed, zero-copy application mounting and launching service for Cold Loader.
 */
object ColdCloner {
    private const val TAG = "ColdCloner"

    fun isInstalledInSandbox(packageName: String, userId: Int = 0): Boolean {
        return try {
            CCoreCore.get().isInstalled(packageName, userId)
        } catch (t: Throwable) {
            false
        }
    }

    fun prepareAndInstall(context: Context, packageName: String, userId: Int = 0): Boolean {
        if (isInstalledInSandbox(packageName, userId)) {
            Log.i(TAG, "Package $packageName is already mounted in sandbox user $userId")
            return true
        }

        Log.i(TAG, "Mounting host package $packageName into CCore sandbox user $userId...")
        val result: InstallResult = CCoreCore.get().installPackageAsUser(packageName, userId)
        if (result.success) {
            Log.i(TAG, "Mounted $packageName successfully")
            return true
        } else {
            Log.e(TAG, "Failed to mount $packageName: ${result.msg}")
            return false
        }
    }

    fun launch(context: Context, packageName: String, userId: Int = 0): Boolean {
        Log.i(TAG, "Attempting multi-tier launch for $packageName in virtual sandbox slot :p0 (user=$userId)...")

        // Tier 1: CCoreCore launchApk resolution
        try {
            if (CCoreCore.get().launchApk(packageName, userId)) {
                Log.i(TAG, "Tier 1: CCoreCore.launchApk succeeded for $packageName")
                return true
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Tier 1: launchApk threw exception: ${t.message}")
        }

        // Tier 2: Host PackageManager launch intent resolution
        try {
            val hostIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (hostIntent != null) {
                hostIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                CCoreCore.getBActivityManager().startActivity(hostIntent, userId)
                Log.i(TAG, "Tier 2: Launched via host packageManager intent: ${hostIntent.component}")
                return true
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Tier 2: Host intent launch failed: ${t.message}")
        }

        // Tier 3: Direct canonical UE4/PUBG activity components
        val candidateActivities = listOf(
            "com.epicgames.ue4.SplashActivity",
            "com.tencent.tmgp.pubgmhd.SplashActivity",
            "com.pubg.imobile.MainActivity",
            "com.rekoo.pubgm.MainActivity",
            "com.vng.pubgmobile.MainActivity"
        )

        for (activityName in candidateActivities) {
            try {
                val directIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    component = ComponentName(packageName, activityName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                CCoreCore.getBActivityManager().startActivity(directIntent, userId)
                Log.i(TAG, "Tier 3: Launched via explicit component: $packageName/$activityName")
                return true
            } catch (t: Throwable) {
                Log.d(TAG, "Tier 3: Failed component $activityName: ${t.message}")
            }
        }

        Log.e(TAG, "All launch tiers failed for $packageName")
        return false
    }

    fun launch(packageName: String, userId: Int = 0): Boolean {
        return launch(ColdApp.getContext(), packageName, userId)
    }
}
