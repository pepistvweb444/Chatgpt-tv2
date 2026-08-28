from pathlib import Path
import re

p=Path('mobile/src/main/java/com/jarvis/mobile/MainActivity.kt')
s=p.read_text()

# Best-effort build-time guard. This patch must NEVER abort an APK build merely
# because another generated patch renamed/reshaped the message renderer.
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
    # Locate any renderMessageCard declaration. If absent, skip: other renderers
    # (widgets/streaming) may be active and dedupe must not break the build.
    matches=list(re.finditer(r'fun\s+renderMessageCard\s*\(', s))
    if not matches:
        print('renderMessageCard not present after generated patches; dedupe guard skipped safely')
        p.write_text(s)
        raise SystemExit(0)
    m=matches[0]
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
    signature=s[m.start():close_paren+1]
    # Extract likely role/text parameter names rather than assuming formatting.
    role_match=re.search(r'\b(role|sender|author)\s*:\s*String', signature)
    text_match=re.search(r'\b(text|message|content)\s*:\s*String', signature)
    if not role_match or not text_match:
        print('renderMessageCard has non-standard parameters; dedupe guard skipped safely')
        p.write_text(s)
        raise SystemExit(0)
    role=role_match.group(1); text=text_match.group(1)
    guard=f'''\n        if ({role} == "assistant") {{\n            val now = System.currentTimeMillis()\n            val key = {text}.trim().replace(Regex("\\\\s+"), " ")\n            if (key.isNotBlank() && key == lastRenderedAssistantKey && now - lastRenderedAssistantAt < 6000L) return\n            lastRenderedAssistantKey = key\n            lastRenderedAssistantAt = now\n        }}'''
    s=s[:brace+1]+guard+s[brace+1:]

p.write_text(s)
print('Duplicate assistant response suppression applied safely')
