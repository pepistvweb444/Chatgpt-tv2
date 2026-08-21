package com.jarvis.tv

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
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
    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null
    private var recorder: MediaRecorder? = null
    private var voiceFile: File? = null
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
        tts = TextToSpeech(this, this)
        conversationId = prefs.getString("conversationId", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("conversationId", it).apply()
        }
        if (prefs.getString("backendUrl", "").isNullOrBlank()) {
            prefs.edit().putString("backendUrl", DEFAULT_BACKEND).apply()
        }
        restoreHistory()
        bindUi()
        setupRecognizer()
        showHome()
        ensureOverlayPermission(true)
        if (intent?.getBooleanExtra("start_voice", false) == true) {
            intent.removeExtra("start_voice")
            handler.postDelayed({ startVoiceInput() }, 600)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("start_voice", false)) {
            intent.removeExtra("start_voice")
            handler.postDelayed({ startVoiceInput() }, 300)
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
        findViewById<Button>(R.id.visionButton).setOnClickListener { showVision() }
        findViewById<Button>(R.id.homeControlButton).setOnClickListener { showHomeControls() }
        findViewById<Button>(R.id.routinesButton).setOnClickListener { showRoutines() }
        findViewById<Button>(R.id.notificationsButton).setOnClickListener { showNotifications() }
        input.setOnEditorActionListener { _, _, _ -> sendMessage(); true }
    }

    private fun assistantName() = prefs.getString("assistantName", "Jarvis") ?: "Jarvis"
    private fun wakeWord() = prefs.getString("wakeWord", "Hola ChatGPT") ?: "Hola ChatGPT"

    private fun showHome() {
        title.text = "${assistantName()} · Vision Home"
        subtitle.text = "Información contextual, voz, casa y comunicaciones en un solo lugar"
        status.text = "● Listo · ${wakeWord()}"
        if (transcript.text.isBlank()) appendAssistant("Hola. Soy ${assistantName()}. Pulsa el micrófono del mando o la burbuja AI para hablar conmigo.", false)
    }

    private fun showChat() { title.text = "ChatGPT"; subtitle.text = "Conversación Jarvis sincronizable con TV y móvil"; input.requestFocus() }
    private fun showVision() { title.text = "Vision AI"; subtitle.text = "Consulta lo que aparece en pantalla"; appendSystem("Vision usará MediaProjection y backend multimodal cuando el contenido permita captura.") }
    private fun showHomeControls() { title.text = "Casa"; subtitle.text = "Luces, persianas, climatización, escenas y sensores"; appendSystem("Conectores preparados para Homey / SmartThings / Home Assistant / IFTTT.") }
    private fun showRoutines() { title.text = "Rutinas"; subtitle.text = "Combina briefing, TV y domótica"; appendSystem("Ejemplo: 08:00 → persianas → luces → calendario → tiempo → avisos.") }
    private fun showNotifications() { title.text = "Centro personal"; subtitle.text = "Llamadas, mensajes, correo y notificaciones del móvil compañero"; appendSystem("Preparado para recibir el feed normalizado del móvil y backend.") }

    private fun sendMessage() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        appendUser(text)
        input.text.clear()
        val backend = prefs.getString("backendUrl", DEFAULT_BACKEND)?.trim().orEmpty().ifBlank { DEFAULT_BACKEND }.trimEnd('/')
        status.text = "● Conectando…"
        Thread {
            try {
                val reply = postChat(resolveChatEndpoint(backend), text)
                runOnUiThread { appendAssistant(reply); status.text = "● Conectado · ${wakeWord()}" }
            } catch (e: Exception) {
                val detail = e.message ?: e.javaClass.simpleName
                runOnUiThread { appendAssistant("Error de backend: $detail", false); status.text = "● Error · ${detail.take(70)}" }
            }
        }.start()
    }

    private fun resolveChatEndpoint(base: String): String {
        val clean = base.trim().trimEnd('/')
        return when { clean.endsWith("/api/chat") -> clean; clean.endsWith("/api") -> "$clean/chat"; else -> "$clean/api/chat" }
    }

    private fun resolveTranscribeEndpoint(base: String): String {
        val clean = base.trim().trimEnd('/')
        val root = when { clean.endsWith("/api/chat") -> clean.removeSuffix("/api/chat"); clean.endsWith("/api") -> clean.removeSuffix("/api"); else -> clean }
        return "$root/api/transcribe"
    }

    private fun postChat(endpoint: String, message: String): String {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 12000; readTimeout = 60000; doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8"); setRequestProperty("Accept", "application/json"); setRequestProperty("User-Agent", "JarvisTV/0.4.2")
        }
        val payload = JSONObject().put("message", message).put("conversationId", conversationId).put("client", "jarvis-tv").put("assistantName", assistantName()).toString()
        connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            val serverError = runCatching { JSONObject(body).optString("error") }.getOrNull().orEmpty()
            throw IllegalStateException("HTTP $code ${serverError.ifBlank { body.take(220) }}")
        }
        val json = JSONObject(body)
        return json.optString("reply").ifBlank { json.optString("text") }.ifBlank { body }
    }

    private fun postAudio(endpoint: String, file: File): String {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 12000; readTimeout = 60000; doOutput = true
            setRequestProperty("Content-Type", "audio/mp4"); setRequestProperty("Accept", "application/json"); setRequestProperty("X-Filename", "jarvis-voice.m4a")
        }
        connection.outputStream.use { out -> file.inputStream().use { input -> input.copyTo(out) } }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) throw IllegalStateException("HTTP $code ${body.take(220)}")
        val json = JSONObject(body)
        return json.optString("text").trim().ifBlank { throw IllegalStateException("Transcripción vacía") }
    }

    private fun testBackend(base: String) {
        val clean = base.trim().ifBlank { DEFAULT_BACKEND }
        status.text = "● Probando backend…"
        Thread {
            try {
                val reply = postChat(resolveChatEndpoint(clean), "Responde únicamente con: OK")
                runOnUiThread { status.text = "● Backend OK"; Toast.makeText(this, "Backend y OpenAI OK: ${reply.take(80)}", Toast.LENGTH_LONG).show(); appendSystem("Diagnóstico: Vercel ✓ · OpenAI ✓ · respuesta ✓") }
            } catch (e: Exception) {
                val detail = e.message ?: e.javaClass.simpleName
                runOnUiThread { status.text = "● Backend ERROR"; Toast.makeText(this, "Fallo backend: $detail", Toast.LENGTH_LONG).show(); appendSystem("Diagnóstico backend: $detail") }
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
                override fun onError(error: Int) {
                    status.text = "● Voz local no disponible · usando OpenAI…"
                    if (error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_SERVER || error == SpeechRecognizer.ERROR_AUDIO || error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) startServerVoiceCapture()
                    else Toast.makeText(this@MainActivity, speechError(error), Toast.LENGTH_LONG).show()
                }
                override fun onResults(results: Bundle?) {
                    status.text = "● Listo · ${wakeWord()}"
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    if (text.isNotBlank()) { input.setText(text); sendMessage() }
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    if (text.isNotBlank()) status.text = "● $text"
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    private fun startVoiceInput() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO); return
        }
        if (recognizer == null) setupRecognizer()
        val r = recognizer
        if (r == null) { startServerVoiceCapture(); return }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM); putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES"); putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true); putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
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
        val mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
        recorder = mediaRecorder
        try {
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mediaRecorder.setAudioEncodingBitRate(64000)
            mediaRecorder.setAudioSamplingRate(16000)
            mediaRecorder.setOutputFile(file.absolutePath)
            mediaRecorder.prepare(); mediaRecorder.start()
            status.text = "● Escuchando 6 s · OpenAI…"
            Toast.makeText(this, "Habla ahora", Toast.LENGTH_SHORT).show()
            handler.postDelayed({ stopRecorder(true) }, 6000)
        } catch (e: Exception) {
            stopRecorder(false)
            status.text = "● Micrófono no accesible"
            Toast.makeText(this, "Android no entrega audio a Jarvis: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopRecorder(upload: Boolean) {
        val active = recorder ?: return
        try { active.stop() } catch (_: Exception) {}
        try { active.reset(); active.release() } catch (_: Exception) {}
        recorder = null
        val file = voiceFile
        voiceFile = null
        if (upload && file != null && file.exists() && file.length() > 256) {
            status.text = "● Transcribiendo con OpenAI…"
            val backend = prefs.getString("backendUrl", DEFAULT_BACKEND).orEmpty().ifBlank { DEFAULT_BACKEND }
            Thread {
                try {
                    val text = postAudio(resolveTranscribeEndpoint(backend), file)
                    runOnUiThread { input.setText(text); status.text = "● $text"; sendMessage() }
                } catch (e: Exception) {
                    val detail = e.message ?: e.javaClass.simpleName
                    runOnUiThread { status.text = "● Error transcripción"; Toast.makeText(this, "Transcripción: $detail", Toast.LENGTH_LONG).show() }
                } finally { file.delete() }
            }.start()
        } else file?.delete()
    }

    private fun speechError(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "Error al acceder al audio."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Falta permiso de micrófono."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "El reconocimiento local no ha podido conectar con la red."
        SpeechRecognizer.ERROR_NO_MATCH -> "No he podido entender la voz."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "El reconocedor está ocupado."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No se ha detectado voz."
        else -> "Error de reconocimiento de voz ($code)."
    }

    private fun overlayStatus(): String = when { Build.VERSION.SDK_INT < Build.VERSION_CODES.M -> "compatible"; Settings.canDrawOverlays(this) -> "PERMITIDO ✓"; else -> "NO PERMITIDO ✗" }

    private fun ensureOverlayPermission(force: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) { startBubbleService(); return }
        if (!force) return
        val packageIntent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        val genericIntent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
        try {
            when { packageIntent.resolveActivity(packageManager) != null -> startActivityForResult(packageIntent, REQ_OVERLAY); genericIntent.resolveActivity(packageManager) != null -> startActivityForResult(genericIntent, REQ_OVERLAY); else -> throw IllegalStateException("El firmware no ofrece la pantalla de permiso overlay") }
            Toast.makeText(this, "Activa 'Mostrar sobre otras apps' para Jarvis TV", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            AlertDialog.Builder(this).setTitle("Permiso de burbuja").setMessage("Ve a Ajustes → Apps → Acceso especial → Mostrar sobre otras apps → Jarvis TV.\n\n${e.message}").setPositiveButton("OK", null).show()
        }
    }

    private fun startBubbleService() {
        try { ContextCompat.startForegroundService(this, Intent(this, OverlayService::class.java)) }
        catch (e: Exception) { Toast.makeText(this, "No se pudo iniciar la burbuja: ${e.message}", Toast.LENGTH_LONG).show() }
    }

    private fun showSettings() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(36, 16, 36, 8) }
        val name = edit("Nombre del asistente", assistantName())
        val wake = edit("Palabra de activación", wakeWord())
        val backend = edit("URL de Jarvis Backend", prefs.getString("backendUrl", DEFAULT_BACKEND).orEmpty().ifBlank { DEFAULT_BACKEND })
        val testBackendButton = Button(this).apply { text = "PROBAR BACKEND / OPENAI"; setOnClickListener { prefs.edit().putString("backendUrl", backend.text.toString().trim().ifBlank { DEFAULT_BACKEND }).apply(); testBackend(backend.text.toString()) } }
        val overlayButton = Button(this).apply { text = "ACTIVAR BURBUJA FLOTANTE"; setOnClickListener { ensureOverlayPermission(true) } }
        val micTestButton = Button(this).apply { text = "PROBAR MICRÓFONO"; setOnClickListener { startVoiceInput() } }
        val diagnostics = TextView(this).apply {
            text = "\nBackend: ${prefs.getString("backendUrl", DEFAULT_BACKEND)}\nOverlay: ${overlayStatus()}\nReconocedor Android: ${if (SpeechRecognizer.isRecognitionAvailable(this@MainActivity)) "disponible" else "NO disponible · se usará OpenAI"}\n\nEntradas de audio:\n${audioInputs()}"
            textSize = 15f
        }
        box.addView(name); box.addView(wake); box.addView(backend); box.addView(testBackendButton); box.addView(overlayButton); box.addView(micTestButton); box.addView(diagnostics)
        AlertDialog.Builder(this).setTitle("Ajustes de Jarvis TV v0.4.2").setView(box)
            .setPositiveButton("GUARDAR") { _, _ -> prefs.edit().putString("assistantName", name.text.toString().trim().ifBlank { "Jarvis" }).putString("wakeWord", wake.text.toString().trim().ifBlank { "Hola ChatGPT" }).putString("backendUrl", backend.text.toString().trim().ifBlank { DEFAULT_BACKEND }).apply(); showHome() }
            .setNeutralButton("BORRAR HISTORIAL") { _, _ -> prefs.edit().remove("history").apply(); transcript.text = ""; showHome() }
            .setNegativeButton("CERRAR", null).show()
    }

    private fun edit(hint: String, value: String) = EditText(this).apply { this.hint = hint; setText(value); isSingleLine = true; setPadding(12, 14, 12, 14) }

    private fun audioInputs(): String {
        val manager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return manager.getDevices(AudioManager.GET_DEVICES_INPUTS).joinToString("\n") { d -> "• ${d.productName} · ${audioType(d.type)} · id ${d.id}" }.ifBlank { "• Android no informa ninguna entrada de audio" }
    }

    private fun audioType(type: Int): String = when (type) { AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO"; AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Micrófono integrado"; AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB"; AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Auriculares"; else -> "tipo $type" }
    private fun appendUser(text: String) { appendLine("\nTÚ\n$text\n") }
    private fun appendAssistant(text: String, speak: Boolean = true) { appendLine("\n${assistantName().uppercase()}\n$text\n"); if (speak && prefs.getBoolean("tts", true)) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis") }
    private fun appendSystem(text: String) = appendLine("\nSISTEMA\n$text\n")
    private fun appendLine(text: String) { transcript.append(text); prefs.edit().putString("history", (prefs.getString("history", "").orEmpty() + text).takeLast(24000)).apply() }
    private fun restoreHistory() { transcript.text = prefs.getString("history", "").orEmpty() }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP && (event.keyCode == KeyEvent.KEYCODE_SEARCH || event.keyCode == KeyEvent.KEYCODE_VOICE_ASSIST)) { startVoiceInput(); return true }
        return super.dispatchKeyEvent(event)
    }

    override fun onInit(code: Int) { if (code == TextToSpeech.SUCCESS) tts?.language = Locale("es", "ES") }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_OVERLAY) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) { startBubbleService(); Toast.makeText(this, "Permiso concedido. Burbuja iniciada.", Toast.LENGTH_LONG).show() }
            else Toast.makeText(this, "El permiso de superposición sigue desactivado", Toast.LENGTH_LONG).show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_AUDIO && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startVoiceInput()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null); stopRecorder(false); recognizer?.destroy(); tts?.stop(); tts?.shutdown(); super.onDestroy()
    }

    companion object {
        private const val REQ_AUDIO = 10
        private const val REQ_OVERLAY = 30
        private const val DEFAULT_BACKEND = "https://chatgpt-tv2.vercel.app"
    }
}
