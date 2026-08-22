package com.jarvis.mobile

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LocalActionRouter(private val activity: Activity) {
    data class Result(val handled: Boolean, val message: String = "")

    fun handle(raw: String): Result {
        val text = raw.trim()
        val lower = text.lowercase()
        when {
            lower.startsWith("llama a ") || lower.startsWith("llamar a ") -> return callContact(text.substringAfter(" ").substringAfter("a ").trim())
            lower.startsWith("llama al ") || lower.startsWith("llamar al ") -> return callContact(text.substringAfter("al ").trim())
            lower.matches(Regex("^(llama|llamar)\\s+[+0-9][0-9 .-]{5,}$")) -> return callNumber(text.substringAfter(" ").trim())
            lower.contains("tienes acceso a mis mensajes") || lower.contains("tiene acceso a mis mensajes") || lower.contains("puedes leer mis mensajes") || lower.contains("puede leer mis mensajes") || lower.contains("acceso a los mensajes") || lower.contains("acceso a mis sms") -> return messageAccessStatus()
            lower.contains("whatsapp") && (lower.contains("lee") || lower.contains("léeme") || lower.contains("leeme") || lower.contains("mensajes") || lower.contains("escrito")) -> return readNotificationMessages("whatsapp", 12)
            lower.contains("rcs") && (lower.contains("lee") || lower.contains("léeme") || lower.contains("leeme") || lower.contains("mensajes")) -> return readNotificationMessages("messages", 12)
            lower.contains("últimos mensajes") || lower.contains("ultimos mensajes") || lower.contains("últimos sms") || lower.contains("ultimos sms") || lower == "lee mis mensajes" || lower == "léeme mis mensajes" || lower == "leeme mis mensajes" || lower == "lee los mensajes" || lower == "léeme los mensajes" || lower == "leeme los mensajes" || lower == "mis mensajes" || lower == "mensajes" -> return readRecentSms(10)
            lower.startsWith("qué me ha escrito ") || lower.startsWith("que me ha escrito ") || lower.startsWith("qué dice ") || lower.startsWith("que dice ") -> {
                val name = text.substringAfter("escrito ", text.substringAfter("dice ")).trim()
                return readSmsFrom(name)
            }
            lower.startsWith("contesta a ") && lower.contains(" diciendo ") -> {
                val name = text.substringAfter("contesta a ").substringBefore(" diciendo ").trim()
                val body = text.substringAfter(" diciendo ").trim()
                return smsContact(name, body)
            }
            lower.startsWith("envía un sms a ") || lower.startsWith("manda un sms a ") || lower.startsWith("enviar sms a ") -> {
                val after = text.substringAfter(" a ")
                return smsContact(after.substringBefore(":").trim(), after.substringAfter(":", "").trim())
            }
            lower.startsWith("abre ") -> return openApp(text.substringAfter(" ").trim())
            lower.contains("abre ajustes") -> { activity.startActivity(Intent(Settings.ACTION_SETTINGS)); return Result(true, "He abierto Ajustes.") }
            lower == "inicio" || lower == "ve a inicio" -> { activity.sendBroadcast(Intent(JarvisAccessibilityService.ACTION_HOME).setPackage(activity.packageName)); return Result(true, "He vuelto a Inicio.") }
            lower == "atrás" || lower == "volver" -> { activity.sendBroadcast(Intent(JarvisAccessibilityService.ACTION_BACK).setPackage(activity.packageName)); return Result(true, "He pulsado Atrás.") }
            lower.contains("recientes") -> { activity.sendBroadcast(Intent(JarvisAccessibilityService.ACTION_RECENTS).setPackage(activity.packageName)); return Result(true, "He abierto aplicaciones recientes.") }
        }
        return Result(false)
    }

    private fun messageAccessStatus(): Result {
        val read = ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
        val receive = ContextCompat.checkSelfPermission(activity, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
        val send = ContextCompat.checkSelfPermission(activity, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
        val prefs = activity.getSharedPreferences("jarvis_mobile", Activity.MODE_PRIVATE)
        val listener = prefs.getBoolean("notification_listener_connected", false)
        val feed = notificationFeed()
        var whatsapp = 0; var messages = 0
        for (i in 0 until feed.length()) {
            val pkg = feed.optJSONObject(i)?.optString("package").orEmpty()
            if (pkg.contains("whatsapp", true)) whatsapp++
            if (pkg.contains("messag", true) || pkg.contains("sms", true)) messages++
        }
        if (!read) ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS), 72)
        return Result(true, "SMS lectura: ${if (read) "sí" else "no"} · recepción: ${if (receive) "sí" else "no"} · envío: ${if (send) "sí" else "no"}. Acceso a notificaciones: ${if (listener) "conectado" else "no conectado"}. Capturados: WhatsApp $whatsapp · Mensajes/RCS $messages.")
    }

    private fun notificationFeed(): JSONArray = runCatching { JSONArray(activity.getSharedPreferences("jarvis_mobile", Activity.MODE_PRIVATE).getString("notification_feed", "[]")) }.getOrElse { JSONArray() }

    private fun readNotificationMessages(filter: String, limit: Int): Result {
        val feed = notificationFeed(); val out = mutableListOf<String>(); val q = filter.lowercase()
        for (i in feed.length() - 1 downTo 0) {
            if (out.size >= limit) break
            val n = feed.optJSONObject(i) ?: continue
            val pkg = n.optString("package")
            val who = n.optString("conversation").ifBlank { n.optString("title") }
            val body = n.optString("text")
            val match = when (q) {
                "whatsapp" -> pkg.contains("whatsapp", true)
                "messages" -> pkg.contains("messag", true) || pkg.contains("sms", true)
                else -> "$pkg $who $body".contains(q, true)
            }
            if (match && body.isNotBlank()) out += "${who.ifBlank { pkg }}: ${body.take(700)}"
        }
        val connected = activity.getSharedPreferences("jarvis_mobile", Activity.MODE_PRIVATE).getBoolean("notification_listener_connected", false)
        return Result(true, if (out.isEmpty()) "No encuentro mensajes de $filter. Acceso a notificaciones: ${if (connected) "conectado" else "NO conectado"}. Activa Jarvis en Acceso a notificaciones y haz que llegue una notificación nueva para probar." else "Mensajes recientes:\n\n" + out.joinToString("\n\n"))
    }

    private fun ensureSmsPermission(): Boolean {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) return true
        ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS), 72)
        return false
    }

    private fun readRecentSms(limit: Int): Result {
        if (!ensureSmsPermission()) return readNotificationMessages("messages", limit)
        val out = mutableListOf<String>(); val sdf = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
        val error = runCatching {
            activity.contentResolver.query(Uri.parse("content://sms/inbox"), arrayOf("address", "body", "date"), null, null, "date DESC")?.use { c ->
                val ai = c.getColumnIndex("address"); val bi = c.getColumnIndex("body"); val di = c.getColumnIndex("date")
                while (c.moveToNext() && out.size < limit) {
                    val address = if (ai >= 0) c.getString(ai).orEmpty() else ""
                    val body = if (bi >= 0) c.getString(bi).orEmpty() else ""
                    val date = if (di >= 0) c.getLong(di) else 0L
                    val who = contactNameForNumber(address) ?: address.ifBlank { "Desconocido" }
                    out += "$who · ${if (date > 0) sdf.format(Date(date)) else ""}\n${body.take(500)}"
                }
            }
        }.exceptionOrNull()
        if (out.isNotEmpty()) return Result(true, "Últimos SMS:\n\n" + out.joinToString("\n\n"))
        val fallback = readNotificationMessages("messages", limit)
        return if (!fallback.message.startsWith("No encuentro")) fallback else Result(true, if (error != null) "READ_SMS figura concedido, pero Android bloqueó el proveedor de SMS (${error.javaClass.simpleName}). Usaré notificaciones para SMS/RCS cuando estén activadas." else "No encuentro SMS en la bandeja. Si usas RCS/Google Messages, activa Acceso a notificaciones.")
    }

    private fun readSmsFrom(name: String): Result {
        if (!ensureSmsPermission()) return readNotificationMessages(name, 8)
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.READ_CONTACTS), 73); return Result(true, "Necesito permiso de Contactos para identificar a $name.")
        }
        val match = findPhone(name) ?: return readNotificationMessages(name, 8)
        val suffix = match.second.replace(" ", "").replace("-", "").takeLast(8); val items = mutableListOf<String>()
        runCatching {
            activity.contentResolver.query(Uri.parse("content://sms/inbox"), arrayOf("address", "body", "date"), null, null, "date DESC")?.use { c ->
                val ai = c.getColumnIndex("address"); val bi = c.getColumnIndex("body")
                while (c.moveToNext() && items.size < 5) {
                    val address = if (ai >= 0) c.getString(ai).orEmpty().replace(" ", "").replace("-", "") else ""
                    if (suffix.isNotBlank() && address.takeLast(8) == suffix) items += if (bi >= 0) c.getString(bi).orEmpty().take(700) else ""
                }
            }
        }
        return if (items.isNotEmpty()) Result(true, "Últimos SMS de ${match.first}:\n\n" + items.joinToString("\n\n")) else readNotificationMessages(name, 8)
    }

    private fun contactNameForNumber(number: String): String? {
        if (number.isBlank() || ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        activity.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) return c.getString(0) }
        return null
    }

    private fun callNumber(number: String): Result {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.CALL_PHONE), 70)
            return Result(true, "Necesito permiso de Teléfono. Concédelo y repite la orden.")
        }
        val clean = number.filter { it.isDigit() || it == '+' }
        return try {
            activity.startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(clean)}")))
            Result(true, "Iniciando llamada a $clean.")
        } catch (e: Exception) {
            runCatching { activity.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(clean)}"))) }
            Result(true, "Android no permitió iniciar la llamada directamente (${e.javaClass.simpleName}). He abierto el marcador con el número preparado.")
        }
    }

    private fun callContact(name: String): Result {
        if (name.isBlank()) return Result(true, "Dime a quién quieres llamar.")
        val needContacts = ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED
        val needPhone = ContextCompat.checkSelfPermission(activity, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED
        if (needContacts || needPhone) {
            val perms = mutableListOf<String>(); if (needContacts) perms += Manifest.permission.READ_CONTACTS; if (needPhone) perms += Manifest.permission.CALL_PHONE
            ActivityCompat.requestPermissions(activity, perms.toTypedArray(), 70)
            return Result(true, "Necesito ${if (needContacts) "Contactos" else ""}${if (needContacts && needPhone) " y " else ""}${if (needPhone) "Teléfono" else ""}. Concédelo y repite la orden.")
        }
        val match = findPhone(name) ?: return Result(true, "No encuentro un contacto llamado $name.")
        AlertDialog.Builder(activity)
            .setTitle("Llamar a ${match.first}")
            .setMessage(match.second)
            .setPositiveButton("LLAMAR") { _, _ ->
                try {
                    activity.startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(match.second)}")))
                } catch (_: Exception) {
                    activity.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(match.second)}")))
                }
            }
            .setNegativeButton("Cancelar", null).show()
        return Result(true, "He encontrado a ${match.first}. Confirma la llamada en pantalla.")
    }

    private fun smsContact(name: String, body: String): Result {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.READ_CONTACTS), 71); return Result(true, "Necesito permiso para leer Contactos. Concédelo y repite la orden.")
        }
        val match = findPhone(name) ?: return Result(true, "No encuentro un contacto llamado $name.")
        activity.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(match.second)}")).apply { putExtra("sms_body", body) })
        return Result(true, "He preparado el SMS para ${match.first}${if (body.isBlank()) "." else ": $body"}")
    }

    private fun openApp(name: String): Result {
        val wanted = name.lowercase().trim(); val apps = activity.packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
        val match = apps.firstOrNull { activity.packageManager.getApplicationLabel(it).toString().lowercase().contains(wanted) } ?: return Result(true, "No encuentro una app llamada $name.")
        val launch = activity.packageManager.getLaunchIntentForPackage(match.packageName) ?: return Result(true, "No puedo abrir $name.")
        activity.startActivity(launch); return Result(true, "He abierto ${activity.packageManager.getApplicationLabel(match)}.")
    }

    private fun findPhone(query: String): Pair<String, String>? {
        val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER)
        activity.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection, "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$query%"), "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC")?.use { c -> if (c.moveToFirst()) return c.getString(0) to c.getString(1) }
        return null
    }
}
