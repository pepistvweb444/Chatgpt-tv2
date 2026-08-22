package com.jarvis.mobile

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject

class JarvisNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn?.notification ?: return
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
        val conversation = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString().orEmpty()
        val packageName = sbn.packageName.orEmpty()
        val body = listOf(bigText, text).firstOrNull { it.isNotBlank() }.orEmpty()
        if (title.isBlank() && body.isBlank()) return

        val item = JSONObject()
            .put("package", packageName)
            .put("title", title)
            .put("text", body)
            .put("subText", subText)
            .put("conversation", conversation)
            .put("time", System.currentTimeMillis())

        val prefs = getSharedPreferences("jarvis_mobile", MODE_PRIVATE)
        val arr = runCatching { JSONArray(prefs.getString("notification_feed", "[]")) }.getOrElse { JSONArray() }
        arr.put(item)
        val trimmed = JSONArray()
        val start = (arr.length() - 150).coerceAtLeast(0)
        for (i in start until arr.length()) trimmed.put(arr.opt(i))
        prefs.edit().putString("notification_feed", trimmed.toString()).apply()
    }
}
