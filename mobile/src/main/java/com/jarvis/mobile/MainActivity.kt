package com.jarvis.mobile

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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

class MainActivity : AppCompatActivity() {
    private lateinit var transcript: TextView
    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var scroll: ScrollView
    private val prefs by lazy { getSharedPreferences("jarvis_mobile", MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())
    private var conversationId = ""
    private var recorder: MediaRecorder? = null
    private var voiceFile: File? = null
    private var player: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        transcript = findViewById(R.id.transcript)
        input = findViewById(R.id.input)
        status = findViewById(R.id.status)
        scroll = findViewById(R.id.scroll)
        conversationId = prefs.getString("currentConversation", null) ?: newConversation(false)
        loadConversation(conversationId)
        findViewById<Button>(R.id.send).setOnClickListener { sendMessage() }
        findViewById<Button>(R.id.mic).setOnClickListener { startVoiceCapture() }
        findViewById<Button>(R.id.newChat).setOnClickListener { newConversation(true) }
        findViewById<Button>(R.id.chats).setOnClickListener { showChats() }
    }

    private fun newConversation(showToast: Boolean): String {
        val id = UUID.randomUUID().toString()
        conversationId = id
        prefs.edit().putString("currentConversation", id).apply()
        val chats = chatIndex()
        chats.put(JSONObject().put("id", id).put("title", "Nuevo chat").put("updated", System.currentTimeMillis()))
        prefs.edit().putString("chatIndex", chats.toString()).putString("chat_$id", "[]").apply()
        transcript.text = ""
        if (showToast) Toast.makeText(this, "Nuevo chat", Toast.LENGTH_SHORT).show()
        return id
    }

    private fun chatIndex(): JSONArray = runCatching { JSONArray(prefs.getString("chatIndex", "[]")) }.getOrElse { JSONArray() }

    private fun showChats() {
        val index = chatIndex()
        if (index.length() == 0) return
        val labels = Array(index.length()) { i -> index.optJSONObject(i)?.optString("title")?.ifBlank { "Chat" } ?: "Chat" }
        AlertDialog.Builder(this).setTitle("Tus chats de Jarvis").setItems(labels) { _, which ->
            val id = index.optJSONObject(which)?.optString("id").orEmpty()
            if (id.isNotBlank()) {
                conversationId = id
                prefs.edit().putString("currentConversation", id).apply()
                loadConversation(id)
            }
        }.show()
    }

    private fun historyArray(): JSONArray = runCatching { JSONArray(prefs.getString("chat_$conversationId", "[]")) }.getOrElse { JSONArray() }

    private fun loadConversation(id: String) {
        val arr = runCatching { JSONArray(prefs.getString("chat_$id", "[]")) }.getOrElse { JSONArray() }
        val out = StringBuilder()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            out.append(if (item.optString("role") == "user") "\nTÚ\n" else "\nJARVIS\n")
            out.append(item.optString("content")).append("\n")
        }
        transcript.text = out.toString()
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun append(role: String, text: String) {
        val arr = historyArray()
        arr.put(JSONObject().put("role", role).put("content", text))
        prefs.edit().putString("chat_$conversationId", arr.toString()).apply()
        transcript.append(if (role == "user") "\nTÚ\n$text\n" else "\nJARVIS\n$text\n")
        updateTitleIfNeeded(text, role)
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun updateTitleIfNeeded(text: String, role: String) {
        if (role != "user") return
        val index = chatIndex()
        for (i in 0 until index.length()) {
            val item = index.optJSONObject(i) ?: continue
            if (item.optString("id") == conversationId && item.optString("title") == "Nuevo chat") {
                item.put("title", text.take(42)).put("updated", System.currentTimeMillis())
                prefs.edit().putString("chatIndex", index.toString()).apply()
                break
            }
        }
    }

    private fun sendMessage() {
        val message = input.text.toString().trim()
        if (message.isBlank()) return
        input.text.clear()
        append("user", message)
        status.text = "Pensando…"
        val history = historyArray()
        Thread {
            try {
                val reply = postChat(message, history)
                runOnUiThread {
                    append("assistant", reply)
                    status.text = "Listo"
                    speak(reply)
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Error: ${e.message}"; Toast.makeText(this, e.message, Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun postChat(message: String, history: JSONArray): String {
        val c = (URL("$BACKEND/api/chat").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 12000; readTimeout = 60000
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        val historyPayload = JSONArray()
        for (i in 0 until history.length()) {
            val item = history.optJSONObject(i) ?: continue
            if (i == history.length() - 1 && item.optString("role") == "user" && item.optString("content") == message) continue
            historyPayload.put(item)
        }
        val body = JSONObject().put("message", message).put("conversationId", conversationId).put("client", "jarvis-mobile").put("history", historyPayload).toString()
        c.outputStream.use { it.write(body.toByteArray()) }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) throw IllegalStateException("HTTP $code $text")
        return JSONObject(text).optString("reply").ifBlank { text }
    }

    private fun startVoiceCapture() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO)
            return
        }
        stopRecorder(false)
        val file = File(cacheDir, "voice-${System.currentTimeMillis()}.m4a")
        voiceFile = file
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
        recorder = r
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(16000)
            r.setAudioEncodingBitRate(64000)
            r.setOutputFile(file.absolutePath)
            r.prepare(); r.start()
            status.text = "Escuchando…"
            handler.postDelayed({ stopRecorder(true) }, 7000)
        } catch (e: Exception) {
            stopRecorder(false)
            Toast.makeText(this, "Micrófono: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopRecorder(upload: Boolean) {
        val r = recorder ?: return
        runCatching { r.stop() }; runCatching { r.release() }; recorder = null
        val file = voiceFile; voiceFile = null
        if (!upload || file == null || !file.exists() || file.length() < 256) { file?.delete(); return }
        status.text = "Transcribiendo…"
        Thread {
            try {
                val text = transcribe(file)
                runOnUiThread { input.setText(text); status.text = "Listo"; sendMessage() }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Error de voz"; Toast.makeText(this, e.message, Toast.LENGTH_LONG).show() }
            } finally { file.delete() }
        }.start()
    }

    private fun transcribe(file: File): String {
        val c = (URL("$BACKEND/api/transcribe").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 12000; readTimeout = 60000
            setRequestProperty("Content-Type", "audio/mp4"); setRequestProperty("X-Filename", "jarvis-mobile.m4a")
        }
        c.outputStream.use { out -> file.inputStream().use { it.copyTo(out) } }
        val code = c.responseCode
        val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) throw IllegalStateException("HTTP $code $body")
        return JSONObject(body).optString("text")
    }

    private fun speak(text: String) {
        Thread {
            try {
                val c = (URL("$BACKEND/api/speech").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; doOutput = true; connectTimeout = 12000; readTimeout = 60000
                    setRequestProperty("Content-Type", "application/json")
                }
                c.outputStream.use { it.write(JSONObject().put("text", text).toString().toByteArray()) }
                if (c.responseCode !in 200..299) return@Thread
                val file = File(cacheDir, "reply-${System.currentTimeMillis()}.mp3")
                c.inputStream.use { inputStream -> file.outputStream().use { inputStream.copyTo(it) } }
                runOnUiThread {
                    player?.release()
                    player = MediaPlayer().apply {
                        setDataSource(file.absolutePath)
                        setOnCompletionListener { it.release(); file.delete() }
                        prepare(); start()
                    }
                }
            } catch (_: Exception) {}
        }.start()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_AUDIO && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startVoiceCapture()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null); stopRecorder(false); player?.release(); super.onDestroy()
    }

    companion object {
        private const val BACKEND = "https://chatgpt-tv2.vercel.app"
        private const val REQ_AUDIO = 20
    }
}
