package com.jarvis.mobile

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject

class JarvisNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn?.notification ?: return
        val extras = n.extras
        val title = extras.getCharSequence("android.title")?.toString().orEmpty()
        val text = extras.getCharSequence("android.text")?.toString().orEmpty()
        val packageName = sbn.packageName.orEmpty()
        val item = JSONObject()
            .put("package", packageName)
            .put("title", title)
            .put("text", text)
            .put("time", System.currentTimeMillis())
        val prefs = getSharedPreferences("jarvis_mobile", MODE_PRIVATE)
        val arr = runCatching { JSONArray(prefs.getString("notification_feed", "[]")) }.getOrElse { JSONArray() }
        arr.put(item)
        val trimmed = JSONArray()
        val start = (arr.length() - 60).coerceAtLeast(0)
        for (i in start until arr.length()) trimmed.put(arr.opt(i))
        prefs.edit().putString("notification_feed", trimmed.toString()).apply()
    }
}
