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
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class MobileSpeechService : Service() {
    private data class SpeechRequest(val text: String, val voice: String)

    private var player: MediaPlayer? = null
    private var nextPlayer: MediaPlayer? = null
    private val stopGeneration = AtomicInteger(0)
    private val queue = LinkedBlockingQueue<SpeechRequest>()
    private val workerRunning = AtomicBoolean(false)
    private val downloader = Executors.newFixedThreadPool(6)
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var lastQueuedText = ""
    @Volatile private var lastQueuedAt = 0L

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) runCatching {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL, "Voz de Jarvis", NotificationManager.IMPORTANCE_LOW))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = runCatching {
        if (intent?.action == ACTION_STOP) {
            stopGeneration.incrementAndGet()
            queue.clear()
            stopPlayback()
            stopSelf()
            return@runCatching START_NOT_STICKY
        }
        val text = intent?.getStringExtra("text").orEmpty().trim()
        val voice = intent?.getStringExtra("voice").orEmpty().ifBlank { "coral" }
        if (text.isBlank()) return@runCatching START_NOT_STICKY

        // Multiple widget/render callbacks can request speech almost simultaneously.
        // Do not cancel the phrase already being spoken; enqueue the next unique phrase.
        val now = System.currentTimeMillis()
        val key = text.replace(Regex("\\s+"), " ").trim()
        if (key == lastQueuedText && now - lastQueuedAt < 5000L) return@runCatching START_NOT_STICKY
        lastQueuedText = key; lastQueuedAt = now
        queue.offer(SpeechRequest(text, voice))

        startForeground(4406, NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Jarvis está hablando")
            .setContentText(text.take(80))
            .setOngoing(true).build())
        ensureWorker()
        START_NOT_STICKY
    }.getOrElse { stopSelf(); START_NOT_STICKY }

    private fun ensureWorker() {
        if (!workerRunning.compareAndSet(false, true)) return
        worker.submit {
            try {
                while (true) {
                    val req = queue.poll() ?: break
                    val token = stopGeneration.get()
                    speakContinuous(req.text, req.voice, token)
                    if (token != stopGeneration.get()) break
                }
            } finally {
                workerRunning.set(false)
                if (queue.isNotEmpty()) ensureWorker()
                else {
                    sendBroadcast(Intent(ACTION_SPEECH_DONE).setPackage(packageName))
                    stopSelf()
                }
            }
        }
    }

    private fun chunks(text: String): List<String> {
        val clean = text.replace(Regex("https?://\\S+"), " ")
            .replace(Regex("[*_`#>|]+"), " ")
            .replace(Regex("\\s+"), " ").trim()
        if (clean.isBlank()) return emptyList()
        val out = mutableListOf<String>()
        var rest = clean
        var first = true
        while (rest.isNotBlank()) {
            // Very short first phrase starts audio quickly; the remaining large chunks
            // are synthesized in parallel while that phrase is already playing.
            val max = if (first) 80 else 1200
            val minCut = if (first) 28 else 350
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
        if (token != stopGeneration.get()) return null
        repeat(2) { attempt ->
            val file = File(cacheDir, "jarvis-bg-$token-$index-$attempt.mp3")
            try {
                val c = (URL("$BACKEND/api/speech").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; doOutput = true
                    connectTimeout = 1800
                    readTimeout = if (index == 0) 12000 else 22000
                    setRequestProperty("Content-Type", "application/json")
                }
                val provider = when (voice.lowercase()) {
                    "openvoice" -> "openvoice"
                    "noiz", "my_voice", "mi_voz" -> "noiz"
                    else -> "auto"
                }
                val payload = JSONObject().put("text", chunk).put("voice", voice).put("provider", provider).put("speed", 1.04)
                c.outputStream.use { it.write(payload.toString().toByteArray()) }
                if (c.responseCode in 200..299) {
                    c.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
                    if (file.length() > 256) return file
                }
            } catch (_: Throwable) { }
            runCatching { file.delete() }
            if (token != stopGeneration.get()) return null
        }
        return null
    }

    private fun media(file: File): MediaPlayer? = runCatching {
        MediaPlayer().apply { setDataSource(file.absolutePath); prepare() }
    }.getOrNull()

    private fun speakContinuous(text: String, voice: String, token: Int) {
        val parts = chunks(text)
        if (parts.isEmpty() || token != stopGeneration.get()) return
        val futures: List<Future<File?>> = parts.mapIndexed { index, part ->
            downloader.submit<File?> { downloadChunk(part, voice, token, index) }
        }
        val files = arrayOfNulls<File>(parts.size)

        fun await(index: Int): File? {
            if (index !in parts.indices || token != stopGeneration.get()) return null
            if (files[index] == null) files[index] = runCatching { futures[index].get() }.getOrNull()
            return files[index]
        }

        var i = 0
        while (i < parts.size && token == stopGeneration.get()) {
            val f = await(i)
            if (f == null) { i++; continue }
            val current = media(f)
            if (current == null) { runCatching { f.delete() }; i++; continue }

            // Prepare the immediate successor before playback starts. All audio downloads
            // were launched at once, so later chunks normally finish while current plays.
            var nextIndex = i + 1
            var nextFile: File? = null
            while (nextIndex < parts.size && nextFile == null) {
                nextFile = await(nextIndex)
                if (nextFile == null) nextIndex++
            }
            val preparedNext = nextFile?.let { media(it) }
            player = current
            nextPlayer = preparedNext
            if (preparedNext != null) runCatching { current.setNextMediaPlayer(preparedNext) }

            val lock = Object()
            current.setOnCompletionListener { synchronized(lock) { lock.notifyAll() } }
            current.setOnErrorListener { _, _, _ -> synchronized(lock) { lock.notifyAll() }; true }
            synchronized(lock) {
                current.start()
                lock.wait(180000L)
            }
            runCatching { current.release() }
            runCatching { f.delete() }

            if (preparedNext != null && nextFile != null && token == stopGeneration.get()) {
                // setNextMediaPlayer has already started the successor gaplessly. Wait for it
                // here, then continue from the next yet-unspoken chunk.
                player = preparedNext
                nextPlayer = null
                val nextLock = Object()
                preparedNext.setOnCompletionListener { synchronized(nextLock) { nextLock.notifyAll() } }
                preparedNext.setOnErrorListener { _, _, _ -> synchronized(nextLock) { nextLock.notifyAll() }; true }
                synchronized(nextLock) { nextLock.wait(180000L) }
                runCatching { preparedNext.release() }
                runCatching { nextFile.delete() }
                i = nextIndex + 1
            } else {
                i++
            }
        }
        files.forEach { runCatching { it?.delete() } }
        player = null; nextPlayer = null
    }

    private fun stopPlayback() {
        runCatching { player?.stop() }; runCatching { player?.release() }
        runCatching { nextPlayer?.release() }
        player = null; nextPlayer = null
    }

    override fun onDestroy() {
        stopGeneration.incrementAndGet()
        queue.clear()
        stopPlayback()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.jarvis.mobile.STOP_SPEECH"
        const val ACTION_SPEECH_DONE = "com.jarvis.mobile.SPEECH_DONE"
        private const val CHANNEL = "jarvis_speech"
        private const val BACKEND = "https://chatgpt-tv2.vercel.app"
    }
}
