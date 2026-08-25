from pathlib import Path

p = Path('mobile/src/main/java/com/jarvis/mobile/MainActivity.kt')
s = p.read_text()

# Embedded Vosk imports. This engine runs entirely inside the APK and does not
# use Android SpeechRecognizer or any cloud transcription API.
imports = [
    'import org.vosk.Model\n',
    'import org.vosk.Recognizer\n',
    'import org.vosk.android.SpeechService\n',
    'import org.vosk.android.StorageService\n',
    'import org.vosk.android.RecognitionListener as VoskRecognitionListener\n',
]
anchor = 'import org.json.JSONObject\n'
for imp in imports:
    if imp not in s:
        if anchor in s:
            s = s.replace(anchor, anchor + imp, 1)
        else:
            s = s.replace('import java.util.UUID\n', imp + 'import java.util.UUID\n', 1)

# Remove stale Android speech-service imports from previous builds.
for stale in [
    'import android.speech.RecognitionListener\n',
    'import android.speech.RecognizerIntent\n',
    'import android.speech.SpeechRecognizer\n',
    'import android.os.Build\n',
    'import java.util.Locale\n',
    'import android.media.MediaRecorder\n',
    'import android.os.Handler\n',
    'import android.os.Looper\n',
    'import java.io.File\n',
]:
    s = s.replace(stale, '')

# Remove fields belonging to legacy OpenAI/Android speech implementations.
for stale in [
    '    private var voiceRecorder: MediaRecorder? = null\n',
    '    private var voiceFile: File? = null\n',
    '    private var voiceRecording = false\n',
    '    private val voiceHandler = Handler(Looper.getMainLooper())\n',
    '    private var speechRecognizer: SpeechRecognizer? = null\n',
    '    private var speechListening = false\n',
]:
    s = s.replace(stale, '')

field_marker = '    private var lastLocation: Location? = null\n'
fields = '''    private var voskModel: Model? = null
    private var voskSpeechService: SpeechService? = null
    private var voskReady = false
    private var speechListening = false
    private var pendingVoiceStart = false
'''
if 'private var voskModel: Model? = null' not in s:
    if field_marker not in s:
        raise SystemExit('location field anchor not found')
    s = s.replace(field_marker, field_marker + fields, 1)

# Initialize embedded Spanish model while the rest of Jarvis starts.
setup_anchor = '        warmLocation()\n'
setup = '''        warmLocation()
        initEmbeddedVosk()
'''
if 'initEmbeddedVosk()' not in s:
    if setup_anchor not in s:
        raise SystemExit('warmLocation anchor not found')
    s = s.replace(setup_anchor, setup, 1)

# Wire microphone to embedded Vosk.
for old in [
    'findViewById<View>(R.id.mic).setOnClickListener { Toast.makeText(this, "Habla con Jarvis", Toast.LENGTH_SHORT).show() }',
    'findViewById<View>(R.id.mic).setOnClickListener { startVoiceRecognition() }',
    'findViewById<View>(R.id.mic).setOnClickListener { toggleOpenAiVoiceCapture() }',
    'findViewById<View>(R.id.mic).setOnClickListener { toggleNativeVoiceRecognition() }',
]:
    s = s.replace(old, 'findViewById<View>(R.id.mic).setOnClickListener { toggleEmbeddedVoiceRecognition() }')

# Remove stale voice method blocks when present.
for name in [
    'toggleOpenAiVoiceCapture','startOpenAiVoiceCapture','stopOpenAiVoiceCapture',
    'startVoiceRecognition','toggleNativeVoiceRecognition','startNativeVoiceRecognition'
]:
    while True:
        start = s.find(f'    private fun {name}(')
        if start < 0:
            break
        candidates = [x for x in [
            s.find('\n    private fun ', start + 10),
            s.find('\n    override fun ', start + 10),
            s.find('\n    @Deprecated', start + 10),
            s.find('\n    companion object', start + 10),
        ] if x >= 0]
        if not candidates:
            raise SystemExit(f'could not find end of stale method {name}')
        s = s[:start] + s[min(candidates)+1:]

marker = '    private fun chooseProfileImage() {'
methods = r'''    private fun initEmbeddedVosk() {
        if (voskReady || voskModel != null) return
        status.text = "Preparando voz local…"
        StorageService.unpack(
            this,
            "model-es",
            "vosk-model-es",
            { model ->
                voskModel = model
                voskReady = true
                runOnUiThread {
                    status.text = "Jarvis listo"
                    if (pendingVoiceStart) {
                        pendingVoiceStart = false
                        startEmbeddedVoiceRecognition()
                    }
                }
            },
            { error ->
                runOnUiThread { status.text = "Error cargando voz local: ${error.message ?: "modelo no disponible"}" }
            }
        )
    }

    private fun toggleEmbeddedVoiceRecognition() {
        if (speechListening) {
            stopEmbeddedVoiceRecognition(false)
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO)
            return
        }
        if (!voskReady || voskModel == null) {
            pendingVoiceStart = true
            status.text = "Preparando voz local…"
            initEmbeddedVosk()
            return
        }
        startEmbeddedVoiceRecognition()
    }

    private fun startEmbeddedVoiceRecognition() {
        if (speechListening) return
        val model = voskModel ?: run {
            pendingVoiceStart = true
            initEmbeddedVosk()
            return
        }
        try {
            val recognizer = Recognizer(model, 16000.0f)
            voskSpeechService?.shutdown()
            voskSpeechService = SpeechService(recognizer, 16000.0f)
            speechListening = true
            status.text = "Escuchando…"
            voskSpeechService?.startListening(object : VoskRecognitionListener {
                override fun onPartialResult(hypothesis: String?) {
                    val partial = runCatching { JSONObject(hypothesis.orEmpty()).optString("partial") }.getOrDefault("").trim()
                    if (partial.isNotBlank()) runOnUiThread {
                        input.setText(partial)
                        input.setSelection(partial.length)
                    }
                }

                override fun onResult(hypothesis: String?) {
                    val text = runCatching { JSONObject(hypothesis.orEmpty()).optString("text") }.getOrDefault("").trim()
                    if (text.isNotBlank()) runOnUiThread {
                        input.setText(text)
                        input.setSelection(text.length)
                    }
                }

                override fun onFinalResult(hypothesis: String?) {
                    speechListening = false
                    val text = runCatching { JSONObject(hypothesis.orEmpty()).optString("text") }.getOrDefault("").trim()
                    runOnUiThread {
                        if (text.isNotBlank()) {
                            input.setText(text)
                            input.setSelection(text.length)
                            status.text = "Voz lista"
                            sendMessage()
                        } else {
                            status.text = "No he entendido la voz"
                        }
                    }
                }

                override fun onError(exception: Exception?) {
                    speechListening = false
                    runOnUiThread { status.text = "Error de voz local: ${exception?.message ?: "desconocido"}" }
                }

                override fun onTimeout() {
                    speechListening = false
                    runOnUiThread { status.text = "No he detectado voz" }
                }
            })
        } catch (e: Exception) {
            speechListening = false
            status.text = "No se pudo iniciar la voz local: ${e.message ?: "error"}"
        }
    }

    private fun stopEmbeddedVoiceRecognition(restart: Boolean) {
        speechListening = false
        runCatching { voskSpeechService?.stop() }
        runCatching { voskSpeechService?.shutdown() }
        voskSpeechService = null
        if (restart) window.decorView.postDelayed({ startEmbeddedVoiceRecognition() }, 350L)
    }

'''
if 'private fun toggleEmbeddedVoiceRecognition()' not in s:
    if marker not in s:
        raise SystemExit('profile picker marker not found')
    s = s.replace(marker, methods + marker, 1)

# Permission callback starts embedded engine.
perm_marker = '    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {'
idx = s.find(perm_marker)
if idx >= 0:
    body_start = s.find('\n', idx) + 1
    import re
    nearby = s[body_start:body_start+900]
    nearby2 = re.sub(r'\s*if \(requestCode == REQ_AUDIO\) \{[\s\S]*?\n\s*\}\n', '\n', nearby, count=1)
    s = s[:body_start] + nearby2 + s[body_start+900:]
    body_start = s.find('\n', s.find(perm_marker)) + 1
    s = s[:body_start] + '        if (requestCode == REQ_AUDIO) {\n            if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) startEmbeddedVoiceRecognition() else status.text = "Permiso de micrófono denegado"\n            return\n        }\n' + s[body_start:]

if 'private const val REQ_AUDIO = 1206' not in s:
    s = s.replace('private const val REQ_PROFILE_IMAGE = 1205', 'private const val REQ_PROFILE_IMAGE = 1205\n        private const val REQ_AUDIO = 1206')

p.write_text(s)
print('MainActivity embedded Vosk Spanish voice recognition patch applied')
