package com.jarvis.tv

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

class MobileRemoteClient(context: Context) {
    private val prefs = context.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    private fun host(): String = prefs.getString("mobile_remote_host", "").orEmpty().trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
    private fun token(): String = prefs.getString("mobile_remote_token", "").orEmpty().trim()

    fun configured(): Boolean = host().isNotBlank() && token().isNotBlank()
    fun ping(): JSONObject = get("/ping")
    fun sendTask(task: String): JSONObject = get("/remote?task=${URLEncoder.encode(task, "UTF-8")}", auth = true)
    fun unreadMessages(): JSONObject = get("/unread", auth = true)
    fun agenda(): JSONObject = get("/agenda", auth = true)
    fun incomingCall(): JSONObject = get("/incoming-call", auth = true)
    fun callAction(action: String): JSONObject = get("/incoming-call-action?action=${URLEncoder.encode(action, "UTF-8")}", auth = true)

    private fun get(path: String, auth: Boolean = false): JSONObject {
        val h = host()
        if (h.isBlank()) throw IllegalStateException("Configura primero la IP o nombre del móvil")
        val c = (URL("http://$h:8765$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 5000; readTimeout = 12000
            setRequestProperty("Accept", "application/json")
            if (auth) setRequestProperty("Authorization", "Bearer ${token()}")
        }
        val raw = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (c.responseCode !in 200..299) throw IllegalStateException("Móvil HTTP ${c.responseCode}: ${runCatching { JSONObject(raw).optString("error") }.getOrNull().orEmpty().ifBlank { raw.take(120) }}")
        return runCatching { JSONObject(raw) }.getOrElse { JSONObject().put("raw", raw) }
    }
}
