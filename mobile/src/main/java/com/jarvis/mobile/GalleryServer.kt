package com.jarvis.mobile

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.URLDecoder

/** Jarvis Galería: serves the phone photo gallery to the TV on port 8766 (same token as the 8765 bridge). */
object GalleryServer {
    const val PORT = 8766
    @Volatile private var server: ServerSocket? = null

    fun permission(): String = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
    fun granted(ctx: Context) = ContextCompat.checkSelfPermission(ctx, permission()) == PackageManager.PERMISSION_GRANTED

    @Synchronized fun start(ctx: Context) {
        if (server != null) return
        val app = ctx.applicationContext
        Thread {
            try {
                val ss = ServerSocket(PORT); server = ss
                while (!ss.isClosed) { val s = ss.accept(); Thread { runCatching { serveOne(app, s) }; runCatching { s.close() } }.start() }
            } catch (_: Throwable) { server = null }
        }.start()
    }

    fun stop() { runCatching { server?.close() }; server = null }

    private fun q(path: String, key: String, def: String = ""): String {
        val qs = path.substringAfter('?', "")
        for (p in qs.split('&')) { val k = p.substringBefore('='); if (k == key) return URLDecoder.decode(p.substringAfter('=', ""), "UTF-8") }
        return def
    }

    private fun authorized(ctx: Context, headers: Map<String, String>, path: String): Boolean {
        val prefs = ctx.getSharedPreferences("jarvis_mobile", Context.MODE_PRIVATE)
        val expected = prefs.getString("remote_token", "").orEmpty()
        val bearer = headers["authorization"].orEmpty().removePrefix("Bearer ").trim()
        return expected.isNotBlank() && (bearer == expected || q(path, "token") == expected)
    }

    private fun serveOne(ctx: Context, s: java.net.Socket) {
        s.soTimeout = 15000
        val reader = BufferedReader(InputStreamReader(s.getInputStream()))
        val first = reader.readLine().orEmpty()
        val headers = mutableMapOf<String, String>()
        while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break; val c = line.indexOf(':'); if (c > 0) headers[line.substring(0, c).trim().lowercase()] = line.substring(c + 1).trim() }
        val method = first.substringBefore(' ')
        val path = first.split(" ").getOrNull(1).orEmpty()
        val out = s.getOutputStream()
        fun send(code: Int, type: String, body: ByteArray, cache: Boolean = false) {
            val h = "HTTP/1.1 $code ${if (code == 200) "OK" else "ERROR"}\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Headers: Authorization, Content-Type\r\nCache-Control: ${if (cache) "max-age=86400" else "no-store"}\r\nConnection: close\r\n\r\n"
            out.write(h.toByteArray()); if (method != "HEAD") out.write(body); out.flush()
        }
        fun json(code: Int, o: Any) = send(code, "application/json; charset=utf-8", o.toString().toByteArray())
        if (method == "OPTIONS") return send(204, "text/plain", ByteArray(0))
        if (path.startsWith("/ping")) return json(200, JSONObject().put("ok", true).put("device", "jarvis-gallery").put("permission", granted(ctx)))
        if (!authorized(ctx, headers, path)) return json(401, JSONObject().put("error", "unauthorized"))
        if (!granted(ctx)) return json(403, JSONObject().put("error", "gallery-permission-required").put("hint", "Abre 'Jarvis Galería' en el móvil y concede acceso a fotos"))
        try {
            when {
                path.startsWith("/albums") -> json(200, albums(ctx))
                path.startsWith("/photos") -> json(200, photos(ctx, q(path, "album"), q(path, "offset", "0").toIntOrNull() ?: 0, (q(path, "limit", "60").toIntOrNull() ?: 60).coerceIn(1, 500)))
                path.startsWith("/photo?") -> {
                    val id = q(path, "id").toLongOrNull() ?: return json(400, JSONObject().put("error", "id-required"))
                    val w = (q(path, "w", "1920").toIntOrNull() ?: 1920).coerceIn(64, 3840)
                    send(200, "image/jpeg", jpeg(ctx, id, w), cache = true)
                }
                else -> json(404, JSONObject().put("error", "not-found"))
            }
        } catch (e: Throwable) { json(500, JSONObject().put("error", e.message ?: e.javaClass.simpleName)) }
    }

    private val COLL = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

    private fun albums(ctx: Context): JSONObject {
        val counts = linkedMapOf<String, Pair<String, Int>>()
        val cover = mutableMapOf<String, Long>()
        var total = 0
        ctx.contentResolver.query(COLL, arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.BUCKET_ID, MediaStore.Images.Media.BUCKET_DISPLAY_NAME), null, null, MediaStore.Images.Media.DATE_TAKEN + " DESC")?.use { c ->
            while (c.moveToNext()) {
                total++
                val b = c.getString(1) ?: continue
                val p = counts[b]; counts[b] = Pair(c.getString(2) ?: "Sin nombre", (p?.second ?: 0) + 1)
                if (!cover.containsKey(b)) cover[b] = c.getLong(0)
            }
        }
        val arr = JSONArray()
        counts.entries.sortedByDescending { it.value.second }.forEach { (b, v) -> arr.put(JSONObject().put("id", b).put("name", v.first).put("count", v.second).put("coverId", cover[b])) }
        return JSONObject().put("ok", true).put("total", total).put("albums", arr)
    }

    private fun photos(ctx: Context, album: String, offset: Int, limit: Int): JSONObject {
        val sel = if (album.isNotBlank()) MediaStore.Images.Media.BUCKET_ID + "=?" else null
        val args = if (album.isNotBlank()) arrayOf(album) else null
        val arr = JSONArray(); var total = 0
        ctx.contentResolver.query(COLL, arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.BUCKET_DISPLAY_NAME, MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.ORIENTATION), sel, args, MediaStore.Images.Media.DATE_TAKEN + " DESC, " + MediaStore.Images.Media.DATE_ADDED + " DESC")?.use { c ->
            total = c.count
            if (offset < total && c.moveToPosition(offset)) {
                do {
                    val taken = c.getLong(1).takeIf { it > 0 } ?: (c.getLong(2) * 1000)
                    val rot = c.getInt(7); var w = c.getInt(4); var h = c.getInt(5); if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
                    arr.put(JSONObject().put("id", c.getLong(0)).put("date", taken).put("album", c.getString(3) ?: "").put("width", w).put("height", h).put("name", c.getString(6) ?: ""))
                } while (arr.length() < limit && c.moveToNext())
            }
        }
        return JSONObject().put("ok", true).put("total", total).put("offset", offset).put("photos", arr)
    }

    private fun jpeg(ctx: Context, id: Long, maxSide: Int): ByteArray {
        val uri = ContentUris.withAppendedId(COLL, id)
        val bmp: Bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { dec, info, _ ->
            val w = info.size.width; val h = info.size.height; val m = maxOf(w, h)
            if (m > maxSide) { val f = maxSide.toFloat() / m; dec.setTargetSize(maxOf(1, (w * f).toInt()), maxOf(1, (h * f).toInt())) }
            dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val bo = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, if (maxSide <= 400) 78 else 88, bo); bmp.recycle()
        return bo.toByteArray()
    }
}

/** Launcher entry "Jarvis Galería": asks for photo permission and starts the gallery server. */
class GalleryPermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = TextView(this).apply { textSize = 18f; setPadding(48, 96, 48, 48) }
        setContentView(tv)
        GalleryServer.start(this)
        if (!GalleryServer.granted(this)) ActivityCompat.requestPermissions(this, arrayOf(GalleryServer.permission()), 4711)
        render(tv)
    }
    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) { super.onRequestPermissionsResult(rc, p, r); (window.decorView.findViewById<android.view.View>(android.R.id.content) as? android.view.ViewGroup)?.getChildAt(0)?.let { render(it as TextView) } }
    private fun render(tv: TextView) {
        tv.text = if (GalleryServer.granted(this)) "Jarvis Galería activa.\n\nTu galería ya se puede ver en la TV (app Jarvis Galería).\nEl móvil y la TV deben estar en la misma Wi-Fi y el puente de Jarvis activo."
        else "Jarvis necesita acceso a tus fotos para mostrarlas en la TV.\nConcede el permiso 'Fotos' (Permitir todo)."
    }
}
