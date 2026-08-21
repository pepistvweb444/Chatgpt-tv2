package com.jarvis.tv

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.KeyEvent
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
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
import java.util.Locale
import java.util.UUID

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    private lateinit var transcript: TextView
    private lateinit var input: EditText
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var status: TextView
    private lateinit var currentChatLabel: TextView
    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null
    private var recorder: MediaRecorder? = null
    private var voiceFile: File? = null
    private var voicePlayer: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("jarvis", MODE_PRIVATE) }
    private var conversationId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        transcript = findViewById(R.id.transcript)
        input = findViewById(R.id.messageInput)
        title = findViewById(R.id.pageTitle)
        subtitle = findViewById(R.id.pageSubtitle)
        status = findViewById(R.id.statusText)
        currentChatLabel = findViewById(R.id.currentChatLabel)
        tts = TextToSpeech(this, this)

        conversationId = prefs.getString("currentConversation", null) ?: createConversation(false)
        if (prefs.getString("backendUrl", "").isNullOrBlank()) prefs.edit().putString("backendUrl", DEFAULT_BACKEND).apply()
        migrateLegacyHistoryIfNeeded()
        loadConversation(conversationId)
        bindUi()
        if (!isFireTv()) setupRecognizer()
        showHome()
        ensureOverlayPermission(true)
        if (intent?.getBooleanExtra("start_voice", false) == true) {
            intent.removeExtra("start_voice")
            handler.postDelayed({ startVoiceInput() }, 500)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("start_voice", false)) {
            intent.removeExtra("start_voice")
            handler.postDelayed({ startVoiceInput() }, 250)
        }
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) startBubbleService()
    }

    private fun bindUi() {
        findViewById<Button>(R.id.sendButton).setOnClickListener { sendMessage() }
        findViewById<Button>(R.id.micButton).setOnClickListener { startVoiceInput() }
        findViewById<Button>(R.id.assistantBubble).setOnClickListener { startVoiceInput() }
        findViewById<Button>(R.id.settingsButton).setOnClickListener { showSettings() }
        findViewById<Button>(R.id.homeButton).setOnClickListener { showHome() }
        findViewById<Button>(R.id.chatButton).setOnClickListener { showChat() }
        findViewById<Button>(R.id.chatsButton).setOnClickListener { showChats() }
        findViewById<Button>(R.id.connectionsButton).setOnClickListener { showConnections() }
        findViewById<Button>(R.id.visionButton).setOnClickListener { showVision() }
        findViewById<Button>(R.id.homeControlButton).setOnClickListener { showHomeControls() }
        findViewById<Button>(R.id.routinesButton).setOnClickListener { showRoutines() }
        findViewById<Button>(R.id.notificationsButton).setOnClickListener { showNotifications() }
        input.setOnEditorActionListener { _, _, _ -> sendMessage(); true }
    }

    private fun assistantName() = prefs.getString("assistantName", "Jarvis") ?: "Jarvis"
    private fun wakeWord() = prefs.getString("wakeWord", "Hola ChatGPT") ?: "Hola ChatGPT"
    private fun isFireTv() = Build.MANUFACTURER.contains("Amazon", true) || Build.MODEL.contains("AFT", true)

    private fun chatIndex(): JSONArray = runCatching { JSONArray(prefs.getString("chatIndex", "[]")) }.getOrElse { JSONArray() }

    private fun sortedChats(): List<JSONObject> {
        val arr = chatIndex(); val out = mutableListOf<JSONObject>()
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { out.add(it) }
        return out.sortedByDescending { it.optLong("updated") }
    }

    private fun createConversation(showToast: Boolean): String {
        val id = UUID.randomUUID().toString()
        conversationId = id
        val idx = chatIndex()
        idx.put(JSONObject().put("id", id).put("title", "Nuevo chat").put("updated", System.currentTimeMillis()))
        prefs.edit().putString("currentConversation", id).putString("chatIndex", idx.toString()).putString("chat_$id", "[]").remove("response_$id").apply()
        if (::transcript.isInitialized) transcript.text = ""
        if (::currentChatLabel.isInitialized) currentChatLabel.text = "Nuevo chat"
        if (showToast) Toast.makeText(this, "Nuevo chat", Toast.LENGTH_SHORT).show()
        return id
    }

    private fun showChats() {
        val items = sortedChats()
        val labels = mutableListOf<String>()
        labels.add("＋ Nuevo chat")
        labels.addAll(items.map { it.optString("title").ifBlank { "Chat" } })
        AlertDialog.Builder(this).setTitle("Mis chats").setItems(labels.toTypedArray()) { _, which ->
            if (which == 0) {
                createConversation(true)
            } else {
                val item = items[which - 1]
                val id = item.optString("id")
                if (id.isNotBlank()) {
                    conversationId = id
                    prefs.edit().putString("currentConversation", id).apply()
                    loadConversation(id)
                    status.text = "● Chat cargado · memoria activa"
                }
            }
        }.setNegativeButton("Cerrar", null).show()
    }

    private fun showConnections() {
        status.text = "● Comprobando conexiones…"
        Thread {
            try {
                val backend = prefs.getString("backendUrl", DEFAULT_BACKEND).orEmpty().ifBlank { DEFAULT_BACKEND }
                val c = (URL(resolveEndpoint(backend, "capabilities")).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"; connectTimeout = 10000; readTimeout = 20000
                }
                val code = c.responseCode
                val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) throw IllegalStateException("HTTP $code")
                val json = JSONObject(body)
                val mcps = json.optJSONArray("mcps") ?: JSONArray()
                val lines = mutableListOf("Búsqueda web: ${if (json.optBoolean("webSearch")) "ACTIVA ✓" else "No disponible"}")
                if (mcps.length() == 0) lines.add("MCP: ninguno configurado todavía en Vercel")
                else for (i in 0 until mcps.length()) {
                    val m = mcps.optJSONObject(i) ?: continue
                    lines.add("MCP · ${m.optString("label")} · aprobación ${m.optString("approval")}")
                }
                runOnUiThread {
                    status.text = "● Conexiones listas"
                    AlertDialog.Builder(this).setTitle("Conexiones Jarvis").setMessage(lines.joinToString("\n\n")).setPositiveButton("OK", null).show()
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "● Error conexiones"; Toast.makeText(this, e.message, Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun historyArray(): JSONArray = runCatching { JSONArray(prefs.getString("chat_$conversationId", "[]")) }.getOrElse { JSONArray() }

    private fun migrateLegacyHistoryIfNeeded() {
        val arr = historyArray()
        if (arr.length() == 0) {
            val legacy = prefs.getString("history", "").orEmpty()
            if (legacy.isNotBlank()) {
                arr.put(JSONObject().put("role", "assistant").put("content", legacy.takeLast(10000)))
                prefs.edit().putString("chat_$conversationId", arr.toString()).remove("history").apply()
            }
        }
    }

    private fun loadConversation(id: String) {
        val arr = runCatching { JSONArray(prefs.getString("chat_$id", "[]")) }.getOrElse { JSONArray() }
        val out = StringBuilder()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val role = item.optString("role")
            out.append(if (role == "user") "\nTÚ\n" else "\n${assistantName().uppercase()}\n")
            out.append(item.optString("content")).append("\n")
        }
        transcript.text = out.toString()
        val title = sortedChats().firstOrNull { it.optString("id") == id }?.optString("title").orEmpty().ifBlank { "Chat" }
        currentChatLabel.text = title
    }

    private fun append(role: String, text: String, speak: Boolean = false) {
        val arr = historyArray(); arr.put(JSONObject().put("role", role).put("content", text))
        prefs.edit().putString("chat_$conversationId", arr.toString()).apply()
        transcript.append(if (role == "user") "\nTÚ\n$text\n" else "\n${assistantName().uppercase()}\n$text\n")
        updateChatMeta(text, role)
        if (role == "assistant" && speak) speakWithOpenAI(text)
    }

    private fun updateChatMeta(text: String, role: String) {
        val idx = chatIndex()
        for (i in 0 until idx.length()) {
            val item = idx.optJSONObject(i) ?: continue
            if (item.optString("id") == conversationId) {
                if (role == "user" && item.optString("title") == "Nuevo chat") item.put("title", text.take(42))
                item.put("updated", System.currentTimeMillis())
                prefs.edit().putString("chatIndex", idx.toString()).apply()
                currentChatLabel.text = item.optString("title").ifBlank { "Chat" }
                break
            }
        }
    }

    private fun showHome() {
        title.text = "${assistantName()} · Now Brief"
        subtitle.text = if (isFireTv()) "Fire TV · voz directa · web · memoria" else "Información contextual, voz, casa y comunicaciones"
        status.text = "● Listo · ${wakeWord()}"
        if (transcript.text.isBlank()) append("assistant", "Hola. Soy ${assistantName()}. Esta conversación mantiene su contexto. Pulsa Mis chats para recuperar otra.")
    }

    private fun showChat() { title.text = "ChatGPT"; subtitle.text = "Conversación con memoria y búsqueda web"; input.requestFocus() }
    private fun showVision() { title.text = "Vision AI"; subtitle.text = "Consulta lo que aparece en pantalla"; Toast.makeText(this, "Vision multimodal se ampliará en la siguiente iteración", Toast.LENGTH_SHORT).show() }
    private fun showHomeControls() { title.text = "Casa"; subtitle.text = "Luces, persianas, climatización, escenas y sensores" }
    private fun showRoutines() { title.text = "Rutinas"; subtitle.text = "Combina briefing, TV y domótica" }
    private fun showNotifications() { title.text = "Centro personal"; subtitle.text = "Correo, mensajes, llamadas y notificaciones mediante conectores" }

    private fun sendMessage() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.text.clear()
        append("user", text)
        val backend = prefs.getString("backendUrl", DEFAULT_BACKEND)?.trim().orEmpty().ifBlank { DEFAULT_BACKEND }.trimEnd('/')
        val previous = prefs.getString("response_$conversationId", null)
        val history = historyArray()
        status.text = "● Pensando · memoria activa…"
        Thread {
            try {
                val result = postChat(resolveEndpoint(backend, "chat"), text, history, previous)
                if (!result.second.isNullOrBlank()) prefs.edit().putString("response_$conversationId", result.second).apply()
                runOnUiThread { append("assistant", result.first, true); status.text = "● Conectado · contexto guardado" }
            } catch (e: Exception) {
                val detail = e.message ?: e.javaClass.simpleName
                runOnUiThread { status.text = "● Error · ${detail.take(70)}"; Toast.makeText(this, detail, Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun resolveEndpoint(base: String, endpoint: String): String {
        val clean = base.trim().trimEnd('/').removeSuffix("/api/chat").removeSuffix("/api")
        return "$clean/api/$endpoint"
    }

    private fun postChat(endpoint: String, message: String, history: JSONArray, previousResponseId: String?): Pair<String, String?> {
        val c = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 12000; readTimeout = 60000; doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8"); setRequestProperty("Accept", "application/json"); setRequestProperty("User-Agent", "JarvisTV/0.6.1")
        }
        val historyPayload = JSONArray()
        for (i in 0 until history.length()) {
            val item = history.optJSONObject(i) ?: continue
            if (i == history.length() - 1 && item.optString("role") == "user" && item.optString("content") == message) continue
            historyPayload.put(item)
        }
        val payload = JSONObject()
            .put("message", message)
            .put("conversationId", conversationId)
            .put("client", "jarvis-tv")
            .put("assistantName", assistantName())
            .put("history", historyPayload)
            .apply { if (!previousResponseId.isNullOrBlank()) put("previousResponseId", previousResponseId) }
            .toString()
        c.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            val serverError = runCatching { JSONObject(body).optString("error") }.getOrNull().orEmpty()
            throw IllegalStateException("HTTP $code ${serverError.ifBlank { body.take(220) }}")
        }
        val json = JSONObject(body)
        return Pair(json.optString("reply").ifBlank { json.optString("text") }.ifBlank { body }, json.optString("responseId").ifBlank { null })
    }

    private fun postAudio(endpoint: String, file: File): String {
        val c = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 12000; readTimeout = 60000; doOutput = true
            setRequestProperty("Content-Type", "audio/mp4"); setRequestProperty("Accept", "application/json"); setRequestProperty("X-Filename", "jarvis-voice.m4a")
        }
        c.outputStream.use { out -> file.inputStream().use { it.copyTo(out) } }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) throw IllegalStateException("HTTP $code ${body.take(220)}")
        return JSONObject(body).optString("text").trim().ifBlank { throw IllegalStateException("Transcripción vacía") }
    }

    private fun downloadSpeech(endpoint: String, text: String, outFile: File) {
        val c = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 12000; readTimeout = 60000; doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8"); setRequestProperty("Accept", "audio/mpeg")
        }
        c.outputStream.use { it.write(JSONObject().put("text", text).put("voice", prefs.getString("voice", "alloy") ?: "alloy").toString().toByteArray()) }
        val code = c.responseCode
        if (code !in 200..299) throw IllegalStateException("TTS HTTP $code")
        c.inputStream.use { inputStream -> outFile.outputStream().use { inputStream.copyTo(it) } }
    }

    private fun speakWithOpenAI(text: String) {
        val backend = prefs.getString("backendUrl", DEFAULT_BACKEND).orEmpty().ifBlank { DEFAULT_BACKEND }
        Thread {
            val file = File(cacheDir, "jarvis-reply-${System.currentTimeMillis()}.mp3")
            try {
                downloadSpeech(resolveEndpoint(backend, "speech"), text, file)
                runOnUiThread {
                    voicePlayer?.release()
                    voicePlayer = MediaPlayer().apply {
                        setDataSource(file.absolutePath)
                        setOnCompletionListener { p -> p.release(); if (voicePlayer === p) voicePlayer = null; file.delete() }
                        setOnErrorListener { p, _, _ -> p.release(); if (voicePlayer === p) voicePlayer = null; file.delete(); true }
                        prepare(); start()
                    }
                }
            } catch (_: Exception) {
                file.delete(); runOnUiThread { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis-fallback") }
            }
        }.start()
    }

    private fun testBackend(base: String) {
        status.text = "● Probando backend…"
        Thread {
            try {
                val result = postChat(resolveEndpoint(base.ifBlank { DEFAULT_BACKEND }, "chat"), "Responde únicamente con: OK", JSONArray(), null)
                runOnUiThread { status.text = "● Backend OK"; Toast.makeText(this, "Backend y OpenAI OK: ${result.first.take(80)}", Toast.LENGTH_LONG).show() }
            } catch (e: Exception) {
                runOnUiThread { status.text = "● Backend ERROR"; Toast.makeText(this, "Fallo backend: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun setupRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { recognizer = null; return }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { status.text = "● Escuchando…" }
                override fun onBeginningOfSpeech() { status.text = "● Te escucho…" }
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { status.text = "● Procesando voz…" }
                override fun onError(error: Int) { startServerVoiceCapture() }
                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    if (text.isNotBlank()) { input.setText(text); sendMessage() } else startServerVoiceCapture()
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    private fun startVoiceInput() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO); return
        }
        if (isFireTv()) { startServerVoiceCapture(); return }
        if (recognizer == null) setupRecognizer()
        val r = recognizer
        if (r == null) { startServerVoiceCapture(); return }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        try { r.cancel(); r.startListening(intent) } catch (_: Exception) { startServerVoiceCapture() }
    }

    private fun startServerVoiceCapture() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO); return
        }
        stopRecorder(false)
        val file = File(cacheDir, "jarvis-voice-${System.currentTimeMillis()}.m4a")
        voiceFile = file
        val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
        recorder = mr
        try {
            mr.setAudioSource(if (isFireTv()) MediaRecorder.AudioSource.MIC else MediaRecorder.AudioSource.VOICE_RECOGNITION)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioEncodingBitRate(64000)
            mr.setAudioSamplingRate(16000)
            mr.setOutputFile(file.absolutePath)
            mr.prepare(); mr.start()
            status.text = "● Escuchando 7 s · OpenAI…"
            Toast.makeText(this, "Habla con Jarvis ahora", Toast.LENGTH_SHORT).show()
            handler.postDelayed({ stopRecorder(true) }, 7000)
        } catch (e: Exception) {
            stopRecorder(false); status.text = "● Micrófono no accesible"; Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun stopRecorder(upload: Boolean) {
        val active = recorder ?: return
        try { active.stop() } catch (_: Exception) {}
        try { active.reset(); active.release() } catch (_: Exception) {}
        recorder = null
        val file = voiceFile; voiceFile = null
        if (upload && file != null && file.exists() && file.length() > 256) {
            status.text = "● Transcribiendo con OpenAI…"
            val backend = prefs.getString("backendUrl", DEFAULT_BACKEND).orEmpty().ifBlank { DEFAULT_BACKEND }
            Thread {
                try {
                    val text = postAudio(resolveEndpoint(backend, "transcribe"), file)
                    runOnUiThread { input.setText(text); status.text = "● $text"; sendMessage() }
                } catch (e: Exception) {
                    runOnUiThread { status.text = "● Error transcripción"; Toast.makeText(this, e.message, Toast.LENGTH_LONG).show() }
                } finally { file.delete() }
            }.start()
        } else file?.delete()
    }

    private fun overlayStatus(): String = when { Build.VERSION.SDK_INT < Build.VERSION_CODES.M -> "compatible"; Settings.canDrawOverlays(this) -> "PERMITIDO ✓"; else -> "NO PERMITIDO · usa Accesibilidad" }

    private fun ensureOverlayPermission(force: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) { startBubbleService(); return }
        if (!force) return
        val packageIntent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        try { if (packageIntent.resolveActivity(packageManager) != null) startActivityForResult(packageIntent, REQ_OVERLAY) else openAccessibilitySettings() }
        catch (_: Exception) { openAccessibilitySettings() }
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "Activa 'Jarvis TV bubble' para mantener la burbuja visible", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            AlertDialog.Builder(this).setTitle("Burbuja persistente").setMessage("Activa Jarvis TV en Ajustes → Accesibilidad. ${e.message ?: ""}").setPositiveButton("OK", null).show()
        }
    }

    private fun startBubbleService() {
        try { ContextCompat.startForegroundService(this, Intent(this, OverlayService::class.java)) } catch (_: Exception) {}
    }

    private fun showSettings() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(36, 16, 36, 8) }
        val name = edit("Nombre del asistente", assistantName())
        val wake = edit("Palabra de activación", wakeWord())
        val backend = edit("URL de Jarvis Backend", prefs.getString("backendUrl", DEFAULT_BACKEND).orEmpty().ifBlank { DEFAULT_BACKEND })
        val testBackendButton = Button(this).apply { text = "PROBAR BACKEND / OPENAI"; setOnClickListener { testBackend(backend.text.toString()) } }
        val connections = Button(this).apply { text = "VER WEB + MCP"; setOnClickListener { showConnections() } }
        val newChat = Button(this).apply { text = "NUEVO CHAT"; setOnClickListener { createConversation(true) } }
        val overlayButton = Button(this).apply { text = "PERMISO BURBUJA NORMAL"; setOnClickListener { ensureOverlayPermission(true) } }
        val accessibilityButton = Button(this).apply { text = "BURBUJA SIEMPRE VISIBLE · ACCESIBILIDAD"; setOnClickListener { openAccessibilitySettings() } }
        val micTestButton = Button(this).apply { text = "PROBAR MICRÓFONO DIRECTO"; setOnClickListener { startServerVoiceCapture() } }
        val voiceTestButton = Button(this).apply { text = "PROBAR VOZ OPENAI"; setOnClickListener { speakWithOpenAI("Hola. Esta es la voz de Jarvis usando OpenAI.") } }
        val diagnostics = TextView(this).apply {
            text = "\nBackend: ${prefs.getString("backendUrl", DEFAULT_BACKEND)}\nDispositivo: ${Build.MANUFACTURER} ${Build.MODEL}\nModo Fire TV: ${if (isFireTv()) "SÍ" else "NO"}\nOverlay: ${overlayStatus()}\nChats guardados: ${sortedChats().size}\n\nEntradas de audio:\n${audioInputs()}"
            textSize = 15f
        }
        box.addView(name); box.addView(wake); box.addView(backend); box.addView(testBackendButton); box.addView(connections); box.addView(newChat); box.addView(overlayButton); box.addView(accessibilityButton); box.addView(micTestButton); box.addView(voiceTestButton); box.addView(diagnostics)
        AlertDialog.Builder(this).setTitle("Ajustes de Jarvis TV v0.6.1").setView(box)
            .setPositiveButton("GUARDAR") { _, _ -> prefs.edit().putString("assistantName", name.text.toString().trim().ifBlank { "Jarvis" }).putString("wakeWord", wake.text.toString().trim().ifBlank { "Hola ChatGPT" }).putString("backendUrl", backend.text.toString().trim().ifBlank { DEFAULT_BACKEND }).apply(); showHome() }
            .setNegativeButton("CERRAR", null).show()
    }

    private fun edit(hint: String, value: String) = EditText(this).apply { this.hint = hint; setText(value); isSingleLine = true; setPadding(12, 14, 12, 14) }

    private fun audioInputs(): String {
        val manager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return manager.getDevices(AudioManager.GET_DEVICES_INPUTS).joinToString("\n") { d -> "• ${d.productName} · ${audioType(d.type)} · id ${d.id}" }.ifBlank { "• Android no informa ninguna entrada de audio" }
    }

    private fun audioType(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO"
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Micrófono integrado"
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Auriculares"
        else -> "tipo $type"
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!isFireTv() && event.action == KeyEvent.ACTION_UP && (event.keyCode == KeyEvent.KEYCODE_SEARCH || event.keyCode == KeyEvent.KEYCODE_VOICE_ASSIST)) { startVoiceInput(); return true }
        return super.dispatchKeyEvent(event)
    }

    override fun onInit(code: Int) { if (code == TextToSpeech.SUCCESS) tts?.language = Locale("es", "ES") }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_OVERLAY) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) startBubbleService() else openAccessibilitySettings()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_AUDIO && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startVoiceInput()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null); stopRecorder(false); recognizer?.destroy(); voicePlayer?.release(); voicePlayer = null; tts?.stop(); tts?.shutdown(); super.onDestroy()
    }

    companion object {
        private const val REQ_AUDIO = 10
        private const val REQ_OVERLAY = 30
        private const val DEFAULT_BACKEND = "https://chatgpt-tv2.vercel.app"
    }
}