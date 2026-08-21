package com.jarvis.tv

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView

class JarvisAccessibilityService : AccessibilityService() {
    private var windowManager: WindowManager? = null
    private var bubble: TextView? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        showBubble()
    }

    private fun showBubble() {
        if (bubble != null) return
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm
        val view = TextView(this).apply {
            text = "AI"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xD91B1F2A.toInt())
            setPadding(30, 22, 30, 22)
            isClickable = true
            contentDescription = "Hablar con Jarvis"
            setOnClickListener {
                startActivity(Intent(this@JarvisAccessibilityService, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra("start_voice", true)
                })
            }
        }
        bubble = view
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = 42
            y = 42
        }
        try { wm.addView(view, params) } catch (_: Exception) { bubble = null }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        bubble?.let { try { windowManager?.removeView(it) } catch (_: Exception) {} }
        bubble = null
        super.onDestroy()
    }
}
