package com.jarvis.tv

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TranslationService : Service() {
    @Volatile private var running = false
    @Volatile private var targetLanguage = "es"
    private var worker: Thread? = null
    private var recorder: MediaRecorder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_START) {
            ACTION_STOP -> {
                stopTranslation(true)
                return START_NOT_STICKY
            }
            ACTION_SET_LANGUAGE -> {
                targetLanguage = normalizeLanguage(intent.getStringExtra(EXTRA_LANGUAGE))
                prefs().edit().putString("translation_target", targetLanguage).apply()
                if (running) updateNotification()
                return START_STICKY
            }
            else -> {
                targetLanguage = normalizeLanguage(intent?.getStringExtra(EXTRA_LANGUAGE) ?: prefs().getString("translation_target", "es"))
                prefs().edit().putString("translation_target", targetLanguage).putBoolean("translation_active", true).apply()
                stopService(Intent(this, WakeWordService::class.java))
                startForeground(NOTIFICATION_ID, notification())
                if (!running) {
                    running = true
                    worker = Thread { translationLoop() }.also { it.start() }
                }
            }
        }
        return START_STICKY
    }

    private fun translationLoop() {
        while (running) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                prefs().edit().putString("translation_status", "sin permiso de micrófono").apply()
                break
            }
            val file = File(cacheDir, "translate-${System.currentTimeMillis()}.m4a")
            try {
                recordChunk(file, 3200L)
                if (!running) break
                if (!file.exists() || file.length() < 512) continue
                prefs().edit().putString("translation_status", "traduciendo").apply()
                val result = translateAudio(file, targetLanguage)
                val transcript = result.optString("transcript").trim()
                val translated = result.optString("translation").trim()

                val command = transcript.lowercase()
                if (isStopCommand(command)) {
                    stopTranslation(true)
                    return
                }
                commandLanguage(command)?.let { lang ->
                    targetLanguage = lang
                    prefs().edit().putString("translation_target", lang).apply()
                    updateNotification()
                    continue
                }

                if (translated.isNotBlank() && !looksLikeSilence(translated)) {
                    prefs().edit()
                        .putString("translation_last_source", transcript.take(500))
                        .putString("translation_last_text", translated.take(1000))
                        .putString("translation_status", "hablando")
                        .apply()
                    speak(translated)
                }
            } catch (e: Throwable) {
                prefs().edit().putString("translation_status", "error: " + (e.message ?: e.javaClass.simpleName).take(120)).apply()
                try { Thread.sleep(900L) } catch (_: InterruptedException) {}
            } finally {
                file.delete()
            }
        }
        running = false
        prefs().edit().putBoolean("translation_active", false).apply()
    }

    private fun recordChunk(file: File, millis: Long) {
        val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
        recorder = mr
        try {
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioEncodingBitRate(64000)
            mr.setAudioSamplingRate(16000)
            mr.setOutputFile(file.absolutePath)
            mr.prepare()
            mr.start()
            prefs().edit().putString("translation_status", "escuchando").apply()
            var remaining = millis
            while (running && remaining > 0) {
                val step = minOf(200L, remaining)
                Thread.sleep(step)
                remaining -= step
            }
        } finally {
            try { mr.stop() } catch (_: Throwable) {}
            try { mr.reset(); mr.release() } catch (_: Throwable) {}
            recorder = null
        }
    }

    private fun translateAudio(file: File, lang: String): JSONObject {
        val base = prefs().getString("backendUrl", DEFAULT_BACKEND).orEmpty().ifBlank { DEFAULT_BACKEND }
            .trim().trimEnd('/').removeSuffix("/api/chat").removeSuffix("/api")
        val endpoint = "$base/api/translate-audio?target=" + URLEncoder.encode(lang, "UTF-8")
        val c = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 9000
            readTimeout = 45000
            doOutput = true
            setRequestProperty("Content-Type", "audio/mp4")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-Filename", "firetv-translate.m4a")
            setRequestProperty("User-Agent", "Javistv/0.6.18")
        }
        c.outputStream.use { out -> file.inputStream().use { it.copyTo(out) } }
        val code = c.responseCode
        val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) throw IllegalStateException("translate HTTP $code " + body.take(160))
        return JSONObject(body)
    }

    private fun speak(text: String) {
        val base = prefs().getString("backendUrl", DEFAULT_BACKEND).orEmpty().ifBlank { DEFAULT_BACKEND }
            .trim().trimEnd('/').removeSuffix("/api/chat").removeSuffix("/api")
        val file = File(cacheDir, "translated-${System.currentTimeMillis()}.mp3")
        try {
            val c = (URL("$base/api/speech").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 9000
                readTimeout = 45000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "audio/mpeg")
            }
            val payload = JSONObject()
                .put("text", text.take(1200))
                .put("voice", prefs().getString("translation_voice", "coral") ?: "coral")
                .put("speed", 1.06)
                .toString()
            c.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            if (c.responseCode !in 200..299) throw IllegalStateException("speech HTTP " + c.responseCode)
            c.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }

            val done = CountDownLatch(1)
            val player = MediaPlayer()
            player.setDataSource(file.absolutePath)
            player.setOnCompletionListener { p -> p.release(); done.countDown() }
            player.setOnErrorListener { p, _, _ -> p.release(); done.countDown(); true }
            player.prepare()
            player.start()
            done.await(30, TimeUnit.SECONDS)
        } finally {
            file.delete()
        }
    }

    private fun stopTranslation(restartWake: Boolean) {
        running = false
        try { recorder?.stop() } catch (_: Throwable) {}
        try { recorder?.release() } catch (_: Throwable) {}
        recorder = null
        worker?.interrupt()
        prefs().edit().putBoolean("translation_active", false).putString("translation_status", "detenida").apply()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        if (restartWake && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            runCatching { ContextCompat.startForegroundService(this, Intent(this, WakeWordService::class.java)) }
        }
    }

    private fun isStopCommand(s: String): Boolean =
        s.contains("jarvis") && (s.contains("para la tradu") || s.contains("deten la tradu") || s.contains("detén la tradu") || s.contains("stop translation") || s.contains("deja de traduc"))

    private fun commandLanguage(s: String): String? {
        if (!s.contains("jarvis") || !(s.contains("tradu") || s.contains("idioma"))) return null
        return when {
            s.contains("español") || s.contains("castellano") || s.contains("spanish") -> "es"
            s.contains("inglés") || s.contains("ingles") || s.contains("english") -> "en"
            s.contains("francés") || s.contains("frances") || s.contains("french") -> "fr"
            s.contains("alemán") || s.contains("aleman") || s.contains("german") -> "de"
            s.contains("italiano") || s.contains("italian") -> "it"
            s.contains("portugués") || s.contains("portugues") || s.contains("portuguese") -> "pt"
            else -> null
        }
    }

    private fun looksLikeSilence(s: String): Boolean {
        val x = s.trim().lowercase()
        return x.isBlank() || x == "[silence]" || x == "(silence)" || x == "silencio" || x == "..."
    }

    private fun normalizeLanguage(raw: String?): String = when (raw?.trim()?.lowercase()) {
        "en", "english", "inglés", "ingles" -> "en"
        "fr", "french", "francés", "frances" -> "fr"
        "de", "german", "alemán", "aleman" -> "de"
        "it", "italian", "italiano" -> "it"
        "pt", "portuguese", "portugués", "portugues" -> "pt"
        else -> "es"
    }

    private fun prefs() = getSharedPreferences("jarvis", MODE_PRIVATE)

    private fun languageLabel(code: String) = when (code) {
        "en" -> "inglés"; "fr" -> "francés"; "de" -> "alemán"; "it" -> "italiano"; "pt" -> "portugués"; else -> "español"
    }

    private fun notification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setContentTitle("Javistv · traducción activa")
        .setContentText("Traduciendo a " + languageLabel(targetLanguage) + " · di “Jarvis, para la traducción”")
        .setOngoing(true)
        .setSilent(true)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        .build()

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Traducción de vídeo", NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null) }
            )
        }
    }

    override fun onDestroy() {
        running = false
        try { recorder?.release() } catch (_: Throwable) {}
        recorder = null
        prefs().edit().putBoolean("translation_active", false).apply()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.jarvis.tv.translation.START"
        const val ACTION_STOP = "com.jarvis.tv.translation.STOP"
        const val ACTION_SET_LANGUAGE = "com.jarvis.tv.translation.LANGUAGE"
        const val EXTRA_LANGUAGE = "language"
        private const val CHANNEL_ID = "jarvis_translation"
        private const val NOTIFICATION_ID = 617
        private const val DEFAULT_BACKEND = "https://chatgpt-tv2.vercel.app"
    }
}
