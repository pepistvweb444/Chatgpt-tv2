from pathlib import Path
p=Path('mobile/src/main/java/com/jarvis/mobile/MainActivity.kt')
s=p.read_text()

# State for short-window duplicate suppression.
anchor='''    private var lastLocation: Location? = null'''
insert=anchor+'''\n    private var lastRenderedAssistantKey: String = ""\n    private var lastRenderedAssistantAt: Long = 0L'''
if 'lastRenderedAssistantKey' not in s:
    if anchor not in s: raise SystemExit('state anchor not found')
    s=s.replace(anchor,insert,1)

sig='''    private fun renderMessageCard(role: String, text: String, images: List<String> = emptyList(), videos: List<String> = emptyList()) {\n        welcomePanel.visibility = View.GONE'''
replacement='''    private fun renderMessageCard(role: String, text: String, images: List<String> = emptyList(), videos: List<String> = emptyList()) {\n        if (role == "assistant") {\n            val now = System.currentTimeMillis()\n            val key = text.trim().replace(Regex("\\\\s+"), " ") + "|" + images.joinToString(",") + "|" + videos.joinToString(",")\n            if (key.isNotBlank() && key == lastRenderedAssistantKey && now - lastRenderedAssistantAt < 6000L) return\n            lastRenderedAssistantKey = key\n            lastRenderedAssistantAt = now\n        }\n        welcomePanel.visibility = View.GONE'''
if sig in s:
    s=s.replace(sig,replacement,1)
elif 'if (role == "assistant") {' not in s:
    raise SystemExit('renderMessageCard signature anchor not found')

p.write_text(s)
print('Duplicate assistant response suppression applied')
