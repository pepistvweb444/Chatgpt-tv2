from pathlib import Path

p = Path('mobile/src/main/java/com/jarvis/mobile/MainActivity.kt')
s = p.read_text()

# Send the selected provider with every chat request.
needle = 'val body = JSONObject().put("message", message).put("conversationId", conversationId).put("client", "jarvis-mobile").put("history", history()).put("selectedTools", selected)'
if needle in s:
    repl = needle + '.put("preferredProvider", prefs.getString("ai_provider", "auto"))'
    s = s.replace(needle, repl, 1)

# Record which provider actually answered without cluttering the conversation.
needle2 = 'val reply = json.optString("reply")'
if needle2 in s and 'json.optString("provider")' not in s:
    s = s.replace(needle2, 'val providerUsed = json.optString("provider")\n                ' + needle2, 1)
    # status updates vary between generated versions; insert a lightweight one after parsing reply.
    marker = 'if (reply.isBlank()) throw IllegalStateException('
    idx = s.find(marker)
    if idx >= 0:
        line_start = s.rfind('\n', 0, idx) + 1
        s = s[:line_start] + '                if (providerUsed.isNotBlank()) runOnUiThread { status.text = "Jarvis · IA: ${providerUsed.uppercase()}" }\n' + s[line_start:]

# Add a real provider picker and retain the existing voice picker.
start = s.find('    private fun showVoiceSettings() {')
if start < 0:
    raise SystemExit('showVoiceSettings not found')
end = s.find('\n    private fun ', start + 20)
if end < 0:
    raise SystemExit('showVoiceSettings end not found')
replacement = r'''    private fun showVoiceSettings() {
        val items = arrayOf("Motor IA", "Voz de Jarvis")
        AlertDialog.Builder(this).setTitle("Ajustes de Jarvis").setItems(items) { _, which ->
            if (which == 0) showAiProviderPicker() else showJarvisVoicePicker()
        }.setNegativeButton("Cerrar", null).show()
    }

    private fun showAiProviderPicker() {
        val ids = arrayOf("auto", "qwen", "gemini", "openai", "groq", "openrouter")
        val names = arrayOf("Automático · Qwen → Gemini → otros → OpenAI", "Qwen", "Gemini", "OpenAI", "Groq", "OpenRouter")
        val current = prefs.getString("ai_provider", "auto").orEmpty()
        val checked = ids.indexOf(current).let { if (it >= 0) it else 0 }
        AlertDialog.Builder(this)
            .setTitle("Motor IA")
            .setSingleChoiceItems(names, checked) { dialog, which ->
                prefs.edit().putString("ai_provider", ids[which]).apply()
                status.text = "Motor IA · ${names[which]}"
                Toast.makeText(this, "Motor IA: ${names[which]}", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun showJarvisVoicePicker() {
        val voices = arrayOf("coral", "alloy", "ash", "ballad", "echo", "fable", "nova", "onyx", "sage", "shimmer", "verse")
        AlertDialog.Builder(this).setTitle("Voz de Jarvis").setItems(voices) { _, i ->
            prefs.edit().putString("voice", voices[i]).apply()
            safeSpeak("Hola. Esta es mi voz de Jarvis.")
        }.show()
    }
'''
s = s[:start] + replacement + s[end:]

p.write_text(s)
print('AI provider picker and preferredProvider request applied')
