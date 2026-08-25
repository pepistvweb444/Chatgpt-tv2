package com.jarvis.mobile

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.text.Normalizer

class WakeWordService : Service() {
    private var model: Model? = null
    private var speechService: SpeechService? = null
    private var armedUntil = 0L
    private var ignoreUntil = 0L

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).putExtra("hands_free", true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        startForeground(
            71,
            NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Jarvis escuchando")
                .setContentText("Di “Hola Jarvis” o “Hola Ale”")
                .setOngoing(true)
                .setContentIntent(open)
                .build()
        )
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf(); return
        }
        StorageService.unpack(
            this,
            "model-es",
            "vosk-wake-model",
            { m -> model = m; startListening() },
            { stopSelf() }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun startListening() {
        val m = model ?: return
        runCatching { speechService?.shutdown() }
        val recognizer = Recognizer(m, 16000.0f)
        speechService = SpeechService(recognizer, 16000.0f).also { service ->
            service.startListening(object : RecognitionListener {
                override fun onPartialResult(hypothesis: String?) {
                    val text = parse(hypothesis, "partial")
                    if (text.isNotBlank()) handle(text, false)
                }
                override fun onResult(hypothesis: String?) {
                    val text = parse(hypothesis, "text")
                    if (text.isNotBlank()) handle(text, true)
                }
                override fun onFinalResult(hypothesis: String?) {
                    val text = parse(hypothesis, "text")
                    if (text.isNotBlank()) handle(text, true)
                }
                override fun onError(exception: Exception?) {
                    android.os.Handler(mainLooper).postDelayed({ if (model != null) startListening() }, 1200L)
                }
                override fun onTimeout() {
                    android.os.Handler(mainLooper).postDelayed({ if (model != null) startListening() }, 600L)
                }
            })
        }
    }

    private fun parse(raw: String?, key: String): String = runCatching {
        JSONObject(raw.orEmpty()).optString(key).trim()
    }.getOrDefault("")

    private fun normalizeSpeech(value: String): String = Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("[^a-z0-9 ]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun isWakePhrase(raw: String): Boolean {
        val text = normalizeSpeech(raw)
        val jarvisVariants = listOf("jarvis", "yarvis", "yervis", "yerviz", "jervis", "jerviz", "harvis")
        val aleVariants = listOf("ale", "hale")
        val prefixes = listOf("hola", "oye", "hey", "ey")
        val greeting = prefixes.any { text.contains(it) }
        val jarvis = jarvisVariants.any { text.contains(it) }
        val ale = aleVariants.any { Regex("(^| )${Regex.escape(it)}( |$)").containsMatchIn(text) }
        return greeting && (jarvis || ale)
    }

    private fun stripWakePhrase(raw: String): String {
        var text = raw
        val patterns = listOf(
            "hola jarvis", "hola yarvis", "hola yervis", "hola yerviz", "hola jervis", "hola jerviz", "hola harvis",
            "oye jarvis", "hey jarvis", "hola ale", "hola hale", "oye ale", "hey ale"
        )
        patterns.forEach { p -> text = text.replace(Regex(Regex.escape(p), RegexOption.IGNORE_CASE), " ") }
        return text.replace(Regex("\\s+"), " ").trim()
    }

    private fun handle(raw: String, finalChunk: Boolean) {
        val now = System.currentTimeMillis()
        if (now < ignoreUntil) return
        if (armedUntil > now) {
            if (!finalChunk) return
            val command = stripWakePhrase(raw)
            if (command.isBlank() || isWakePhrase(command)) return
            armedUntil = 0L
            ignoreUntil = now + 9000L
            showOverlayCommand(command)
            return
        }
        if (isWakePhrase(raw)) {
            val inlineCommand = stripWakePhrase(raw)
            showOverlay("Te escucho…")
            if (inlineCommand.isNotBlank()) {
                ignoreUntil = now + 9000L
                showOverlayCommand(inlineCommand)
            } else {
                armedUntil = now + 8000L
            }
        }
    }

    private fun showOverlay(text: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
            startService(Intent(this, JarvisOverlayService::class.java).apply {
                action = JarvisOverlayService.ACTION_SHOW
                putExtra(JarvisOverlayService.EXTRA_TEXT, text)
            })
        }
    }

    private fun showOverlayCommand(command: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
            startService(Intent(this, JarvisOverlayService::class.java).apply {
                action = JarvisOverlayService.ACTION_COMMAND
                putExtra(JarvisOverlayService.EXTRA_COMMAND, command)
            })
        }
    }

    override fun onDestroy() {
        runCatching { speechService?.shutdown() }
        speechService = null
        runCatching { model?.close() }
        model = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Jarvis wake word", NotificationManager.IMPORTANCE_LOW))
        }
    }

    companion object { private const val CHANNEL = "jarvis_wake_word" }
}
