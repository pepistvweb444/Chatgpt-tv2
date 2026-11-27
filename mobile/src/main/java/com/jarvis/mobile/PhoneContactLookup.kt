package com.jarvis.mobile

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

data class PhoneContact(val id: Long, val name: String, val number: String, val photoUri: String, val priority: Boolean)

object PhoneContactLookup {
    fun find(context: Context, number: String): PhoneContact? {
        if (number.isBlank()) return null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        val projection = arrayOf(
            ContactsContract.PhoneLookup._ID,
            ContactsContract.PhoneLookup.DISPLAY_NAME,
            ContactsContract.PhoneLookup.PHOTO_URI,
            ContactsContract.PhoneLookup.NUMBER
        )
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            val id = cursor.getLong(0)
            val name = cursor.getString(1).orEmpty().ifBlank { number }
            val photo = cursor.getString(2).orEmpty()
            val foundNumber = cursor.getString(3).orEmpty().ifBlank { number }
            return PhoneContact(id, name, foundNumber, photo, isPriority(context, id, number))
        }
        return null
    }

    fun hasPhoto(context: Context, contactId: Long): Boolean {
        if (contactId <= 0) return false
        return runCatching {
            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts.PHOTO_URI),
                "${ContactsContract.Contacts._ID}=?",
                arrayOf(contactId.toString()),
                null
            )?.use { cursor -> cursor.moveToFirst() && !cursor.getString(0).isNullOrBlank() } ?: false
        }.getOrDefault(false)
    }

    private fun isPriority(context: Context, id: Long, number: String): Boolean {
        var starred = false
        runCatching {
            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts.STARRED),
                "${ContactsContract.Contacts._ID}=?",
                arrayOf(id.toString()),
                null
            )?.use { cursor -> if (cursor.moveToFirst()) starred = cursor.getInt(0) == 1 }
        }
        if (starred) return true
        val digits = number.filter { it.isDigit() }.takeLast(9)
        val list = runCatching {
            org.json.JSONArray(context.getSharedPreferences("jarvis_mobile", Context.MODE_PRIVATE).getString("priority_contacts_json", "[]"))
        }.getOrElse { org.json.JSONArray() }
        for (i in 0 until list.length()) {
            val item = list.optJSONObject(i) ?: continue
            if (digits.isNotBlank() && item.optString("phone").filter { it.isDigit() }.takeLast(9) == digits) return true
        }
        return false
    }
}
