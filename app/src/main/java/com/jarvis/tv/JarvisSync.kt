package com.jarvis.tv

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object JarvisSync {
    private const val BACKEND = "https://chatgpt-tv2.vercel.app"
    private const val PREFS = "jarvis"
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var applyingRemote = false
    private var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    fun start(context: Context) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (listener == null) {
            listener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
                if (!applyingRemote && key != "sync_updated_at" && key != "sync_last_ok") {
                    p.edit().putLong("sync_updated_at", System.currentTimeMillis()).apply()
                    force(app)
                }
            }
            prefs.registerOnSharedPreferenceChangeListener(listener)
        }
        main.post(object : Runnable {
            override fun run() { force(app); main.postDelayed(this, 15000) }
        })
    }

    fun force(context: Context) { val app = context.applicationContext; Thread { runCatching { syncOnce(app) } }.start() }

    private fun syncOnce(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val syncKey = prefs.getString("sync_key", "").orEmpty(); if (syncKey.length < 16) return
        val localUpdated = prefs.getLong("sync_updated_at", 0L)
        val get = (URL("$BACKEND/api/sync").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 7000; readTimeout = 10000
            setRequestProperty("X-Jarvis-Sync-Key", syncKey); setRequestProperty("Accept", "application/json")
        }
        val body = (if (get.responseCode in 200..299) get.inputStream else get.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (get.responseCode !in 200..299) return
        val remote = JSONObject(body); val remoteUpdated = remote.optLong("updatedAt", 0L)
        if (remoteUpdated > localUpdated) {
            applyState(prefs, remote.optJSONObject("state") ?: JSONObject())
            prefs.edit().putLong("sync_updated_at", remoteUpdated).putLong("sync_last_ok", System.currentTimeMillis()).apply(); return
        }
        val post = (URL("$BACKEND/api/sync").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 7000; readTimeout = 10000
            setRequestProperty("X-Jarvis-Sync-Key", syncKey); setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        val payload = JSONObject().put("updatedAt", if (localUpdated > 0) localUpdated else System.currentTimeMillis()).put("state", snapshot(prefs)).toString()
        post.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
        if (post.responseCode in 200..299) prefs.edit().putLong("sync_last_ok", System.currentTimeMillis()).apply()
    }

    private fun snapshot(prefs: SharedPreferences): JSONObject {
        val out = JSONObject()
        for ((k, v) in prefs.all) if (shouldSync(k)) when (v) {
            is String -> out.put(k, v); is Boolean -> out.put(k, v); is Int -> out.put(k, v); is Long -> out.put(k, v); is Float -> out.put(k, v.toDouble()); is Set<*> -> out.put(k, JSONArray(v.toList()))
        }
        return out
    }

    private fun applyState(prefs: SharedPreferences, state: JSONObject) {
        applyingRemote = true
        try {
            val e = prefs.edit(); val keys = state.keys()
            while (keys.hasNext()) { val k = keys.next(); if (!shouldSync(k)) continue; when (val v = state.opt(k)) {
                is Boolean -> e.putBoolean(k, v); is Int -> e.putInt(k, v); is Long -> e.putLong(k, v); is Double -> e.putLong(k, v.toLong()); else -> e.putString(k, v?.toString() ?: "")
            }}
            e.apply()
        } finally { applyingRemote = false }
    }

    private fun shouldSync(k: String): Boolean = k == "chatIndex" || k == "currentConversation" || k == "mcps" || k == "voice" || k == "assistantName" || k == "wakeWord" || k.startsWith("chat_") || k.startsWith("response_") || k.startsWith("phone_") || k.startsWith("reminder_")
}
