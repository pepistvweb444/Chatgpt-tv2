package com.jarvis.tv

import android.app.Activity
import android.app.AlertDialog
import android.graphics.BitmapFactory
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL

/** Stores structured media returned by the Jarvis backend and renders it natively on TV. */
object MediaResponseStore {
    @Volatile private var pending: JSONObject? = null

    fun capture(response: JSONObject) {
        val media = JSONObject()
        copy(response, media, "image_url", "imageUrl", "thumbnail", "image")
        copy(response, media, "video_url", "videoUrl", "youtube_url", "youtubeUrl", "video")
        response.optJSONArray("media")?.let { media.put("media", it) }
        response.optJSONArray("images")?.let { media.put("images", it) }
        response.optJSONArray("videos")?.let { media.put("videos", it) }
        response.optJSONArray("news")?.let { media.put("news", it) }
        if (media.length() > 0) pending = media
    }

    fun take(): JSONObject? = pending.also { pending = null }

    private fun copy(source: JSONObject, target: JSONObject, vararg keys: String) {
        for (key in keys) if (source.has(key) && !source.isNull(key)) target.put(key, source.opt(key))
    }
}

object MediaViewer {
    fun showPending(activity: Activity) {
        val payload = MediaResponseStore.take() ?: return
        val urls = linkedSetOf<String>()
        collectUrls(payload, urls)
        val image = urls.firstOrNull { looksLikeImage(it) }
        val video = urls.firstOrNull { looksLikeVideo(it) }
        if (image == null && video == null) return

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
        }
        root.addView(TextView(activity).apply {
            text = "Contenido relacionado"
            textSize = 20f
        })

        image?.let { imageUrl ->
            val view = ImageView(activity).apply {
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 360)
            }
            root.addView(view)
            Thread {
                runCatching { BitmapFactory.decodeStream(URL(imageUrl).openStream()) }.getOrNull()?.let { bitmap ->
                    activity.runOnUiThread { view.setImageBitmap(bitmap) }
                }
            }.start()
        }

        video?.let { videoUrl ->
            val web = WebView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 420)
                settings.javaScriptEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                webChromeClient = WebChromeClient()
                webViewClient = WebViewClient()
            }
            root.addView(web)
            val embed = youtubeEmbed(videoUrl)
            val html = if (embed != null) {
                """<html><body style='margin:0;background:#000'><iframe width='100%' height='100%' src='$embed?autoplay=0&rel=0' frameborder='0' allow='accelerometer; autoplay; encrypted-media; gyroscope; picture-in-picture' allowfullscreen></iframe></body></html>"""
            } else {
                """<html><body style='margin:0;background:#000'><video width='100%' height='100%' controls src='${escape(videoUrl)}'></video></body></html>"""
            }
            web.loadDataWithBaseURL("https://www.youtube.com", html, "text/html", "UTF-8", null)
        }

        AlertDialog.Builder(activity)
            .setView(root)
            .setPositiveButton("Cerrar", null)
            .show()
    }

    private fun collectUrls(value: Any?, out: MutableSet<String>, depth: Int = 0) {
        if (value == null || depth > 5 || out.size >= 12) return
        when (value) {
            is JSONObject -> value.keys().forEach { key -> collectUrls(value.opt(key), out, depth + 1) }
            is JSONArray -> for (i in 0 until value.length()) collectUrls(value.opt(i), out, depth + 1)
            is String -> if (value.startsWith("http://") || value.startsWith("https://")) out.add(value)
        }
    }

    private fun looksLikeImage(url: String): Boolean {
        val u = url.lowercase()
        return listOf(".jpg", ".jpeg", ".png", ".webp", ".gif", "image").any { u.contains(it) } && !looksLikeVideo(url)
    }

    private fun looksLikeVideo(url: String): Boolean {
        val u = url.lowercase()
        return u.contains("youtube.com/") || u.contains("youtu.be/") || u.contains(".mp4") || u.contains(".webm") || u.contains("video")
    }

    private fun youtubeEmbed(url: String): String? {
        val id = when {
            url.contains("youtu.be/") -> url.substringAfter("youtu.be/").substringBefore('?').substringBefore('&')
            url.contains("youtube.com/watch") -> url.substringAfter("v=").substringBefore('&')
            url.contains("youtube.com/shorts/") -> url.substringAfter("/shorts/").substringBefore('?')
            url.contains("youtube.com/embed/") -> url.substringAfter("/embed/").substringBefore('?')
            else -> ""
        }.trim()
        return id.takeIf { it.matches(Regex("[A-Za-z0-9_-]{6,}")) }?.let { "https://www.youtube.com/embed/$it" }
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("'", "&#39;").replace("\"", "&quot;")
}
