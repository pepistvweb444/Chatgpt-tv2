package com.jarvis.mobile

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

class JarvisAccessibilityService : AccessibilityService() {
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
                ACTION_HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
                ACTION_RECENTS -> performGlobalAction(GLOBAL_ACTION_RECENTS)
                ACTION_TAP -> {
                    val x = intent.getFloatExtra("x", -1f); val y = intent.getFloatExtra("y", -1f)
                    if (x >= 0 && y >= 0) tap(x, y)
                }
                ACTION_CLICK_TEXT -> clickByText(intent.getStringExtra("text").orEmpty())
                ACTION_SET_TEXT -> setText(intent.getStringExtra("text").orEmpty(), intent.getStringExtra("target").orEmpty())
                ACTION_SCROLL_FORWARD -> scroll(true)
                ACTION_SCROLL_BACKWARD -> scroll(false)
                ACTION_REFRESH_UI -> persistUiSnapshot()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val filter = IntentFilter().apply {
            addAction(ACTION_BACK); addAction(ACTION_HOME); addAction(ACTION_RECENTS); addAction(ACTION_TAP)
            addAction(ACTION_CLICK_TEXT); addAction(ACTION_SET_TEXT); addAction(ACTION_SCROLL_FORWARD)
            addAction(ACTION_SCROLL_BACKWARD); addAction(ACTION_REFRESH_UI)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(receiver, filter)
        getSharedPreferences("jarvis_mobile", MODE_PRIVATE).edit().putBoolean("accessibility_connected", true).apply()
        persistUiSnapshot()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString().orEmpty()
        if (pkg.isNotBlank()) getSharedPreferences("jarvis_mobile", MODE_PRIVATE).edit().putString("foreground_package", pkg).apply()
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> persistUiSnapshot()
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        getSharedPreferences("jarvis_mobile", MODE_PRIVATE).edit().putBoolean("accessibility_connected", false).apply()
        runCatching { unregisterReceiver(receiver) }
        super.onDestroy()
    }

    private fun root(): AccessibilityNodeInfo? = rootInActiveWindow

    private fun walk(node: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>) {
        if (node == null) return
        out += node
        for (i in 0 until node.childCount) walk(node.getChild(i), out)
    }

    private fun allNodes(): List<AccessibilityNodeInfo> = mutableListOf<AccessibilityNodeInfo>().also { walk(root(), it) }

    private fun safeLabel(n: AccessibilityNodeInfo): String {
        if (n.isPassword) return ""
        return listOf(n.text?.toString().orEmpty(), n.contentDescription?.toString().orEmpty())
            .firstOrNull { it.isNotBlank() }.orEmpty().trim()
    }

    private fun persistUiSnapshot() {
        val arr = JSONArray()
        allNodes().take(140).forEach { n ->
            val label = safeLabel(n)
            if (label.isBlank() && !n.isClickable && !n.isEditable) return@forEach
            val r = android.graphics.Rect(); n.getBoundsInScreen(r)
            arr.put(JSONObject()
                .put("text", label.take(240))
                .put("class", n.className?.toString().orEmpty())
                .put("clickable", n.isClickable)
                .put("editable", n.isEditable)
                .put("scrollable", n.isScrollable)
                .put("enabled", n.isEnabled)
                .put("bounds", "${r.left},${r.top},${r.right},${r.bottom}"))
        }
        getSharedPreferences("jarvis_mobile", MODE_PRIVATE).edit()
            .putString("accessibility_ui", arr.toString())
            .putLong("accessibility_ui_at", System.currentTimeMillis()).apply()
    }

    private fun clickableParent(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var n = node; var depth = 0
        while (n != null && depth++ < 5) {
            if (n.isClickable && n.isEnabled) return n
            n = n.parent
        }
        return null
    }

    private fun clickByText(query: String) {
        val q = query.trim(); if (q.isBlank()) return
        val candidates = allNodes().filter { safeLabel(it).contains(q, ignoreCase = true) }
        val node = candidates.firstOrNull { it.isClickable } ?: candidates.firstOrNull()?.let { clickableParent(it) }
        if (node != null) node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        persistUiSnapshot()
    }

    private fun setText(value: String, target: String) {
        val nodes = allNodes().filter { it.isEditable && it.isEnabled && !it.isPassword }
        val node = if (target.isBlank()) nodes.firstOrNull() else nodes.firstOrNull { safeLabel(it).contains(target, true) } ?: nodes.firstOrNull()
        if (node != null) {
            val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }
        persistUiSnapshot()
    }

    private fun scroll(forward: Boolean) {
        val node = allNodes().firstOrNull { it.isScrollable && it.isEnabled }
        node?.performAction(if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
        persistUiSnapshot()
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
        const val ACTION_CLICK_TEXT = "com.jarvis.mobile.action.CLICK_TEXT"
        const val ACTION_SET_TEXT = "com.jarvis.mobile.action.SET_TEXT"
        const val ACTION_SCROLL_FORWARD = "com.jarvis.mobile.action.SCROLL_FORWARD"
        const val ACTION_SCROLL_BACKWARD = "com.jarvis.mobile.action.SCROLL_BACKWARD"
        const val ACTION_REFRESH_UI = "com.jarvis.mobile.action.REFRESH_UI"
    }
}
