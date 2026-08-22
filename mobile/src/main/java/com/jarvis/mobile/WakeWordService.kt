package com.jarvis.mobile

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.util.Locale

class WakeWordService : Service() {
    @Volatile private var running = false
    private var recorder: MediaRecorder? = null
    private val backend = "https://chatgpt-tv2.vercel.app"
    private val prefs by lazy { getSharedPreferences("jarvis_mobile", MODE_PRIVATE) }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val open = PendingIntent.getActivity(this, 0, Intent(this, ChatActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        startForeground(
            71,
            NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Ale / Jarvis escuchando")
                .setContentText("Di “Hola Ale” o “Hola Jarvis” para abrir el asistente")
                .setOngoing(true)
                .setContentIntent(open)
                .build()
        )
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            running = true
            Thread { loop() }.start()
        } else stopSelf()
    }

    private fun loop() {
        while (running) {
            val f = File(cacheDir, "wake-${System.currentTimeMillis()}.m4a")
            try {
                val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
                recorder = r
                r.setAudioSource(MediaRecorder.AudioSource.MIC)
                r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioSamplingRate(16000)
                r.setAudioEncodingBitRate(32000)
                r.setOutputFile(f.absolutePath)
                r.prepare(); r.start()
                Thread.sleep(2200)
                runCatching { r.stop() }; runCatching { r.release() }; recorder = null
                if (f.exists() && f.length() > 256) {
                    val text = normalize(transcribe(f))
                    if (matchesWakeWord(text)) {
                        val i = Intent(this, ChatActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            putExtra("wake_word_triggered", true)
                            putExtra("continuous_voice", true)
                        }
                        startActivity(i)
                        Thread.sleep(1800)
                    }
                }
            } catch (_: Exception) {
                runCatching { recorder?.release() }; recorder = null
                try { Thread.sleep(1200) } catch (_: Exception) {}
            } finally { f.delete() }
        }
    }

    private fun matchesWakeWord(text: String): Boolean {
        if (text.isBlank()) return false
        val configured = prefs.getString("wake_names", "ale,jarvis").orEmpty()
            .split(',')
            .map { normalize(it) }
            .filter { it.isNotBlank() }
            .ifEmpty { listOf("ale", "jarvis") }
        return configured.any { name ->
            text.contains("hola $name") || text.contains("oye $name") || text.contains("hey $name") || text.contains("eh $name")
        }
    }

    private fun normalize(value: String): String = Normalizer.normalize(value.lowercase(Locale.getDefault()), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("[^a-z0-9 ]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun transcribe(file: File): String {
        val c = (URL("$backend/api/transcribe").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 6000; readTimeout = 14000
            setRequestProperty("Content-Type", "audio/mp4"); setRequestProperty("X-Filename", "wake.m4a")
        }
        c.outputStream.use { out -> file.inputStream().use { it.copyTo(out) } }
        val code = c.responseCode
        val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) return ""
        return runCatching { JSONObject(body).optString("text") }.getOrDefault("")
    }

    override fun onDestroy() {
        running = false
        runCatching { recorder?.stop() }; runCatching { recorder?.release() }; recorder = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Ale / Jarvis wake word", NotificationManager.IMPORTANCE_LOW))
        }
    }

    companion object { private const val CHANNEL = "jarvis_wake_word" }
}
