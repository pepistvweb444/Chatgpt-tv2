from pathlib import Path

p = Path('mobile/src/main/java/com/jarvis/mobile/MainActivity.kt')
s = p.read_text()

# Imports for speech completion receiver.
if 'import android.content.BroadcastReceiver' not in s:
    s = s.replace('import android.content.Context\n', 'import android.content.BroadcastReceiver\nimport android.content.Context\n')
if 'import android.content.IntentFilter' not in s:
    s = s.replace('import android.content.Intent\n', 'import android.content.Intent\nimport android.content.IntentFilter\n')

field = '    private var voiceRecording = false\n'
if 'private var handsFreeMode = false' not in s:
    s = s.replace(field, field + '    private var handsFreeMode = false\n')

# Receiver restarts listening only after Jarvis has actually finished speaking.
field_anchor = '    private val voiceHandler = Handler(Looper.getMainLooper())\n'
receiver = '''    private val speechDoneReceiver = object : BroadcastReceiver() {\n        override fun onReceive(context: Context?, intent: Intent?) {\n            if (intent?.action == MobileSpeechService.ACTION_SPEECH_DONE && handsFreeMode) {\n                voiceHandler.postDelayed({ if (handsFreeMode && !voiceRecording) startOpenAiVoiceCapture() }, 350L)\n            }\n        }\n    }\n'''
if 'speechDoneReceiver' not in s:
    s = s.replace(field_anchor, field_anchor + receiver)

# Enter hands-free immediately when opened by wake word.
setup_anchor = '        warmLocation()\n'
setup = '''        warmLocation()\n        handsFreeMode = intent?.getBooleanExtra("hands_free", false) == true || intent?.getBooleanExtra("wake_word_triggered", false) == true\n        val speechFilter = IntentFilter(MobileSpeechService.ACTION_SPEECH_DONE)\n        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(speechDoneReceiver, speechFilter, RECEIVER_NOT_EXPORTED)\n        else @Suppress("DEPRECATION") registerReceiver(speechDoneReceiver, speechFilter)\n        if (handsFreeMode) voiceHandler.postDelayed({ if (!voiceRecording) startOpenAiVoiceCapture() }, 500L)\n'''
if 'IntentFilter(MobileSpeechService.ACTION_SPEECH_DONE)' not in s:
    s = s.replace(setup_anchor, setup, 1)

# Wake button enables persistent wake service and hands-free mode.
old_wake = '''        findViewById<View>(R.id.wakeWord).setOnClickListener {\n            closeDrawer(); runCatching { startService(Intent(this, WakeWordService::class.java)) }; status.text = "Hola Jarvis · escuchando"\n        }'''
new_wake = '''        findViewById<View>(R.id.wakeWord).setOnClickListener {\n            closeDrawer()\n            prefs.edit().putBoolean("wake_word_enabled", true).apply()\n            handsFreeMode = true\n            runCatching { androidx.core.content.ContextCompat.startForegroundService(this, Intent(this, WakeWordService::class.java)) }\n            if (!voiceRecording) startOpenAiVoiceCapture()\n            status.text = "Hola Jarvis / Hola Ale · manos libres"\n        }'''
if old_wake in s:
    s = s.replace(old_wake, new_wake)

# Replace manual-stop status/timeout with automatic silence detection.
s = s.replace('status.text = "Escuchando con OpenAI… pulsa el micrófono para terminar"', 'status.text = if (handsFreeMode) "Escuchando…" else "Escuchando con OpenAI…"')
s = s.replace('voiceHandler.postDelayed({ if (voiceRecording) stopOpenAiVoiceCapture() }, 12000L)', '''val startedAt = System.currentTimeMillis()\n            var speechSeen = false\n            var silenceSince = 0L\n            val monitor = object : Runnable {\n                override fun run() {\n                    if (!voiceRecording) return\n                    val now = System.currentTimeMillis()\n                    val amp = runCatching { voiceRecorder?.maxAmplitude ?: 0 }.getOrDefault(0)\n                    if (amp > 1300) { speechSeen = true; silenceSince = 0L }\n                    else if (speechSeen) {\n                        if (silenceSince == 0L) silenceSince = now\n                        if (now - silenceSince > 1050L) { stopOpenAiVoiceCapture(); return }\n                    }\n                    if (now - startedAt > 18000L) { stopOpenAiVoiceCapture(); return }\n                    voiceHandler.postDelayed(this, 140L)\n                }\n            }\n            voiceHandler.postDelayed(monitor, 350L)''')

# Hands-free sends the transcription immediately instead of waiting for another tap.
old_transcribed = '''                        input.setText(spoken)\n                        input.setSelection(spoken.length)\n                        status.text = "Transcripción OpenAI lista"'''
new_transcribed = '''                        input.setText(spoken)\n                        input.setSelection(spoken.length)\n                        status.text = "Transcripción OpenAI lista"\n                        if (handsFreeMode) sendMessage()'''
if old_transcribed in s:
    s = s.replace(old_transcribed, new_transcribed)

# If no speech/error in hands-free, continue listening rather than requiring a tap.
s = s.replace('status.text = "No se ha detectado audio"\n            return', 'status.text = "No se ha detectado audio"\n            if (handsFreeMode) voiceHandler.postDelayed({ startOpenAiVoiceCapture() }, 500L)\n            return')
s = s.replace('runOnUiThread { status.text = "Error de transcripción: ${e.message ?: "OpenAI no disponible"}" }', 'runOnUiThread { status.text = "Error de transcripción: ${e.message ?: "OpenAI no disponible"}"; if (handsFreeMode) voiceHandler.postDelayed({ startOpenAiVoiceCapture() }, 900L) }')

# Clean receiver on activity destroy.
if 'override fun onDestroy()' not in s:
    companion = '    companion object {'
    destroy = '''    override fun onDestroy() {\n        runCatching { unregisterReceiver(speechDoneReceiver) }\n        super.onDestroy()\n    }\n\n'''
    s = s.replace(companion, destroy + companion)

p.write_text(s)

# Speech service broadcasts completion so MainActivity can resume listening exactly then.
p = Path('mobile/src/main/java/com/jarvis/mobile/MobileSpeechService.kt')
s = p.read_text()
old = '        if (token == generation.get()) stopSelf()\n'
new = '        if (token == generation.get()) { sendBroadcast(Intent(ACTION_SPEECH_DONE).setPackage(packageName)); stopSelf() }\n'
if old in s:
    s = s.replace(old, new)
if 'import android.content.Intent' not in s:
    s = s.replace('import android.app.Service\n', 'import android.app.Service\nimport android.content.Intent\n')
if 'const val ACTION_SPEECH_DONE' not in s:
    s = s.replace('const val ACTION_STOP = "com.jarvis.mobile.STOP_SPEECH"', 'const val ACTION_STOP = "com.jarvis.mobile.STOP_SPEECH"\n        const val ACTION_SPEECH_DONE = "com.jarvis.mobile.SPEECH_DONE"')
p.write_text(s)

# Wake service should be restartable and always use the main UI.
p = Path('mobile/src/main/java/com/jarvis/mobile/WakeWordService.kt')
s = p.read_text()
if 'override fun onStartCommand' not in s:
    marker = '    private fun loop() {'
    method = '''    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {\n        return START_STICKY\n    }\n\n'''
    s = s.replace(marker, method + marker)
p.write_text(s)
print('Hands-free voice loop patch applied')