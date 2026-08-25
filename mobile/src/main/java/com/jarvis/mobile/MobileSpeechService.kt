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
    private val generation = AtomicInteger(0)
    private val downloader = Executors.newFixedThreadPool(4)

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) runCatching {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL, "Voz de Jarvis", NotificationManager.IMPORTANCE_LOW))
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
            .setContentTitle("Jarvis está hablando").setContentText(text.take(80)).setOngoing(true).build())
        Thread { speakPipelined(text, voice, token) }.start()
        START_NOT_STICKY
    }.getOrElse { stopSelf(); START_NOT_STICKY }

    private fun chunks(text: String): List<String> {
        val clean = text.replace(Regex("https?://\\S+"), " ").replace(Regex("[*_`#>|]+"), " ").replace(Regex("\\s+"), " ").trim()
        if (clean.isBlank()) return emptyList()
        val out = mutableListOf<String>(); var rest = clean; var first = true
        while (rest.isNotBlank()) {
            val max = if (first) 95 else 620
            val minCut = if (first) 28 else 180
            val limit = minOf(max, rest.length)
            val marks = listOf(". ", "? ", "! ", "; ", ", ")
            val cut = marks.map { rest.lastIndexOf(it, limit) }.filter { it >= minCut }.maxOrNull()?.plus(1) ?: limit
            out += rest.take(cut).trim(); rest = rest.drop(cut).trim(); first = false
        }
        return out
    }

    private fun downloadChunk(chunk: String, voice: String, token: Int, index: Int): File? {
        if (token != generation.get()) return null
        val file = File(cacheDir, "jarvis-bg-$token-$index.mp3")
        return try {
            val c = (URL("$BACKEND/api/speech").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true; connectTimeout = 3500; readTimeout = 22000
                setRequestProperty("Content-Type", "application/json")
            }
            val payload = JSONObject().put("text", chunk).put("voice", voice).put("provider", "auto").put("speed", 1.02)
            c.outputStream.use { it.write(payload.toString().toByteArray()) }
            if (c.responseCode !in 200..299) { file.delete(); null }
            else { c.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }; file }
        } catch (_: Throwable) { runCatching { file.delete() }; null }
    }

    private fun speakPipelined(text: String, voice: String, token: Int) {
        val parts = chunks(text)
        if (parts.isEmpty()) { if (token == generation.get()) stopSelf(); return }
        val futures = parts.mapIndexed { index, part -> downloader.submit<File?> { downloadChunk(part, voice, token, index) } }
        for (i in parts.indices) {
            if (token != generation.get()) return
            val file = runCatching { futures[i].get() }.getOrNull() ?: continue
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
        }
        if (token == generation.get()) stopSelf()
    }

    override fun onDestroy() { generation.incrementAndGet(); runCatching { player?.release() }; player = null; super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.jarvis.mobile.STOP_SPEECH"
        private const val CHANNEL = "jarvis_speech"
        private const val BACKEND = "https://chatgpt-tv2.vercel.app"
    }
}
