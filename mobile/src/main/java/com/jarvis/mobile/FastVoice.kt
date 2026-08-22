package com.jarvis.mobile

import android.app.Activity
import android.content.SharedPreferences
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object FastVoice {
    private const val BACKEND = "https://chatgpt-tv2.vercel.app"
    private val pool = Executors.newFixedThreadPool(3)
    private var player: MediaPlayer? = null
    private var localTts: TextToSpeech? = null

    fun speak(activity: Activity, prefs: SharedPreferences, raw: String, onStart: (() -> Unit)? = null) {
        val text = raw.replace(Regex("(?m)^\\s*[-•*]+\\s*"), "").replace(Regex("[`#*_>]+"), "").replace(Regex("[\\r\\n]+"), " ").replace(Regex("\\s+"), " ").trim()
        if (text.isBlank()) { activity.runOnUiThread { onStart?.invoke() }; return }
        val chunks = chunk(text)
        val files = ConcurrentHashMap<Int, File>()
        val started = AtomicBoolean(false)
        val failed = AtomicBoolean(false)

        fun fallback() {
            if (!started.compareAndSet(false, true)) return
            failed.set(true)
            activity.runOnUiThread {
                onStart?.invoke()
                if (localTts == null) localTts = TextToSpeech(activity.applicationContext) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        localTts?.language = Locale("es", "ES"); localTts?.setSpeechRate(1.08f)
                        localTts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis-fast-fallback")
                    }
                } else localTts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis-fast-fallback")
            }
        }

        activity.window.decorView.postDelayed({ if (!started.get()) fallback() }, 4500L)
        chunks.forEachIndexed { index, part ->
            pool.execute {
                if (failed.get()) return@execute
                try {
                    files[index] = download(activity, prefs, part, index)
                    if (index == 0 && started.compareAndSet(false, true)) activity.runOnUiThread { onStart?.invoke(); play(activity, files, chunks.size, 0) }
                } catch (_: Exception) { if (index == 0) fallback() }
            }
        }
    }

    private fun chunk(text: String): List<String> {
        val out = mutableListOf<String>(); var rest = text; val max = 140
        while (rest.isNotBlank()) {
            if (rest.length <= max) { out += rest; break }
            val w = rest.take(max + 1)
            var cut = listOf(w.lastIndexOf('.'), w.lastIndexOf('?'), w.lastIndexOf('!'), w.lastIndexOf(','), w.lastIndexOf(' ')).maxOrNull() ?: max
            if (cut < 55) cut = max
            val take = (cut + if (w.getOrNull(cut) in listOf('.', '?', '!', ',')) 1 else 0).coerceAtMost(rest.length)
            out += rest.take(take).trim(); rest = rest.drop(take).trim()
        }
        return out.filter { it.isNotBlank() }
    }

    private fun download(activity: Activity, prefs: SharedPreferences, text: String, index: Int): File {
        val c = (URL("$BACKEND/api/speech").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 3500; readTimeout = 12000
            setRequestProperty("Content-Type", "application/json; charset=utf-8"); setRequestProperty("Accept", "audio/mpeg")
        }
        val voice = prefs.getString("voice", "openvoice") ?: "openvoice"
        val provider = if (voice == "openvoice") "openvoice" else "noiz"
        c.outputStream.use { it.write(JSONObject().put("text", text).put("voice", voice).put("provider", provider).put("speed", 1.18).toString().toByteArray()) }
        if (c.responseCode !in 200..299) throw IllegalStateException("TTS ${c.responseCode}")
        return File(activity.cacheDir, "jarvis-fast-${System.currentTimeMillis()}-$index.mp3").also { f -> c.inputStream.use { input -> f.outputStream().use { input.copyTo(it) } } }
    }

    private fun play(activity: Activity, files: ConcurrentHashMap<Int, File>, total: Int, index: Int) {
        if (index >= total) return
        val file = files[index]
        if (file == null) { activity.window.decorView.postDelayed({ play(activity, files, total, index) }, 120L); return }
        runCatching { player?.release() }
        player = MediaPlayer().apply {
            setDataSource(file.absolutePath); setOnPreparedListener { it.start() }
            setOnCompletionListener { p -> p.release(); file.delete(); if (player === p) player = null; play(activity, files, total, index + 1) }
            setOnErrorListener { p, _, _ -> p.release(); file.delete(); if (player === p) player = null; play(activity, files, total, index + 1); true }
            prepareAsync()
        }
    }
}
