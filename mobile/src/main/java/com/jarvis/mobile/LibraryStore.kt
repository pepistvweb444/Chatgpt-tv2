package com.jarvis.mobile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object LibraryStore {
    private const val PREFS = "jarvis_mobile"
    private const val KEY = "library_items_v1"

    data class Item(
        val id: String,
        val type: String,
        val title: String,
        val uri: String,
        val mime: String,
        val source: String,
        val conversationId: String,
        val createdAt: Long
    )

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(context: Context): List<Item> {
        val arr = runCatching { JSONArray(prefs(context).getString(KEY, "[]")) }.getOrElse { JSONArray() }
        val out = mutableListOf<Item>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out += Item(
                id = o.optString("id"),
                type = o.optString("type", "file"),
                title = o.optString("title", "Archivo"),
                uri = o.optString("uri"),
                mime = o.optString("mime", "*/*"),
                source = o.optString("source", "Jarvis"),
                conversationId = o.optString("conversationId"),
                createdAt = o.optLong("createdAt", 0L)
            )
        }
        return out.sortedByDescending { it.createdAt }
    }

    fun add(
        context: Context,
        type: String,
        title: String,
        uri: String,
        mime: String = "*/*",
        source: String = "Jarvis",
        conversationId: String = ""
    ) {
        if (uri.isBlank()) return
        val arr = runCatching { JSONArray(prefs(context).getString(KEY, "[]")) }.getOrElse { JSONArray() }
        // Avoid duplicate media/file entries while preserving the newest title/source.
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("uri") == uri) {
                o.put("title", title.ifBlank { o.optString("title") })
                o.put("source", source)
                o.put("conversationId", conversationId)
                o.put("createdAt", System.currentTimeMillis())
                prefs(context).edit().putString(KEY, arr.toString()).apply()
                return
            }
        }
        arr.put(JSONObject()
            .put("id", UUID.randomUUID().toString())
            .put("type", type)
            .put("title", title.ifBlank { defaultTitle(type) })
            .put("uri", uri)
            .put("mime", mime)
            .put("source", source)
            .put("conversationId", conversationId)
            .put("createdAt", System.currentTimeMillis()))
        while (arr.length() > 500) arr.remove(0)
        prefs(context).edit().putString(KEY, arr.toString()).apply()
    }

    fun indexMessage(
        context: Context,
        text: String,
        images: List<String>,
        videos: List<String>,
        conversationId: String
    ) {
        images.forEachIndexed { i, u -> add(context, "image", "Imagen ${i + 1}", u, "image/*", "Chat", conversationId) }
        videos.forEachIndexed { i, u -> add(context, "video", "Vídeo ${i + 1}", u, "video/*", "Chat", conversationId) }

        val urls = Regex("https?://[^\\s)\\]}>]+", RegexOption.IGNORE_CASE).findAll(text).map { it.value.trimEnd('.', ',', ';') }.toList()
        urls.forEach { url ->
            val low = url.lowercase()
            when {
                low.endsWith(".pdf") -> add(context, "document", fileName(url, "PDF"), url, "application/pdf", "Chat", conversationId)
                low.endsWith(".docx") || low.endsWith(".doc") -> add(context, "document", fileName(url, "Word"), url, "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "Chat", conversationId)
                low.endsWith(".xlsx") || low.endsWith(".xls") -> add(context, "document", fileName(url, "Hoja de cálculo"), url, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "Chat", conversationId)
                low.endsWith(".pptx") || low.endsWith(".ppt") -> add(context, "document", fileName(url, "Presentación"), url, "application/vnd.openxmlformats-officedocument.presentationml.presentation", "Chat", conversationId)
                low.endsWith(".apk") -> add(context, "apk", fileName(url, "APK"), url, "application/vnd.android.package-archive", "Chat", conversationId)
                low.endsWith(".zip") || low.endsWith(".aab") -> add(context, "project", fileName(url, "Proyecto"), url, "application/octet-stream", "Chat", conversationId)
                low.endsWith(".html") || low.contains("vercel.app") || low.contains("github.io") -> add(context, "web", fileName(url, "Aplicación web"), url, "text/html", "Chat", conversationId)
            }
        }
    }

    private fun fileName(url: String, fallback: String): String {
        val last = url.substringBefore('?').substringAfterLast('/').takeIf { it.isNotBlank() }
        return last ?: fallback
    }

    private fun defaultTitle(type: String) = when (type) {
        "image" -> "Imagen"
        "video" -> "Vídeo"
        "document" -> "Documento"
        "apk" -> "APK"
        "web" -> "Aplicación web"
        "project" -> "Proyecto"
        "mail" -> "Resumen de correo"
        else -> "Archivo"
    }
}
