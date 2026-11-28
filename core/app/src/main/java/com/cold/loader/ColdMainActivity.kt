package com.cold.loader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Main dashboard for Cold Loader.
 * Designed with a clean cyber-dark theme, original vector branding,
 * real-time target detection, and one-tap isolated container execution.
 */
class ColdMainActivity : AppCompatActivity() {

    private lateinit var targetCardsLayout: LinearLayout
    private var selectedTarget: ColdTarget = ColdTarget.BGMI

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildInterface()
        validatePermissions()
    }

    override fun onResume() {
        super.onResume()
        renderTargetList()
    }

    private fun buildInterface() {
        val scrollView = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0B0E14"))
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(20), dpToPx(32), dpToPx(20), dpToPx(32))
        }
        scrollView.addView(root)

        // Logo & Title Header
        val brandHeader = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, dpToPx(24))
        }

        val title = TextView(this).apply {
            text = "COLD LOADER"
            textSize = 24f
            setTextColor(Color.parseColor("#00E5FF"))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        brandHeader.addView(title)

        val subtitle = TextView(this).apply {
            text = "Engineered on CCore 2 Virtualization & Dear ImGui"
            textSize = 12f
            setTextColor(Color.parseColor("#8B949E"))
            gravity = Gravity.CENTER
            setPadding(0, dpToPx(4), 0, 0)
        }
        brandHeader.addView(subtitle)
        root.addView(brandHeader)

        // System Capabilities Card
        val systemCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(16), dpToPx(14), dpToPx(16), dpToPx(14))
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(10).toFloat()
                setColor(Color.parseColor("#161B22"))
                setStroke(dpToPx(1), Color.parseColor("#30363D"))
            }
            background = bg
        }

        val cardTitle = TextView(this).apply {
            text = "CONTAINER STATUS"
            textSize = 13f
            setTextColor(Color.parseColor("#58A6FF"))
            typeface = Typeface.DEFAULT_BOLD
        }
        systemCard.addView(cardTitle)

        val cardDesc = TextView(this).apply {
            text = "• Virtualization Core : CCore v1.0.0 (ARM64)\n• System Hook Engine  : SandHook Native ArtMethod\n• ImGui Overlay Module: libcold.so (GLES3)\n• Attestation Cloak   : Android 14+ Rule Suppression\n• VFS Encrypted Mounts: /data/misc_ce & misc_de Active"
            textSize = 11f
            setTextColor(Color.parseColor("#C9D1D9"))
            setPadding(0, dpToPx(6), 0, 0)
        }
        systemCard.addView(cardDesc)
        root.addView(systemCard)

        // Section Title: Select Target
        val sectionTitle = TextView(this).apply {
            text = "TARGET GAME"
            textSize = 13f
            setTextColor(Color.parseColor("#8B949E"))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dpToPx(4), dpToPx(20), 0, dpToPx(10))
        }
        root.addView(sectionTitle)

        targetCardsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(targetCardsLayout)

        // Launch Action Button
        val launchButton = Button(this).apply {
            text = "LAUNCH IN CCore SANDBOX"
            textSize = 15f
            setTextColor(Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD

            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(8).toFloat()
                setColor(Color.parseColor("#00E5FF"))
            }
            background = bg
            setPadding(0, dpToPx(16), 0, dpToPx(16))
            setOnClickListener { executeLaunch() }
        }

        val buttonParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dpToPx(16)
        }
        root.addView(launchButton, buttonParams)

        setContentView(scrollView)
    }

    private fun renderTargetList() {
        targetCardsLayout.removeAllViews()

        for (target in ColdTarget.ALL_TARGETS) {
            val isHostInstalled = target.isInstalled(this)
            val isMounted = ColdCloner.isInstalledInSandbox(target.packageName, 0)
            val isSelected = target.packageName == selectedTarget.packageName

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12))

                val bg = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dpToPx(8).toFloat()
                    setColor(if (isSelected) Color.parseColor("#1C2333") else Color.parseColor("#161B22"))
                    setStroke(dpToPx(1), if (isSelected) Color.parseColor("#00E5FF") else Color.parseColor("#30363D"))
                }
                background = bg

                val titleText = TextView(this@ColdMainActivity).apply {
                    text = target.title
                    textSize = 14f
                    setTextColor(if (isSelected) Color.parseColor("#00E5FF") else Color.WHITE)
                    typeface = Typeface.DEFAULT_BOLD
                }
                addView(titleText)

                val statusText = TextView(this@ColdMainActivity).apply {
                    val status = when {
                        isMounted -> "STATUS: MOUNTED IN SANDBOX"
                        isHostInstalled -> "STATUS: INSTALLED ON DEVICE (READY TO MOUNT)"
                        else -> "STATUS: NOT INSTALLED"
                    }
                    text = "${target.packageName} (${target.region})\n$status"
                    textSize = 11f
                    setTextColor(if (isMounted || isHostInstalled) Color.parseColor("#3FB950") else Color.parseColor("#F85149"))
                    setPadding(0, dpToPx(4), 0, 0)
                }
                addView(statusText)

                setOnClickListener {
                    selectedTarget = target
                    renderTargetList()
                }
            }

            val cardParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(10)
            }
            targetCardsLayout.addView(card, cardParams)
        }
    }

    private fun executeLaunch() {
        if (!validatePermissions()) {
            Toast.makeText(this, "Please grant required permissions first", Toast.LENGTH_SHORT).show()
            return
        }

        // 1. Verify and mount target package
        val ready = ColdCloner.prepareAndInstall(this, selectedTarget.packageName, 0)
        if (!ready) {
            Toast.makeText(this, "Target game must be installed on your device first", Toast.LENGTH_LONG).show()
            return
        }

        // 2. Start anti-capture floating overlay HUD
        ColdOverlayService.start(this, selectedTarget.packageName)

        // 3. Launch the game in CCore sandbox process :p0
        val launched = ColdCloner.launch(this, selectedTarget.packageName, 0)
        if (launched) {
            Toast.makeText(this, "Launched ${selectedTarget.title} in Slot :p0", Toast.LENGTH_SHORT).show()
            moveTaskToBack(true)
        } else {
            Toast.makeText(this, "Failed to launch container client", Toast.LENGTH_LONG).show()
        }
    }

    private fun validatePermissions(): Boolean {
        // Overlay permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivity(intent)
            return false
        }

        // Android 11+ All Files Access (API 30+)
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                val isManager = Environment::class.java.getMethod("isExternalStorageManager").invoke(null) as Boolean
                if (!isManager) {
                    val intent = Intent("android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION", Uri.parse("package:$packageName"))
                    startActivity(intent)
                    return false
                }
            } catch (t: Throwable) {
                // Ignore fallback
            }
        }

        // Legacy storage permissions
        if (Build.VERSION.SDK_INT < 30) {
            val perms = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            val missing = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isNotEmpty()) {
                ActivityCompat.requestPermissions(this, missing.toTypedArray(), 101)
                return false
            }
        }

        return true
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics).toInt()
    }
}
