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
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer

class WakeWordService : Service() {
    @Volatile private var running = false
    private var recorder: MediaRecorder? = null
    private val backend = "https://chatgpt-tv2.vercel.app"

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).putExtra("hands_free", true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        startForeground(
            71,
            NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Jarvis escuchando")
                .setContentText("Di “Hola Jarvis” o “Hola Ale”")
                .setOngoing(true)
                .setContentIntent(open)
                .build()
        )
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            running = true
            Thread { loop() }.start()
        } else stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun normalizeSpeech(value: String): String {
        return Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9 ]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun isWakePhrase(raw: String): Boolean {
        val text = normalizeSpeech(raw)
        if (text.isBlank()) return false

        val jarvisVariants = listOf("jarvis", "yarvis", "yervis", "yerviz", "jervis", "jerviz", "harvis")
        val aleVariants = listOf("ale", "hale", "alé")
        val prefixes = listOf("hola", "oye", "hey", "ey")

        if (jarvisVariants.any { v -> prefixes.any { p -> text.contains("$p $v") } }) return true
        if (aleVariants.any { v -> prefixes.any { p -> text.contains("$p $v") } }) return true

        // Tolerate short ASR fragments such as "hola ... yerviz".
        val hasGreeting = prefixes.any { p -> text.contains(p) }
        val hasJarvisLike = jarvisVariants.any { text.contains(it) }
        val hasAleLike = aleVariants.any { v -> Regex("(^| )${Regex.escape(v)}( |$)").containsMatchIn(text) }
        return hasGreeting && (hasJarvisLike || hasAleLike)
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
                Thread.sleep(1600)
                runCatching { r.stop() }; runCatching { r.release() }; recorder = null
                if (f.exists() && f.length() > 256) {
                    val text = transcribe(f)
                    if (isWakePhrase(text)) {
                        showOverlay()
                        val i = Intent(this, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            putExtra("wake_word_triggered", true)
                            putExtra("hands_free", true)
                        }
                        runCatching { startActivity(i) }
                        Thread.sleep(3200)
                    }
                }
            } catch (_: Exception) {
                runCatching { recorder?.release() }; recorder = null
                try { Thread.sleep(700) } catch (_: Exception) {}
            } finally { f.delete() }
        }
    }

    private fun showOverlay() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
            runCatching {
                startService(Intent(this, JarvisOverlayService::class.java).apply {
                    action = JarvisOverlayService.ACTION_SHOW
                    putExtra(JarvisOverlayService.EXTRA_TEXT, "Te escucho…")
                })
            }
        }
    }

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
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Jarvis wake word", NotificationManager.IMPORTANCE_LOW))
        }
    }

    companion object { private const val CHANNEL = "jarvis_wake_word" }
}
