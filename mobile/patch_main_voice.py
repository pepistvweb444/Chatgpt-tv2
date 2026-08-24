from pathlib import Path

p = Path('mobile/src/main/java/com/jarvis/mobile/MainActivity.kt')
s = p.read_text()

# Imports for integrated audio recording. No RecognizerIntent / Google speech UI.
if 'import android.media.MediaRecorder' not in s:
    s = s.replace('import android.location.LocationManager\n', 'import android.location.LocationManager\nimport android.media.MediaRecorder\n')
if 'import android.os.Build' not in s:
    s = s.replace('import android.os.Bundle\n', 'import android.os.Build\nimport android.os.Bundle\n')
if 'import android.os.Handler' not in s:
    s = s.replace('import android.os.Bundle\n', 'import android.os.Bundle\nimport android.os.Handler\nimport android.os.Looper\n')
if 'import java.io.File' not in s:
    s = s.replace('import java.net.HttpURLConnection\n', 'import java.io.File\nimport java.net.HttpURLConnection\n')

# Remove any stale Google recognizer import if present.
s = s.replace('import android.speech.RecognizerIntent\n', '')
s = s.replace('import android.speech.SpeechRecognizer\n', '')
s = s.replace('import android.speech.RecognitionListener\n', '')

# Add recorder state fields.
field_marker = '    private var lastLocation: Location? = null\n'
if 'private var voiceRecorder: MediaRecorder? = null' not in s:
    s = s.replace(field_marker, field_marker + '    private var voiceRecorder: MediaRecorder? = null\n    private var voiceFile: File? = null\n    private var voiceRecording = false\n    private val voiceHandler = Handler(Looper.getMainLooper())\n')

# Replace mic action.
s = s.replace(
    'findViewById<View>(R.id.mic).setOnClickListener { Toast.makeText(this, "Habla con Jarvis", Toast.LENGTH_SHORT).show() }',
    'findViewById<View>(R.id.mic).setOnClickListener { toggleOpenAiVoiceCapture() }'
)
s = s.replace(
    'findViewById<View>(R.id.mic).setOnClickListener { startVoiceRecognition() }',
    'findViewById<View>(R.id.mic).setOnClickListener { toggleOpenAiVoiceCapture() }'
)

# Remove old RecognizerIntent implementation if a previous build patch inserted it.
start = s.find('    private fun startVoiceRecognition() {')
if start != -1:
    end = s.find('    @Deprecated("Deprecated in Java")', start)
    if end != -1:
        s = s[:start] + s[end:]

# Insert integrated OpenAI recording/transcription methods before profile picker.
marker = '    private fun chooseProfileImage() {'
methods = r'''    private fun toggleOpenAiVoiceCapture() {
        if (voiceRecording) {
            stopOpenAiVoiceCapture()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO)
            return
        }
        startOpenAiVoiceCapture()
    }

    private fun startOpenAiVoiceCapture() {
        if (voiceRecording) return
        val file = File(cacheDir, "jarvis-openai-${System.currentTimeMillis()}.m4a")
        voiceFile = file
        try {
            voiceRecorder = (if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(16000)
                setAudioEncodingBitRate(64000)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            voiceRecording = true
            status.text = "Escuchando con OpenAI… pulsa el micrófono para terminar"
            voiceHandler.postDelayed({ if (voiceRecording) stopOpenAiVoiceCapture() }, 12000L)
        } catch (e: Throwable) {
            voiceRecording = false
            runCatching { voiceRecorder?.release() }
            voiceRecorder = null
            status.text = "No se pudo iniciar el micrófono"
        }
    }

    private fun stopOpenAiVoiceCapture() {
        if (!voiceRecording) return
        voiceRecording = false
        voiceHandler.removeCallbacksAndMessages(null)
        val recorder = voiceRecorder
        voiceRecorder = null
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        val file = voiceFile
        voiceFile = null
        if (file == null || !file.exists() || file.length() < 512) {
            runCatching { file?.delete() }
            status.text = "No se ha detectado audio"
            return
        }
        status.text = "Transcribiendo con OpenAI…"
        Thread {
            try {
                val c = (URL("$BACKEND/api/transcribe").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 8000
                    readTimeout = 30000
                    setRequestProperty("Content-Type", "audio/mp4")
                    setRequestProperty("X-Filename", file.name)
                }
                c.outputStream.use { out -> file.inputStream().use { it.copyTo(out) } }
                val raw = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}: ${raw.take(160)}")
                val spoken = JSONObject(raw).optString("text").trim()
                runOnUiThread {
                    if (spoken.isNotBlank()) {
                        input.setText(spoken)
                        input.setSelection(spoken.length)
                        status.text = "Transcripción OpenAI lista"
                    } else {
                        status.text = "No he entendido la voz"
                    }
                }
            } catch (e: Throwable) {
                runOnUiThread { status.text = "Error de transcripción: ${e.message ?: "OpenAI no disponible"}" }
            } finally {
                file.delete()
            }
        }.start()
    }

'''
if 'private fun toggleOpenAiVoiceCapture()' not in s:
    if marker not in s:
        raise SystemExit('profile picker marker not found')
    s = s.replace(marker, methods + marker)

# Ensure audio permission callback starts recording. Weather callback may be expanded later by its patch.
perm_marker = '    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {'
idx = s.find(perm_marker)
if idx != -1 and 'requestCode == REQ_AUDIO' not in s[idx:idx+700]:
    body_start = s.find('\n', idx) + 1
    s = s[:body_start] + '        if (requestCode == REQ_AUDIO) {\n            if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) startOpenAiVoiceCapture() else status.text = "Permiso de micrófono denegado"\n            return\n        }\n' + s[body_start:]

# Remove old voice request code constant and add integrated audio request constant.
s = s.replace('        private const val REQ_VOICE = 1206\n', '')
if 'private const val REQ_AUDIO = 1206' not in s:
    s = s.replace('private const val REQ_PROFILE_IMAGE = 1205', 'private const val REQ_PROFILE_IMAGE = 1205\n        private const val REQ_AUDIO = 1206')

p.write_text(s)
print('MainActivity integrated OpenAI transcription patch applied')
