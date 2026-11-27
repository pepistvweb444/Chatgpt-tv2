package com.jarvis.mobile

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.jarvis.logic.maySaveContactPhoto
import java.io.File

object ContactPhotoStore {
    fun cache(context: Context, number: String, jpeg: ByteArray) {
        if (jpeg.isEmpty()) return
        val key = number.filter { it.isDigit() }.takeLast(9).ifBlank { return }
        val dir = File(context.filesDir, "caller-photos").apply { mkdirs() }
        File(dir, "$key.jpg").writeBytes(jpeg)
    }

    fun cached(context: Context, number: String): ByteArray? {
        val key = number.filter { it.isDigit() }.takeLast(9).ifBlank { return null }
        val file = File(context.filesDir, "caller-photos/$key.jpg")
        return if (file.exists()) file.readBytes() else null
    }

    /** Fills an empty photo on a contact that already exists. Never creates a contact and never replaces a photo. */
    fun saveIfEmpty(context: Context, contact: PhoneContact?, jpeg: ByteArray): Boolean {
        val person = contact ?: return false
        if (jpeg.isEmpty()) return false
        val allowed = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED
        if (!allowed) return false
        val already = person.photoUri.isNotBlank() || PhoneContactLookup.hasPhoto(context, person.id)
        if (!maySaveContactPhoto(contactExists = true, alreadyHasPhoto = already, hasCandidate = true)) return false
        val rawId = rawContactId(context, person.id) ?: return false
        val values = ContentValues().apply {
            put(ContactsContract.Data.RAW_CONTACT_ID, rawId)
            put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE)
            put(ContactsContract.CommonDataKinds.Photo.PHOTO, jpeg)
        }
        return runCatching { context.contentResolver.insert(ContactsContract.Data.CONTENT_URI, values) != null }.getOrDefault(false)
    }

    private fun rawContactId(context: Context, contactId: Long): Long? {
        return runCatching {
            context.contentResolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts._ID),
                "${ContactsContract.RawContacts.CONTACT_ID}=?",
                arrayOf(contactId.toString()),
                null
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
        }.getOrNull()
    }
}
