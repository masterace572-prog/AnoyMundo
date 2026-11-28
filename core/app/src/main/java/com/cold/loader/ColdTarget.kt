package com.cold.loader

import android.content.Context
import android.content.pm.PackageManager

/**
 * Supported game targets for Cold Loader.
 */
data class ColdTarget(
    val title: String,
    val packageName: String,
    val region: String
) {
    fun isInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    companion object {
        val BGMI = ColdTarget(
            title = "Battlegrounds Mobile India",
            packageName = "com.pubg.imobile",
            region = "India (v3.x / v4.x)"
        )

        val BGMI_AIR = ColdTarget(
            title = "BGMI Air",
            packageName = "com.krafton.bgmilite",
            region = "India Lite"
        )

        val GLOBAL = ColdTarget(
            title = "PUBG Mobile (Global)",
            packageName = "com.tencent.ig",
            region = "Global Edition"
        )

        val KOREA = ColdTarget(
            title = "PUBG Mobile (KR / JP)",
            packageName = "com.pubg.krmobile",
            region = "Korea & Japan"
        )

        val VIETNAM = ColdTarget(
            title = "PUBG Mobile (VN)",
            packageName = "com.vng.pubgmobile",
            region = "Vietnam"
        )

        val TAIWAN = ColdTarget(
            title = "PUBG Mobile (TW)",
            packageName = "com.revmob.game",
            region = "Taiwan"
        )

        val ALL_TARGETS = listOf(BGMI, BGMI_AIR, GLOBAL, KOREA, VIETNAM, TAIWAN)
        val ALL_PACKAGES = ALL_TARGETS.map { it.packageName }.toSet()
    }
}
