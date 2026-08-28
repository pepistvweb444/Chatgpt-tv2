package com.jarvis.mobile

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
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
        root.addView(TextView(this).apply { text = "Llamadas · SMS/RCS · WhatsApp · apps/navegador · Philips Hue · Homey · LG ThinQ · puente con TV"; textSize = 15f; setTextColor(0xFFB8B8B8.toInt()); setPadding(0,8,0,16) })
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
        add("Activar Jarvis para identificar llamadas") { requestCallScreeningRole() }
        add("Contactos prioritarios / favoritos") { startActivity(Intent(this, FavoriteContactsActivity::class.java)) }
        add("Activar acceso a WhatsApp / RCS / redes / correo") { openNotificationListenerSettings(); Toast.makeText(this, "Activa Jarvis en Acceso a notificaciones.", Toast.LENGTH_LONG).show() }
        add("Reiniciar lector de mensajes") { runCatching { NotificationListenerService.requestRebind(ComponentName(this, JarvisNotificationListener::class.java)) }; refreshStatus() }
        add("Probar WhatsApp / RCS capturados") {
            val feed = runCatching { JSONArray(getSharedPreferences("jarvis_mobile", MODE_PRIVATE).getString("notification_feed", "[]")) }.getOrElse { JSONArray() }
            val lines = mutableListOf<String>()
            for (i in feed.length()-1 downTo 0) {
                val o = feed.optJSONObject(i) ?: continue
                val pkg = o.optString("package")
                if (pkg.contains("whatsapp", true) || pkg.contains("messag", true) || pkg.contains("sms", true)) {
                    lines += "${o.optString("title")}: ${o.optString("text").take(220)}"
                    if (lines.size >= 5) break
                }
            }
            Toast.makeText(this, if (lines.isEmpty()) "No hay mensajes capturados todavía." else lines.joinToString("\n\n"), Toast.LENGTH_LONG).show()
        }
        add("Probar lectura de SMS") {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS), 51)
            } else {
                var preview = ""
                runCatching { contentResolver.query(Uri.parse("content://sms/inbox"), arrayOf("address","body"), null, null, "date DESC")?.use { c -> if (c.moveToFirst()) preview = "${c.getString(0)}: ${c.getString(1).orEmpty().take(220)}" } }
                Toast.makeText(this, if (preview.isBlank()) "No hay SMS visibles" else preview, Toast.LENGTH_LONG).show()
            }
        }
        add("Probar llamadas") { if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CALL_PHONE), 52) else startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:"))) }
        add("Activar puente con Jarvis TV") { runCatching { ContextCompat.startForegroundService(this, Intent(this, PhoneBridgeService::class.java)); Toast.makeText(this, "Puente TV activo en ${localIp()}:${PhoneBridgeService.PORT}", Toast.LENGTH_LONG).show() }.onFailure { Toast.makeText(this, "No se pudo iniciar el puente TV", Toast.LENGTH_LONG).show() } }
        add("Detener puente con TV") { stopService(Intent(this, PhoneBridgeService::class.java)) }
        add("Philips Hue · conectar Bridge / controlar luces") { startActivity(Intent(this, HueActivity::class.java)) }
        add("Homey Cloud · luces y dispositivos") { startActivity(Intent(this, HomeyActivity::class.java)) }
        add("LG ThinQ · conectar / API") { startActivity(Intent(this, LgThinQActivity::class.java)) }
        add("Acceso a notificaciones") { openNotificationListenerSettings() }
        add("Control de aplicaciones · Accesibilidad") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        add("Abrir navegador") { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com"))) }
        add("Abrir marcador") { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:"))) }
        add("Abrir SMS") { startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:"))) }
        add("Abrir WhatsApp") { packageManager.getLaunchIntentForPackage("com.whatsapp")?.let { startActivity(it) } ?: packageManager.getLaunchIntentForPackage("com.whatsapp.w4b")?.let { startActivity(it) } ?: Toast.makeText(this, "WhatsApp no está instalado", Toast.LENGTH_SHORT).show() }
        add("Ajustes de llamadas predeterminadas") { runCatching { startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) } }
        add("Volver a Jarvis") { finish() }
        setContentView(root)
    }

    private fun requestCallScreeningRole() {
        if (Build.VERSION.SDK_INT >= 29) {
            val rm = getSystemService(RoleManager::class.java)
            if (rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) {
                if (rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)) Toast.makeText(this, "Jarvis ya identifica las llamadas entrantes.", Toast.LENGTH_LONG).show()
                else startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING), 54)
                return
            }
        }
        Toast.makeText(this, "Abre Ajustes > Apps predeterminadas > Identificador y spam y selecciona Jarvis.", Toast.LENGTH_LONG).show()
        runCatching { startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }
    }

    override fun onResume() { super.onResume(); if (::permissionStatus.isInitialized) refreshStatus() }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) { super.onRequestPermissionsResult(requestCode, permissions, grantResults); refreshStatus() }
    @Deprecated("Deprecated in Java") override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) { super.onActivityResult(requestCode,resultCode,data); if(requestCode==54){ Toast.makeText(this, if(resultCode==RESULT_OK) "Jarvis ya puede identificar llamadas entrantes" else "No se activó el identificador de llamadas", Toast.LENGTH_LONG).show(); refreshStatus() } }
    private fun openNotificationListenerSettings() { runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }.onFailure { startActivity(Intent(Settings.ACTION_SETTINGS)) } }
    private fun notificationAccessGranted(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners").orEmpty()
        val mine = ComponentName(this, JarvisNotificationListener::class.java).flattenToString()
        return enabled.contains(mine, true) || enabled.contains(packageName, true)
    }
    private fun callScreeningEnabled(): Boolean = if (Build.VERSION.SDK_INT >= 29) runCatching { getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_CALL_SCREENING) }.getOrDefault(false) else false
    private fun refreshStatus() {
        fun granted(permission: String) = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        val prefs = getSharedPreferences("jarvis_mobile", MODE_PRIVATE)
        val listenerConnected = prefs.getBoolean("notification_listener_connected", false)
        val hueConnected = prefs.getString("hue_bridge_ip", "").orEmpty().isNotBlank() && prefs.getString("hue_username", "").orEmpty().isNotBlank()
        val homeyConnected = prefs.getString("homey_session", "").orEmpty().isNotBlank()
        val thinQConnected = prefs.getString("lg_thinq_pat", "").orEmpty().isNotBlank()
        val priorityCount = runCatching { JSONArray(prefs.getString("priority_contacts_json","[]")).length() }.getOrDefault(0)
        val contacts = if (granted(Manifest.permission.READ_CONTACTS)) "✓" else "✗"
        val phone = if (granted(Manifest.permission.CALL_PHONE)) "✓" else "✗"
        val smsRead = if (granted(Manifest.permission.READ_SMS)) "✓" else "✗"
        val smsSend = if (granted(Manifest.permission.SEND_SMS)) "✓" else "✗"
        val notifications = if (notificationAccessGranted()) "✓" else "✗"
        val listener = if (listenerConnected) "CONECTADO" else "NO CONECTADO"
        val hue = if (hueConnected) "CONECTADO" else "NO CONECTADO"
        val homey = if (homeyConnected) "CONECTADO" else "NO CONECTADO"
        val thinQ = if (thinQConnected) "CONFIGURADO" else "NO CONFIGURADO"
        val screening = if (callScreeningEnabled()) "ACTIVO" else "INACTIVO"
        permissionStatus.text = "Contactos $contacts · prioritarios $priorityCount   Teléfono $phone\nIdentificador llamadas $screening\nSMS lectura $smsRead   SMS envío $smsSend\nWhatsApp/redes/correo $notifications · lector $listener\nPhilips Hue $hue · Homey Cloud $homey · LG ThinQ $thinQ"
    }
    private fun localIp(): String = runCatching { NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }.firstOrNull { !it.isLoopbackAddress && it is Inet4Address }?.hostAddress ?: "IP-del-móvil" }.getOrDefault("IP-del-móvil")
}
