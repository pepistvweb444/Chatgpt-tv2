package com.jarvis.mobile

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Adapter for the InitTPV / WaVoIP line the user already uses on both phones.
 * The repo does not ship a vendor SDK or a token. The base URL and bearer token
 * come from the user's own session, stored on the device.
 *
 * Expected HTTP contract (the line can be pointed at a gateway that speaks this):
 * POST {base}/v1/calls/{id}/ai
 * POST {base}/v1/calls/{id}/hangup
 * POST {base}/v1/calls/{id}/mute            {"muted":true}
 * POST {base}/v1/calls/{id}/takeover        {"video":false}
 * POST {base}/v1/calls/outbound             {"to":"+34..."}
 * GET  {base}/v1/calls/active
 * GET  {base}/v1/calls/{id}/video-frame     image/jpeg, or 204 when the platform
 *                                           cannot show the caller before answer
 *
 * This client never opens the camera or the microphone.
 */
class InitTpvClient(context: Context) {
    private val prefs = context.getSharedPreferences("jarvis_mobile", Context.MODE_PRIVATE)

    fun configured(): Boolean = base().startsWith("https://") && token().isNotBlank()

    fun base(): String = prefs.getString("inittpv_base_url", "").orEmpty().trim().trimEnd('/')

    fun token(): String = prefs.getString("inittpv_token", "").orEmpty().trim()

    fun displayNumber(): String = prefs.getString("inittpv_caller_id", "").orEmpty().trim()

    fun handToAi(callId: String): Boolean = post("/v1/calls/${enc(callId)}/ai", "{}")

    fun hangup(callId: String): Boolean = post("/v1/calls/${enc(callId)}/hangup", "{}")

    fun mute(callId: String, muted: Boolean): Boolean = post("/v1/calls/${enc(callId)}/mute", """{"muted":$muted}""")

    fun takeover(callId: String, video: Boolean): Boolean = post("/v1/calls/${enc(callId)}/takeover", """{"video":$video}""")

    fun outbound(number: String): Boolean = post("/v1/calls/outbound", JSONObject().put("to", number).toString())

    fun pollActive(): JSONObject? {
        val response = request("GET", "/v1/calls/active", null) ?: return null
        if (response.first !in 200..299) return null
        return runCatching { JSONObject(response.second) }.getOrNull()
    }

    /** Remote JPEG of the caller, if the platform can provide it before we answer. Null means it cannot. */
    fun videoFrame(callId: String): ByteArray? {
        if (!configured() || callId.isBlank()) return null
        return runCatching {
            val connection = open("GET", "/v1/calls/${enc(callId)}/video-frame")
            connection.doInput = true
            val code = connection.responseCode
            if (code == 204 || code !in 200..299) return null
            val type = connection.contentType.orEmpty()
            if (!type.startsWith("image/") && !type.contains("json")) {
                val bytes = connection.inputStream.use { it.readBytes() }
                return bytes.takeIf { it.size in 32..400_000 }
            }
            if (type.contains("json")) {
                val raw = connection.inputStream.bufferedReader().use { it.readText() }
                val encoded = JSONObject(raw).optString("jpegBase64")
                if (encoded.isBlank()) return null
                return Base64.decode(encoded, Base64.DEFAULT).takeIf { it.size in 32..400_000 }
            }
            connection.inputStream.use { it.readBytes() }.takeIf { it.size in 32..400_000 }
        }.getOrNull()
    }

    private fun post(path: String, body: String): Boolean {
        val response = request("POST", path, body) ?: return false
        return response.first in 200..299
    }

    private fun request(method: String, path: String, body: String?): Pair<Int, String>? {
        if (!configured()) return null
        return runCatching {
            val connection = open(method, path)
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            code to text
        }.getOrNull()
    }

    private fun open(method: String, path: String): HttpURLConnection {
        val connection = (URL(base() + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 4000
            readTimeout = 7000
            setRequestProperty("Accept", "application/json, image/jpeg")
            setRequestProperty("Authorization", "Bearer ${token()}")
        }
        return connection
    }

    private fun enc(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")
}
