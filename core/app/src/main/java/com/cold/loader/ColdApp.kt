package com.cold.loader

import android.app.Application
import android.content.Context
import android.util.Log
import com.ccore.CCoreCore
import com.ccore.app.BActivityThread
import com.ccore.app.configuration.AppLifecycleCallback
import com.ccore.app.configuration.ClientConfiguration
import java.io.File

/**
 * Application entry point for Cold Loader.
 * Boots the CCore 2 virtualization container and registers the in-process
 * native module loader (libcold.so) inside the client runtime lifecycle.
 */
class ColdApp : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        mContext = base

        try {
            // 1. Initialize CCore Virtual Container
            CCoreCore.get().doAttachBaseContext(base, object : ClientConfiguration() {
                override fun getHostPackageName(): String {
                    return base.packageName
                }

                override fun isHideRoot(): Boolean = true
                override fun isHideXposed(): Boolean = true
                override fun isEnableDaemonService(): Boolean = false
                override fun isEnableLauncherActivity(): Boolean = false

                override fun requestInstallPackage(file: File?): Boolean = false
            })

            // 2. Register in-process lifecycle callback
            CCoreCore.get().addAppLifecycleCallback(object : AppLifecycleCallback() {
                override fun beforeApplicationOnCreate(
                    packageName: String?,
                    processName: String?,
                    application: Application?,
                    userId: Int
                ) {
                    Log.d(TAG, "beforeApplicationOnCreate: pkg=$packageName, process=$processName, userId=$userId")
                    // If running inside client slot :p0 and target is a supported game, inject libcold.so
                    if (ColdTarget.ALL_PACKAGES.contains(packageName)) {
                        try {
                            System.loadLibrary("cold")
                            Log.i(TAG, "Successfully injected libcold.so into $packageName (PID: ${android.os.Process.myPid()})")
                        } catch (t: Throwable) {
                            Log.d(TAG, "libcold.so load status: ${t.message}")
                        }
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach CCore sandbox: ${e.message}", e)
        }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            CCoreCore.get().doCreate()
            Log.i(TAG, "CCore Virtualization Engine initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize CCore engine: ${e.message}", e)
        }
    }

    companion object {
        const val TAG = "ColdLoaderApp"
        private lateinit var mContext: Context

        fun getContext(): Context = mContext
    }
}
