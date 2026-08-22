package com.jarvis.mobile

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import java.net.Inet4Address
import java.net.NetworkInterface

class DeviceHubActivity : Activity() {
    private lateinit var permissionStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32,32,32,32); setBackgroundColor(0xFF0B0B0B.toInt()) }
        root.addView(TextView(this).apply { text = "Control del teléfono"; textSize = 26f; setTextColor(0xFFFFFFFF.toInt()) })
        root.addView(TextView(this).apply { text = "Llamadas · SMS/RCS · WhatsApp · notificaciones · puente con TV"; textSize = 15f; setTextColor(0xFFB8B8B8.toInt()); setPadding(0,8,0,16) })
        permissionStatus = TextView(this).apply { textSize = 14f; setTextColor(0xFFE0E0E0.toInt()); setPadding(0,0,0,14) }
        root.addView(permissionStatus)
        refreshStatus()

        fun add(label: String, action: () -> Unit) = root.addView(Button(this).apply { text = label; setOnClickListener { action() } })

        add("Conceder permisos de teléfono y SMS") {
            val wanted = mutableListOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE, Manifest.permission.ANSWER_PHONE_CALLS, Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS)
            if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
            val permissions = wanted.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()
            if (permissions.isNotEmpty()) ActivityCompat.requestPermissions(this, permissions, 50) else Toast.makeText(this, "Permisos ya concedidos", Toast.LENGTH_SHORT).show()
        }

        add("Activar acceso a WhatsApp / RCS") { openNotificationListenerSettings(); Toast.makeText(this, "Activa Jarvis en Acceso a notificaciones y vuelve.", Toast.LENGTH_LONG).show() }
        add("Reiniciar lector de mensajes") { runCatching { NotificationListenerService.requestRebind(ComponentName(this, JarvisNotificationListener::class.java)) }; refreshStatus() }
        add("Probar WhatsApp / RCS capturados") {
            val feed = runCatching { JSONArray(getSharedPreferences("jarvis_mobile", MODE_PRIVATE).getString("notification_feed", "[]")) }.getOrElse { JSONArray() }
            val lines = mutableListOf<String>()
            for (i in feed.length()-1 downTo 0) {
                val o = feed.optJSONObject(i) ?: continue; val pkg = o.optString("package")
                if (pkg.contains("whatsapp", true) || pkg.contains("messag", true) || pkg.contains("sms", true)) { lines += "${o.optString("title")}: ${o.optString("text").take(220)}"; if (lines.size >= 5) break }
            }
            Toast.makeText(this, if (lines.isEmpty()) "No hay mensajes capturados todavía." else lines.joinToString("\n\n"), Toast.LENGTH_LONG).show()
        }
        add("Probar llamadas") { runCatching { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:"))) }.onFailure { Toast.makeText(this, "No se pudo abrir el marcador", Toast.LENGTH_LONG).show() } }
        add("Activar puente con Jarvis TV") {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 54)
                Toast.makeText(this, "Concede Notificaciones y vuelve a pulsar Activar puente con Jarvis TV.", Toast.LENGTH_LONG).show()
            } else {
                val ok = runCatching { ContextCompat.startForegroundService(this, Intent(this, PhoneBridgeService::class.java)); true }.getOrDefault(false)
                Toast.makeText(this, if (ok) "Puente TV solicitado en ${localIp()}:${PhoneBridgeService.PORT}" else "No se pudo iniciar el puente TV", Toast.LENGTH_LONG).show()
            }
        }
        add("Detener puente con TV") { stopService(Intent(this, PhoneBridgeService::class.java)); Toast.makeText(this, "Puente TV detenido", Toast.LENGTH_SHORT).show() }
        add("Acceso a notificaciones") { openNotificationListenerSettings() }
        add("Control de aplicaciones · Accesibilidad") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        add("Abrir marcador") { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:"))) }
        add("Abrir SMS") { startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:"))) }
        add("Abrir WhatsApp") { packageManager.getLaunchIntentForPackage("com.whatsapp")?.let { startActivity(it) } ?: packageManager.getLaunchIntentForPackage("com.whatsapp.w4b")?.let { startActivity(it) } ?: Toast.makeText(this, "WhatsApp no está instalado", Toast.LENGTH_SHORT).show() }
        add("Volver a Jarvis") { finish() }
        setContentView(root)
    }

    override fun onResume() { super.onResume(); if (::permissionStatus.isInitialized) refreshStatus() }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) { super.onRequestPermissionsResult(requestCode, permissions, grantResults); refreshStatus() }

    private fun openNotificationListenerSettings() { runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }.onFailure { startActivity(Intent(Settings.ACTION_SETTINGS)) } }
    private fun notificationAccessGranted(): Boolean { val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners").orEmpty(); val mine = ComponentName(this, JarvisNotificationListener::class.java).flattenToString(); return enabled.contains(mine, true) || enabled.contains(packageName, true) }
    private fun refreshStatus() {
        fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
        val prefs = getSharedPreferences("jarvis_mobile", MODE_PRIVATE); val listenerConnected = prefs.getBoolean("notification_listener_connected", false)
        permissionStatus.text = "Contactos ${if (granted(Manifest.permission.READ_CONTACTS)) "✓" else "✗"}   Teléfono ${if (granted(Manifest.permission.CALL_PHONE)) "✓" else "✗"}\nSMS lectura ${if (granted(Manifest.permission.READ_SMS)) "✓" else "✗"}   SMS envío ${if (granted(Manifest.permission.SEND_SMS)) "✓" else "✗"}\nWhatsApp/RCS permiso ${if (notificationAccessGranted()) "✓" else "✗"} · lector ${if (listenerConnected) "CONECTADO" else "NO CONECTADO"}"
    }
    private fun localIp(): String = runCatching { NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }.firstOrNull { !it.isLoopbackAddress && it is Inet4Address }?.hostAddress ?: "IP-del-móvil" }.getOrDefault("IP-del-móvil")
}
