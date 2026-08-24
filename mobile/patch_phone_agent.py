from pathlib import Path
p=Path('mobile/src/main/java/com/jarvis/mobile/MainActivity.kt')
s=p.read_text()
field='    private var lastLocation: Location? = null\n'
if 'private val phoneAgent by lazy' not in s:
    s=s.replace(field, field+'    private val phoneAgent by lazy { PhoneAgentController(this) }\n')
needle='''        renderMessageCard("user", message)\n        saveHistory("user", message, false)\n        when (val kind = classifyVisualRequest(message)) {'''
repl='''        renderMessageCard("user", message)\n        saveHistory("user", message, false)\n        if (phoneAgent.looksLikePhoneTask(message)) {\n            val enabled = prefs.getBoolean("accessibility_connected", false)\n            if (!enabled) {\n                renderMessageCard("assistant", "Para controlar otras aplicaciones necesito que actives Jarvis en Accesibilidad. Abro la configuración para que puedas concederlo.")\n                runCatching { startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) }\n                return\n            }\n            status.text = "Jarvis está controlando el teléfono…"\n            phoneAgent.run(message,\n                onUpdate = { t -> runOnUiThread { status.text = t } },\n                onDone = { result ->\n                    status.text = "Jarvis listo"\n                    renderMessageCard("assistant", result)\n                    saveHistory("assistant", result, false)\n                    safeSpeak(result)\n                })\n            return\n        }\n        when (val kind = classifyVisualRequest(message)) {'''
if needle not in s:
    raise SystemExit('sendMessage marker not found')
s=s.replace(needle,repl)
p.write_text(s)
print('Phone agent wired into MainActivity')
