package com.jarvis.mobile

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.jarvis.logic.IdentityHit
import com.jarvis.logic.IdentityOrigin
import com.jarvis.logic.chooseIdentity
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object CallerEnricher {
    private const val BACKEND = "https://chatgpt-tv2.vercel.app"

    fun enrich(context: Context) {
        val call = CallStateStore.current(context)
        if (!call.optBoolean("active", false)) return
        val id = call.optString("id")
        val number = call.optString("number")
        val hits = mutableListOf<IdentityHit>()
        val contact = if (number.isNotBlank()) PhoneContactLookup.find(context, number) else null
        if (contact != null) {
            hits += IdentityHit(IdentityOrigin.PHONE, contact.name, "Contacto", knownContact = true)
        }
        val appName = call.optString("appName").ifBlank { call.optString("name") }
        val appSource = call.optString("app").ifBlank { "App" }
        if (call.optString("source") == "voip" && appName.isNotBlank() && contact == null) {
            hits += IdentityHit(IdentityOrigin.APP, appName, appSource, photoUrl = call.optString("photoData").ifBlank { null })
        }
        var downloaded: ByteArray? = null
        val needsPublicIdentity = contact == null && number.isNotBlank()
        val needsPhotoOnly = contact != null && contact.photoUri.isBlank() && number.isNotBlank()
        if (needsPublicIdentity || needsPhotoOnly) {
            val looked = lookup(number)
            if (looked != null) {
                val origin = if (looked.optString("origin") == "maps") IdentityOrigin.MAPS else IdentityOrigin.WEB
                val label = looked.optString("source").ifBlank { if (origin == IdentityOrigin.MAPS) "Google Maps" else "Web" }
                if (needsPublicIdentity && looked.optString("name").isNotBlank()) {
                    hits += IdentityHit(
                        origin = origin,
                        name = looked.optString("name"),
                        sourceLabel = label,
                        ratingText = looked.optString("rating").ifBlank { null },
                        reviewText = looked.optString("reviews").ifBlank { null },
                        address = looked.optString("address").ifBlank { null }
                    )
                }
                val encoded = looked.optString("photoBase64")
                if (encoded.isNotBlank()) downloaded = runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrNull()
            }
        }
        val best = chooseIdentity(hits)
        val photo = when {
            contact != null && contact.photoUri.isNotBlank() -> CallStateStore.uriToBase64(context, contact.photoUri)
            call.optString("photoData").isNotBlank() -> call.optString("photoData")
            downloaded != null -> Base64.encodeToString(downloaded, Base64.NO_WRAP)
            else -> ContactPhotoStore.cached(context, number)?.let { Base64.encodeToString(it, Base64.NO_WRAP) }.orEmpty()
        }
        if (downloaded != null) ContactPhotoStore.cache(context, number, downloaded)
        if (downloaded != null) ContactPhotoStore.saveIfEmpty(context, contact, downloaded)
        CallStateStore.update(context, id) {
            if (best != null) {
                it.put("name", best.name)
                it.put("knownContact", best.knownContact)
                it.put("sourceLabel", best.sourceLabel)
                it.put("classification", if (best.knownContact) "contact" else "identified")
                best.ratingText?.let { rating -> it.put("ratingText", rating) }
                best.reviewText?.let { reviews -> it.put("reviewText", reviews) }
                best.address?.let { address -> it.put("address", address) }
                if (!best.knownContact) it.put("publicLabel", best.name).put("publicSource", best.sourceLabel)
            } else if (contact == null) {
                it.put("classification", "unknown")
                it.put("sourceLabel", "búsqueda pública")
            }
            if (photo.isNotBlank()) it.put("photoData", photo)
            it.put("updatedAt", System.currentTimeMillis())
        }
    }

    private fun lookup(number: String): JSONObject? {
        val primary = get("$BACKEND/api/caller-lookup?number=${Uri.encode(number)}")
        if (primary != null && primary.optString("name").isNotBlank()) return primary
        val legacy = get("$BACKEND/api/caller-reputation?number=${Uri.encode(number)}") ?: return primary
        val match = legacy.optJSONObject("publicMatch") ?: return primary
        val name = match.optString("label")
        if (name.isBlank()) return primary
        return JSONObject()
            .put("name", name)
            .put("source", match.optString("source").ifBlank { "Web" })
            .put("origin", "web")
    }

    private fun get(url: String): JSONObject? = runCatching {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 4000
            readTimeout = 7000
            setRequestProperty("Accept", "application/json")
        }
        val raw = (if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (connection.responseCode !in 200..299 || raw.isBlank()) null else JSONObject(raw)
    }.getOrNull()
}
