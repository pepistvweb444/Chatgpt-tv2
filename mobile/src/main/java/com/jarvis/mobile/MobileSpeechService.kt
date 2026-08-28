package com.jarvis.mobile

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger

class MobileSpeechService : Service() {
    private var player: MediaPlayer? = null
    private var nextPlayer: MediaPlayer? = null
    private val generation = AtomicInteger(0)
    private val downloader = Executors.newFixedThreadPool(8)

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) runCatching {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL, "Voz de Jarvis", NotificationManager.IMPORTANCE_LOW))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = runCatching {
        if (intent?.action == ACTION_STOP) {
            stopPlayback(); stopSelf(); return@runCatching START_NOT_STICKY
        }
        val text = intent?.getStringExtra("text").orEmpty().trim()
        val voice = intent?.getStringExtra("voice").orEmpty().ifBlank { "coral" }
        if (text.isBlank()) return@runCatching START_NOT_STICKY
        val token = generation.incrementAndGet()
        runCatching { player?.release() }; runCatching { nextPlayer?.release() }
        player = null; nextPlayer = null
        startForeground(4406, NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Jarvis está hablando").setContentText(text.take(80)).setOngoing(true).build())
        Thread { speakContinuous(text, voice, token) }.start()
        START_NOT_STICKY
    }.getOrElse { stopSelf(); START_NOT_STICKY }

    private fun chunks(text: String): List<String> {
        val clean = text.replace(Regex("https?://\\S+"), " ")
            .replace(Regex("[*_`#>|]+"), " ")
            .replace(Regex("\\s+"), " ").trim()
        if (clean.isBlank()) return emptyList()
        val out = mutableListOf<String>()
        var rest = clean
        var first = true
        while (rest.isNotBlank()) {
            // The old 34-character first chunk could finish before the next MediaPlayer
            // had been prepared, which caused the observed 2-3 second speech cutoff.
            // 110 chars still starts quickly but gives the parallel prefetch time to finish.
            val max = if (first) 110 else 520
            val minCut = if (first) 45 else 160
            val limit = minOf(max, rest.length)
            val marks = listOf(". ", "? ", "! ", "; ", ", ", ": ")
            val cut = marks.map { rest.lastIndexOf(it, limit) }
                .filter { it >= minCut }.maxOrNull()?.plus(1) ?: limit
            out += rest.take(cut).trim()
            rest = rest.drop(cut).trim()
            first = false
        }
        return out.filter { it.isNotBlank() }
    }

    private fun downloadChunk(chunk: String, voice: String, token: Int, index: Int): File? {
        if (token != generation.get()) return null
        val file = File(cacheDir, "jarvis-bg-$token-$index.mp3")
        return try {
            val c = (URL("$BACKEND/api/speech").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true; connectTimeout = 1800; readTimeout = 15000
                setRequestProperty("Content-Type", "application/json")
            }
            val provider = when (voice.lowercase()) {
                "openvoice" -> "openvoice"
                "noiz", "my_voice", "mi_voz" -> "noiz"
                else -> "auto"
            }
            val payload = JSONObject().put("text", chunk).put("voice", voice).put("provider", provider).put("speed", 1.03)
            c.outputStream.use { it.write(payload.toString().toByteArray()) }
            if (c.responseCode !in 200..299) { file.delete(); null }
            else { c.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }; file }
        } catch (_: Throwable) {
            runCatching { file.delete() }; null
        }
    }

    private fun media(file: File): MediaPlayer? = runCatching {
        MediaPlayer().apply { setDataSource(file.absolutePath); prepare() }
    }.getOrNull()

    private fun speakContinuous(text: String, voice: String, token: Int) {
        val parts = chunks(text)
        if (parts.isEmpty()) { finishSpeech(token); return }

        // Download every segment concurrently from the start. Before playback begins we
        // prepare the first TWO successful segments, so the first player always has a
        // real successor attached and cannot finish into silence.
        val futures: List<Future<File?>> = parts.mapIndexed { index, part ->
            downloader.submit<File?> { downloadChunk(part, voice, token, index) }
        }
        val files = arrayOfNulls<File>(parts.size)

        fun awaitFile(index: Int): File? {
            if (index !in parts.indices || token != generation.get()) return null
            if (files[index] == null) files[index] = runCatching { futures[index].get() }.getOrNull()
            return files[index]
        }

        fun findPrepared(from: Int): Pair<Int, MediaPlayer>? {
            for (i in from until parts.size) {
                val f = awaitFile(i) ?: continue
                val mp = media(f) ?: continue
                return i to mp
            }
            return null
        }

        val firstPair = findPrepared(0) ?: run { finishSpeech(token); return }
        val firstIndex = firstPair.first
        val first = firstPair.second
        val secondPair = findPrepared(firstIndex + 1)
        player = first

        fun chain(currentIndex: Int, current: MediaPlayer, preparedNext: Pair<Int, MediaPlayer>?) {
            if (token != generation.get()) {
                runCatching { current.release() }
                runCatching { preparedNext?.second?.release() }
                return
            }
            if (preparedNext == null) {
                current.setOnCompletionListener { finished ->
                    runCatching { finished.release() }
                    files.forEach { f -> runCatching { f?.delete() } }
                    finishSpeech(token)
                }
                return
            }

            val nextIndex = preparedNext.first
            val next = preparedNext.second
            nextPlayer = next
            runCatching { current.setNextMediaPlayer(next) }
            current.setOnCompletionListener { finished ->
                runCatching { finished.release() }
                player = next
                nextPlayer = null
                // The next segment has already started gaplessly. Prepare its successor
                // while it is playing; all downloads were launched in parallel earlier.
                downloader.submit {
                    val successor = findPrepared(nextIndex + 1)
                    if (token == generation.get()) chain(nextIndex, next, successor)
                    else runCatching { successor?.second?.release() }
                }
            }
        }

        chain(firstIndex, first, secondPair)
        if (token == generation.get()) first.start() else runCatching { first.release() }
    }

    private fun finishSpeech(token: Int) {
        if (token == generation.get()) {
            sendBroadcast(Intent(ACTION_SPEECH_DONE).setPackage(packageName))
            stopSelf()
        }
    }

    private fun stopPlayback() {
        generation.incrementAndGet()
        runCatching { player?.stop() }; runCatching { player?.release() }
        runCatching { nextPlayer?.release() }
        player = null; nextPlayer = null
    }

    override fun onDestroy() { stopPlayback(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.jarvis.mobile.STOP_SPEECH"
        const val ACTION_SPEECH_DONE = "com.jarvis.mobile.SPEECH_DONE"
        private const val CHANNEL = "jarvis_speech"
        private const val BACKEND = "https://chatgpt-tv2.vercel.app"
    }
}
