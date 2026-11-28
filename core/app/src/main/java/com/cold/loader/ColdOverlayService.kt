package com.cold.loader

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Foreground floating service managing the Cold Loader overlay HUD.
 */
class ColdOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var floatingHeadView: View? = null
    private var expandedDashboardView: View? = null
    private lateinit var headParams: WindowManager.LayoutParams
    private lateinit var dashParams: WindowManager.LayoutParams

    private var activePackage: String = "com.pubg.imobile"
    private var isExpanded: Boolean = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForegroundNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra(EXTRA_PACKAGE)?.let {
            activePackage = it
        }

        if (floatingHeadView == null) {
            initFloatingHead()
            initDashboard()
        }

        return START_NOT_STICKY
    }

    private fun startForegroundNotification() {
        val channelId = "cold_loader_service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Cold Loader Foreground Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)

            val notification = Notification.Builder(this, channelId)
                .setContentTitle("Cold Loader Active")
                .setContentText("Container overlay is active")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .build()

            startForeground(2002, notification)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initFloatingHead() {
        headParams = ColdOverlayManager.createLayoutParams(
            width = dpToPx(54),
            height = dpToPx(54),
            x = 40,
            y = 200
        )

        val head = TextView(this).apply {
            text = "COLD"
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setTypeface(null, android.graphics.Typeface.BOLD)

            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#0D1117"))
                setStroke(dpToPx(2), Color.parseColor("#00E5FF"))
            }
            background = bg
        }

        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        var isClick = false

        head.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = headParams.x
                    initialY = headParams.y
                    touchX = event.rawX
                    touchY = event.rawY
                    isClick = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) isClick = false
                    headParams.x = initialX + dx
                    headParams.y = initialY + dy
                    try { windowManager.updateViewLayout(head, headParams) } catch (ignored: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isClick) toggleDashboard()
                    true
                }
                else -> false
            }
        }

        floatingHeadView = head
        try { windowManager.addView(floatingHeadView, headParams) } catch (e: Exception) { e.printStackTrace() }
    }

    private fun initDashboard() {
        dashParams = ColdOverlayManager.createLayoutParams(
            width = dpToPx(280),
            height = WindowManager.LayoutParams.WRAP_CONTENT,
            x = 50,
            y = 260
        )

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16))

            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(10).toFloat()
                setColor(Color.parseColor("#E60D1117"))
                setStroke(dpToPx(1), Color.parseColor("#00E5FF"))
            }
            background = bg
        }

        val header = TextView(this).apply {
            text = "COLD CONTAINER HUD"
            textSize = 15f
            setTextColor(Color.parseColor("#00E5FF"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        panel.addView(header)

        val body = TextView(this).apply {
            text = "Target: $activePackage\nProcess: Slot :p0 (Isolated)\nEngine: CCore v1.0.0 (ARM64)\nImGui Overlay: libcold.so Active\nScreen Record Cloak: Active"
            textSize = 11f
            setTextColor(Color.LTGRAY)
            setPadding(0, dpToPx(10), 0, dpToPx(10))
        }
        panel.addView(body)

        val hideBtn = Button(this).apply {
            text = "MINIMIZE"
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1F6FEB"))
            setOnClickListener { toggleDashboard() }
        }
        panel.addView(hideBtn)

        expandedDashboardView = panel
    }

    private fun toggleDashboard() {
        isExpanded = !isExpanded
        if (isExpanded) {
            dashParams.x = headParams.x
            dashParams.y = headParams.y + dpToPx(60)
            try { windowManager.addView(expandedDashboardView, dashParams) } catch (ignored: Exception) {}
        } else {
            try { windowManager.removeView(expandedDashboardView) } catch (ignored: Exception) {}
        }
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics).toInt()
    }

    override fun onDestroy() {
        super.onDestroy()
        floatingHeadView?.let { try { windowManager.removeView(it) } catch (ignored: Exception) {} }
        expandedDashboardView?.let { try { windowManager.removeView(it) } catch (ignored: Exception) {} }
    }

    companion object {
        const val EXTRA_PACKAGE = "extra_target_package"

        fun start(context: Context, targetPackage: String) {
            val intent = Intent(context, ColdOverlayService::class.java).apply {
                putExtra(EXTRA_PACKAGE, targetPackage)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ColdOverlayService::class.java))
        }
    }
}
