package com.jarvis.mobile

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.os.Build
import android.view.accessibility.AccessibilityEvent

class JarvisAccessibilityService : AccessibilityService() {
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
                ACTION_HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
                ACTION_RECENTS -> performGlobalAction(GLOBAL_ACTION_RECENTS)
                ACTION_TAP -> {
                    val x = intent.getFloatExtra("x", -1f)
                    val y = intent.getFloatExtra("y", -1f)
                    if (x >= 0 && y >= 0) tap(x, y)
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val filter = IntentFilter().apply {
            addAction(ACTION_BACK); addAction(ACTION_HOME); addAction(ACTION_RECENTS); addAction(ACTION_TAP)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(receiver, filter)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString().orEmpty()
        if (pkg.isNotBlank()) getSharedPreferences("jarvis_mobile", MODE_PRIVATE).edit().putString("foreground_package", pkg).apply()
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        super.onDestroy()
    }

    private fun tap(x: Float, y: Float) {
        if (Build.VERSION.SDK_INT < 24) return
        val path = Path().apply { moveTo(x, y) }
        dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 70)).build(), null, null)
    }

    companion object {
        const val ACTION_BACK = "com.jarvis.mobile.action.BACK"
        const val ACTION_HOME = "com.jarvis.mobile.action.HOME"
        const val ACTION_RECENTS = "com.jarvis.mobile.action.RECENTS"
        const val ACTION_TAP = "com.jarvis.mobile.action.TAP"
    }
}
