package com.jarvis.tv

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
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
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class TranslationService : Service() {
    @Volatile private var running = false
    @Volatile private var targetLanguage = "es"

    private var captureWorker: Thread? = null
    private var translateWorker: Thread? = null
    private var playbackWorker: Thread? = null
    private var recorder: MediaRecorder? = null
    private var currentPlayer: MediaPlayer? = null

    private val audioQueue = LinkedBlockingQueue<File>(4)
    private val playbackQueue = LinkedBlockingQueue<DubSegment>(5)
    private val sessionId = UUID.randomUUID().toString().replace("-", "").take(20)

    @Volatile private var lastDubText = ""
    @Volatile private var sequence = 0L

    data class DubSegment(
        val file: File,
        val translated: String,
        val voiceMode: String
    )

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
                updateNotification()
                return START_STICKY
            }
            else -> {
                targetLanguage = normalizeLanguage(
                    intent?.getStringExtra(EXTRA_LANGUAGE)
                        ?: prefs().getString("translation_target", "es")
                )
                prefs().edit()
                    .putString("translation_target", targetLanguage)
                    .putBoolean("translation_active", true)
                    .putString("translation_status", "iniciando doblaje continuo")
                    .apply()

                stopService(Intent(this, WakeWordService::class.java))
                startForeground(NOTIFICATION_ID, notification())
                if (!running) {
                    running = true
                    startPipeline()
                }
            }
        }
        return START_STICKY
    }

    private fun startPipeline() {
        captureWorker = Thread({ captureLoop() }, "JavistvDubCapture").also { it.start() }
        translateWorker = Thread({ translateLoop() }, "JavistvDubTranslate").also { it.start() }
        playbackWorker = Thread({ playbackLoop() }, "JavistvDubPlayback").also { it.start() }
    }

    private fun captureLoop() {
        while (running) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                prefs().edit().putString("translation_status", "sin permiso de micrófono").apply()
                break
            }

            val file = File(cacheDir, "dub-source-${System.currentTimeMillis()}.m4a")
            try {
                recordChunk(file, CHUNK_MS)
                if (!running) break
                if (!file.exists() || file.length() < MIN_AUDIO_BYTES) {
                    file.delete()
                    continue
                }

                if (!audioQueue.offer(file)) {
                    audioQueue.poll()?.delete()
                    audioQueue.offer(file)
                }
                prefs().edit().putString(
                    "translation_status",
                    "escuchando · cola ${audioQueue.size} · ${languageLabel(targetLanguage)}"
                ).apply()
            } catch (e: Throwable) {
                file.delete()
                prefs().edit().putString(
                    "translation_status",
                    "captura: " + (e.message ?: e.javaClass.simpleName).take(100)
                ).apply()
                sleepQuietly(500)
            }
        }
    }

    private fun translateLoop() {
        while (running) {
            val source = try {
                audioQueue.poll(1, TimeUnit.SECONDS) ?: continue
            } catch (_: InterruptedException) {
                continue
            }

            try {
                val lang = targetLanguage
                prefs().edit().putString("translation_status", "transcribiendo / traduciendo").apply()
                val result = translateAudio(source, lang)
                val transcript = result.optString("transcript").trim()
                val translated = result.optString("translation").trim()

                if (transcript.isBlank() || translated.isBlank() || looksLikeSilence(translated)) continue
                if (looksLikeOwnDub(transcript)) continue

                val command = transcript.lowercase()
                if (isStopCommand(command)) {
                    stopTranslation(true)
                    return
                }

                val requestedLanguage = commandLanguage(command)
                if (requestedLanguage != null) {
                    targetLanguage = requestedLanguage
                    prefs().edit().putString("translation_target", requestedLanguage).apply()
                    updateNotification()
                    continue
                }

                prefs().edit()
                    .putString("translation_last_source", transcript.take(700))
                    .putString("translation_last_text", translated.take(1000))
                    .putString("translation_status", "generando voz del hablante")
                    .apply()

                sendBroadcast(
                    Intent(JarvisAccessibilityService.ACTION_SHOW_TRANSLATION)
                        .setPackage(packageName)
                        .putExtra("text", translated.take(1000))
                )

                val dubbed = synthesizeDub(source, translated, lang)
                lastDubText = normalizeText(translated)
                if (!playbackQueue.offer(dubbed)) {
                    playbackQueue.poll()?.file?.delete()
                    playbackQueue.offer(dubbed)
                }
            } catch (e: Throwable) {
                prefs().edit().putString(
                    "translation_status",
                    "traducción: " + (e.message ?: e.javaClass.simpleName).take(140)
                ).apply()
                sleepQuietly(350)
            } finally {
                source.delete()
            }
        }
    }

    private fun playbackLoop() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        while (running) {
            val segment = try {
                playbackQueue.poll(1, TimeUnit.SECONDS) ?: continue
            } catch (_: InterruptedException) {
                continue
            }

            try {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )

                prefs().edit().putString(
                    "translation_status",
                    "doblando · ${segment.voiceMode} · ${languageLabel(targetLanguage)}"
                ).apply()

                val done = CountDownLatch(1)
                val player = MediaPlayer()
                currentPlayer = player
                player.setAudioStreamType(AudioManager.STREAM_MUSIC)
                player.setDataSource(segment.file.absolutePath)
                player.setOnCompletionListener {
                    it.release()
                    if (currentPlayer === it) currentPlayer = null
                    done.countDown()
                }
                player.setOnErrorListener { p, _, _ ->
                    p.release()
                    if (currentPlayer === p) currentPlayer = null
                    done.countDown()
                    true
                }
                player.prepare()
                player.start()
                done.await(20, TimeUnit.SECONDS)
            } catch (e: Throwable) {
                prefs().edit().putString(
                    "translation_status",
                    "audio doblado: " + (e.message ?: e.javaClass.simpleName).take(120)
                ).apply()
            } finally {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
                segment.file.delete()
            }
        }
    }

    private fun buildRecorder(file: File, source: Int): MediaRecorder {
        val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        mr.setAudioSource(source)
        mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        mr.setAudioEncodingBitRate(64000)
        mr.setAudioSamplingRate(16000)
        mr.setOutputFile(file.absolutePath)
        return mr
    }

    private fun recordChunk(file: File, millis: Long) {
        var mr: MediaRecorder? = null
        try {
            mr = runCatching {
                buildRecorder(file, MediaRecorder.AudioSource.VOICE_RECOGNITION).also { it.prepare() }
            }.getOrElse {
                runCatching { mr?.release() }
                buildRecorder(file, MediaRecorder.AudioSource.MIC).also { it.prepare() }
            }
            recorder = mr
            mr.start()
            var remaining = millis
            while (running && remaining > 0) {
                val step = minOf(100L, remaining)
                Thread.sleep(step)
                remaining -= step
            }
        } finally {
            runCatching { mr?.stop() }
            runCatching { mr?.reset() }
            runCatching { mr?.release() }
            if (recorder === mr) recorder = null
        }
    }

    private fun translateAudio(file: File, lang: String): JSONObject {
        val base = backendBase()
        val endpoint = "$base/api/translate-audio?target=" + URLEncoder.encode(lang, "UTF-8")
        val c = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 9000
            readTimeout = 45000
            doOutput = true
            setRequestProperty("Content-Type", "audio/mp4")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-Filename", "firetv-dub-${sequence++}.m4a")
            setRequestProperty("User-Agent", "Javistv/0.6.22")
        }
        c.outputStream.use { out -> file.inputStream().use { it.copyTo(out) } }
        val code = c.responseCode
        val body = (if (code in 200..299) c.inputStream else c.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            val detail = runCatching {
                val j = JSONObject(body)
                j.optJSONArray("details")?.let { a ->
                    (0 until minOf(a.length(), 3)).joinToString(" | ") { a.optString(it) }
                } ?: j.optString("error")
            }.getOrDefault(body.take(220))
            throw IllegalStateException("translate HTTP $code · ${detail.take(220)}")
        }
        return JSONObject(body)
    }

    private fun synthesizeDub(source: File, text: String, lang: String): DubSegment {
        val endpoint = "${backendBase()}/api/speech-dub"
        val out = File(cacheDir, "dub-out-${System.currentTimeMillis()}.audio")
        val encodedText = Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))
        val c = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 9000
            readTimeout = 50000
            doOutput = true
            setRequestProperty("Content-Type", "audio/mp4")
            setRequestProperty("Accept", "audio/*")
            setRequestProperty("X-Filename", "speaker-reference.m4a")
            setRequestProperty("X-Dub-Text-B64", encodedText)
            setRequestProperty("X-Target-Language", lang)
            setRequestProperty("X-Dub-Session", sessionId)
            setRequestProperty("X-Dub-Speed", "1.05")
            setRequestProperty("User-Agent", "Javistv/0.6.22")
        }
        c.outputStream.use { dst -> source.inputStream().use { it.copyTo(dst) } }
        val code = c.responseCode
        if (code !in 200..299) {
            val body = c.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            throw IllegalStateException("dub HTTP $code · ${body.take(180)}")
        }
        c.inputStream.use { input -> out.outputStream().use { input.copyTo(it) } }
        if (out.length() < 256) {
            out.delete()
            throw IllegalStateException("audio doblado vacío")
        }
        val mode = c.getHeaderField("X-Javistv-Voice-Mode") ?: "voice-match"
        return DubSegment(out, text, mode)
    }

    private fun looksLikeOwnDub(transcript: String): Boolean {
        val heard = normalizeText(transcript)
        val own = lastDubText
        if (heard.length < 10 || own.length < 10) return false
        return heard.contains(own.take(28)) || own.contains(heard.take(28))
    }

    private fun normalizeText(value: String): String =
        value.lowercase()
            .replace(Regex("[^a-záéíóúüñ0-9 ]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun backendBase(): String =
        prefs().getString("backendUrl", DEFAULT_BACKEND).orEmpty()
            .ifBlank { DEFAULT_BACKEND }
            .trim().trimEnd('/')
            .removeSuffix("/api/chat")
            .removeSuffix("/api")

    private fun stopTranslation(restartWake: Boolean) {
        running = false
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        runCatching { currentPlayer?.stop() }
        runCatching { currentPlayer?.release() }
        currentPlayer = null

        captureWorker?.interrupt()
        translateWorker?.interrupt()
        playbackWorker?.interrupt()
        captureWorker = null
        translateWorker = null
        playbackWorker = null

        while (true) audioQueue.poll()?.delete() ?: break
        while (true) playbackQueue.poll()?.file?.delete() ?: break

        sendBroadcast(Intent(JarvisAccessibilityService.ACTION_HIDE_TRANSLATION).setPackage(packageName))
        prefs().edit()
            .putBoolean("translation_active", false)
            .putString("translation_status", "detenida")
            .apply()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()

        if (
            restartWake &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ) {
            runCatching {
                ContextCompat.startForegroundService(this, Intent(this, WakeWordService::class.java))
            }
        }
    }

    private fun isStopCommand(s: String): Boolean =
        s.contains("jarvis") && (
            s.contains("para la tradu") ||
                s.contains("deten la tradu") ||
                s.contains("detén la tradu") ||
                s.contains("stop translation") ||
                s.contains("deja de traduc")
            )

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
        "en" -> "inglés"
        "fr" -> "francés"
        "de" -> "alemán"
        "it" -> "italiano"
        "pt" -> "portugués"
        else -> "español"
    }

    private fun notification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setContentTitle("Javistv · doblaje en tiempo real")
        .setContentText(
            "A " + languageLabel(targetLanguage) +
                " · voz de referencia temporal · di “Jarvis, para la traducción”"
        )
        .setOngoing(true)
        .setSilent(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        .build()

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Doblaje de vídeo",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { setSound(null, null) }
            )
        }
    }

    private fun sleepQuietly(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) {}
    }

    override fun onDestroy() {
        if (running) stopTranslation(false)
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
        private const val CHUNK_MS = 2200L
        private const val MIN_AUDIO_BYTES = 420L
    }
}
