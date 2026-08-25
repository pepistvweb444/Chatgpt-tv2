from pathlib import Path

p = Path('mobile/src/main/java/com/jarvis/mobile/MainActivity.kt')
s = p.read_text()

# Use Android SpeechRecognizer first so voice commands do not depend on paid
# OpenAI/Gemini transcription quotas.
imports = {
    'import android.speech.RecognitionListener\n': 'import android.location.LocationManager\n',
    'import android.speech.RecognizerIntent\n': 'import android.location.LocationManager\n',
    'import android.speech.SpeechRecognizer\n': 'import android.location.LocationManager\n',
}
for imp, marker in imports.items():
    if imp not in s:
        s = s.replace(marker, marker + imp)
if 'import android.os.Build\n' not in s:
    s = s.replace('import android.os.Bundle\n', 'import android.os.Build\nimport android.os.Bundle\n')
if 'import java.util.Locale\n' not in s:
    s = s.replace('import java.util.UUID\n', 'import java.util.UUID\nimport java.util.Locale\n')

# Remove stale MediaRecorder implementation imports/fields from previous patches.
for stale in ['import android.media.MediaRecorder\n','import android.os.Handler\n','import android.os.Looper\n','import java.io.File\n']:
    s = s.replace(stale, '')
for stale in [
    '    private var voiceRecorder: MediaRecorder? = null\n',
    '    private var voiceFile: File? = null\n',
    '    private var voiceRecording = false\n',
    '    private val voiceHandler = Handler(Looper.getMainLooper())\n'
]:
    s = s.replace(stale, '')
field_marker = '    private var lastLocation: Location? = null\n'
if 'private var speechRecognizer: SpeechRecognizer? = null' not in s:
    s = s.replace(field_marker, field_marker + '    private var speechRecognizer: SpeechRecognizer? = null\n    private var speechListening = false\n')

# Wire microphone to native recognizer.
for old in [
    'findViewById<View>(R.id.mic).setOnClickListener { Toast.makeText(this, "Habla con Jarvis", Toast.LENGTH_SHORT).show() }',
    'findViewById<View>(R.id.mic).setOnClickListener { startVoiceRecognition() }',
    'findViewById<View>(R.id.mic).setOnClickListener { toggleOpenAiVoiceCapture() }'
]:
    s = s.replace(old, 'findViewById<View>(R.id.mic).setOnClickListener { toggleNativeVoiceRecognition() }')

# Remove stale voice method blocks when present.
for name in ['toggleOpenAiVoiceCapture','startOpenAiVoiceCapture','stopOpenAiVoiceCapture','startVoiceRecognition']:
    start = s.find(f'    private fun {name}(')
    if start >= 0:
        # Find next member function/override.
        candidates = [x for x in [s.find('\n    private fun ', start+10), s.find('\n    override fun ', start+10), s.find('\n    @Deprecated', start+10)] if x >= 0]
        if candidates:
            s = s[:start] + s[min(candidates)+1:]

marker = '    private fun chooseProfileImage() {'
methods = r'''    private fun toggleNativeVoiceRecognition() {
        if (speechListening) {
            runCatching { speechRecognizer?.stopListening() }
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO)
            return
        }
        startNativeVoiceRecognition()
    }

    private fun startNativeVoiceRecognition() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            status.text = "Reconocimiento de voz de Android no disponible"
            return
        }
        runCatching { speechRecognizer?.destroy() }
        speechRecognizer = if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        } else {
            SpeechRecognizer.createSpeechRecognizer(this)
        }
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { speechListening = true; status.text = "Escuchando…" }
            override fun onBeginningOfSpeech() { status.text = "Escuchando…" }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { speechListening = false; status.text = "Procesando voz…" }
            override fun onError(error: Int) {
                speechListening = false
                status.text = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "No he entendido la voz"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No he detectado voz"
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Reconocimiento de voz sin conexión disponible"
                    else -> "Error de voz de Android ($error)"
                }
            }
            override fun onResults(results: Bundle?) {
                speechListening = false
                val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().trim()
                if (spoken.isNotBlank()) {
                    input.setText(spoken)
                    input.setSelection(spoken.length)
                    status.text = "Voz lista"
                    sendMessage()
                } else status.text = "No he entendido la voz"
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().trim()
                if (partial.isNotBlank()) { input.setText(partial); input.setSelection(partial.length) }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "es-ES")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        speechRecognizer?.startListening(intent)
    }

'''
if 'private fun toggleNativeVoiceRecognition()' not in s:
    if marker not in s:
        raise SystemExit('profile picker marker not found')
    s = s.replace(marker, methods + marker, 1)

# Permission callback must start native recognizer.
perm_marker = '    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {'
idx = s.find(perm_marker)
if idx >= 0:
    body_start = s.find('\n', idx) + 1
    # remove stale audio callback if found nearby
    nearby = s[body_start:body_start+700]
    import re
    nearby2 = re.sub(r'\s*if \(requestCode == REQ_AUDIO\) \{[\s\S]*?\n\s*\}\n', '\n', nearby, count=1)
    s = s[:body_start] + nearby2 + s[body_start+700:]
    body_start = s.find('\n', s.find(perm_marker)) + 1
    s = s[:body_start] + '        if (requestCode == REQ_AUDIO) {\n            if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) startNativeVoiceRecognition() else status.text = "Permiso de micrófono denegado"\n            return\n        }\n' + s[body_start:]

if 'private const val REQ_AUDIO = 1206' not in s:
    s = s.replace('private const val REQ_PROFILE_IMAGE = 1205', 'private const val REQ_PROFILE_IMAGE = 1205\n        private const val REQ_AUDIO = 1206')

p.write_text(s)
print('MainActivity native Android voice recognition patch applied')
