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
import java.util.UUID

class JarvisNotificationListener : NotificationListenerService() {
    private val callControlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_CALL_CONTROL && intent?.action != ACTION_CALL_NOTIFICATION) return
            val key = intent.getStringExtra("notification_key").orEmpty().ifBlank { intent.getStringExtra("key").orEmpty() }
            val action = intent.getStringExtra("call_action").orEmpty().ifBlank { intent.getStringExtra("callAction").orEmpty() }
            controlVoipCall(key, action)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val f = IntentFilter().apply { addAction(ACTION_CALL_CONTROL); addAction(ACTION_CALL_NOTIFICATION) }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(callControlReceiver, f, RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(callControlReceiver, f)
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

        if (isIncomingVoipCall(n, packageName, title, "$text $bigText $subText")) {
            publishVoipCall(source, title, text.ifBlank { bigText })
        } else {
            val current = CallStateStore.current(this)
            if (current.optBoolean("active", false) && current.optString("source") == "voip" && current.optString("notificationKey") == source.key && n.category == Notification.CATEGORY_CALL) {
                CallStateStore.clear(this, "connected_or_updated")
                IncomingCallPresenter.dismiss(this)
            }
        }

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
        val item = JSONObject().put("key", source.key).put("package", packageName).put("title", title).put("text", body)
            .put("subText", subText).put("conversation", conversation).put("time", System.currentTimeMillis())
        val prefs = getSharedPreferences("jarvis_mobile", MODE_PRIVATE)
        val history = runCatching { JSONArray(prefs.getString("notification_feed", "[]")) }.getOrElse { JSONArray() }
        history.put(item)
        val trimmed = JSONArray(); val start = (history.length() - 250).coerceAtLeast(0)
        for (i in start until history.length()) trimmed.put(history.opt(i))
        val active = runCatching { JSONArray(prefs.getString("active_notification_feed", "[]")) }.getOrElse { JSONArray() }
        val next = JSONArray()
        for (i in 0 until active.length()) active.optJSONObject(i)?.let { if (it.optString("key") != source.key) next.put(it) }
        next.put(item)
        prefs.edit().putString("notification_feed", trimmed.toString()).putString("active_notification_feed", next.toString()).apply()
    }

    private fun isIncomingVoipCall(n: Notification, pkg: String, title: String, body: String): Boolean {
        val known = listOf("whatsapp", "facebook.orca", "messenger", "instagram", "zoom", "telegram", "teams", "meet").any { pkg.contains(it, true) }
        if (!known && n.category != Notification.CATEGORY_CALL) return false
        val actions = n.actions.orEmpty().map { it.title?.toString().orEmpty().lowercase() }
        val hasAnswer = actions.any { listOf("answer", "accept", "contestar", "aceptar", "join", "unirse").any(it::contains) }
        val hasReject = actions.any { listOf("decline", "reject", "rechazar", "colgar", "dismiss").any(it::contains) }
        val words = "$title $body".lowercase()
        val incomingWords = listOf("incoming call", "llamada entrante", "videollamada entrante", "te está llamando", "te esta llamando", "is calling", "ringing").any(words::contains)
        return (hasAnswer && hasReject) || (n.category == Notification.CATEGORY_CALL && incomingWords)
    }

    private fun publishVoipCall(source: StatusBarNotification, title: String, body: String) {
        val n = source.notification
        val app = when {
            source.packageName.contains("whatsapp", true) -> "WhatsApp"
            source.packageName.contains("instagram", true) -> "Instagram"
            source.packageName.contains("facebook", true) || source.packageName.contains("messenger", true) -> "Messenger/Facebook"
            source.packageName.contains("zoom", true) -> "Zoom"
            source.packageName.contains("telegram", true) -> "Telegram"
            source.packageName.contains("teams", true) -> "Microsoft Teams"
            source.packageName.contains("meet", true) -> "Google Meet"
            else -> source.packageName.substringAfterLast('.')
        }
        val iconData = runCatching { CallStateStore.drawableToBase64(n.getLargeIcon()?.loadDrawable(this)) }.getOrDefault("")
        val blob = "$title $body".lowercase()
        val video = blob.contains("video") || blob.contains("vídeo")
        val existing = CallStateStore.current(this)
        val same = existing.optBoolean("active", false) && existing.optString("notificationKey") == source.key
        val id = if (same) existing.optString("id") else UUID.randomUUID().toString()
        val number = Regex("""\+?\d[\d\s]{7,}\d""").find(blob)?.value.orEmpty()
        val call = JSONObject().put("id", id).put("active", true)
            .put("state", if (same) existing.optString("state").ifBlank { "ringing" } else "ringing")
            .put("phase", if (same) existing.optString("phase").ifBlank { "ringing" } else "ringing")
            .put("source", "voip").put("app", app).put("appName", title.ifBlank { app })
            .put("package", source.packageName).put("notificationKey", source.key)
            .put("number", number.ifBlank { if (same) existing.optString("number") else "" })
            .put("name", if (same && existing.optString("name").isNotBlank()) existing.optString("name") else title.ifBlank { app })
            .put("knownContact", if (same) existing.optBoolean("knownContact") else false)
            .put("priority", if (same) existing.optBoolean("priority") else false)
            .put("classification", if (same) existing.optString("classification").ifBlank { "unknown" } else "unknown")
            .put("sourceLabel", if (same) existing.optString("sourceLabel").ifBlank { app } else app)
            .put("lineLabel", "InitTPV")
            .put("channelLabel", if (video) "VIDEOLLAMADA ${app.uppercase()}" else "LLAMADA ENTRANTE")
            .put("photoData", iconData.ifBlank { existing.optString("photoData") })
            .put("video", video)
            .put("videoPreview", if (same && existing.optString("videoPreview") == "live") "live" else if (video) "unavailable" else "hidden")
            .put("videoFrame", if (same) existing.optString("videoFrame") else "")
            .put("transcript", if (same) existing.optJSONArray("transcript") ?: org.json.JSONArray() else org.json.JSONArray())
            .put("aiConnected", if (same) existing.optBoolean("aiConnected") else false)
            .put("aiStatus", if (same) existing.optString("aiStatus") else "")
            .put("wantsUser", if (same) existing.optBoolean("wantsUser") else false)
            .put("time", if (same) existing.optLong("time", System.currentTimeMillis()) else System.currentTimeMillis())
            .put("updatedAt", System.currentTimeMillis())
        n.actions.orEmpty().forEach { action ->
            val label = action.title?.toString().orEmpty().lowercase()
            when {
                listOf("answer", "accept", "contestar", "aceptar", "unirse", "join").any(label::contains) ->
                    CallActionRegistry.put(source.key, CallActionRegistry.Actions(action.actionIntent, CallActionRegistry.actions(source.key)?.reject, CallActionRegistry.actions(source.key)?.open))
                listOf("decline", "reject", "rechazar", "colgar").any(label::contains) ->
                    CallActionRegistry.put(source.key, CallActionRegistry.Actions(CallActionRegistry.actions(source.key)?.answer, action.actionIntent, CallActionRegistry.actions(source.key)?.open))
            }
        }
        CallStateStore.save(this, call)
        IncomingCallPresenter.show(this, call)
        if (!same) CallSessionWatcher.afterCallPublished(this, id)
    }

    private fun controlVoipCall(key: String, requested: String) {
        if (key.isBlank()) return
        val sbn = activeNotifications?.firstOrNull { it.key == key } ?: return
        val wanted = if (requested == "answer") listOf("answer", "accept", "contestar", "aceptar", "join", "unirse") else listOf("decline", "reject", "rechazar", "colgar", "dismiss")
        val action = sbn.notification.actions.orEmpty().firstOrNull { a ->
            val label = a.title?.toString().orEmpty().lowercase(); wanted.any(label::contains)
        }
        if (action != null) runCatching { action.actionIntent.send() }
        CallStateStore.update(this, CallStateStore.current(this).optString("id")) {
            it.put("state", if (requested == "answer") "answered" else "rejected").put("active", requested == "answer")
        }
        IncomingCallPresenter.dismiss(this)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val source = sbn ?: return
        val prefs = getSharedPreferences("jarvis_mobile", MODE_PRIVATE)
        val active = runCatching { JSONArray(prefs.getString("active_notification_feed", "[]")) }.getOrElse { JSONArray() }
        val next = JSONArray()
        for (i in 0 until active.length()) active.optJSONObject(i)?.let { if (it.optString("key") != source.key) next.put(it) }
        prefs.edit().putString("active_notification_feed", next.toString()).apply()
        val call = CallStateStore.current(this)
        if (call.optBoolean("active", false) && call.optString("notificationKey") == source.key) {
            CallStateStore.clear(this, "ended"); IncomingCallPresenter.dismiss(this)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        val prefs = getSharedPreferences("jarvis_mobile", MODE_PRIVATE)
        val active = JSONArray()
        runCatching {
            activeNotifications?.forEach { sbn ->
                val n = sbn.notification; val extras = n.extras
                val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
                val body = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty().ifBlank { extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty() }
                if (title.isNotBlank() || body.isNotBlank()) active.put(JSONObject().put("key", sbn.key).put("package", sbn.packageName.orEmpty()).put("title", title).put("text", body).put("time", System.currentTimeMillis()))
            }
        }
        prefs.edit().putBoolean("notification_listener_connected", true).putLong("notification_listener_connected_at", System.currentTimeMillis()).putString("active_notification_feed", active.toString()).apply()
    }

    override fun onListenerDisconnected() {
        getSharedPreferences("jarvis_mobile", MODE_PRIVATE).edit().putBoolean("notification_listener_connected", false).apply()
        super.onListenerDisconnected()
    }

    override fun onDestroy() { runCatching { unregisterReceiver(callControlReceiver) }; super.onDestroy() }

    companion object {
        const val ACTION_CALL_CONTROL = "com.jarvis.mobile.action.CALL_CONTROL"
        const val ACTION_CALL_NOTIFICATION = "com.jarvis.mobile.action.VOIP_CALL_ACTION"
    }
}
