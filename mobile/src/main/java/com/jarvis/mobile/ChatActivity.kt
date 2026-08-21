package com.jarvis.mobile

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class ChatActivity : AppCompatActivity() {
    private lateinit var transcript: TextView
    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var scroll: ScrollView
    private val prefs by lazy { getSharedPreferences("jarvis_mobile", MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())
    private val actionRouter by lazy { LocalActionRouter(this) }
    private var conversationId = ""
    private var recorder: MediaRecorder? = null
    private var voiceFile: File? = null
    private var player: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)
        transcript = findViewById(R.id.transcript)
        input = findViewById(R.id.input)
        status = findViewById(R.id.status)
        scroll = findViewById(R.id.scroll)
        conversationId = prefs.getString("currentConversation", null) ?: newChat(false)
        loadConversation()

        findViewById<Button>(R.id.send).setOnClickListener { sendMessage() }
        findViewById<Button>(R.id.mic).setOnClickListener { startVoiceCapture() }
        findViewById<Button>(R.id.menu).setOnClickListener { showChats() }
        findViewById<Button>(R.id.newChat).setOnClickListener { newChat(true) }
        findViewById<Button>(R.id.plugins).setOnClickListener { showPlugins() }
        findViewById<Button>(R.id.device).setOnClickListener { startActivity(Intent(this, DeviceHubActivity::class.java)) }
        findViewById<Button>(R.id.wake).setOnClickListener { toggleWakeWord() }
        findViewById<Button>(R.id.voice).setOnClickListener { showVoiceSettings() }
        findViewById<Button>(R.id.camera).setOnClickListener { runCatching { startActivity(Intent(MediaStore.ACTION_IMAGE_CAPTURE)) } }
        findViewById<Button>(R.id.files).setOnClickListener { startActivity(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }) }
        findViewById<TextView>(R.id.phoneCard).setOnClickListener { startActivity(Intent(this, DeviceHubActivity::class.java)) }
        findViewById<TextView>(R.id.visionCard).setOnClickListener { runCatching { startActivity(Intent(MediaStore.ACTION_IMAGE_CAPTURE)) } }
        findViewById<TextView>(R.id.nowBrief).setOnClickListener { refreshNowBrief() }

        if (intent.getBooleanExtra("wake_word_triggered", false)) handler.postDelayed({ startVoiceCapture() }, 300)
    }

    private fun newChat(showToast: Boolean): String {
        val id = UUID.randomUUID().toString()
        conversationId = id
        prefs.edit().putString("currentConversation", id).putString("chat_$id", "[]").remove("response_$id").apply()
        val index = chatIndex(); index.put(JSONObject().put("id", id).put("title", "Nuevo chat").put("updated", System.currentTimeMillis()))
        prefs.edit().putString("chatIndex", index.toString()).apply()
        if (::transcript.isInitialized) transcript.text = ""
        if (showToast) Toast.makeText(this, "Nuevo chat", Toast.LENGTH_SHORT).show()
        return id
    }

    private fun chatIndex(): JSONArray = runCatching { JSONArray(prefs.getString("chatIndex", "[]")) }.getOrElse { JSONArray() }

    private fun showChats() {
        val arr = chatIndex(); val items = mutableListOf<JSONObject>()
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { items.add(it) }
        items.sortByDescending { it.optLong("updated") }
        if (items.isEmpty()) return
        AlertDialog.Builder(this).setTitle("Chats")
            .setItems(items.map { it.optString("title", "Chat") }.toTypedArray()) { _, which ->
                conversationId = items[which].optString("id")
                prefs.edit().putString("currentConversation", conversationId).apply(); loadConversation()
            }.setNegativeButton("Cerrar", null).show()
    }

    private fun history(): JSONArray = runCatching { JSONArray(prefs.getString("chat_$conversationId", "[]")) }.getOrElse { JSONArray() }

    private fun append(role: String, text: String) {
        val arr = history(); arr.put(JSONObject().put("role", role).put("content", text))
        prefs.edit().putString("chat_$conversationId", arr.toString()).apply()
        transcript.append(if (role == "user") "\nTú\n$text\n" else "\nJarvis\n$text\n")
        val idx = chatIndex()
        for (i in 0 until idx.length()) {
            val o = idx.optJSONObject(i) ?: continue
            if (o.optString("id") == conversationId) {
                if (role == "user" && o.optString("title") == "Nuevo chat") o.put("title", text.take(40))
                o.put("updated", System.currentTimeMillis()); break
            }
        }
        prefs.edit().putString("chatIndex", idx.toString()).apply()
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun loadConversation() {
        val arr = history(); val sb = StringBuilder()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            sb.append(if (o.optString("role") == "user") "\nTú\n" else "\nJarvis\n").append(o.optString("content")).append("\n")
        }
        transcript.text = sb.toString()
    }

    private fun sendMessage() {
        val message = input.text.toString().trim(); if (message.isBlank()) return
        input.text.clear(); append("user", message)
        val local = actionRouter.handle(message)
        if (local.handled) {
            append("assistant", local.message); status.text = "Acción del teléfono"; speak(local.message); return
        }
        status.text = "Pensando…"
        val history = history(); val previous = prefs.getString("response_$conversationId", null)
        Thread {
            try {
                val result = postChat(message, history, previous)
                if (!result.second.isNullOrBlank()) prefs.edit().putString("response_$conversationId", result.second).apply()
                runOnUiThread { append("assistant", result.first); status.text = "● Listo · memoria activa"; speak(result.first) }
            } catch (e: Exception) { runOnUiThread { status.text = "Error"; Toast.makeText(this, e.message, Toast.LENGTH_LONG).show() } }
        }.start()
    }

    private fun postChat(message: String, history: JSONArray, previous: String?): Pair<String, String?> {
        val c = (URL("$BACKEND/api/chat").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 12000; readTimeout = 60000; setRequestProperty("Content-Type", "application/json")
        }
        val payload = JSONObject().put("message", message).put("conversationId", conversationId).put("client", "jarvis-mobile")
            .put("history", history).apply { if (!previous.isNullOrBlank()) put("previousResponseId", previous) }
        c.outputStream.use { it.write(payload.toString().toByteArray()) }
        val body = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode} $body")
        val json = JSONObject(body); return json.optString("reply") to json.optString("responseId").ifBlank { null }
    }

    private fun refreshNowBrief() {
        val card = findViewById<TextView>(R.id.nowBrief); card.text = "NOW BRIEF\nActualizando…"
        val notif = runCatching { JSONArray(prefs.getString("notification_feed", "[]")) }.getOrElse { JSONArray() }
        val recent = mutableListOf<String>()
        for (i in (notif.length()-1) downTo 0) {
            val o = notif.optJSONObject(i) ?: continue
            val t = listOf(o.optString("title"), o.optString("text")).filter { it.isNotBlank() }.joinToString(": ")
            if (t.isNotBlank()) recent.add(t.take(120)); if (recent.size >= 4) break
        }
        val prompt = "Crea un Now Brief breve para hoy. Incluye tiempo actual y previsión usando búsqueda web si hace falta, y resume estas notificaciones recientes del teléfono: ${recent.joinToString(" | ")}. Máximo 8 líneas, en español."
        Thread {
            try {
                val result = postChat(prompt, JSONArray(), null).first
                runOnUiThread { card.text = "NOW BRIEF\n$result" }
            } catch (_: Exception) { runOnUiThread { card.text = "NOW BRIEF\nNo se pudo actualizar" } }
        }.start()
    }

    private fun showPlugins() {
        AlertDialog.Builder(this).setTitle("Complementos")
            .setMessage("MCP remotos y conexiones de Jarvis. Puedes añadir servidores desde la pantalla avanzada de complementos.")
            .setPositiveButton("ABRIR COMPLEMENTOS") { _, _ -> startActivity(Intent(this, MainActivity::class.java)) }
            .setNegativeButton("Cerrar", null).show()
    }

    private fun showVoiceSettings() {
        val voices = arrayOf("alloy","ash","ballad","coral","echo","fable","nova","onyx","sage","shimmer","verse")
        val cur = prefs.getString("voice", "coral") ?: "coral"
        AlertDialog.Builder(this).setTitle("Voz de Jarvis")
            .setSingleChoiceItems(voices, voices.indexOf(cur).coerceAtLeast(0)) { d, w -> prefs.edit().putString("voice", voices[w]).apply(); d.dismiss(); speak("Hola. Esta es mi nueva voz.") }
            .show()
    }

    private fun toggleWakeWord() {
        val enabled = prefs.getBoolean("wake_word_enabled", false)
        if (enabled) { stopService(Intent(this, WakeWordService::class.java)); prefs.edit().putBoolean("wake_word_enabled", false).apply(); status.text = "Hola Jarvis desactivado" }
        else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 91); return }
            ContextCompat.startForegroundService(this, Intent(this, WakeWordService::class.java)); prefs.edit().putBoolean("wake_word_enabled", true).apply(); status.text = "Hola Jarvis escuchando"
        }
    }

    private fun startVoiceCapture() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 90); return }
        recorder?.let { runCatching { it.stop() }; runCatching { it.release() } }
        val file = File(cacheDir, "voice-${System.currentTimeMillis()}.m4a"); voiceFile = file
        recorder = (if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC); setOutputFormat(MediaRecorder.OutputFormat.MPEG_4); setAudioEncoder(MediaRecorder.AudioEncoder.AAC); setAudioSamplingRate(16000); setAudioEncodingBitRate(64000); setOutputFile(file.absolutePath); prepare(); start()
        }
        status.text = "Escuchando…"; handler.postDelayed({ stopVoiceCapture() }, 6000)
    }

    private fun stopVoiceCapture() {
        val r = recorder ?: return; runCatching { r.stop() }; runCatching { r.release() }; recorder = null
        val file = voiceFile ?: return; voiceFile = null
        Thread {
            try {
                val c = (URL("$BACKEND/api/transcribe").openConnection() as HttpURLConnection).apply { requestMethod="POST"; doOutput=true; setRequestProperty("Content-Type","audio/mp4") }
                c.outputStream.use { out -> file.inputStream().use { it.copyTo(out) } }
                val body = c.inputStream.bufferedReader().use { it.readText() }; val text = JSONObject(body).optString("text")
                runOnUiThread { input.setText(text); sendMessage() }
            } catch (_: Exception) { runOnUiThread { status.text = "Error de voz" } } finally { file.delete() }
        }.start()
    }

    private fun speak(text: String) {
        Thread {
            try {
                val c = (URL("$BACKEND/api/speech").openConnection() as HttpURLConnection).apply { requestMethod="POST"; doOutput=true; setRequestProperty("Content-Type","application/json") }
                c.outputStream.use { it.write(JSONObject().put("text", text.take(900)).put("voice", prefs.getString("voice","coral")).toString().toByteArray()) }
                if (c.responseCode !in 200..299) return@Thread
                val file = File(cacheDir, "speech-${System.currentTimeMillis()}.mp3"); c.inputStream.use { i -> file.outputStream().use { i.copyTo(it) } }
                runOnUiThread { player?.release(); player = MediaPlayer().apply { setDataSource(file.absolutePath); setOnCompletionListener { p -> p.release(); file.delete() }; prepare(); start() } }
            } catch (_: Exception) {}
        }.start()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 90 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startVoiceCapture()
        if (requestCode == 91 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) toggleWakeWord()
    }

    override fun onDestroy() { handler.removeCallbacksAndMessages(null); recorder?.let { runCatching { it.release() } }; player?.release(); super.onDestroy() }

    companion object { private const val BACKEND = "https://chatgpt-tv2.vercel.app" }
}
