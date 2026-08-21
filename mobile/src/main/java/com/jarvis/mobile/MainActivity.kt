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
import android.widget.LinearLayout
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
    private lateinit var recentChats: TextView
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
        recentChats = findViewById(R.id.recentChats)
        conversationId = prefs.getString("currentConversation", null) ?: newConversation(false)
        loadConversation(conversationId)
        refreshRecentChats()

        findViewById<Button>(R.id.send).setOnClickListener { sendMessage() }
        findViewById<Button>(R.id.mic).setOnClickListener { startVoiceCapture() }
        findViewById<Button>(R.id.newChat).setOnClickListener { newConversation(true); refreshRecentChats() }
        findViewById<Button>(R.id.chats).setOnClickListener { showChats() }
        findViewById<Button>(R.id.connections).setOnClickListener { showConnections() }
        findViewById<Button>(R.id.tools).setOnClickListener { showConnections() }
        findViewById<Button>(R.id.voiceSettings).setOnClickListener { showVoiceSettings() }
        findViewById<Button>(R.id.camera).setOnClickListener { openCamera() }
        findViewById<Button>(R.id.files).setOnClickListener { openFiles() }
        findViewById<Button>(R.id.phoneControl).setOnClickListener { startActivity(Intent(this, DeviceHubActivity::class.java)) }
        findViewById<Button>(R.id.wakeWord).setOnClickListener { toggleWakeWord() }
        recentChats.setOnClickListener { showChats() }

        if (intent?.getBooleanExtra("wake_word_triggered", false) == true) {
            status.text = "Hola Jarvis detectado · te escucho"
            handler.postDelayed({ startVoiceCapture() }, 350)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("wake_word_triggered", false)) {
            status.text = "Hola Jarvis detectado · te escucho"
            handler.postDelayed({ startVoiceCapture() }, 250)
        }
    }

    private fun toggleWakeWord() {
        val enabled = prefs.getBoolean("wake_word_enabled", false)
        if (enabled) {
            stopService(Intent(this, WakeWordService::class.java))
            prefs.edit().putBoolean("wake_word_enabled", false).apply()
            Toast.makeText(this, "Activación 'Hola Jarvis' desactivada", Toast.LENGTH_SHORT).show()
            status.text = "Hola Jarvis · desactivado"
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_WAKE_AUDIO)
            return
        }
        startWakeWordService()
    }

    private fun startWakeWordService() {
        ContextCompat.startForegroundService(this, Intent(this, WakeWordService::class.java))
        prefs.edit().putBoolean("wake_word_enabled", true).apply()
        Toast.makeText(this, "Di 'Hola Jarvis' para activarme", Toast.LENGTH_LONG).show()
        status.text = "Hola Jarvis · escuchando en segundo plano"
    }

    private fun newConversation(showToast: Boolean): String {
        val id = UUID.randomUUID().toString()
        conversationId = id
        prefs.edit().putString("currentConversation", id).apply()
        val chats = chatIndex()
        chats.put(JSONObject().put("id", id).put("title", "Nuevo chat").put("updated", System.currentTimeMillis()))
        prefs.edit().putString("chatIndex", chats.toString()).putString("chat_$id", "[]").remove("response_$id").apply()
        if (::transcript.isInitialized) transcript.text = ""
        if (showToast) Toast.makeText(this, "Nuevo chat", Toast.LENGTH_SHORT).show()
        return id
    }

    private fun chatIndex(): JSONArray = runCatching { JSONArray(prefs.getString("chatIndex", "[]")) }.getOrElse { JSONArray() }

    private fun sortedChatObjects(): List<JSONObject> {
        val arr = chatIndex()
        val list = mutableListOf<JSONObject>()
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { list.add(it) }
        return list.sortedByDescending { it.optLong("updated") }
    }

    private fun refreshRecentChats() {
        val chats = sortedChatObjects().take(3)
        recentChats.text = if (chats.isEmpty()) "Chats recientes · todavía no hay conversaciones" else
            "Chats recientes\n" + chats.joinToString("  •  ") { it.optString("title").ifBlank { "Chat" }.take(28) }
    }

    private fun showChats() {
        val items = sortedChatObjects()
        if (items.isEmpty()) { Toast.makeText(this, "Todavía no hay chats guardados", Toast.LENGTH_SHORT).show(); return }
        val labels = items.map { it.optString("title").ifBlank { "Chat" } }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Tus chats").setItems(labels) { _, which ->
            val id = items[which].optString("id")
            if (id.isNotBlank()) {
                conversationId = id
                prefs.edit().putString("currentConversation", id).apply()
                loadConversation(id)
                status.text = "Chat cargado · memoria activa"
            }
        }.setNegativeButton("Cerrar", null).show()
    }

    private fun localMcps(): JSONArray = runCatching { JSONArray(prefs.getString("mcps", "[]")) }.getOrElse { JSONArray() }

    private fun showConnections() {
        status.text = "Comprobando complementos…"
        Thread {
            var remoteInfo = "Búsqueda web: disponible"
            try {
                val c = (URL("$BACKEND/api/capabilities").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"; connectTimeout = 8000; readTimeout = 15000
                }
                val body = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (c.responseCode in 200..299) {
                    val json = JSONObject(body)
                    val serverMcps = json.optJSONArray("mcps") ?: JSONArray()
                    remoteInfo = "Búsqueda web: ${if (json.optBoolean("webSearch")) "ACTIVA ✓" else "no disponible"}\nMCP del backend: ${serverMcps.length()}"
                }
            } catch (_: Exception) {}
            val local = localMcps()
            val lines = mutableListOf(remoteInfo, "", "Tus complementos MCP: ${local.length()}")
            for (i in 0 until local.length()) {
                val m = local.optJSONObject(i) ?: continue
                lines.add("• ${m.optString("server_label")}\n  ${m.optString("server_url")}")
            }
            runOnUiThread {
                status.text = "Complementos"
                AlertDialog.Builder(this)
                    .setTitle("Complementos y MCP")
                    .setMessage(lines.joinToString("\n"))
                    .setPositiveButton("AÑADIR MCP") { _, _ -> showAddMcp() }
                    .setNeutralButton("BORRAR MCP") { _, _ -> prefs.edit().remove("mcps").apply(); Toast.makeText(this, "MCP locales borrados", Toast.LENGTH_SHORT).show() }
                    .setNegativeButton("Cerrar", null)
                    .show()
            }
        }.start()
    }

    private fun showAddMcp() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(42, 8, 42, 4) }
        val label = EditText(this).apply { hint = "Nombre (ej. HomeAssistant)" }
        val url = EditText(this).apply { hint = "https://servidor-mcp/..." }
        val token = EditText(this).apply { hint = "Token/Bearer opcional" }
        box.addView(label); box.addView(url); box.addView(token)
        AlertDialog.Builder(this).setTitle("Conectar servidor MCP").setView(box)
            .setPositiveButton("CONECTAR") { _, _ ->
                val l = label.text.toString().trim()
                val u = url.text.toString().trim()
                if (l.isBlank() || !u.startsWith("https://")) {
                    Toast.makeText(this, "Indica nombre y URL HTTPS", Toast.LENGTH_LONG).show()
                } else {
                    val arr = localMcps()
                    val obj = JSONObject().put("server_label", l).put("server_url", u).put("require_approval", "always")
                    val t = token.text.toString().trim()
                    if (t.isNotBlank()) obj.put("authorization", if (t.startsWith("Bearer ")) t else "Bearer $t")
                    arr.put(obj)
                    prefs.edit().putString("mcps", arr.toString()).apply()
                    Toast.makeText(this, "MCP conectado a Jarvis", Toast.LENGTH_SHORT).show()
                }
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun showVoiceSettings() {
        val voices = arrayOf("alloy", "ash", "ballad", "coral", "echo", "fable", "nova", "onyx", "sage", "shimmer", "verse")
        val current = prefs.getString("voice", "coral") ?: "coral"
        val checked = voices.indexOf(current).coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("Voz de Jarvis · OpenAI")
            .setSingleChoiceItems(voices, checked) { dialog, which ->
                prefs.edit().putString("voice", voices[which]).apply()
                dialog.dismiss()
                Toast.makeText(this, "Voz: ${voices[which]}", Toast.LENGTH_SHORT).show()
                speak("Hola. Esta es mi nueva voz.")
            }.setNegativeButton("Cerrar", null).show()
    }

    private fun openCamera() {
        try { startActivityForResult(Intent(MediaStore.ACTION_IMAGE_CAPTURE), REQ_CAMERA) }
        catch (e: Exception) { Toast.makeText(this, "No se pudo abrir la cámara: ${e.message}", Toast.LENGTH_LONG).show() }
    }

    private fun openFiles() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }
        startActivityForResult(i, REQ_FILE)
    }

    private fun historyArray(): JSONArray = runCatching { JSONArray(prefs.getString("chat_$conversationId", "[]")) }.getOrElse { JSONArray() }

    private fun loadConversation(id: String) {
        val arr = runCatching { JSONArray(prefs.getString("chat_$id", "[]")) }.getOrElse { JSONArray() }
        val out = StringBuilder()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            out.append(if (item.optString("role") == "user") "\nTú\n" else "\nJarvis\n")
            out.append(item.optString("content")).append("\n")
        }
        transcript.text = out.toString()
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun append(role: String, text: String) {
        val arr = historyArray()
        arr.put(JSONObject().put("role", role).put("content", text))
        prefs.edit().putString("chat_$conversationId", arr.toString()).apply()
        transcript.append(if (role == "user") "\nTú\n$text\n" else "\nJarvis\n$text\n")
        updateTitleAndTimestamp(text, role)
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun updateTitleAndTimestamp(text: String, role: String) {
        val index = chatIndex()
        for (i in 0 until index.length()) {
            val item = index.optJSONObject(i) ?: continue
            if (item.optString("id") == conversationId) {
                if (role == "user" && item.optString("title") == "Nuevo chat") item.put("title", text.take(42))
                item.put("updated", System.currentTimeMillis())
                prefs.edit().putString("chatIndex", index.toString()).apply()
                break
            }
        }
        refreshRecentChats()
    }

    private fun sendMessage() {
        val message = input.text.toString().trim()
        if (message.isBlank()) return
        input.text.clear()
        append("user", message)
        status.text = "Pensando · memoria activa…"
        val history = historyArray()
        val previous = prefs.getString("response_$conversationId", null)
        Thread {
            try {
                val result = postChat(message, history, previous)
                if (!result.second.isNullOrBlank()) prefs.edit().putString("response_$conversationId", result.second).apply()
                runOnUiThread {
                    append("assistant", result.first)
                    status.text = "Listo · contexto guardado"
                    speak(result.first)
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Error: ${e.message}"; Toast.makeText(this, e.message, Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun postChat(message: String, history: JSONArray, previousResponseId: String?): Pair<String, String?> {
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
        val body = JSONObject().put("message", message).put("conversationId", conversationId).put("client", "jarvis-mobile")
            .put("history", historyPayload).put("clientMcps", localMcps())
            .apply { if (!previousResponseId.isNullOrBlank()) put("previousResponseId", previousResponseId) }.toString()
        c.outputStream.use { it.write(body.toByteArray()) }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) throw IllegalStateException("HTTP $code $text")
        val json = JSONObject(text)
        return Pair(json.optString("reply").ifBlank { text }, json.optString("responseId").ifBlank { null })
    }

    private fun startVoiceCapture() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO); return
        }
        stopRecorder(false)
        val file = File(cacheDir, "voice-${System.currentTimeMillis()}.m4a")
        voiceFile = file
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
        recorder = r
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC); r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC); r.setAudioSamplingRate(16000); r.setAudioEncodingBitRate(64000)
            r.setOutputFile(file.absolutePath); r.prepare(); r.start(); status.text = "Escuchando…"
            handler.postDelayed({ stopRecorder(true) }, 7000)
        } catch (e: Exception) { stopRecorder(false); Toast.makeText(this, "Micrófono: ${e.message}", Toast.LENGTH_LONG).show() }
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

    private fun splitForSpeech(text: String): List<String> {
        val clean = text.replace(Regex("\\s+"), " ").trim()
        if (clean.length <= 520) return listOf(clean)
        val out = mutableListOf<String>()
        var rest = clean
        while (rest.isNotBlank()) {
            if (rest.length <= 520) { out.add(rest); break }
            val cut = rest.take(520).lastIndexOfAny(charArrayOf('.', '!', '?', ';', ',')).let { if (it < 180) 500 else it + 1 }
            out.add(rest.take(cut).trim()); rest = rest.drop(cut).trim()
        }
        return out
    }

    private fun speak(text: String) {
        val chunks = splitForSpeech(text)
        if (chunks.isEmpty()) return
        player?.release(); player = null
        playSpeechChunk(chunks, 0)
    }

    private fun playSpeechChunk(chunks: List<String>, index: Int) {
        if (index >= chunks.size) return
        Thread {
            try {
                val c = (URL("$BACKEND/api/speech").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; doOutput = true; connectTimeout = 10000; readTimeout = 40000
                    setRequestProperty("Content-Type", "application/json")
                }
                val voice = prefs.getString("voice", "coral") ?: "coral"
                c.outputStream.use { it.write(JSONObject().put("text", chunks[index]).put("voice", voice).toString().toByteArray()) }
                if (c.responseCode !in 200..299) return@Thread
                val file = File(cacheDir, "reply-${System.currentTimeMillis()}-$index.mp3")
                c.inputStream.use { inputStream -> file.outputStream().use { inputStream.copyTo(it) } }
                runOnUiThread {
                    player?.release()
                    player = MediaPlayer().apply {
                        setDataSource(file.absolutePath)
                        setOnCompletionListener { p -> p.release(); file.delete(); if (player === p) player = null; playSpeechChunk(chunks, index + 1) }
                        prepare(); start()
                    }
                }
            } catch (_: Exception) {}
        }.start()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQ_CAMERA -> { status.text = "Foto capturada"; Toast.makeText(this, "Foto capturada. Vision multimodal será el siguiente paso.", Toast.LENGTH_LONG).show() }
            REQ_FILE -> { val uri = data?.data; status.text = "Archivo seleccionado"; Toast.makeText(this, "Archivo seleccionado: ${uri ?: ""}", Toast.LENGTH_LONG).show() }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_AUDIO && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startVoiceCapture()
        if (requestCode == REQ_WAKE_AUDIO && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startWakeWordService()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null); stopRecorder(false); player?.release(); super.onDestroy()
    }

    companion object {
        private const val BACKEND = "https://chatgpt-tv2.vercel.app"
        private const val REQ_AUDIO = 20
        private const val REQ_CAMERA = 21
        private const val REQ_FILE = 22
        private const val REQ_WAKE_AUDIO = 23
    }
}