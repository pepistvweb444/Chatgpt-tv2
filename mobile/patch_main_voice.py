from pathlib import Path

p = Path('mobile/src/main/java/com/jarvis/mobile/MainActivity.kt')
s = p.read_text()

if 'import android.speech.RecognizerIntent' not in s:
    s = s.replace('import android.os.Bundle\n', 'import android.os.Bundle\nimport android.speech.RecognizerIntent\n')

s = s.replace(
    'findViewById<View>(R.id.mic).setOnClickListener { Toast.makeText(this, "Habla con Jarvis", Toast.LENGTH_SHORT).show() }',
    'findViewById<View>(R.id.mic).setOnClickListener { startVoiceRecognition() }'
)

marker = '''    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PROFILE_IMAGE && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                prefs.edit().putString("profile_uri", uri.toString()).apply()
                loadConversation()
            }
        }
    }
'''
replacement = '''    private fun startVoiceRecognition() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Habla con Jarvis")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        status.text = "Escuchando…"
        runCatching { startActivityForResult(intent, REQ_VOICE) }
            .onFailure { status.text = "Reconocimiento de voz no disponible" }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VOICE) {
            if (resultCode == RESULT_OK) {
                val spoken = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty().trim()
                if (spoken.isNotBlank()) {
                    input.setText(spoken)
                    input.setSelection(spoken.length)
                    status.text = "Transcripción lista"
                    sendMessage()
                } else status.text = "No he entendido la voz"
            } else status.text = "Jarvis listo"
            return
        }
        if (requestCode == REQ_PROFILE_IMAGE && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                prefs.edit().putString("profile_uri", uri.toString()).apply()
                loadConversation()
            }
        }
    }
'''

if marker not in s:
    raise SystemExit('onActivityResult marker not found')
s = s.replace(marker, replacement)

s = s.replace(
    'private const val REQ_PROFILE_IMAGE = 1205',
    'private const val REQ_PROFILE_IMAGE = 1205\n        private const val REQ_VOICE = 1206'
)

p.write_text(s)
print('MainActivity voice transcription patch applied')
