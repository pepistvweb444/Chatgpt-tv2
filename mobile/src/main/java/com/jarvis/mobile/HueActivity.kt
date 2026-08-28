package com.jarvis.mobile

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class HueActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("jarvis_mobile", MODE_PRIVATE) }
    private lateinit var list: LinearLayout
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 28, 28, 28)
            setBackgroundColor(0xFF080B10.toInt())
        }
        root.addView(TextView(this).apply { text = "Philips Hue"; textSize = 26f; setTextColor(Color.WHITE) })
        root.addView(TextView(this).apply {
            text = "Conexión directa al Hue Bridge · sin Homey ni otro hub intermedio"
            textSize = 14f; setTextColor(0xFFB8C0CC.toInt()); setPadding(0, 6, 0, 14)
        })
        status = TextView(this).apply { textSize = 13f; setTextColor(0xFF85B7FF.toInt()); setPadding(0, 0, 0, 12) }
        root.addView(status)
        root.addView(Button(this).apply { text = "Detectar Hue Bridge"; setOnClickListener { discoverBridge() } })
        root.addView(Button(this).apply { text = "Vincular Jarvis con el Bridge"; setOnClickListener { pairBridge() } })
        root.addView(Button(this).apply { text = "Actualizar luces"; setOnClickListener { loadLights() } })
        val scroll = ScrollView(this)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 12, 0, 20) }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        refreshStatus()
        if (prefs.getString("hue_bridge_ip", "").orEmpty().isNotBlank() && prefs.getString("hue_username", "").orEmpty().isNotBlank()) loadLights()
    }

    private fun refreshStatus() {
        val ip = prefs.getString("hue_bridge_ip", "").orEmpty()
        val user = prefs.getString("hue_username", "").orEmpty()
        status.text = when {
            ip.isBlank() -> "Bridge: no detectado"
            user.isBlank() -> "Bridge: $ip · falta vincular Jarvis"
            else -> "Bridge: $ip · Jarvis vinculado"
        }
    }

    private fun discoverBridge() {
        status.text = "Buscando Hue Bridge…"
        Thread {
            try {
                val c = (URL("https://discovery.meethue.com/").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000; readTimeout = 7000; setRequestProperty("Accept", "application/json")
                }
                val raw = c.inputStream.bufferedReader().use { it.readText() }
                val a = JSONArray(raw)
                val ip = (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("internalipaddress")?.takeIf(String::isNotBlank) }.firstOrNull()
                    ?: error("No se encontró ningún Hue Bridge en esta red")
                prefs.edit().putString("hue_bridge_ip", ip).apply()
                runOnUiThread { refreshStatus(); Toast.makeText(this, "Hue Bridge encontrado: $ip", Toast.LENGTH_LONG).show() }
            } catch (e: Throwable) {
                runOnUiThread { status.text = "No se pudo detectar Hue: ${e.message}" }
            }
        }.start()
    }

    private fun pairBridge() {
        val ip = prefs.getString("hue_bridge_ip", "").orEmpty()
        if (ip.isBlank()) { Toast.makeText(this, "Primero detecta el Hue Bridge", Toast.LENGTH_LONG).show(); return }
        AlertDialog.Builder(this)
            .setTitle("Vincular Philips Hue")
            .setMessage("Pulsa ahora el botón físico del Hue Bridge y, dentro de 30 segundos, pulsa Vincular.")
            .setPositiveButton("Vincular") { _, _ -> doPair(ip) }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun doPair(ip: String) {
        status.text = "Vinculando con Hue Bridge…"
        Thread {
            try {
                val c = (URL("http://$ip/api").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; doOutput = true; connectTimeout = 5000; readTimeout = 7000
                    setRequestProperty("Content-Type", "application/json")
                }
                c.outputStream.use { it.write(JSONObject().put("devicetype", "jarvis#mobile").toString().toByteArray()) }
                val raw = c.inputStream.bufferedReader().use { it.readText() }
                val a = JSONArray(raw)
                val user = a.optJSONObject(0)?.optJSONObject("success")?.optString("username").orEmpty()
                if (user.isBlank()) {
                    val msg = a.optJSONObject(0)?.optJSONObject("error")?.optString("description").orEmpty().ifBlank { "No se recibió autorización" }
                    error(msg)
                }
                prefs.edit().putString("hue_username", user).apply()
                runOnUiThread { refreshStatus(); Toast.makeText(this, "Jarvis vinculado con Philips Hue", Toast.LENGTH_LONG).show(); loadLights() }
            } catch (e: Throwable) {
                runOnUiThread { status.text = "Hue: ${e.message}" }
            }
        }.start()
    }

    private fun loadLights() {
        val ip = prefs.getString("hue_bridge_ip", "").orEmpty()
        val user = prefs.getString("hue_username", "").orEmpty()
        if (ip.isBlank() || user.isBlank()) { refreshStatus(); return }
        status.text = "Leyendo luces Hue…"
        Thread {
            try {
                val raw = URL("http://$ip/api/$user/lights").readText()
                val root = JSONObject(raw)
                val lights = mutableListOf<Pair<String, JSONObject>>()
                root.keys().forEach { id -> root.optJSONObject(id)?.let { lights += id to it } }
                prefs.edit().putString("hue_lights_json", root.toString()).apply()
                runOnUiThread {
                    list.removeAllViews()
                    lights.sortedBy { it.second.optString("name") }.forEach { (id, o) -> addLightCard(id, o) }
                    if (lights.isEmpty()) list.addView(TextView(this).apply { text = "El Hue Bridge no devuelve luces."; setTextColor(Color.WHITE); textSize = 15f })
                    status.text = "${lights.size} luz${if (lights.size == 1) "" else "es"} Hue"
                }
            } catch (e: Throwable) {
                runOnUiThread { status.text = "No se pudieron leer las luces: ${e.message}" }
            }
        }.start()
    }

    private fun addLightCard(id: String, o: JSONObject) {
        val state = o.optJSONObject("state") ?: JSONObject()
        val on = state.optBoolean("on", false)
        val bri = state.optInt("bri", 0).coerceIn(0, 254)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 16, 18, 16)
            background = GradientDrawable().apply { cornerRadius = 22f; setColor(0xFF143F36.toInt()) }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 12 }
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(this).apply { text = "💡"; textSize = 28f; setPadding(0, 0, 14, 0) })
        head.addView(TextView(this).apply {
            text = o.optString("name").ifBlank { "Luz Hue $id" } + "\n" + if (on) "Encendida" else "Apagada"
            textSize = 16f; setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(Button(this).apply {
            text = if (on) "Apagar" else "Encender"
            setOnClickListener { setLightState(id, JSONObject().put("on", !on)) }
        })
        card.addView(head)
        card.addView(TextView(this).apply { text = "Brillo ${((bri / 254.0) * 100).toInt()}% · ${o.optString("type")}"; textSize = 12f; setTextColor(0xFFDCE5E1.toInt()); setPadding(0, 8, 0, 2) })
        card.addView(SeekBar(this).apply {
            max = 254; progress = bri
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {}
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) { setLightState(id, JSONObject().put("on", true).put("bri", (seekBar?.progress ?: bri).coerceAtLeast(1))) }
            })
        })
        list.addView(card)
    }

    private fun setLightState(id: String, body: JSONObject) {
        val ip = prefs.getString("hue_bridge_ip", "").orEmpty(); val user = prefs.getString("hue_username", "").orEmpty()
        Thread {
            try {
                val c = (URL("http://$ip/api/$user/lights/$id/state").openConnection() as HttpURLConnection).apply {
                    requestMethod = "PUT"; doOutput = true; connectTimeout = 4500; readTimeout = 6500
                    setRequestProperty("Content-Type", "application/json")
                }
                c.outputStream.use { it.write(body.toString().toByteArray()) }
                c.inputStream.close()
                runOnUiThread { loadLights() }
            } catch (e: Throwable) { runOnUiThread { Toast.makeText(this, "Hue: ${e.message}", Toast.LENGTH_LONG).show() } }
        }.start()
    }
}
