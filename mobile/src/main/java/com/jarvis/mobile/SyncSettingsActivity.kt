package com.jarvis.mobile

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SyncSettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("jarvis_mobile", MODE_PRIVATE)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32) }
        val title = TextView(this).apply { text = "Sincronización Jarvis"; textSize = 24f }
        val key = EditText(this).apply { hint = "Clave de sincronización (mín. 16 caracteres)"; setText(prefs.getString("sync_key", "")) }
        val info = TextView(this).apply { text = "Usa la misma clave en móvil y TV. Sincroniza chats, MCP, voz y recordatorios." }
        val save = Button(this).apply { text = "GUARDAR Y SINCRONIZAR" }
        box.addView(title); box.addView(info); box.addView(key); box.addView(save)
        setContentView(box)
        save.setOnClickListener {
            val value = key.text.toString().trim()
            if (value.length < 16) { Toast.makeText(this, "Usa al menos 16 caracteres", Toast.LENGTH_LONG).show(); return@setOnClickListener }
            prefs.edit().putString("sync_key", value).putLong("sync_updated_at", System.currentTimeMillis()).apply()
            JarvisSync.force(this)
            Toast.makeText(this, "Sincronización activada", Toast.LENGTH_LONG).show()
        }
    }
}
