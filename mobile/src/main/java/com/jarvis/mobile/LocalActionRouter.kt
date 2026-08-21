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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LocalActionRouter(private val activity: Activity) {
    data class Result(val handled: Boolean, val message: String = "")

    fun handle(raw: String): Result {
        val text = raw.trim()
        val lower = text.lowercase()

        when {
            lower.startsWith("llama a ") || lower.startsWith("llamar a ") -> {
                val name = text.substringAfter(" ").substringAfter("a ").trim()
                return callContact(name)
            }
            lower.contains("tienes acceso a mis mensajes") || lower.contains("tiene acceso a mis mensajes") ||
                lower.contains("puedes leer mis mensajes") || lower.contains("puede leer mis mensajes") ||
                lower.contains("acceso a los mensajes") || lower.contains("acceso a mis sms") -> {
                return smsAccessStatus()
            }
            lower.contains("últimos mensajes") || lower.contains("ultimos mensajes") ||
                lower.contains("últimos sms") || lower.contains("ultimos sms") ||
                lower == "lee mis mensajes" || lower == "léeme mis mensajes" || lower == "leeme mis mensajes" ||
                lower == "lee los mensajes" || lower == "léeme los mensajes" || lower == "leeme los mensajes" ||
                lower == "mis mensajes" || lower == "mensajes" -> {
                return readRecentSms(8)
            }
            lower.startsWith("qué me ha escrito ") || lower.startsWith("que me ha escrito ") ||
                lower.startsWith("qué dice ") || lower.startsWith("que dice ") -> {
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
                val name = after.substringBefore(":").trim()
                val body = after.substringAfter(":", "").trim()
                return smsContact(name, body)
            }
            lower.startsWith("abre ") -> {
                val app = text.substringAfter(" ").trim()
                return openApp(app)
            }
            lower.contains("abre ajustes") -> {
                activity.startActivity(Intent(Settings.ACTION_SETTINGS))
                return Result(true, "He abierto Ajustes.")
            }
            lower == "inicio" || lower == "ve a inicio" -> {
                activity.sendBroadcast(Intent(JarvisAccessibilityService.ACTION_HOME).setPackage(activity.packageName))
                return Result(true, "He vuelto a Inicio.")
            }
            lower == "atrás" || lower == "volver" -> {
                activity.sendBroadcast(Intent(JarvisAccessibilityService.ACTION_BACK).setPackage(activity.packageName))
                return Result(true, "He pulsado Atrás.")
            }
            lower.contains("recientes") -> {
                activity.sendBroadcast(Intent(JarvisAccessibilityService.ACTION_RECENTS).setPackage(activity.packageName))
                return Result(true, "He abierto aplicaciones recientes.")
            }
        }
        return Result(false)
    }

    private fun smsAccessStatus(): Result {
        val read = ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
        val receive = ContextCompat.checkSelfPermission(activity, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
        val send = ContextCompat.checkSelfPermission(activity, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
        if (!read) {
            ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS), 72)
            return Result(true, "Todavía no tengo READ_SMS concedido. He abierto la solicitud de permisos.")
        }
        return try {
            val count = activity.contentResolver.query(Uri.parse("content://sms/inbox"), arrayOf("_id"), null, null, null)?.use { it.count } ?: 0
            Result(true, "Sí. Android me ha concedido acceso a SMS. Lectura: sí · recepción: ${if (receive) "sí" else "no"} · envío: ${if (send) "sí" else "no"}. Veo $count SMS en la bandeja de entrada.")
        } catch (e: SecurityException) {
            Result(true, "READ_SMS figura como concedido, pero Android bloquea el proveedor de mensajes: ${e.message ?: "restricción del sistema"}. Abre Teléfono > Permisos de teléfono y SMS y vuelve a conceder SMS.")
        } catch (e: Exception) {
            Result(true, "El permiso SMS está concedido, pero no puedo consultar la bandeja: ${e.message ?: e.javaClass.simpleName}.")
        }
    }

    private fun ensureSmsPermission(): Boolean {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) return true
        ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS), 72)
        return false
    }

    private fun readRecentSms(limit: Int): Result {
        if (!ensureSmsPermission()) return Result(true, "Necesito permiso para leer SMS. Concédelo y vuelve a pedírmelo.")
        return try {
            val out = mutableListOf<String>()
            val sdf = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
            activity.contentResolver.query(
                Uri.parse("content://sms/inbox"),
                arrayOf("address", "body", "date"), null, null, "date DESC"
            )?.use { c ->
                val ai = c.getColumnIndex("address"); val bi = c.getColumnIndex("body"); val di = c.getColumnIndex("date")
                while (c.moveToNext() && out.size < limit) {
                    val address = if (ai >= 0) c.getString(ai).orEmpty() else ""
                    val body = if (bi >= 0) c.getString(bi).orEmpty() else ""
                    val date = if (di >= 0) c.getLong(di) else 0L
                    val who = contactNameForNumber(address) ?: address.ifBlank { "Desconocido" }
                    out += "$who · ${if (date > 0) sdf.format(Date(date)) else ""}\n${body.take(220)}"
                }
            }
            Result(true, if (out.isEmpty()) "Tengo permiso de SMS, pero no encuentro SMS recibidos en la bandeja." else "Últimos SMS:\n\n" + out.joinToString("\n\n"))
        } catch (e: SecurityException) {
            Result(true, "El permiso READ_SMS aparece concedido, pero Android está bloqueando la lectura de la bandeja. Revisa Teléfono > Permisos de teléfono y SMS.")
        } catch (e: Exception) {
            Result(true, "No he podido leer los SMS: ${e.message ?: e.javaClass.simpleName}.")
        }
    }

    private fun readSmsFrom(name: String): Result {
        if (!ensureSmsPermission()) return Result(true, "Necesito permiso para leer SMS. Concédelo y vuelve a pedírmelo.")
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.READ_CONTACTS), 73)
            return Result(true, "Necesito permiso de Contactos para identificar a $name.")
        }
        val match = findPhone(name) ?: return Result(true, "No encuentro un contacto llamado $name.")
        val normalized = match.second.replace(" ", "").replace("-", "")
        val suffix = normalized.takeLast(8)
        val items = mutableListOf<String>()
        return try {
            activity.contentResolver.query(
                Uri.parse("content://sms/inbox"),
                arrayOf("address", "body", "date"), null, null, "date DESC"
            )?.use { c ->
                val ai = c.getColumnIndex("address"); val bi = c.getColumnIndex("body")
                while (c.moveToNext() && items.size < 5) {
                    val address = if (ai >= 0) c.getString(ai).orEmpty().replace(" ", "").replace("-", "") else ""
                    if (suffix.isNotBlank() && address.takeLast(8) == suffix) {
                        items += if (bi >= 0) c.getString(bi).orEmpty().take(500) else ""
                    }
                }
            }
            Result(true, if (items.isEmpty()) "No encuentro SMS recientes de ${match.first}." else "Últimos SMS de ${match.first}:\n\n" + items.joinToString("\n\n"))
        } catch (e: Exception) {
            Result(true, "No he podido consultar los SMS de ${match.first}: ${e.message ?: e.javaClass.simpleName}.")
        }
    }

    private fun contactNameForNumber(number: String): String? {
        if (number.isBlank() || ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        activity.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return null
    }

    private fun callContact(name: String): Result {
        if (name.isBlank()) return Result(true, "Dime a quién quieres llamar.")
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(activity, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE), 70)
            return Result(true, "Necesito permiso de Contactos y Teléfono. Concédelo y repite la orden.")
        }
        val match = findPhone(name) ?: return Result(true, "No encuentro un contacto llamado $name.")
        AlertDialog.Builder(activity)
            .setTitle("Llamar a ${match.first}")
            .setMessage(match.second)
            .setPositiveButton("LLAMAR") { _, _ ->
                activity.startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(match.second)}")))
            }
            .setNegativeButton("Cancelar", null)
            .show()
        return Result(true, "He encontrado a ${match.first}. Confirma la llamada en pantalla.")
    }

    private fun smsContact(name: String, body: String): Result {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.READ_CONTACTS), 71)
            return Result(true, "Necesito permiso para leer Contactos. Concédelo y repite la orden.")
        }
        val match = findPhone(name) ?: return Result(true, "No encuentro un contacto llamado $name.")
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(match.second)}")).apply { putExtra("sms_body", body) }
        activity.startActivity(intent)
        return Result(true, "He preparado el SMS para ${match.first}${if (body.isBlank()) "." else ": $body"}")
    }

    private fun openApp(name: String): Result {
        val wanted = name.lowercase().trim()
        val apps = activity.packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
        val match = apps.firstOrNull { info -> activity.packageManager.getApplicationLabel(info).toString().lowercase().contains(wanted) }
            ?: return Result(true, "No encuentro una app llamada $name.")
        val launch = activity.packageManager.getLaunchIntentForPackage(match.packageName) ?: return Result(true, "No puedo abrir $name.")
        activity.startActivity(launch)
        return Result(true, "He abierto ${activity.packageManager.getApplicationLabel(match)}.")
    }

    private fun findPhone(query: String): Pair<String, String>? {
        val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER)
        activity.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$query%"),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
        )?.use { c -> if (c.moveToFirst()) return c.getString(0) to c.getString(1) }
        return null
    }
}
