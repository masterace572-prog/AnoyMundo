package com.cold.loader

import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Window manager configuration for Cold Loader floating overlays.
 * Applies OEM-specific display flags to prevent screen recording and surface mirroring.
 */
object ColdOverlayManager {
    private const val TAG = "ColdOverlayManager"

    // AOSP Extended Private Flag
    private const val PRIVATE_FLAG_IS_ROUNDED_CORNERS_OVERLAY = 0x00100000

    // Meizu Flyme Flag
    private const val MEIZU_FLAG_RECORD_FILTER = 0x2000

    // Samsung OneUI Extension Flag
    private const val SEM_EXTENSION_FLAG_RESIZE_DISABLE = 0x00000001

    fun createLayoutParams(width: Int, height: Int, x: Int = 80, y: Int = 160): WindowManager.LayoutParams {
        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        val params = WindowManager.LayoutParams(
            width,
            height,
            windowType,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }

        applyReflections(params)
        return params
    }

    fun applyReflections(params: WindowManager.LayoutParams) {
        // 1. AOSP privateFlags
        try {
            val field: Field = WindowManager.LayoutParams::class.java.getDeclaredField("privateFlags")
            field.isAccessible = true
            val curr = field.getInt(params)
            field.setInt(params, curr or PRIVATE_FLAG_IS_ROUNDED_CORNERS_OVERLAY)
        } catch (ignored: Throwable) {}

        // 2. Meizu Flyme
        try {
            val clazz = Class.forName("android.view.MeizuLayoutParams")
            val flagField = clazz.getDeclaredField("flags")
            flagField.isAccessible = true
            val inst = clazz.getDeclaredConstructor().newInstance()
            flagField.setInt(inst, MEIZU_FLAG_RECORD_FILTER)

            val meizuParamsField = WindowManager.LayoutParams::class.java.getDeclaredField("meizuParams")
            meizuParamsField.isAccessible = true
            meizuParamsField.set(params, inst)
        } catch (ignored: Throwable) {}

        // 3. Samsung OneUI
        try {
            val method: Method = WindowManager.LayoutParams::class.java.getMethod("semAddExtensionFlags", Int::class.javaPrimitiveType)
            method.isAccessible = true
            method.invoke(params, SEM_EXTENSION_FLAG_RESIZE_DISABLE)
        } catch (ignored: Throwable) {}
    }
}
