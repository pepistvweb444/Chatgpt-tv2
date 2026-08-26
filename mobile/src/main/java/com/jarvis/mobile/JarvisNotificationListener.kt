package com.jarvis.mobile

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject

class JarvisNotificationListener : NotificationListenerService() {
    private val callActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_CALL_NOTIFICATION) return
            val key = intent.getStringExtra("key").orEmpty()
            val wanted = intent.getStringExtra("callAction").orEmpty()
            performNotificationCallAction(key, wanted)
        }
    }

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
        val history = runCatching { JSONArray(prefs.getString("notification_feed", "[]")) }.getOrElse { JSONArray() }
        history.put(item)
        val trimmed = JSONArray()
        val start = (history.length() - 250).coerceAtLeast(0)
        for (i in start until history.length()) trimmed.put(history.opt(i))

        val active = runCatching { JSONArray(prefs.getString("active_notification_feed", "[]")) }.getOrElse { JSONArray() }
        val next = JSONArray()
        for (i in 0 until active.length()) {
            val old = active.optJSONObject(i) ?: continue
            if (old.optString("key") != source.key) next.put(old)
        }
        next.put(item)
        prefs.edit().putString("notification_feed", trimmed.toString()).putString("active_notification_feed", next.toString()).apply()

        if (looksLikeIncomingVoipCall(source, title, body)) publishVoipCall(source, title, body)
    }

    private fun looksLikeIncomingVoipCall(sbn: StatusBarNotification, title:String, body:String): Boolean {
        val pkg = sbn.packageName.orEmpty().lowercase()
        val supported = listOf("whatsapp","instagram","facebook.orca","facebook.katana","zoom","telegram","teams","meet").any { pkg.contains(it) }
        if (!supported) return false
        if (sbn.notification.category == Notification.CATEGORY_CALL) return true
        val blob = "$title $body".lowercase()
        return listOf("llamada entrante","incoming call","videollamada entrante","video call","te está llamando","is calling").any { blob.contains(it) }
    }

    private fun publishVoipCall(sbn: StatusBarNotification, title:String, body:String) {
        val actions = sbn.notification.actions.orEmpty()
        val actionNames = JSONArray()
        actions.forEach { actionNames.put(it.title?.toString().orEmpty()) }
        val pkg = sbn.packageName.orEmpty()
        val source = when {
            pkg.contains("whatsapp",true) -> "WhatsApp"
            pkg.contains("instagram",true) -> "Instagram"
            pkg.contains("facebook",true) -> "Messenger/Facebook"
            pkg.contains("zoom",true) -> "Zoom"
            pkg.contains("telegram",true) -> "Telegram"
            pkg.contains("teams",true) -> "Teams"
            pkg.contains("meet",true) -> "Google Meet"
            else -> pkg.substringAfterLast('.')
        }
        val card = JSONObject().put("source",source).put("package",pkg).put("name",title.ifBlank { body })
            .put("number","").put("notificationKey",sbn.key).put("actions",actionNames)
            .put("video",("$title $body").contains("video",true) || ("$title $body").contains("vídeo",true))
            .put("time",System.currentTimeMillis())
        getSharedPreferences("jarvis_mobile",MODE_PRIVATE).edit().putString("incoming_voip_call",card.toString()).apply()
        runCatching {
            startActivity(Intent(this, IncomingCallActivity::class.java)
                .putExtra("source",source).putExtra("name",title.ifBlank { body }).putExtra("notificationKey",sbn.key)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
    }

    private fun performNotificationCallAction(key:String, wanted:String) {
        val sbn = activeNotifications?.firstOrNull { it.key == key } ?: return
        val positive = listOf("answer","contestar","aceptar","accept","join","unirse")
        val negative = listOf("reject","rechazar","decline","colgar","hang up","dismiss")
        val words = if (wanted == "answer") positive else negative
        val action = sbn.notification.actions.orEmpty().firstOrNull { a -> words.any { a.title?.toString().orEmpty().contains(it,true) } } ?: return
        runCatching { action.actionIntent.send() }
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
        val filter = IntentFilter(ACTION_CALL_NOTIFICATION)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(callActionReceiver, filter, RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(callActionReceiver, filter)
        val prefs = getSharedPreferences("jarvis_mobile", MODE_PRIVATE)
        val active = JSONArray()
        runCatching {
            activeNotifications?.forEach { sbn ->
                val n = sbn.notification; val extras = n.extras
                val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
                val body = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty().ifBlank { extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty() }
                if (title.isNotBlank() || body.isNotBlank()) active.put(JSONObject().put("key", sbn.key).put("package", sbn.packageName.orEmpty()).put("title", title).put("text", body).put("subText", extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()).put("conversation", extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString().orEmpty()).put("time", System.currentTimeMillis()))
            }
        }
        prefs.edit().putBoolean("notification_listener_connected", true).putLong("notification_listener_connected_at", System.currentTimeMillis()).putString("active_notification_feed", active.toString()).apply()
    }

    override fun onListenerDisconnected() {
        runCatching { unregisterReceiver(callActionReceiver) }
        getSharedPreferences("jarvis_mobile", MODE_PRIVATE).edit().putBoolean("notification_listener_connected", false).apply()
        super.onListenerDisconnected()
    }

    companion object { const val ACTION_CALL_NOTIFICATION = "com.jarvis.mobile.action.VOIP_CALL_ACTION" }
}
