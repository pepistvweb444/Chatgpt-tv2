package com.jarvis.tv

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

class MobileRemoteClient(context: Context) {
    private val prefs = context.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    private fun rawHost(): String =
        prefs.getString("mobile_remote_host", "").orEmpty().trim().trimEnd('/')

    private fun token(): String =
        prefs.getString("mobile_remote_token", "").orEmpty().trim()

    private fun baseUrl(): String {
        val raw = rawHost()
        if (raw.isBlank()) throw IllegalStateException("Configura primero la IP o nombre del teléfono")
        val noScheme = raw.removePrefix("http://").removePrefix("https://").trimEnd('/')
        val hostPort = if (Regex(""".+:\d+$""").matches(noScheme)) noScheme else "$noScheme:8765"
        return "http://$hostPort"
    }

    fun hostDisplay(): String = rawHost()
    fun paired(): Boolean = token().isNotBlank()
    fun configured(): Boolean = rawHost().isNotBlank() && token().isNotBlank()

    fun pair(pin: String): JSONObject {
        val clean = pin.trim()
        if (!clean.matches(Regex("\\d{4}"))) throw IllegalArgumentException("El PIN debe tener 4 dígitos")
        val result = get("/pair?pin=${URLEncoder.encode(clean, "UTF-8")}")
        val newToken = result.optString("token").trim()
        if (!result.optBoolean("paired") || newToken.isBlank()) {
            throw IllegalStateException("El teléfono no devolvió una sesión válida")
        }
        prefs.edit().putString("mobile_remote_token", newToken).apply()
        return result
    }

    fun forget() {
        prefs.edit().remove("mobile_remote_host").remove("mobile_remote_token").apply()
    }

    fun ping(): JSONObject = get("/ping")
    fun sendTask(task: String): JSONObject =
        get("/remote?task=${URLEncoder.encode(task, "UTF-8")}", auth = true)
    fun unreadMessages(): JSONObject = get("/unread", auth = true)
    fun agenda(): JSONObject = get("/agenda", auth = true)
    fun permissions(): JSONObject = get("/permissions", auth = true)
    fun incomingCall(): JSONObject = get("/incoming-call", auth = true)
    fun callAction(action: String): JSONObject =
        get("/incoming-call-action?action=${URLEncoder.encode(action, "UTF-8")}", auth = true)

    private fun get(path: String, auth: Boolean = false): JSONObject {
        val c = (URL(baseUrl() + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5000
            readTimeout = 12000
            setRequestProperty("Accept", "application/json")
            if (auth) setRequestProperty("Authorization", "Bearer ${token()}")
        }
        val code = c.responseCode
        val raw = (if (code in 200..299) c.inputStream else c.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            val server = runCatching { JSONObject(raw).optString("error") }.getOrNull().orEmpty()
            throw IllegalStateException("Teléfono HTTP $code: ${server.ifBlank { raw.take(160) }}")
        }
        return runCatching { JSONObject(raw) }.getOrElse { JSONObject().put("raw", raw) }
    }
}
