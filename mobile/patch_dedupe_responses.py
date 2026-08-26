from pathlib import Path
import re

p=Path('mobile/src/main/java/com/jarvis/mobile/MainActivity.kt')
s=p.read_text()

# State for short-window duplicate suppression.
if 'lastRenderedAssistantKey' not in s:
    anchors = [
        '    private var lastLocation: Location? = null',
        '    private lateinit var conversationHost:',
        'class MainActivity :'
    ]
    inserted=False
    for anchor in anchors:
        if anchor in s:
            if anchor.startswith('class MainActivity'):
                m=re.search(r'class MainActivity[^\{]*\{', s)
                if m:
                    pos=m.end()
                    s=s[:pos]+'\n    private var lastRenderedAssistantKey: String = ""\n    private var lastRenderedAssistantAt: Long = 0L\n'+s[pos:]
                    inserted=True
                    break
            else:
                idx=s.index(anchor)
                line_end=s.find('\n',idx)
                s=s[:line_end+1]+'    private var lastRenderedAssistantKey: String = ""\n    private var lastRenderedAssistantAt: Long = 0L\n'+s[line_end+1:]
                inserted=True
                break
    if not inserted:
        raise SystemExit('dedupe state insertion point not found')

# Find renderMessageCard regardless of parameter formatting/defaults added by other patches.
if 'val key = text.trim().replace(Regex("\\\\s+")' not in s:
    m=re.search(r'(\s*private\s+fun\s+renderMessageCard\s*\([^)]*\)\s*\{)', s)
    if not m:
        raise SystemExit('renderMessageCard function not found')
    guard='''\n        if (role == "assistant") {\n            val now = System.currentTimeMillis()\n            val key = text.trim().replace(Regex("\\\\s+"), " ")\n            if (key.isNotBlank() && key == lastRenderedAssistantKey && now - lastRenderedAssistantAt < 6000L) return\n            lastRenderedAssistantKey = key\n            lastRenderedAssistantAt = now\n        }'''
    s=s[:m.end()]+guard+s[m.end():]

p.write_text(s)
print('Duplicate assistant response suppression applied')
