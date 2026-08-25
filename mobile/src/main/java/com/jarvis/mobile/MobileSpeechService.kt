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
    private val generation = AtomicInteger(0)
    private val downloader = Executors.newFixedThreadPool(2)

    override fun onCreate() {
        super.onCreate()
        runCatching {
            if (Build.VERSION.SDK_INT >= 26) {
                getSystemService(NotificationManager::class.java)
                    .createNotificationChannel(NotificationChannel(CHANNEL, "Voz de Jarvis", NotificationManager.IMPORTANCE_LOW))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = runCatching {
        if (intent?.action == ACTION_STOP) {
            generation.incrementAndGet(); runCatching { player?.release() }; player = null; stopSelf(); return@runCatching START_NOT_STICKY
        }
        val text = intent?.getStringExtra("text").orEmpty().trim()
        val voice = intent?.getStringExtra("voice").orEmpty().ifBlank { "coral" }
        if (text.isBlank()) return@runCatching START_NOT_STICKY
        val token = generation.incrementAndGet()
        startForeground(4406, NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Jarvis está hablando")
            .setContentText(text.take(80))
            .setOngoing(true).build())
        Thread { speakPipelined(text, voice, token) }.start()
        START_NOT_STICKY
    }.getOrElse { stopSelf(); START_NOT_STICKY }

    /**
     * First chunk is deliberately short so Jarvis begins speaking quickly.
     * Remaining chunks are larger and are downloaded while the previous chunk
     * is already playing, eliminating the old 5-10 second silent gaps.
     */
    private fun chunks(text: String): List<String> {
        val clean = text.replace(Regex("https?://\\S+"), " ")
            .replace(Regex("[*_`#>|]+"), " ")
            .replace(Regex("\\s+"), " ").trim()
        if (clean.isBlank()) return emptyList()
        val out = mutableListOf<String>()
        var rest = clean
        var first = true
        while (rest.isNotBlank()) {
            val max = if (first) 240 else 1200
            val minCut = if (first) 70 else 280
            val limit = minOf(max, rest.length)
            val marks = listOf(". ", "? ", "! ", "; ", ", ")
            val candidates = marks.map { rest.lastIndexOf(it, limit) }.filter { it >= minCut }
            val cut = candidates.maxOrNull()?.plus(1) ?: limit
            out += rest.take(cut).trim()
            rest = rest.drop(cut).trim()
            first = false
        }
        return out
    }

    private fun downloadChunk(chunk: String, voice: String, token: Int, index: Int): File? {
        if (token != generation.get()) return null
        val file = File(cacheDir, "jarvis-bg-$token-$index.mp3")
        return try {
            val c = (URL("$BACKEND/api/speech").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true; connectTimeout = 4500; readTimeout = 30000
                setRequestProperty("Content-Type", "application/json")
            }
            val payload = JSONObject().put("text", chunk).put("voice", voice)
                .put("provider", if (voice == "openvoice") "openvoice" else "openai").put("speed", 0.94)
            c.outputStream.use { it.write(payload.toString().toByteArray()) }
            if (c.responseCode !in 200..299) { file.delete(); null }
            else { c.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }; file }
        } catch (_: Throwable) { runCatching { file.delete() }; null }
    }

    private fun speakPipelined(text: String, voice: String, token: Int) {
        val parts = chunks(text)
        if (parts.isEmpty()) { if (token == generation.get()) stopSelf(); return }

        var pending: Future<File?> = downloader.submit<File?> { downloadChunk(parts[0], voice, token, 0) }
        for (i in parts.indices) {
            if (token != generation.get()) return
            val file = runCatching { pending.get() }.getOrNull() ?: continue
            // Start generating the next audio BEFORE current playback begins.
            val next: Future<File?>? = if (i + 1 < parts.size) downloader.submit<File?> {
                downloadChunk(parts[i + 1], voice, token, i + 1)
            } else null

            val lock = Object()
            try {
                synchronized(lock) {
                    if (token != generation.get()) return
                    runCatching { player?.release() }
                    player = MediaPlayer().apply {
                        setDataSource(file.absolutePath)
                        setOnCompletionListener { synchronized(lock) { lock.notifyAll() } }
                        setOnErrorListener { _, _, _ -> synchronized(lock) { lock.notifyAll() }; true }
                        prepare(); start()
                    }
                    lock.wait(180000)
                    runCatching { player?.release() }; player = null
                }
            } finally { runCatching { file.delete() } }
            if (next != null) pending = next
        }
        if (token == generation.get()) stopSelf()
    }

    override fun onDestroy() {
        generation.incrementAndGet(); runCatching { player?.release() }; player = null
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.jarvis.mobile.STOP_SPEECH"
        private const val CHANNEL = "jarvis_speech"
        private const val BACKEND = "https://chatgpt-tv2.vercel.app"
    }
}
