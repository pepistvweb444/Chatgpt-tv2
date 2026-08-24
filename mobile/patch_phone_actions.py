from pathlib import Path

p = Path('mobile/src/main/java/com/jarvis/mobile/MainActivity.kt')
s = p.read_text()

# Add LocalActionRouter to the main UI.
needle = '    private val prefs by lazy { getSharedPreferences("jarvis_mobile", MODE_PRIVATE) }\n'
if 'private val actionRouter by lazy' not in s:
    s = s.replace(needle, needle + '    private val actionRouter by lazy { LocalActionRouter(this) }\n')

old = '''    private fun sendMessage() {
        val message = input.text.toString().trim(); if (message.isBlank()) return
        input.text.clear()
        renderMessageCard("user", message)
        saveHistory("user", message, false)
        when (val kind = classifyVisualRequest(message)) {
            "news" -> openNewsFast()
            "weather" -> openWeatherForCurrentLocation(message)
            "home", "day" -> executeChat("$message. Devuelve cada elemento relevante en una línea separada, sin introducción ni conclusión.", kind)
            else -> executeChat(message, null)
        }
    }
'''
new = '''    private fun sendMessage() {
        val message = input.text.toString().trim(); if (message.isBlank()) return
        input.text.clear()
        renderMessageCard("user", message)
        saveHistory("user", message, false)

        val local = runCatching { actionRouter.handle(message) }.getOrNull()
        if (local?.handled == true) {
            if (local.message.isNotBlank()) {
                renderMessageCard("assistant", local.message)
                saveHistory("assistant", local.message, false)
                safeSpeak(local.message)
            }
            status.text = "Acción del teléfono"
            return
        }

        when (val kind = classifyVisualRequest(message)) {
            "news" -> openNewsFast()
            "weather" -> openWeatherForCurrentLocation(message)
            "home", "day" -> executeChat("$message. Devuelve cada elemento relevante en una línea separada, sin introducción ni conclusión.", kind)
            else -> executeChat(message, null)
        }
    }
'''
if old not in s:
    raise SystemExit('sendMessage block not found')
s = s.replace(old, new)

p.write_text(s)
print('Phone actions connected to MainActivity')
