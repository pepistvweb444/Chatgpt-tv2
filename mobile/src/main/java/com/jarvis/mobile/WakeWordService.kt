package com.jarvis.mobile

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.text.Normalizer
import java.util.Locale

class WakeWordService : Service() {
    @Volatile private var running = false
    @Volatile private var triggered = false
    private var recognizer: SpeechRecognizer? = null
    private val main = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("jarvis_mobile", MODE_PRIVATE) }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val open = PendingIntent.getActivity(this, 0, Intent(this, ChatActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        startForeground(
            71,
            NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Leo / Jarvis escuchando")
                .setContentText("Di “Hola Leo” o “Hola Jarvis”")
                .setOngoing(true)
                .setContentIntent(open)
                .build()
        )
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf(); return
        }
        running = true
        main.post { startRecognizer() }
    }

    private fun startRecognizer() {
        if (!running || triggered) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            main.postDelayed({ startRecognizer() }, 1500L)
            return
        }
        runCatching { recognizer?.destroy() }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}

                override fun onPartialResults(partialResults: Bundle?) {
                    val candidates = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                    candidates.firstOrNull { matchesWakeWord(normalize(it)) }?.let { trigger() }
                }

                override fun onResults(results: Bundle?) {
                    val candidates = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                    if (candidates.any { matchesWakeWord(normalize(it)) }) trigger() else restartSoon(120L)
                }

                override fun onError(error: Int) {
                    if (running && !triggered) restartSoon(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 450L else 180L)
                }
            })
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 300L)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        runCatching { recognizer?.startListening(intent) }.onFailure { restartSoon(500L) }
    }

    private fun restartSoon(delay: Long) {
        main.postDelayed({ if (running && !triggered) startRecognizer() }, delay)
    }

    private fun trigger() {
        if (!running || triggered) return
        triggered = true
        runCatching { recognizer?.cancel() }
        val i = Intent(this, ChatActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("wake_word_triggered", true)
            putExtra("continuous_voice", true)
        }
        runCatching { startActivity(i) }
        main.postDelayed({ triggered = false; if (running) startRecognizer() }, 3000L)
    }

    private fun matchesWakeWord(text: String): Boolean {
        if (text.isBlank()) return false
        val configured = prefs.getString("wake_names", "leo,jarvis,ale").orEmpty()
            .split(',').map { normalize(it) }.filter { it.isNotBlank() }
            .ifEmpty { listOf("leo", "jarvis", "ale") }
        return configured.any { name ->
            text == name || text.contains("hola $name") || text.contains("oye $name") || text.contains("hey $name") || text.contains("eh $name")
        }
    }

    private fun normalize(value: String): String = Normalizer.normalize(value.lowercase(Locale.getDefault()), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("[^a-z0-9 ]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    override fun onDestroy() {
        running = false
        main.removeCallbacksAndMessages(null)
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Leo / Jarvis wake word", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    companion object { private const val CHANNEL = "jarvis_wake_word" }
}
