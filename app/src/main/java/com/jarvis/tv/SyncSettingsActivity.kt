package com.jarvis.tv

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
        val prefs = getSharedPreferences("jarvis", MODE_PRIVATE)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 36, 48, 36) }
        val title = TextView(this).apply { text = "Sincronización Jarvis"; textSize = 28f }
        val info = TextView(this).apply { text = "Introduce la misma clave que en el móvil. Se compartirán chats, MCP, voz y recordatorios." }
        val key = EditText(this).apply { hint = "Clave de sincronización"; setText(prefs.getString("sync_key", "")) }
        val save = Button(this).apply { text = "GUARDAR Y SINCRONIZAR" }
        box.addView(title); box.addView(info); box.addView(key); box.addView(save); setContentView(box)
        save.setOnClickListener {
            val value = key.text.toString().trim()
            if (value.length < 16) { Toast.makeText(this, "Usa al menos 16 caracteres", Toast.LENGTH_LONG).show(); return@setOnClickListener }
            prefs.edit().putString("sync_key", value).putLong("sync_updated_at", System.currentTimeMillis()).apply()
            JarvisSync.force(this)
            Toast.makeText(this, "Sincronización activada", Toast.LENGTH_LONG).show()
        }
    }
}
