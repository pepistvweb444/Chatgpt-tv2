package com.jarvis.tv

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * Foreground wake-word listener. It keeps the lightweight Android speech recognizer
 * alive while Jarvis' UI is closed and opens the conversation when the configured
 * wake phrase (or "Hola Jarvis") is detected.
 *
 * Android/Google TV vendors can still suspend microphone access under aggressive
 * power-management rules; in that case the service restarts recognition as soon as
 * the platform makes it available again.
 */
class WakeWordService : Service(), RecognitionListener {
    private var recognizer: SpeechRecognizer? = null
    private var restarting = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, notification())
        startRecognition()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startRecognition()
        return START_STICKY
    }

    override fun onDestroy() {
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startRecognition() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        if (!SpeechRecognizer.isRecognitionAvailable(this) || restarting) return
        restarting = true
        try {
            if (recognizer == null) recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { it.setRecognitionListener(this) }
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.forLanguageTag("es-ES").toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 650L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 450L)
            }
            recognizer?.startListening(i)
        } catch (_: Exception) {
            scheduleRestart(1500)
        } finally {
            restarting = false
        }
    }

    private fun scheduleRestart(delay: Long = 450L) {
        restarting = false
        android.os.Handler(mainLooper).postDelayed({ startRecognition() }, delay)
    }

    private fun inspect(results: ArrayList<String>?) {
        val configured = getSharedPreferences("jarvis", MODE_PRIVATE)
            .getString("wakeWord", "Hola Jarvis").orEmpty().trim().lowercase()
        val accepted = listOf(configured, "hola jarvis", "jarvis").filter { it.isNotBlank() }
        val heard = results.orEmpty().joinToString(" ").lowercase()
        if (accepted.any { heard.contains(it) }) activateJarvis()
    }

    private fun activateJarvis() {
        recognizer?.cancel()
        val i = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("start_voice", true)
            putExtra("wake_word_triggered", true)
        }
        runCatching { startActivity(i) }
        scheduleRestart(1800)
    }

    private fun notification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setContentTitle("Jarvis está escuchando")
        .setContentText("Di “Hola Jarvis” para hablar")
        .setOngoing(true)
        .setSilent(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Hola Jarvis", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Escucha persistente de la palabra de activación"
                setSound(null, null)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onReadyForSpeech(params: android.os.Bundle?) = Unit
    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() = Unit
    override fun onError(error: Int) = scheduleRestart(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1200 else 450)
    override fun onResults(results: android.os.Bundle?) {
        inspect(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION))
        scheduleRestart()
    }
    override fun onPartialResults(partialResults: android.os.Bundle?) {
        inspect(partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION))
    }
    override fun onEvent(eventType: Int, params: android.os.Bundle?) = Unit

    companion object {
        private const val CHANNEL_ID = "jarvis_wake_word"
        private const val NOTIFICATION_ID = 7042
    }
}
