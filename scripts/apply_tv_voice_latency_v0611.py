from pathlib import Path

p=Path('app/src/main/java/com/jarvis/tv/MainActivity.kt')
s=p.read_text()

field_anchor='    private var voicePlayer: MediaPlayer? = null\n'
if 'private var voiceCaptureStartedAt' not in s:
    fields=r'''    private var voiceCaptureStartedAt = 0L
    private var voiceLastSoundAt = 0L
    private var voiceHeard = false
    private val voiceMonitor = object : Runnable {
        override fun run() {
            val r = recorder ?: return
            val now = System.currentTimeMillis()
            val amp = runCatching { r.maxAmplitude }.getOrDefault(0)
            if (amp >= 850) {
                voiceHeard = true
                voiceLastSoundAt = now
            }
            val elapsed = now - voiceCaptureStartedAt
            val silence = now - voiceLastSoundAt
            when {
                elapsed >= 18000L -> stopRecorder(true)
                voiceHeard && elapsed >= 1100L && silence >= 1150L -> stopRecorder(true)
                !voiceHeard && elapsed >= 7000L -> stopRecorder(true)
                else -> handler.postDelayed(this, 120L)
            }
        }
    }
'''
    if field_anchor not in s: raise SystemExit('voicePlayer field anchor not found')
    s=s.replace(field_anchor,field_anchor+fields,1)

# Prefer an actual TV/USB/Bluetooth microphone when Fire OS exposes one.
source_anchor='''        try {
            mr.setAudioSource(if (isFireTv()) MediaRecorder.AudioSource.MIC else MediaRecorder.AudioSource.VOICE_RECOGNITION)'''
if 'preferredVoiceInput' not in s and source_anchor in s:
    replacement=r'''        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val preferredVoiceInput = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { d ->
                d.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || d.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                    d.type == AudioDeviceInfo.TYPE_USB_DEVICE || d.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    d.type == AudioDeviceInfo.TYPE_BUILTIN_MIC
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && preferredVoiceInput != null) runCatching { mr.setPreferredDevice(preferredVoiceInput) }
            mr.setAudioSource(if (isFireTv()) MediaRecorder.AudioSource.MIC else MediaRecorder.AudioSource.VOICE_RECOGNITION)'''
    s=s.replace(source_anchor,replacement,1)

# Replace the fixed 4.2 second recording window with silence detection and a generous hard maximum.
old=r'''            mr.prepare(); mr.start()
            status.text = "● Escuchando a Jarvis…"
            Toast.makeText(this, "Habla con Jarvis ahora", Toast.LENGTH_SHORT).show()
            handler.postDelayed({ stopRecorder(true) }, 4200)'''
new=r'''            mr.prepare(); mr.start()
            voiceCaptureStartedAt = System.currentTimeMillis()
            voiceLastSoundAt = voiceCaptureStartedAt
            voiceHeard = false
            status.text = "● Escuchando · habla con naturalidad…"
            Toast.makeText(this, "Habla con Jarvis", Toast.LENGTH_SHORT).show()
            handler.removeCallbacks(voiceMonitor)
            handler.postDelayed(voiceMonitor, 180L)'''
if old in s:
    s=s.replace(old,new,1)
else:
    s=s.replace('handler.postDelayed({ stopRecorder(true) }, 4200)', 'handler.removeCallbacks(voiceMonitor); handler.postDelayed(voiceMonitor, 180L)', 1)

stop_anchor='''    private fun stopRecorder(upload: Boolean) {
        val active = recorder ?: return'''
if stop_anchor in s and 'handler.removeCallbacks(voiceMonitor)' not in s[s.find(stop_anchor):s.find(stop_anchor)+220]:
    s=s.replace(stop_anchor,'''    private fun stopRecorder(upload: Boolean) {
        handler.removeCallbacks(voiceMonitor)
        val active = recorder ?: return''',1)

# Physical voice/search key: use Jarvis whenever the key event reaches the app, including Fire TV.
s=s.replace('if (!isFireTv() && event.action == KeyEvent.ACTION_UP && (event.keyCode == KeyEvent.KEYCODE_SEARCH || event.keyCode == KeyEvent.KEYCODE_VOICE_ASSIST))',
            'if (event.action == KeyEvent.ACTION_UP && (event.keyCode == KeyEvent.KEYCODE_SEARCH || event.keyCode == KeyEvent.KEYCODE_VOICE_ASSIST))')

# Keep only recent messages in the HTTP body; previousResponseId still keeps server-side continuity.
history_old='''        val historyPayload = JSONArray()
        for (i in 0 until history.length()) {'''
history_new='''        val historyPayload = JSONArray()
        val historyStart = (history.length() - 14).coerceAtLeast(0)
        for (i in historyStart until history.length()) {'''
if history_old in s:
    s=s.replace(history_old,history_new,1)

# Fast speech: cloud voice wins if it arrives quickly; otherwise local TTS starts after 900 ms.
def replace_function(text, signature, replacement):
    start=text.find(signature)
    if start<0: return text, False
    brace=text.find('{',start)
    if brace<0: return text, False
    depth=0
    end=-1
    for i in range(brace,len(text)):
        if text[i]=='{': depth+=1
        elif text[i]=='}':
            depth-=1
            if depth==0:
                end=i+1; break
    if end<0: return text, False
    return text[:start]+replacement+text[end:], True

replacement=r'''    private fun speakWithOpenAI(text: String) {
        val backend = prefs.getString("backendUrl", DEFAULT_BACKEND).orEmpty().ifBlank { DEFAULT_BACKEND }
        val started = java.util.concurrent.atomic.AtomicBoolean(false)
        val localFallback = Runnable {
            if (started.compareAndSet(false, true)) {
                tts?.setSpeechRate(1.04f)
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis-fast-fallback")
            }
        }
        handler.postDelayed(localFallback, 900L)
        Thread {
            val file = File(cacheDir, "jarvis-reply-${System.currentTimeMillis()}.mp3")
            try {
                downloadSpeech(resolveEndpoint(backend, "speech"), text, file)
                runOnUiThread {
                    if (!started.compareAndSet(false, true)) {
                        file.delete()
                        return@runOnUiThread
                    }
                    handler.removeCallbacks(localFallback)
                    voicePlayer?.release()
                    voicePlayer = MediaPlayer().apply {
                        setDataSource(file.absolutePath)
                        setOnPreparedListener { it.start() }
                        setOnCompletionListener { p -> p.release(); if (voicePlayer === p) voicePlayer = null; file.delete() }
                        setOnErrorListener { p, _, _ -> p.release(); if (voicePlayer === p) voicePlayer = null; file.delete(); true }
                        prepareAsync()
                    }
                }
            } catch (_: Exception) {
                file.delete()
                runOnUiThread {
                    handler.removeCallbacks(localFallback)
                    if (started.compareAndSet(false, true)) {
                        tts?.setSpeechRate(1.04f)
                        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis-fast-error-fallback")
                    }
                }
            }
        }.start()
    }'''
s,ok=replace_function(s,'    private fun speakWithOpenAI(text: String)',replacement)
if not ok: raise SystemExit('speakWithOpenAI function not found')

# Slightly faster local voice when it is used.
s=s.replace('override fun onInit(code: Int) { if (code == TextToSpeech.SUCCESS) tts?.language = Locale("es", "ES") }',
            'override fun onInit(code: Int) { if (code == TextToSpeech.SUCCESS) { tts?.language = Locale("es", "ES"); tts?.setSpeechRate(1.04f) } }')

p.write_text(s)
print('TV voice latency/VAD/Fire microphone patch applied')
