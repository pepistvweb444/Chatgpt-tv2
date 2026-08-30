from pathlib import Path
import re

p = Path('mobile/src/main/java/com/jarvis/mobile/JarvisNotificationListener.kt')
s = p.read_text()

# Python generator patches can leave JavaScript/Python-style single regex
# escapes inside Kotlin quoted strings (e.g. Regex("\s+")), which Kotlin
# rejects as an unsupported string escape. Normalize only Regex("...")
# literals and leave already escaped sequences untouched.
pattern = re.compile(r'Regex\("((?:\\.|[^"\\])*)"\)')

def fix(m):
    body = m.group(1)
    body = re.sub(r'(?<!\\)\\(?=[sSdDwWbBpP+().?*{}\[\]|^-])', r'\\\\', body)
    return 'Regex("' + body + '")'

s2 = pattern.sub(fix, s)

# Fail early if common unsupported single escapes survived in Regex strings.
for m in pattern.finditer(s2):
    body = m.group(1)
    if re.search(r'(?<!\\)\\(?=[sSdDwWpP+().?*{}\[\]|^-])', body):
        raise SystemExit('Unsupported Kotlin regex escape survived: ' + body)

p.write_text(s2)
print('JarvisNotificationListener Kotlin regex escapes normalized')
