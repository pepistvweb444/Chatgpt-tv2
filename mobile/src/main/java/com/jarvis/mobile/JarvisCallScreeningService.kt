package com.jarvis.mobile

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.CallScreeningService
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class JarvisCallScreeningService : CallScreeningService() {
    override fun onScreenCall(callDetails: Call.Details) {
        val incoming = if (android.os.Build.VERSION.SDK_INT >= 29) callDetails.callDirection == Call.Details.DIRECTION_INCOMING else true
        if (!incoming) return
        val number = callDetails.handle?.schemeSpecificPart.orEmpty()
        // A CallScreeningService must answer Android quickly. Jarvis never blocks the call while doing lookup.
        respondToCall(callDetails, CallResponse.Builder().setDisallowCall(false).setRejectCall(false).setSilenceCall(false).build())

        val id = UUID.randomUUID().toString()
        val contact = lookupContact(number)
        val recent = wasSeenBefore(number)
        val call = JSONObject()
            .put("id", id).put("active", true).put("state", "ringing")
            .put("source", "cellular").put("app", "Teléfono").put("number", number)
            .put("name", contact?.name ?: number.ifBlank { "Número oculto" })
            .put("knownContact", contact != null).put("priority", contact?.priority == true).put("recent", recent)
            .put("classification", if (contact != null) "contact" else "checking")
            .put("spamScore", 0).put("spamSources", "")
            .put("publicLabel", "").put("publicSource", "").put("publicConfidence", "")
            .put("photoData", contact?.photoData.orEmpty()).put("video", false)
            .put("time", System.currentTimeMillis()).put("updatedAt", System.currentTimeMillis())
        CallStateStore.save(this, call)
        appendFeed(call)
        IncomingCallPresenter.show(this, call)

        if (contact == null && number.isNotBlank()) Thread {
            val rep = runCatching { reputation(number) }.getOrNull() ?: run {
                CallStateStore.update(this, id) { it.put("classification", "unknown").put("updatedAt", System.currentTimeMillis()) }?.let { IncomingCallPresenter.show(this, it) }
                return@Thread
            }
            val cls = rep.optString("classification").ifBlank { "unknown" }
            val sources = rep.optJSONArray("sources") ?: JSONArray()
            val sourceText = (0 until sources.length()).map { sources.optString(it) }.filter { it.isNotBlank() }.joinToString(", ")
            val publicMatch = rep.optJSONObject("publicMatch")
            CallStateStore.update(this, id) {
                it.put("classification", cls)
                    .put("spamScore", rep.optInt("score", 0))
                    .put("spamSources", sourceText)
                    .put("publicLabel", publicMatch?.optString("label").orEmpty())
                    .put("publicSource", publicMatch?.optString("source").orEmpty())
                    .put("publicConfidence", publicMatch?.optString("confidence").orEmpty())
                    .put("updatedAt", System.currentTimeMillis())
            }?.let { IncomingCallPresenter.show(this, it) }
        }.start()
    }

    private data class ContactInfo(val name: String, val photoData: String, val priority: Boolean)

    private fun lookupContact(number: String): ContactInfo? {
        if (number.isBlank() || ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        val projection = arrayOf(ContactsContract.PhoneLookup._ID, ContactsContract.PhoneLookup.DISPLAY_NAME, ContactsContract.PhoneLookup.PHOTO_URI)
        contentResolver.query(uri, projection, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return null
            val id = c.getLong(0)
            val name = c.getString(1).orEmpty().ifBlank { number }
            val photoUri = c.getString(2).orEmpty()
            var starred = false
            runCatching {
                contentResolver.query(ContactsContract.Contacts.CONTENT_URI, arrayOf(ContactsContract.Contacts.STARRED), "${ContactsContract.Contacts._ID}=?", arrayOf(id.toString()), null)?.use { x -> if (x.moveToFirst()) starred = x.getInt(0) == 1 }
            }
            val priorityList = runCatching { JSONArray(getSharedPreferences("jarvis_mobile", MODE_PRIVATE).getString("priority_contacts_json", "[]")) }.getOrElse { JSONArray() }
            val digits = number.filter { it.isDigit() }.takeLast(9)
            var priority = starred
            for (i in 0 until priorityList.length()) {
                val p = priorityList.optJSONObject(i) ?: continue
                if (digits.isNotBlank() && p.optString("phone").filter { it.isDigit() }.takeLast(9) == digits) { priority = true; break }
            }
            return ContactInfo(name, CallStateStore.uriToBase64(this, photoUri), priority)
        }
        return null
    }

    private fun wasSeenBefore(number: String): Boolean {
        if (number.isBlank()) return false
        val feed = runCatching { JSONArray(getSharedPreferences("jarvis_mobile", MODE_PRIVATE).getString("call_feed", "[]")) }.getOrElse { JSONArray() }
        val digits = number.filter { it.isDigit() }.takeLast(9)
        for (i in 0 until feed.length()) {
            val old = feed.optJSONObject(i) ?: continue
            if (old.optString("number").filter { it.isDigit() }.takeLast(9) == digits) return true
        }
        return false
    }

    private fun reputation(number: String): JSONObject {
        val c = (URL("https://chatgpt-tv2.vercel.app/api/caller-reputation?number=" + Uri.encode(number)).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 2500; readTimeout = 4500; setRequestProperty("Accept", "application/json")
        }
        val raw = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
        return JSONObject(raw)
    }

    private fun appendFeed(call: JSONObject) {
        val prefs = getSharedPreferences("jarvis_mobile", MODE_PRIVATE)
        val arr = runCatching { JSONArray(prefs.getString("call_feed", "[]")) }.getOrElse { JSONArray() }
        arr.put(JSONObject(call.toString()))
        val trimmed = JSONArray(); val start = (arr.length() - 100).coerceAtLeast(0)
        for (i in start until arr.length()) trimmed.put(arr.opt(i))
        prefs.edit().putString("call_feed", trimmed.toString()).putString("last_incoming_call", call.optString("number")).putLong("last_incoming_call_at", System.currentTimeMillis()).apply()
    }
}
