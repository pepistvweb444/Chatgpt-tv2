package com.jarvis.mobile

import android.app.PendingIntent
import java.util.concurrent.ConcurrentHashMap

object CallActionRegistry {
    data class Actions(val answer: PendingIntent?, val reject: PendingIntent?, val open: PendingIntent?)
    private val calls = ConcurrentHashMap<String, Actions>()

    fun put(key: String, actions: Actions) { if (key.isNotBlank()) calls[key] = actions }
    fun remove(key: String) { calls.remove(key) }
    fun execute(key: String, action: String): Boolean {
        val a = calls[key] ?: return false
        val p = when (action.lowercase()) {
            "answer", "accept", "contestar" -> a.answer
            "reject", "decline", "rechazar" -> a.reject
            "open" -> a.open
            else -> null
        } ?: return false
        return runCatching { p.send(); true }.getOrDefault(false)
    }
}
