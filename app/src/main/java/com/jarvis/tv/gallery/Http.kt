package com.jarvis.tv.gallery

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class HttpError(val code: Int, val body: String) : Exception("HTTP $code: ${body.take(200)}") {
    fun json(): JSONObject = runCatching { JSONObject(body) }.getOrElse { JSONObject() }
}

object Http {
    fun form(vararg p: Pair<String, String>) = p.joinToString("&") { URLEncoder.encode(it.first, "UTF-8") + "=" + URLEncoder.encode(it.second, "UTF-8") }

    fun request(url: String, method: String = "GET", body: String? = null, contentType: String = "application/json", headers: Map<String, String> = emptyMap()): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method; connectTimeout = 10000; readTimeout = 20000
            setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            if (body != null) { doOutput = true; setRequestProperty("Content-Type", contentType); outputStream.use { it.write(body.toByteArray()) } }
        }
        val code = c.responseCode
        val raw = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) throw HttpError(code, raw)
        return raw
    }

    fun json(url: String, method: String = "GET", body: String? = null, contentType: String = "application/json", headers: Map<String, String> = emptyMap()): JSONObject =
        request(url, method, body, contentType, headers).let { if (it.isBlank()) JSONObject() else JSONObject(it) }

    fun bytes(url: String, headers: Map<String, String> = emptyMap()): ByteArray {
        var target = url; var hops = 0
        while (true) {
            val c = (URL(target).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000; readTimeout = 30000; instanceFollowRedirects = false
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            val code = c.responseCode
            if (code in 300..399 && hops < 5) { target = c.getHeaderField("Location") ?: throw HttpError(code, "redirect"); hops++; continue }
            if (code !in 200..299) throw HttpError(code, c.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty())
            return c.inputStream.use { it.readBytes() }
        }
    }
}
