package com.jarvis.tv.gallery

import android.content.Context
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Login/client configuration for Jarvis Galería, read from a JSON file on the TV.
 * Primary path:  /sdcard/Android/data/com.jarvis.tv/files/gallery-config.json
 * Fallback path: /sdcard/Download/jarvis-gallery-config.json
 * Nothing here is invented: if an ID is missing the corresponding login is shown as "not configured".
 */
data class GalleryConfig(
    val googleClientId: String,
    val googleClientSecret: String,
    val microsoftClientId: String,
    val microsoftTenant: String,
    val oneDriveFolders: List<String>,
    val supabaseUrl: String,
    val supabaseAnonKey: String,
    val supabaseBucket: String,
    val source: String,
) {
    val googleReady get() = googleClientId.isNotBlank() && googleClientSecret.isNotBlank()
    val microsoftReady get() = microsoftClientId.isNotBlank()
    val supabaseReady get() = supabaseUrl.startsWith("https://") && supabaseAnonKey.isNotBlank()

    companion object {
        const val FILE_NAME = "gallery-config.json"
        val DEFAULT_ONEDRIVE = listOf("special/cameraroll", "root:/Pictures/Samsung Gallery:", "root:/Imágenes/Samsung Gallery:")

        fun candidates(ctx: Context): List<File> = listOfNotNull(
            ctx.getExternalFilesDir(null)?.let { File(it, FILE_NAME) },
            runCatching { File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "jarvis-gallery-config.json") }.getOrNull(),
        )

        fun load(ctx: Context): GalleryConfig {
            var json = JSONObject(); var src = "(sin fichero)"
            for (f in candidates(ctx)) {
                val parsed = runCatching { if (f.isFile) JSONObject(f.readText()) else null }.getOrNull()
                if (parsed != null) { json = parsed; src = f.absolutePath; break }
            }
            val folders = json.optJSONArray("oneDriveFolders")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } } ?: DEFAULT_ONEDRIVE
            return GalleryConfig(
                googleClientId = json.optString("googleClientId").trim(),
                googleClientSecret = json.optString("googleClientSecret").trim(),
                microsoftClientId = json.optString("microsoftClientId").trim(),
                microsoftTenant = json.optString("microsoftTenant", "consumers").trim().ifBlank { "consumers" },
                oneDriveFolders = folders,
                supabaseUrl = json.optString("supabaseUrl").trim().trimEnd('/'),
                supabaseAnonKey = json.optString("supabaseAnonKey").trim(),
                supabaseBucket = json.optString("supabaseBucket", "jarvis-gallery").trim().ifBlank { "jarvis-gallery" },
                source = src,
            )
        }

        /** Writes an empty template the first time so the user can see the exact path/keys. */
        fun writeTemplateIfMissing(ctx: Context) {
            val f = ctx.getExternalFilesDir(null)?.let { File(it, FILE_NAME) } ?: return
            if (f.exists()) return
            runCatching {
                f.writeText(JSONObject()
                    .put("googleClientId", "").put("googleClientSecret", "")
                    .put("microsoftClientId", "").put("microsoftTenant", "consumers")
                    .put("oneDriveFolders", JSONArray(DEFAULT_ONEDRIVE))
                    .put("supabaseUrl", "").put("supabaseAnonKey", "").put("supabaseBucket", "jarvis-gallery")
                    .toString(2))
            }
        }
    }
}
