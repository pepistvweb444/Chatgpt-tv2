package com.jarvis.tv

import android.media.MediaPlayer
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class BriefingActivity : AppCompatActivity() {
    private lateinit var text: TextView
    private var player: MediaPlayer? = null
    private val backend = "https://chatgpt-tv2.vercel.app"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        text = TextView(this).apply { textSize = 28f; text = "Jarvis está preparando tu briefing…"; setPadding(48, 48, 48, 48) }
        setContentView(LinearLayout(this).apply { addView(text) })
        buildBriefing()
    }

    private fun buildBriefing() {
        Thread {
            try {
                val prefs = getSharedPreferences("jarvis", MODE_PRIVATE)
                val synced = JSONObject()
                for ((k, v) in prefs.all) if (k.startsWith("phone_") || k.startsWith("reminder_")) synced.put(k, v?.toString() ?: "")
                val prompt = "Prepara un briefing matinal breve en español para leer en voz alta. Incluye el tiempo de hoy, las noticias más relevantes de esta mañana y, si existen en los datos sincronizados, llamadas, mensajes y recordatorios pendientes. No uses Markdown ni listas con guiones. Datos sincronizados: ${synced}."
                val c = (URL("$backend/api/chat").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; doOutput = true; connectTimeout = 8000; readTimeout = 45000
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                val body = JSONObject().put("message", prompt).put("conversationId", "tv-alarm-briefing").put("client", "jarvis-tv-alarm").put("history", JSONArray()).toString()
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val response = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
                val reply = JSONObject(response).optString("reply").ifBlank { response }
                runOnUiThread { text.text = reply }
                speak(reply)
            } catch (e: Exception) {
                runOnUiThread { text.text = "No he podido preparar el briefing: ${e.message ?: "error"}" }
            }
        }.start()
    }

    private fun speak(reply: String) {
        Thread {
            val file = File(cacheDir, "alarm-briefing.mp3")
            try {
                val prefs = getSharedPreferences("jarvis", MODE_PRIVATE)
                val c = (URL("$backend/api/speech").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; doOutput = true; connectTimeout = 7000; readTimeout = 45000
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                val payload = JSONObject().put("text", reply).put("voice", prefs.getString("voice", "noiz") ?: "noiz").put("provider", "noiz").put("speed", 1.18).toString()
                c.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                if (c.responseCode !in 200..299) return@Thread
                c.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
                runOnUiThread {
                    player?.release()
                    player = MediaPlayer().apply {
                        setDataSource(file.absolutePath)
                        setOnCompletionListener { p -> p.release(); file.delete(); if (player === p) player = null }
                        prepareAsync(); setOnPreparedListener { it.start() }
                    }
                }
            } catch (_: Exception) { file.delete() }
        }.start()
    }

    override fun onDestroy() { player?.release(); super.onDestroy() }
}
