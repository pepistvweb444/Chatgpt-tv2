package com.jarvis.mobile

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.media.MediaPlayer
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.widget.Button
import android.widget.EditText
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

object FastVoice {
    private const val BACKEND = "https://chatgpt-tv2.vercel.app"
    private val pool = Executors.newFixedThreadPool(3)
    private val generation = AtomicInteger(0)
    @Volatile private var player: MediaPlayer? = null
    @Volatile private var localTts: TextToSpeech? = null
    @Volatile private var bargeRecognizer: SpeechRecognizer? = null
    @Volatile private var interrupted = false

    fun stop() {
        generation.incrementAndGet()
        interrupted = true
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        runCatching { localTts?.stop() }
        runCatching { bargeRecognizer?.cancel() }
        runCatching { bargeRecognizer?.destroy() }
        bargeRecognizer = null
    }

    fun speak(activity: Activity, prefs: SharedPreferences, raw: String, onStart: (() -> Unit)? = null) {
        stop()
        interrupted = false
        val myGeneration = generation.incrementAndGet()
        val text = raw
            .replace(Regex("(?m)^\\s*[-•*]+\\s*"), "")
            .replace(Regex("[`#*_>]+"), "")
            .replace(Regex("[\\r\\n]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (text.isBlank()) {
            activity.runOnUiThread { onStart?.invoke(); relisten(activity) }
            return
        }

        val chunks = chunk(text)
        val files = ConcurrentHashMap<Int, File>()
        val started = AtomicBoolean(false)
        val failed = AtomicBoolean(false)

        fun fallback() {
            if (generation.get() != myGeneration || !started.compareAndSet(false, true)) return
            failed.set(true)
            activity.runOnUiThread {
                onStart?.invoke()
                startBargeIn(activity, text, myGeneration)
                if (localTts == null) {
                    localTts = TextToSpeech(activity.applicationContext) { status ->
                        if (status == TextToSpeech.SUCCESS && generation.get() == myGeneration) {
                            localTts?.language = Locale("es", "ES")
                            localTts?.setSpeechRate(1.08f)
                            localTts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis-fast-fallback")
                            activity.window.decorView.postDelayed({
                                if (generation.get() == myGeneration && !interrupted) relisten(activity)
                            }, estimateSpeechMs(text))
                        }
                    }
                } else {
                    localTts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis-fast-fallback")
                    activity.window.decorView.postDelayed({
                        if (generation.get() == myGeneration && !interrupted) relisten(activity)
                    }, estimateSpeechMs(text))
                }
            }
        }

        activity.window.decorView.postDelayed({
            if (generation.get() == myGeneration && !started.get()) fallback()
        }, 4500L)

        chunks.forEachIndexed { index, part ->
            pool.execute {
                if (failed.get() || generation.get() != myGeneration) return@execute
                try {
                    files[index] = download(activity, prefs, part, index)
                    if (index == 0 && started.compareAndSet(false, true) && generation.get() == myGeneration) {
                        activity.runOnUiThread {
                            onStart?.invoke()
                            startBargeIn(activity, text, myGeneration)
                            play(activity, files, chunks.size, 0, myGeneration)
                        }
                    }
                } catch (_: Exception) {
                    if (index == 0) fallback()
                }
            }
        }
    }

    private fun chunk(text: String): List<String> {
        val out = mutableListOf<String>()
        var rest = text
        val max = 140
        while (rest.isNotBlank()) {
            if (rest.length <= max) { out += rest; break }
            val w = rest.take(max + 1)
            var cut = listOf(w.lastIndexOf('.'), w.lastIndexOf('?'), w.lastIndexOf('!'), w.lastIndexOf(','), w.lastIndexOf(' ')).maxOrNull() ?: max
            if (cut < 55) cut = max
            val take = (cut + if (w.getOrNull(cut) in listOf('.', '?', '!', ',')) 1 else 0).coerceAtMost(rest.length)
            out += rest.take(take).trim()
            rest = rest.drop(take).trim()
        }
        return out.filter { it.isNotBlank() }
    }

    private fun download(activity: Activity, prefs: SharedPreferences, text: String, index: Int): File {
        val c = (URL("$BACKEND/api/speech").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 3500
            readTimeout = 12000
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "audio/mpeg")
        }
        val voice = prefs.getString("voice", "openvoice") ?: "openvoice"
        val provider = if (voice == "openvoice") "openvoice" else "noiz"
        c.outputStream.use {
            it.write(JSONObject().put("text", text).put("voice", voice).put("provider", provider).put("speed", 1.18).toString().toByteArray())
        }
        if (c.responseCode !in 200..299) throw IllegalStateException("TTS ${c.responseCode}")
        return File(activity.cacheDir, "jarvis-fast-${System.currentTimeMillis()}-$index.mp3").also { f ->
            c.inputStream.use { input -> f.outputStream().use { input.copyTo(it) } }
        }
    }

    private fun play(activity: Activity, files: ConcurrentHashMap<Int, File>, total: Int, index: Int, myGeneration: Int) {
        if (generation.get() != myGeneration || interrupted) return
        if (index >= total) {
            stopBargeRecognizer()
            activity.window.decorView.postDelayed({
                if (generation.get() == myGeneration && !interrupted) relisten(activity)
            }, 180L)
            return
        }
        val file = files[index]
        if (file == null) {
            activity.window.decorView.postDelayed({ play(activity, files, total, index, myGeneration) }, 80L)
            return
        }
        runCatching { player?.release() }
        player = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            setOnPreparedListener { if (generation.get() == myGeneration && !interrupted) it.start() }
            setOnCompletionListener { p ->
                p.release(); file.delete(); if (player === p) player = null
                play(activity, files, total, index + 1, myGeneration)
            }
            setOnErrorListener { p, _, _ ->
                p.release(); file.delete(); if (player === p) player = null
                play(activity, files, total, index + 1, myGeneration)
                true
            }
            prepareAsync()
        }
    }

    private fun startBargeIn(activity: Activity, assistantText: String, myGeneration: Int) {
        if (!SpeechRecognizer.isRecognitionAvailable(activity)) return
        stopBargeRecognizer()
        val assistantNorm = normalize(assistantText)
        activity.window.decorView.postDelayed({
            if (generation.get() != myGeneration || interrupted) return@postDelayed
            val recognizer = SpeechRecognizer.createSpeechRecognizer(activity)
            bargeRecognizer = recognizer
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {}
                override fun onPartialResults(partialResults: Bundle?) {
                    val heard = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    maybeInterrupt(activity, heard, assistantNorm, myGeneration, false)
                }
                override fun onResults(results: Bundle?) {
                    val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    maybeInterrupt(activity, heard, assistantNorm, myGeneration, true)
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 450L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 300L)
            }
            runCatching { recognizer.startListening(intent) }
        }, 350L)
    }

    private fun maybeInterrupt(activity: Activity, heardRaw: String, assistantNorm: String, myGeneration: Int, final: Boolean) {
        if (generation.get() != myGeneration || interrupted) return
        val heard = heardRaw.trim()
        val heardNorm = normalize(heard)
        if (heardNorm.length < 3) return
        // Ignore probable echo of Jarvis' own speech. Anything materially different is treated as barge-in.
        val looksLikeEcho = assistantNorm.contains(heardNorm) || heardNorm.split(' ').filter { it.length > 2 }.all { assistantNorm.contains(it) }
        if (looksLikeEcho && !final) return
        if (looksLikeEcho) return

        interrupted = true
        runCatching { player?.stop() }; runCatching { player?.release() }; player = null
        runCatching { localTts?.stop() }
        stopBargeRecognizer()
        activity.runOnUiThread {
            val input = activity.findViewById<EditText>(R.id.input)
            input.setText(heard)
            input.setSelection(heard.length)
            activity.findViewById<Button>(R.id.send).performClick()
        }
    }

    private fun relisten(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        activity.runOnUiThread {
            runCatching { activity.findViewById<Button>(R.id.mic).performClick() }
        }
    }

    private fun stopBargeRecognizer() {
        runCatching { bargeRecognizer?.cancel() }
        runCatching { bargeRecognizer?.destroy() }
        bargeRecognizer = null
    }

    private fun normalize(value: String): String = Normalizer.normalize(value.lowercase(Locale.getDefault()), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("[^a-z0-9 ]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun estimateSpeechMs(text: String): Long = ((text.length / 12.0) * 1000.0).toLong().coerceIn(1200L, 45000L)
}
