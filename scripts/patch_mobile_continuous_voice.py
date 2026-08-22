from pathlib import Path

p = Path('mobile/src/main/java/com/jarvis/mobile/ChatActivity.kt')
s = p.read_text()

s = s.replace(
    'if (local.handled) { append("assistant", local.message); status.text = "Acción del teléfono"; speak(local.message); return }',
    'if (local.handled) { status.text = "Preparando voz…"; FastVoice.speak(this, prefs, local.message) { append("assistant", local.message); status.text = "● Hablando · puedes interrumpirme" }; return }'
)

s = s.replace(
    'runOnUiThread { append("assistant", result.first); status.text = "● Listo · memoria activa"; speak(result.first) }',
    'runOnUiThread { status.text = "Preparando voz…"; FastVoice.speak(this, prefs, result.first) { append("assistant", result.first); status.text = "● Hablando · puedes interrumpirme" } }'
)

s = s.replace(
    'if (intent.getBooleanExtra("wake_word_triggered", false)) handler.postDelayed({ startVoiceCapture() }, 200)',
    'if (intent.getBooleanExtra("wake_word_triggered", false)) handler.postDelayed({ status.text = "● Conversación continua"; startVoiceCapture() }, 200)'
)

s = s.replace(
    'override fun onBeginningOfSpeech() { status.text = "Te escucho…" }',
    'override fun onBeginningOfSpeech() { FastVoice.stop(); status.text = "Te escucho…" }'
)

s = s.replace(
    'if (enabled) { stopService(Intent(this, WakeWordService::class.java)); prefs.edit().putBoolean("wake_word_enabled", false).apply(); status.text = "Hola Jarvis desactivado" }',
    'if (enabled) { stopService(Intent(this, WakeWordService::class.java)); prefs.edit().putBoolean("wake_word_enabled", false).apply(); status.text = "Hola Ale / Hola Jarvis desactivado" }'
)

s = s.replace(
    'ContextCompat.startForegroundService(this, Intent(this, WakeWordService::class.java)); prefs.edit().putBoolean("wake_word_enabled", true).apply(); status.text = "Hola Jarvis escuchando"',
    'ContextCompat.startForegroundService(this, Intent(this, WakeWordService::class.java)); prefs.edit().putBoolean("wake_word_enabled", true).apply(); status.text = "Hola Ale / Hola Jarvis escuchando"'
)

p.write_text(s)
