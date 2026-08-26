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

# Find renderMessageCard by balanced parentheses. Its signature contains calls such as
# emptyList(), so a regex using [^)]* breaks on the default-value parentheses.
if 'key == lastRenderedAssistantKey' not in s:
    name = 'private fun renderMessageCard'
    start = s.find(name)
    if start < 0:
        # tolerate modifiers/spacing introduced by future patches
        m = re.search(r'private\s+fun\s+renderMessageCard\s*\(', s)
        if not m:
            raise SystemExit('renderMessageCard function not found')
        start = m.start()
    open_paren = s.find('(', start)
    if open_paren < 0:
        raise SystemExit('renderMessageCard opening parenthesis not found')
    depth = 0
    close_paren = -1
    for i in range(open_paren, len(s)):
        ch = s[i]
        if ch == '(':
            depth += 1
        elif ch == ')':
            depth -= 1
            if depth == 0:
                close_paren = i
                break
    if close_paren < 0:
        raise SystemExit('renderMessageCard closing parenthesis not found')
    brace = s.find('{', close_paren)
    if brace < 0:
        raise SystemExit('renderMessageCard body not found')
    guard='''\n        if (role == "assistant") {\n            val now = System.currentTimeMillis()\n            val key = text.trim().replace(Regex("\\\\s+"), " ")\n            if (key.isNotBlank() && key == lastRenderedAssistantKey && now - lastRenderedAssistantAt < 6000L) return\n            lastRenderedAssistantKey = key\n            lastRenderedAssistantAt = now\n        }'''
    s=s[:brace+1]+guard+s[brace+1:]

p.write_text(s)
print('Duplicate assistant response suppression applied')
