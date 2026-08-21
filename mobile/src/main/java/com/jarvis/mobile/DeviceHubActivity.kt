package com.jarvis.mobile

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.net.Inet4Address
import java.net.NetworkInterface

class DeviceHubActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32,32,32,32); setBackgroundColor(0xFF0B0B0B.toInt()) }
        root.addView(TextView(this).apply { text = "Control del teléfono"; textSize = 26f; setTextColor(0xFFFFFFFF.toInt()) })
        root.addView(TextView(this).apply { text = "Llamadas · SMS · notificaciones · apps · puente con TV"; textSize = 15f; setTextColor(0xFFB8B8B8.toInt()); setPadding(0,8,0,20) })
        fun add(label: String, action: () -> Unit) = root.addView(Button(this).apply { text = label; setOnClickListener { action() } })

        add("Permisos de teléfono y SMS") {
            val permissions = arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE, Manifest.permission.ANSWER_PHONE_CALLS, Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS)
                .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()
            if (permissions.isNotEmpty()) ActivityCompat.requestPermissions(this, permissions, 50) else Toast.makeText(this, "Permisos ya concedidos", Toast.LENGTH_SHORT).show()
        }
        add("Activar llamadas desde Jarvis TV") {
            ContextCompat.startForegroundService(this, Intent(this, PhoneBridgeService::class.java))
            Toast.makeText(this, "Puente TV activo en ${localIp()}:${PhoneBridgeService.PORT}", Toast.LENGTH_LONG).show()
        }
        add("Detener puente con TV") { stopService(Intent(this, PhoneBridgeService::class.java)); Toast.makeText(this, "Puente TV detenido", Toast.LENGTH_SHORT).show() }
        add("Acceso a notificaciones") { startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) }
        add("Control de aplicaciones · Accesibilidad") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        add("Abrir marcador") { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:"))) }
        add("Abrir SMS") { startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:"))) }
        add("Abrir WhatsApp") { packageManager.getLaunchIntentForPackage("com.whatsapp")?.let { startActivity(it) } ?: Toast.makeText(this, "WhatsApp no está instalado", Toast.LENGTH_SHORT).show() }
        add("Ajustes de llamadas predeterminadas") { runCatching { startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) } }
        add("Volver a Jarvis") { finish() }
        setContentView(root)
    }

    private fun localIp(): String = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }?.hostAddress ?: "IP-del-móvil"
    }.getOrDefault("IP-del-móvil")
}
