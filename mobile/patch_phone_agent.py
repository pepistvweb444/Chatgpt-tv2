from pathlib import Path

p = Path('mobile/src/main/java/com/jarvis/mobile/MainActivity.kt')
s = p.read_text()

field = '    private var lastLocation: Location? = null\n'
if 'private val phoneAgent by lazy' not in s:
    if field not in s:
        raise SystemExit('lastLocation field marker not found')
    s = s.replace(field, field + '    private val phoneAgent by lazy { PhoneAgentController(this) }\n', 1)

# patch_phone_actions.py runs before this script and adds LocalActionRouter.
# Multi-step tasks MUST run before the simple local router, otherwise a phrase like
# "abre Glovo y haz un pedido" is incorrectly consumed by the generic "abre ..." rule.
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
                renderMessageCard("assistant", "Para controlar otras aplicaciones necesito que actives Jarvis en Accesibilidad. Abro la configuración para que puedas concederlo.")
                runCatching { startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                return
            }
            status.text = "Jarvis está controlando el teléfono…"
            phoneAgent.run(
                message,
                onUpdate = { t -> runOnUiThread { status.text = t } },
                onDone = { result -> runOnUiThread {
                    status.text = "Jarvis listo"
                    renderMessageCard("assistant", result)
                    saveHistory("assistant", result, false)
                    safeSpeak(result)
                } }
            )
            return
        }

'''
    s = s[:idx_marker] + block + s[idx_marker:]

p.write_text(s)
print('Phone agent wired before LocalActionRouter')
