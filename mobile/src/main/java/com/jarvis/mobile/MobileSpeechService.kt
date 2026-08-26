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
        runCatching { player?.release() }; runCatching { nextPlayer?.release() }; player = null; nextPlayer = null
        startForeground(4406, NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Jarvis está hablando").setContentText(text.take(80)).setOngoing(true).build())
        Thread { speakGapless(text, voice, token) }.start()
        START_NOT_STICKY
    }.getOrElse { stopSelf(); START_NOT_STICKY }

    private fun chunks(text: String): List<String> {
        val clean = text.replace(Regex("https?://\\S+"), " ")
            .replace(Regex("[*_`#>|]+"), " ")
            .replace(Regex("\\s+"), " ").trim()
        if (clean.isBlank()) return emptyList()
        val out = mutableListOf<String>(); var rest = clean; var first = true
        while (rest.isNotBlank()) {
            // Tiny first phrase: begin speaking as soon as possible. Later phrases are
            // long enough to avoid audible paragraph gaps.
            val max = if (first) 34 else 700
            val minCut = if (first) 10 else 180
            val limit = minOf(max, rest.length)
            val marks = listOf(". ", "? ", "! ", "; ", ", ", ": ")
            val cut = marks.map { rest.lastIndexOf(it, limit) }
                .filter { it >= minCut }.maxOrNull()?.plus(1) ?: limit
            out += rest.take(cut).trim(); rest = rest.drop(cut).trim(); first = false
        }
        return out.filter { it.isNotBlank() }
    }

    private fun downloadChunk(chunk: String, voice: String, token: Int, index: Int): File? {
        if (token != generation.get()) return null
        val file = File(cacheDir, "jarvis-bg-$token-$index.mp3")
        return try {
            val c = (URL("$BACKEND/api/speech").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true; connectTimeout = 1800; readTimeout = 12000
                setRequestProperty("Content-Type", "application/json")
            }
            val provider = when (voice.lowercase()) { "openvoice" -> "openvoice"; "noiz", "my_voice", "mi_voz" -> "noiz"; else -> "auto" }
            val payload = JSONObject().put("text", chunk).put("voice", voice).put("provider", provider).put("speed", 1.03)
            c.outputStream.use { it.write(payload.toString().toByteArray()) }
            if (c.responseCode !in 200..299) { file.delete(); null }
            else { c.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }; file }
        } catch (_: Throwable) { runCatching { file.delete() }; null }
    }

    private fun media(file: File): MediaPlayer? = runCatching {
        MediaPlayer().apply { setDataSource(file.absolutePath); prepare() }
    }.getOrNull()

    private fun speakGapless(text: String, voice: String, token: Int) {
        val parts = chunks(text)
        if (parts.isEmpty()) { if (token == generation.get()) stopSelf(); return }
        val futures = parts.mapIndexed { index, part -> downloader.submit<File?> { downloadChunk(part, voice, token, index) } }
        val files = arrayOfNulls<File>(parts.size)
        files[0] = runCatching { futures[0].get() }.getOrNull()
        if (token != generation.get()) return
        val firstFile = files[0] ?: run { stopSelf(); return }
        val first = media(firstFile) ?: run { firstFile.delete(); stopSelf(); return }
        player = first

        fun prepareAndChain(index: Int, current: MediaPlayer) {
            if (index >= parts.size || token != generation.get()) {
                current.setOnCompletionListener {
                    runCatching { it.release() }; files.forEach { f -> runCatching { f?.delete() } }
                    if (token == generation.get()) stopSelf()
                }
                return
            }
            downloader.submit {
                files[index] = runCatching { futures[index].get() }.getOrNull()
                val f = files[index]
                val np = if (f != null) media(f) else null
                if (token != generation.get()) { runCatching { np?.release() }; return@submit }
                if (np == null) {
                    current.setOnCompletionListener {
                        runCatching { it.release() }
                        prepareAndChain(index + 1, it)
                    }
                    return@submit
                }
                nextPlayer = np
                runCatching { current.setNextMediaPlayer(np) }
                current.setOnCompletionListener { finished ->
                    runCatching { finished.release() }
                    player = np; nextPlayer = null
                    prepareAndChain(index + 1, np)
                }
            }
        }

        prepareAndChain(1, first)
        first.start()
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
        private const val CHANNEL = "jarvis_speech"
        private const val BACKEND = "https://chatgpt-tv2.vercel.app"
    }
}
