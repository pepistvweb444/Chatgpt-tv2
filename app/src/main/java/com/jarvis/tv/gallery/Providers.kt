package com.jarvis.tv.gallery

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.UUID

data class GPhoto(val title: String, val thumbUrl: String, val fullUrl: String, val headers: Map<String, String> = emptyMap())

/** Pending device/QR login shown on the TV. */
data class DeviceLogin(val qrUrl: String, val userCode: String, val verificationUrl: String, val deviceCode: String, val intervalSec: Int, val expiresAt: Long)

class Store(ctx: Context) {
    val p = ctx.getSharedPreferences("jarvis_gallery", Context.MODE_PRIVATE)
    fun get(k: String) = p.getString(k, "").orEmpty()
    fun put(vararg kv: Pair<String, String?>) { val e = p.edit(); kv.forEach { (k, v) -> if (v == null) e.remove(k) else e.putString(k, v) }; e.apply() }
}

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

/* ---------------- Jarvis Mobile over LAN (no login) ---------------- */
class LanProvider(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
    fun host(): String = prefs.getString("mobile_remote_host", "").orEmpty().trim().removePrefix("http://").removePrefix("https://").trimEnd('/').substringBefore(':')
    fun token(): String = prefs.getString("mobile_remote_token", "").orEmpty().trim()
    fun configured() = host().isNotBlank() && token().isNotBlank()
    fun list(limit: Int = 300): List<GPhoto> {
        if (!configured()) throw IllegalStateException("Empareja primero el móvil en Javistv (IP + PIN) y abre 'Jarvis Galería' en el móvil.")
        val base = "http://${host()}:8766"; val t = enc(token())
        val arr = Http.json("$base/photos?limit=$limit&token=$t").optJSONArray("photos") ?: JSONArray()
        return (0 until arr.length()).map { val o = arr.getJSONObject(it); val id = o.optLong("id")
            GPhoto(o.optString("name"), "$base/photo?id=$id&w=400&token=$t", "$base/photo?id=$id&w=1920&token=$t") }
    }
}

/* ---------------- Google Photos Picker API (device flow + QR) ---------------- */
class GoogleProvider(private val cfg: GalleryConfig, private val store: Store) {
    companion object { const val SCOPE = "https://www.googleapis.com/auth/photospicker.mediaitems.readonly" }
    fun signedIn() = store.get("g_refresh").isNotBlank()
    fun signOut() = store.put("g_refresh" to null, "g_access" to null, "g_exp" to null, "g_session" to null)

    fun startLogin(): DeviceLogin {
        if (!cfg.googleReady) throw IllegalStateException("Falta googleClientId/googleClientSecret en ${GalleryConfig.FILE_NAME}")
        val r = Http.json("https://oauth2.googleapis.com/device/code", "POST", Http.form("client_id" to cfg.googleClientId, "scope" to SCOPE), "application/x-www-form-urlencoded")
        val url = r.optString("verification_url", "https://www.google.com/device")
        return DeviceLogin(url, r.optString("user_code"), url, r.getString("device_code"), r.optInt("interval", 5), System.currentTimeMillis() + r.optLong("expires_in", 1800) * 1000)
    }

    /** false = still pending; true = done. Throws on denial/expiry. */
    fun poll(d: DeviceLogin): Boolean {
        return try {
            val r = Http.json("https://oauth2.googleapis.com/token", "POST", Http.form("client_id" to cfg.googleClientId, "client_secret" to cfg.googleClientSecret, "device_code" to d.deviceCode, "grant_type" to "urn:ietf:params:oauth:grant-type:device_code"), "application/x-www-form-urlencoded")
            saveTokens(r); true
        } catch (e: HttpError) {
            when (e.json().optString("error")) { "authorization_pending", "slow_down" -> false; else -> throw IllegalStateException("Google: " + e.json().optString("error", e.message ?: "")) }
        }
    }

    private fun saveTokens(r: JSONObject) {
        store.put("g_access" to r.optString("access_token"), "g_exp" to (System.currentTimeMillis() + r.optLong("expires_in", 3600) * 1000 - 60000).toString())
        r.optString("refresh_token").takeIf { it.isNotBlank() }?.let { store.put("g_refresh" to it) }
    }

    fun access(): String {
        if (store.get("g_access").isNotBlank() && (store.get("g_exp").toLongOrNull() ?: 0) > System.currentTimeMillis()) return store.get("g_access")
        val r = Http.json("https://oauth2.googleapis.com/token", "POST", Http.form("client_id" to cfg.googleClientId, "client_secret" to cfg.googleClientSecret, "refresh_token" to store.get("g_refresh"), "grant_type" to "refresh_token"), "application/x-www-form-urlencoded")
        saveTokens(r); return store.get("g_access")
    }
    private fun auth() = mapOf("Authorization" to "Bearer ${access()}")

    /** Creates a picking session; returns pickerUri to show as QR. */
    fun newSession(): String {
        val old = store.get("g_session")
        if (old.isNotBlank()) runCatching { Http.request("https://photospicker.googleapis.com/v1/sessions/$old", "DELETE", headers = auth()) }
        val r = Http.json("https://photospicker.googleapis.com/v1/sessions?requestId=${UUID.randomUUID()}", "POST", "{}", headers = auth())
        store.put("g_session" to r.getString("id"))
        return r.getString("pickerUri")
    }
    fun hasSession() = store.get("g_session").isNotBlank()
    fun sessionDone(): Boolean = Http.json("https://photospicker.googleapis.com/v1/sessions/${store.get("g_session")}", headers = auth()).optBoolean("mediaItemsSet")

    fun list(): List<GPhoto> {
        val sid = store.get("g_session"); if (sid.isBlank()) return emptyList()
        val out = mutableListOf<GPhoto>(); var page = ""
        do {
            val r = Http.json("https://photospicker.googleapis.com/v1/mediaItems?sessionId=${enc(sid)}&pageSize=100" + (if (page.isNotBlank()) "&pageToken=${enc(page)}" else ""), headers = auth())
            val a = r.optJSONArray("mediaItems") ?: JSONArray()
            for (i in 0 until a.length()) {
                val m = a.getJSONObject(i); if (m.optString("type") == "VIDEO") continue
                val f = m.optJSONObject("mediaFile") ?: continue; val b = f.optString("baseUrl")
                out += GPhoto(f.optString("filename"), "$b=w400-h400-c", "$b=w1920-h1080", auth())
            }
            page = r.optString("nextPageToken")
        } while (page.isNotBlank())
        return out
    }
}

/* ---------------- Microsoft OneDrive (Samsung Gallery sync) via device code ---------------- */
class MicrosoftProvider(private val cfg: GalleryConfig, private val store: Store) {
    private val scope = "Files.Read User.Read offline_access"
    private val base get() = "https://login.microsoftonline.com/${cfg.microsoftTenant}/oauth2/v2.0"
    fun signedIn() = store.get("ms_refresh").isNotBlank()
    fun signOut() = store.put("ms_refresh" to null, "ms_access" to null, "ms_exp" to null)

    fun startLogin(): DeviceLogin {
        if (!cfg.microsoftReady) throw IllegalStateException("Falta microsoftClientId en ${GalleryConfig.FILE_NAME}")
        val r = Http.json("$base/devicecode", "POST", Http.form("client_id" to cfg.microsoftClientId, "scope" to scope), "application/x-www-form-urlencoded")
        val url = r.optString("verification_uri", "https://microsoft.com/devicelogin")
        return DeviceLogin(url, r.optString("user_code"), url, r.getString("device_code"), r.optInt("interval", 5), System.currentTimeMillis() + r.optLong("expires_in", 900) * 1000)
    }

    fun poll(d: DeviceLogin): Boolean = try {
        saveTokens(Http.json("$base/token", "POST", Http.form("client_id" to cfg.microsoftClientId, "device_code" to d.deviceCode, "grant_type" to "urn:ietf:params:oauth:grant-type:device_code"), "application/x-www-form-urlencoded")); true
    } catch (e: HttpError) {
        when (e.json().optString("error")) { "authorization_pending", "slow_down" -> false; else -> throw IllegalStateException("Microsoft: " + e.json().optString("error_description", e.message ?: "").take(160)) }
    }

    private fun saveTokens(r: JSONObject) {
        store.put("ms_access" to r.optString("access_token"), "ms_exp" to (System.currentTimeMillis() + r.optLong("expires_in", 3600) * 1000 - 60000).toString())
        r.optString("refresh_token").takeIf { it.isNotBlank() }?.let { store.put("ms_refresh" to it) }
    }
    private fun access(): String {
        if (store.get("ms_access").isNotBlank() && (store.get("ms_exp").toLongOrNull() ?: 0) > System.currentTimeMillis()) return store.get("ms_access")
        saveTokens(Http.json("$base/token", "POST", Http.form("client_id" to cfg.microsoftClientId, "refresh_token" to store.get("ms_refresh"), "grant_type" to "refresh_token", "scope" to scope), "application/x-www-form-urlencoded"))
        return store.get("ms_access")
    }

    fun list(limit: Int = 400): List<GPhoto> {
        val h = mapOf("Authorization" to "Bearer ${access()}")
        val out = mutableListOf<GPhoto>(); val seen = HashSet<String>()
        fun walk(url: String, depth: Int) {
            var next: String? = url
            while (next != null && out.size < limit) {
                val r = try { Http.json(next, headers = h) } catch (e: HttpError) { if (e.code == 404) return else throw e }
                val a = r.optJSONArray("value") ?: JSONArray()
                for (i in 0 until a.length()) {
                    val it = a.getJSONObject(i); val id = it.optString("id")
                    if (it.has("folder") && depth < 2) { walk("https://graph.microsoft.com/v1.0/me/drive/items/$id/children?\$top=200&\$expand=thumbnails", depth + 1); continue }
                    val mime = it.optJSONObject("file")?.optString("mimeType").orEmpty()
                    if (!mime.startsWith("image/") || !seen.add(id)) continue
                    val th = it.optJSONArray("thumbnails")?.optJSONObject(0)
                    val thumb = th?.optJSONObject("medium")?.optString("url") ?: "https://graph.microsoft.com/v1.0/me/drive/items/$id/thumbnails/0/medium/content"
                    val full = "https://graph.microsoft.com/v1.0/me/drive/items/$id/thumbnails/0/c1920x1080/content"
                    out += GPhoto(it.optString("name"), thumb, full, h)
                }
                next = r.optString("@odata.nextLink").takeIf { it.isNotBlank() }
            }
        }
        for (f in cfg.oneDriveFolders) walk("https://graph.microsoft.com/v1.0/me/drive/${f.trim('/')}/children?\$top=200&\$expand=thumbnails", 0)
        return out
    }
}

/* ---------------- Jarvis account: email + password (Supabase Auth + Storage) ---------------- */
class SupabaseProvider(private val cfg: GalleryConfig, private val store: Store) {
    private fun h(bearer: String = cfg.supabaseAnonKey) = mapOf("apikey" to cfg.supabaseAnonKey, "Authorization" to "Bearer $bearer")
    fun signedIn() = store.get("sb_refresh").isNotBlank()
    fun email() = store.get("sb_email")
    fun signOut() = store.put("sb_refresh" to null, "sb_access" to null, "sb_exp" to null, "sb_uid" to null, "sb_email" to null)
    private fun check() { if (!cfg.supabaseReady) throw IllegalStateException("Falta supabaseUrl/supabaseAnonKey en ${GalleryConfig.FILE_NAME}") }

    private fun save(r: JSONObject) {
        store.put("sb_access" to r.optString("access_token"), "sb_refresh" to r.optString("refresh_token"),
            "sb_exp" to (System.currentTimeMillis() + r.optLong("expires_in", 3600) * 1000 - 60000).toString(),
            "sb_uid" to r.optJSONObject("user")?.optString("id"), "sb_email" to r.optJSONObject("user")?.optString("email"))
    }

    fun signIn(email: String, password: String) {
        check()
        try { save(Http.json("${cfg.supabaseUrl}/auth/v1/token?grant_type=password", "POST", JSONObject().put("email", email).put("password", password).toString(), headers = h())) }
        catch (e: HttpError) { throw IllegalStateException(e.json().optString("error_description", e.json().optString("msg", "Credenciales incorrectas"))) }
    }

    /** Returns a message; if email confirmation is enabled the user must confirm first. */
    fun signUp(email: String, password: String): String {
        check()
        val r = try { Http.json("${cfg.supabaseUrl}/auth/v1/signup", "POST", JSONObject().put("email", email).put("password", password).toString(), headers = h()) }
        catch (e: HttpError) { throw IllegalStateException(e.json().optString("msg", e.json().optString("error_description", e.message ?: ""))) }
        return if (r.has("access_token")) { save(r); "Cuenta creada y sesión iniciada" } else "Cuenta creada. Revisa tu email para confirmarla y luego inicia sesión."
    }

    private fun access(): String {
        if ((store.get("sb_exp").toLongOrNull() ?: 0) > System.currentTimeMillis()) return store.get("sb_access")
        save(Http.json("${cfg.supabaseUrl}/auth/v1/token?grant_type=refresh_token", "POST", JSONObject().put("refresh_token", store.get("sb_refresh")).toString(), headers = h()))
        return store.get("sb_access")
    }

    /** Lists images in bucket <supabaseBucket>/<user id>/ (uploaded from the phone or the web). */
    fun list(): List<GPhoto> {
        check()
        val tok = access(); val uid = store.get("sb_uid"); val auth = h(tok)
        val r = Http.request("${cfg.supabaseUrl}/storage/v1/object/list/${cfg.supabaseBucket}", "POST",
            JSONObject().put("prefix", "$uid/").put("limit", 500).put("sortBy", JSONObject().put("column", "created_at").put("order", "desc")).toString(), headers = auth)
        val a = JSONArray(r)
        return (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("name").matches(Regex("(?i).+\\.(jpe?g|png|webp|heic|gif)$")) }.map {
            val path = "${cfg.supabaseBucket}/$uid/${it.optString("name")}"
            GPhoto(it.optString("name"), "${cfg.supabaseUrl}/storage/v1/object/authenticated/$path", "${cfg.supabaseUrl}/storage/v1/object/authenticated/$path", auth)
        }
    }
}
