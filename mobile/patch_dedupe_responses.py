from pathlib import Path
import re

p=Path('mobile/src/main/java/com/jarvis/mobile/MainActivity.kt')
s=p.read_text()

# This is a best-effort build-time guard. It must never abort the APK build
# when another patch has renamed or reshaped the renderer.
if 'lastRenderedAssistantKey' not in s:
    m=re.search(r'class\s+MainActivity[^\{]*\{', s)
    if m:
        pos=m.end()
        s=s[:pos]+'\n    private var lastRenderedAssistantKey: String = ""\n    private var lastRenderedAssistantAt: Long = 0L\n'+s[pos:]
    else:
        print('MainActivity class anchor not found; dedupe patch skipped safely')
        p.write_text(s)
        raise SystemExit(0)

if 'key == lastRenderedAssistantKey' not in s:
    # Accept private/public/override renderer declarations and arbitrary signatures.
    m=re.search(r'(?:private\s+|public\s+|protected\s+|override\s+)*fun\s+renderMessageCard\s*\(', s)
    if not m:
        print('renderMessageCard not present after generated patches; dedupe guard skipped safely')
        p.write_text(s)
        raise SystemExit(0)
    open_paren=s.find('(',m.start())
    depth=0; close_paren=-1
    for i in range(open_paren,len(s)):
        if s[i]=='(': depth+=1
        elif s[i]==')':
            depth-=1
            if depth==0:
                close_paren=i; break
    if close_paren < 0:
        print('renderMessageCard signature incomplete; dedupe guard skipped safely')
        p.write_text(s)
        raise SystemExit(0)
    brace=s.find('{',close_paren)
    if brace < 0:
        print('renderMessageCard body not found; dedupe guard skipped safely')
        p.write_text(s)
        raise SystemExit(0)
    # Only inject when conventional role/text parameters are present.
    signature=s[m.start():close_paren+1]
    if not re.search(r'\brole\s*:', signature) or not re.search(r'\btext\s*:', signature):
        print('renderMessageCard has non-standard parameters; dedupe guard skipped safely')
        p.write_text(s)
        raise SystemExit(0)
    guard='''\n        if (role == "assistant") {\n            val now = System.currentTimeMillis()\n            val key = text.trim().replace(Regex("\\\\s+"), " ")\n            if (key.isNotBlank() && key == lastRenderedAssistantKey && now - lastRenderedAssistantAt < 6000L) return\n            lastRenderedAssistantKey = key\n            lastRenderedAssistantAt = now\n        }'''
    s=s[:brace+1]+guard+s[brace+1:]

p.write_text(s)
print('Duplicate assistant response suppression applied safely')
