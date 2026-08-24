from pathlib import Path

p = Path('app/src/main/java/com/jarvis/tv/MainActivity.kt')
s = p.read_text()

s = s.replace('setRequestProperty("User-Agent", "JarvisTV/0.6.1")', 'setRequestProperty("User-Agent", "JarvisTV/0.6.5")')
s = s.replace('connectTimeout = 12000; readTimeout = 60000', 'connectTimeout = 8000; readTimeout = 45000')
s = s.replace('putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)', 'putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)\n            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 650L)\n            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 450L)')
s = s.replace('● Escuchando 7 s · OpenAI…', '● Escuchando 4 s · Jarvis…')
s = s.replace('handler.postDelayed({ stopRecorder(true) }, 7000)', 'handler.postDelayed({ stopRecorder(true) }, 4000)')
s = s.replace('.put("assistantName", assistantName())\n            .put("history", historyPayload)', '.put("assistantName", assistantName())\n            .put("agentsEnabled", true)\n            .put("responseMode", "multimedia")\n            .put("history", historyPayload)')
s = s.replace('val json = JSONObject(body)\n        return Pair(', 'val json = JSONObject(body)\n        MediaResponseStore.capture(json)\n        return Pair(')
s = s.replace('runOnUiThread { append("assistant", result.first, true); status.text = "● Conectado · contexto guardado" }', 'runOnUiThread { append("assistant", result.first, true); MediaViewer.showPending(this); status.text = "● Conectado · contexto guardado" }')
s = s.replace('showHome()\n        ensureOverlayPermission(true)', 'showHome()\n        startWakeWordService()\n        ensureOverlayPermission(true)')
s = s.replace('if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) startBubbleService()\n    }', 'if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) startBubbleService()\n        startWakeWordService()\n    }')

anchor = '    private fun bindUi() {'
if 'private fun startWakeWordService()' not in s:
    helper = '''    private fun startWakeWordService() {\n        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return\n        runCatching { ContextCompat.startForegroundService(this, Intent(this, WakeWordService::class.java)) }\n    }\n\n'''
    s = s.replace(anchor, helper + anchor)

start = s.index('    private fun downloadSpeech(')
end = s.index('    private fun testBackend(', start)
replacement = r'''    private fun cleanForSpeech(text: String): String {
        return text
            .replace(Regex("https?://\\S+"), "")
            .replace(Regex("[*_#>`~]"), "")
            .replace(Regex("(?m)^\\s*[-•]+\\s*"), "")
            .replace(Regex("[\\r\\n]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun splitForSpeech(text: String): List<String> {
        val clean = cleanForSpeech(text)
        if (clean.isBlank()) return emptyList()
        val sentences = clean.split(Regex("(?<=[.!?;:])\\s+"))
        val out = mutableListOf<String>()
        val current = StringBuilder()
        for (sentence in sentences) {
            val part = sentence.trim()
            if (part.isBlank()) continue
            if (current.isNotEmpty() && current.length + part.length + 1 > 420) {
                out.add(current.toString().trim())
                current.clear()
            }
            if (part.length > 420) {
                var rest = part
                while (rest.length > 420) {
                    var cut = rest.take(421).lastIndexOf(' ')
                    if (cut < 220) cut = 420
                    out.add(rest.take(cut).trim())
                    rest = rest.drop(cut).trim()
                }
                if (rest.isNotBlank()) current.append(rest)
            } else {
                if (current.isNotEmpty()) current.append(' ')
                current.append(part)
            }
        }
        if (current.isNotEmpty()) out.add(current.toString().trim())
        return out
    }

    private fun downloadSpeech(endpoint: String, text: String, outFile: File) {
        val c = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 8000; readTimeout = 45000; doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8"); setRequestProperty("Accept", "audio/mpeg")
        }
        val payload = JSONObject()
            .put("text", text)
            .put("voice", prefs.getString("voice", "coral") ?: "coral")
            .put("speed", 0.94)
            .toString()
        c.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode
        if (code !in 200..299) throw IllegalStateException("TTS HTTP $code")
        c.inputStream.use { inputStream -> outFile.outputStream().use { inputStream.copyTo(it) } }
    }

    private fun speakWithOpenAI(text: String) {
        val backend = prefs.getString("backendUrl", DEFAULT_BACKEND).orEmpty().ifBlank { DEFAULT_BACKEND }
        val chunks = splitForSpeech(text)
        if (chunks.isEmpty()) return
        voicePlayer?.stop(); voicePlayer?.release(); voicePlayer = null
        tts?.stop()
        playSpeechChunk(resolveEndpoint(backend, "speech"), chunks, 0)
    }

    private fun playSpeechChunk(endpoint: String, chunks: List<String>, index: Int) {
        if (index >= chunks.size) {
            runOnUiThread { status.text = "● Listo · respuesta completa" }
            return
        }
        Thread {
            val file = File(cacheDir, "jarvis-reply-${System.currentTimeMillis()}-$index.mp3")
            try {
                downloadSpeech(endpoint, chunks[index], file)
                runOnUiThread {
                    voicePlayer?.release()
                    voicePlayer = MediaPlayer().apply {
                        setDataSource(file.absolutePath)
                        setOnPreparedListener { player ->
                            status.text = "● Hablando · ${index + 1}/${chunks.size}"
                            player.start()
                        }
                        setOnCompletionListener { player ->
                            player.release(); if (voicePlayer === player) voicePlayer = null
                            file.delete(); playSpeechChunk(endpoint, chunks, index + 1)
                        }
                        setOnErrorListener { player, _, _ ->
                            player.release(); if (voicePlayer === player) voicePlayer = null
                            file.delete(); playFallbackChunk(chunks, index); true
                        }
                        prepareAsync()
                    }
                }
            } catch (_: Exception) {
                file.delete(); runOnUiThread { playFallbackChunk(chunks, index) }
            }
        }.start()
    }

    private fun playFallbackChunk(chunks: List<String>, index: Int) {
        val engine = tts ?: run { playSpeechChunk(resolveEndpoint(prefs.getString("backendUrl", DEFAULT_BACKEND).orEmpty().ifBlank { DEFAULT_BACKEND }, "speech"), chunks, index + 1); return }
        val utteranceId = "jarvis-fallback-$index-${System.currentTimeMillis()}"
        engine.speak(chunks[index], TextToSpeech.QUEUE_ADD, null, utteranceId)
        val estimated = (chunks[index].length * 58L).coerceIn(900L, 9000L)
        handler.postDelayed({
            playSpeechChunk(resolveEndpoint(prefs.getString("backendUrl", DEFAULT_BACKEND).orEmpty().ifBlank { DEFAULT_BACKEND }, "speech"), chunks, index + 1)
        }, estimated)
    }

'''
s = s[:start] + replacement + s[end:]

# Stable fallback voice parameters.
s = s.replace('tts?.language = Locale("es", "ES")', 'tts?.language = Locale("es", "ES")\n            tts?.setSpeechRate(0.94f)\n            tts?.setPitch(1.0f)')
s = s.replace('Ajustes de Jarvis TV v0.6.1', 'Ajustes de Jarvis TV v0.6.5')
p.write_text(s)
print('Applied Jarvis TV 0.6.5 source patch')
