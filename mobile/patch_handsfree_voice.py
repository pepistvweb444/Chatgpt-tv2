from pathlib import Path

p = Path('mobile/src/main/java/com/jarvis/mobile/MainActivity.kt')
s = p.read_text()

# Hands-free must reuse Android SpeechRecognizer. Do not restore the legacy
# MediaRecorder/OpenAI transcription path removed by patch_main_voice.py.
if 'import android.content.BroadcastReceiver\n' not in s:
    s = s.replace('import android.content.Context\n', 'import android.content.BroadcastReceiver\nimport android.content.Context\n')
if 'import android.content.IntentFilter\n' not in s:
    s = s.replace('import android.content.Intent\n', 'import android.content.Intent\nimport android.content.IntentFilter\n')

field_anchor = '    private var speechListening = false\n'
fields = '''    private var handsFreeMode = false
    private val speechDoneReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == MobileSpeechService.ACTION_SPEECH_DONE && handsFreeMode) {
                window.decorView.postDelayed({
                    if (handsFreeMode && !speechListening) startNativeVoiceRecognition()
                }, 350L)
            }
        }
    }
'''
if 'private var handsFreeMode = false' not in s:
    if field_anchor not in s:
        raise SystemExit('native speech field anchor not found')
    s = s.replace(field_anchor, field_anchor + fields, 1)

# Register completion receiver and enter hands-free when launched from wake word.
setup_anchor = '        warmLocation()\n'
setup = '''        warmLocation()
        handsFreeMode = intent?.getBooleanExtra("hands_free", false) == true || intent?.getBooleanExtra("wake_word_triggered", false) == true
        val speechFilter = IntentFilter(MobileSpeechService.ACTION_SPEECH_DONE)
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(speechDoneReceiver, speechFilter, RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(speechDoneReceiver, speechFilter)
        if (handsFreeMode) window.decorView.postDelayed({ if (!speechListening) startNativeVoiceRecognition() }, 500L)
'''
if 'IntentFilter(MobileSpeechService.ACTION_SPEECH_DONE)' not in s:
    if setup_anchor not in s:
        raise SystemExit('warmLocation setup anchor not found')
    s = s.replace(setup_anchor, setup, 1)

# Wake button enables persistent wake service and native hands-free recognition.
old_wake = '''        findViewById<View>(R.id.wakeWord).setOnClickListener {
            closeDrawer(); runCatching { startService(Intent(this, WakeWordService::class.java)) }; status.text = "Hola Jarvis · escuchando"
        }'''
new_wake = '''        findViewById<View>(R.id.wakeWord).setOnClickListener {
            closeDrawer()
            prefs.edit().putBoolean("wake_word_enabled", true).apply()
            handsFreeMode = true
            runCatching { androidx.core.content.ContextCompat.startForegroundService(this, Intent(this, WakeWordService::class.java)) }
            if (!speechListening) startNativeVoiceRecognition()
            status.text = "Hola Jarvis / Hola Ale · manos libres"
        }'''
if old_wake in s:
    s = s.replace(old_wake, new_wake, 1)

# In hands-free mode retry Android recognition after recoverable recognition errors.
error_anchor = '''                speechListening = false
                status.text = when (error) {'''
if error_anchor in s and 'if (handsFreeMode) window.decorView.postDelayed' not in s[s.find(error_anchor):s.find(error_anchor)+900]:
    old = '''                    else -> "Error de voz de Android ($error)"
                }
            }'''
    new = '''                    else -> "Error de voz de Android ($error)"
                }
                if (handsFreeMode) window.decorView.postDelayed({ if (!speechListening) startNativeVoiceRecognition() }, 900L)
            }'''
    s = s.replace(old, new, 1)

# Clean up native recognizer and receiver safely.
if 'unregisterReceiver(speechDoneReceiver)' not in s:
    companion = '    companion object {'
    destroy = '''    override fun onDestroy() {
        runCatching { unregisterReceiver(speechDoneReceiver) }
        runCatching { speechRecognizer?.destroy() }
        speechRecognizer = null
        super.onDestroy()
    }

'''
    if companion in s:
        s = s.replace(companion, destroy + companion, 1)

# Ensure no stale OpenAI-recording symbols survive this patch.
s = s.replace('if (!voiceRecording) startOpenAiVoiceCapture()', 'if (!speechListening) startNativeVoiceRecognition()')
s = s.replace('if (handsFreeMode && !voiceRecording) startOpenAiVoiceCapture()', 'if (handsFreeMode && !speechListening) startNativeVoiceRecognition()')

p.write_text(s)

# Speech service broadcasts completion so MainActivity resumes listening after TTS.
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
print('Hands-free voice loop migrated to native Android SpeechRecognizer')
