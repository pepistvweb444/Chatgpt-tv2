package com.jarvis.mobile

import android.app.Notification
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject

class JarvisNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val source = sbn ?: return
        val n = source.notification
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
        val conversation = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString().orEmpty()
        val packageName = source.packageName.orEmpty()

        val messagingLines = mutableListOf<String>()
        runCatching {
            extras.getParcelableArray(Notification.EXTRA_MESSAGES)?.forEach { p ->
                val b = p as? Bundle ?: return@forEach
                val msg = b.getCharSequence("text")?.toString().orEmpty()
                val sender = b.getCharSequence("sender")?.toString().orEmpty()
                if (msg.isNotBlank()) messagingLines += if (sender.isBlank()) msg else "$sender: $msg"
            }
        }
        val body = when {
            messagingLines.isNotEmpty() -> messagingLines.takeLast(8).joinToString("\n")
            bigText.isNotBlank() -> bigText
            else -> text
        }
        if (title.isBlank() && body.isBlank()) return

        val item = JSONObject()
            .put("key", source.key)
            .put("package", packageName)
            .put("title", title)
            .put("text", body)
            .put("subText", subText)
            .put("conversation", conversation)
            .put("time", System.currentTimeMillis())

        val prefs = getSharedPreferences("jarvis_mobile", MODE_PRIVATE)

        // Historical feed remains useful for diagnostics, but is not used as the unread inbox.
        val history = runCatching { JSONArray(prefs.getString("notification_feed", "[]")) }.getOrElse { JSONArray() }
        history.put(item)
        val trimmed = JSONArray()
        val start = (history.length() - 250).coerceAtLeast(0)
        for (i in start until history.length()) trimmed.put(history.opt(i))

        // Active notification feed mirrors what Android still considers pending/unread.
        val active = runCatching { JSONArray(prefs.getString("active_notification_feed", "[]")) }.getOrElse { JSONArray() }
        val next = JSONArray()
        for (i in 0 until active.length()) {
            val old = active.optJSONObject(i) ?: continue
            if (old.optString("key") != source.key) next.put(old)
        }
        next.put(item)
        prefs.edit()
            .putString("notification_feed", trimmed.toString())
            .putString("active_notification_feed", next.toString())
            .apply()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val source = sbn ?: return
        val prefs = getSharedPreferences("jarvis_mobile", MODE_PRIVATE)
        val active = runCatching { JSONArray(prefs.getString("active_notification_feed", "[]")) }.getOrElse { JSONArray() }
        val next = JSONArray()
        for (i in 0 until active.length()) {
            val old = active.optJSONObject(i) ?: continue
            if (old.optString("key") != source.key) next.put(old)
        }
        prefs.edit().putString("active_notification_feed", next.toString()).apply()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        val prefs = getSharedPreferences("jarvis_mobile", MODE_PRIVATE)
        val active = JSONArray()
        runCatching {
            activeNotifications?.forEach { sbn ->
                val n = sbn.notification
                val extras = n.extras
                val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
                val body = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
                    .ifBlank { extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty() }
                if (title.isNotBlank() || body.isNotBlank()) {
                    active.put(JSONObject()
                        .put("key", sbn.key)
                        .put("package", sbn.packageName.orEmpty())
                        .put("title", title)
                        .put("text", body)
                        .put("subText", extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty())
                        .put("conversation", extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString().orEmpty())
                        .put("time", System.currentTimeMillis()))
                }
            }
        }
        prefs.edit()
            .putBoolean("notification_listener_connected", true)
            .putLong("notification_listener_connected_at", System.currentTimeMillis())
            .putString("active_notification_feed", active.toString())
            .apply()
    }

    override fun onListenerDisconnected() {
        getSharedPreferences("jarvis_mobile", MODE_PRIVATE).edit()
            .putBoolean("notification_listener_connected", false)
            .apply()
        super.onListenerDisconnected()
    }
}
