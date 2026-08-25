from pathlib import Path

p = Path('mobile/src/main/java/com/jarvis/mobile/MainActivity.kt')
s = p.read_text()

field = '    private var lastLocation: Location? = null\n'
if 'private val phoneAgent by lazy' not in s:
    if field not in s:
        raise SystemExit('lastLocation field marker not found')
    s = s.replace(field, field + '    private val phoneAgent by lazy { PhoneAgentController(this) }\n', 1)

if 'phoneAgent.looksLikePhoneTask(message)' not in s:
    marker = '''        val local = runCatching { actionRouter.handle(message) }.getOrNull()'''
    idx_send = s.find('    private fun sendMessage() {')
    if idx_send == -1:
        raise SystemExit('sendMessage function not found')
    idx_marker = s.find(marker, idx_send)
    if idx_marker == -1:
        raise SystemExit('local action router marker not found')

    block = '''        if (phoneAgent.looksLikePhoneTask(message)) {
            val enabled = prefs.getBoolean("accessibility_connected", false)
            if (!enabled) {
                val warning = "Para controlar otras aplicaciones necesito que actives Jarvis en Accesibilidad. Abro la configuración para que puedas concederlo."
                renderMessageCard("assistant", warning)
                saveHistory("assistant", warning, false)
                runCatching { startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                return
            }
            status.text = "Jarvis está controlando el teléfono…"
            val started = "Control del teléfono iniciado · ${message.take(180)}"
            renderMessageCard("assistant", started)
            saveHistory("assistant", started, false)
            var lastProgress = ""
            phoneAgent.run(
                message,
                onUpdate = { t -> runOnUiThread {
                    status.text = t
                    if (t.isNotBlank() && t != lastProgress) {
                        lastProgress = t
                        renderMessageCard("assistant", t)
                        saveHistory("assistant", t, false)
                    }
                } },
                onDone = { result -> runOnUiThread {
                    status.text = "Jarvis listo"
                    val finalText = if (result.isBlank()) "He terminado el control del teléfono." else result
                    renderMessageCard("assistant", finalText)
                    saveHistory("assistant", finalText, false)
                    safeSpeak(finalText)
                } }
            )
            return
        }

'''
    s = s[:idx_marker] + block + s[idx_marker:]
else:
    old = '''            phoneAgent.run(
                message,
                onUpdate = { t -> runOnUiThread { status.text = t } },
                onDone = { result -> runOnUiThread {
                    status.text = "Jarvis listo"
                    renderMessageCard("assistant", result)
                    saveHistory("assistant", result, false)
                    safeSpeak(result)
                } }
            )'''
    new = '''            val started = "Control del teléfono iniciado · ${message.take(180)}"
            renderMessageCard("assistant", started)
            saveHistory("assistant", started, false)
            var lastProgress = ""
            phoneAgent.run(
                message,
                onUpdate = { t -> runOnUiThread {
                    status.text = t
                    if (t.isNotBlank() && t != lastProgress) {
                        lastProgress = t
                        renderMessageCard("assistant", t)
                        saveHistory("assistant", t, false)
                    }
                } },
                onDone = { result -> runOnUiThread {
                    status.text = "Jarvis listo"
                    val finalText = if (result.isBlank()) "He terminado el control del teléfono." else result
                    renderMessageCard("assistant", finalText)
                    saveHistory("assistant", finalText, false)
                    safeSpeak(finalText)
                } }
            )'''
    if old in s:
        s = s.replace(old, new, 1)

p.write_text(s)
print('Phone agent progress and final result are persisted in conversation')
