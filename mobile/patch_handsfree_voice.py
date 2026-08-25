from pathlib import Path

p = Path('mobile/src/main/java/com/jarvis/mobile/MainActivity.kt')
s = p.read_text()

# Hands-free reuses the embedded Vosk engine injected by patch_main_voice.py.
if 'import android.content.BroadcastReceiver\n' not in s:
    s = s.replace('import android.content.Context\n', 'import android.content.BroadcastReceiver\nimport android.content.Context\n')
if 'import android.content.IntentFilter\n' not in s:
    s = s.replace('import android.content.Intent\n', 'import android.content.Intent\nimport android.content.IntentFilter\n')

field_anchor = '    private var pendingVoiceStart = false\n'
fields = '''    private var handsFreeMode = false
    private val speechDoneReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == MobileSpeechService.ACTION_SPEECH_DONE && handsFreeMode) {
                window.decorView.postDelayed({
                    if (handsFreeMode && !speechListening) startEmbeddedVoiceRecognition()
                }, 350L)
            }
        }
    }
'''
if 'private var handsFreeMode = false' not in s:
    if field_anchor not in s:
        raise SystemExit('embedded Vosk field anchor not found')
    s = s.replace(field_anchor, field_anchor + fields, 1)

# Register completion receiver and enter hands-free when launched by wake word.
setup_anchor = '        initEmbeddedVosk()\n'
setup = '''        initEmbeddedVosk()
        handsFreeMode = intent?.getBooleanExtra("hands_free", false) == true || intent?.getBooleanExtra("wake_word_triggered", false) == true
        val speechFilter = IntentFilter(MobileSpeechService.ACTION_SPEECH_DONE)
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(speechDoneReceiver, speechFilter, RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(speechDoneReceiver, speechFilter)
        if (handsFreeMode) window.decorView.postDelayed({ if (!speechListening) startEmbeddedVoiceRecognition() }, 500L)
'''
if 'IntentFilter(MobileSpeechService.ACTION_SPEECH_DONE)' not in s:
    if setup_anchor not in s:
        raise SystemExit('embedded Vosk setup anchor not found')
    s = s.replace(setup_anchor, setup, 1)

old_wake = '''        findViewById<View>(R.id.wakeWord).setOnClickListener {
            closeDrawer(); runCatching { startService(Intent(this, WakeWordService::class.java)) }; status.text = "Hola Jarvis · escuchando"
        }'''
new_wake = '''        findViewById<View>(R.id.wakeWord).setOnClickListener {
            closeDrawer()
            prefs.edit().putBoolean("wake_word_enabled", true).apply()
            handsFreeMode = true
            runCatching { androidx.core.content.ContextCompat.startForegroundService(this, Intent(this, WakeWordService::class.java)) }
            if (!speechListening) startEmbeddedVoiceRecognition()
            status.text = "Hola Jarvis / Hola Ale · manos libres local"
        }'''
if old_wake in s:
    s = s.replace(old_wake, new_wake, 1)

# In hands-free mode, after an empty result/error, resume the embedded listener.
empty_anchor = 'status.text = "No he entendido la voz"\n                        }'
if empty_anchor in s:
    s = s.replace(empty_anchor, 'status.text = "No he entendido la voz"\n                            if (handsFreeMode) window.decorView.postDelayed({ if (!speechListening) startEmbeddedVoiceRecognition() }, 650L)\n                        }', 1)

error_anchor = 'runOnUiThread { status.text = "Error de voz local: ${exception?.message ?: "desconocido"}" }'
if error_anchor in s:
    s = s.replace(error_anchor, 'runOnUiThread { status.text = "Error de voz local: ${exception?.message ?: "desconocido"}"; if (handsFreeMode) window.decorView.postDelayed({ if (!speechListening) startEmbeddedVoiceRecognition() }, 900L) }', 1)

timeout_anchor = 'runOnUiThread { status.text = "No he detectado voz" }'
if timeout_anchor in s:
    s = s.replace(timeout_anchor, 'runOnUiThread { status.text = "No he detectado voz"; if (handsFreeMode) window.decorView.postDelayed({ if (!speechListening) startEmbeddedVoiceRecognition() }, 650L) }', 1)

# Clean up Vosk and receiver.
if 'unregisterReceiver(speechDoneReceiver)' not in s:
    companion = '    companion object {'
    destroy = '''    override fun onDestroy() {
        runCatching { unregisterReceiver(speechDoneReceiver) }
        runCatching { voskSpeechService?.stop() }
        runCatching { voskSpeechService?.shutdown() }
        voskSpeechService = null
        runCatching { voskModel?.close() }
        voskModel = null
        super.onDestroy()
    }

'''
    if companion in s:
        s = s.replace(companion, destroy + companion, 1)

p.write_text(s)

# TTS broadcasts completion so the embedded listener resumes only after Jarvis
# has finished speaking.
p = Path('mobile/src/main/java/com/jarvis/mobile/MobileSpeechService.kt')
s = p.read_text()
old = '        if (token == generation.get()) stopSelf()\n'
new = '        if (token == generation.get()) { sendBroadcast(Intent(ACTION_SPEECH_DONE).setPackage(packageName)); stopSelf() }\n'
if old in s:
    s = s.replace(old, new)
if 'import android.content.Intent\n' not in s:
    s = s.replace('import android.app.Service\n', 'import android.app.Service\nimport android.content.Intent\n')
if 'const val ACTION_SPEECH_DONE' not in s:
    s = s.replace('const val ACTION_STOP = "com.jarvis.mobile.STOP_SPEECH"', 'const val ACTION_STOP = "com.jarvis.mobile.STOP_SPEECH"\n        const val ACTION_SPEECH_DONE = "com.jarvis.mobile.SPEECH_DONE"')
p.write_text(s)

# Wake service remains restartable.
p = Path('mobile/src/main/java/com/jarvis/mobile/WakeWordService.kt')
s = p.read_text()
if 'override fun onStartCommand' not in s:
    marker = '    private fun loop() {'
    method = '''    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

'''
    s = s.replace(marker, method + marker)
p.write_text(s)
print('Hands-free voice loop migrated to embedded Vosk')
