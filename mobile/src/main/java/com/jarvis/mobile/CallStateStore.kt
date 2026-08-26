package com.jarvis.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream

object CallStateStore {
    private const val PREFS = "jarvis_mobile"
    private const val KEY = "current_call_json"

    fun current(context: Context): JSONObject {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()
        return runCatching { JSONObject(raw) }.getOrElse { JSONObject().put("active", false) }
    }

    fun save(context: Context, call: JSONObject) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, call.toString())
            .putLong("current_call_updated_at", System.currentTimeMillis())
            .apply()
    }

    fun update(context: Context, id: String, mutate: (JSONObject) -> Unit): JSONObject? {
        val current = current(context)
        if (id.isNotBlank() && current.optString("id") != id) return null
        mutate(current)
        save(context, current)
        return current
    }

    fun clear(context: Context, reason: String = "ended") {
        val current = current(context)
        current.put("active", false).put("state", reason).put("updatedAt", System.currentTimeMillis())
        save(context, current)
    }

    fun bitmapToBase64(bitmap: Bitmap?, maxSide: Int = 320): String {
        if (bitmap == null) return ""
        val scale = minOf(1f, maxSide.toFloat() / maxOf(bitmap.width, bitmap.height).coerceAtLeast(1))
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true) else bitmap
        return runCatching {
            ByteArrayOutputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 82, out)
                Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            }
        }.getOrDefault("")
    }

    fun uriToBase64(context: Context, uri: String): String {
        if (uri.isBlank()) return ""
        return runCatching {
            context.contentResolver.openInputStream(Uri.parse(uri))?.use { input ->
                bitmapToBase64(BitmapFactory.decodeStream(input))
            }.orEmpty()
        }.getOrDefault("")
    }

    fun drawableToBase64(drawable: Drawable?): String {
        if (drawable == null) return ""
        val bitmap = if (drawable is BitmapDrawable) drawable.bitmap else {
            val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: 256
            val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: 256
            Bitmap.createBitmap(w.coerceAtMost(512), h.coerceAtMost(512), Bitmap.Config.ARGB_8888).also { b ->
                val c = Canvas(b); drawable.setBounds(0, 0, b.width, b.height); drawable.draw(c)
            }
        }
        return bitmapToBase64(bitmap)
    }
}
