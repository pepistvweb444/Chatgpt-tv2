from pathlib import Path
import re

p = Path('mobile/src/main/java/com/jarvis/mobile/MainActivity.kt')
s = p.read_text()

pattern = re.compile(r'(?m)^(?P<indent>\s*)override\s+fun\s+onNewIntent\s*\(\s*intent\s*:\s*(?:android\.content\.)?Intent\s*\)\s*\{')
matches = list(pattern.finditer(s))

if len(matches) <= 1:
    print('onNewIntent already unique')
    raise SystemExit(0)

# Rename duplicate overrides after the first to private helper methods.
# Work backwards so offsets do not invalidate earlier match positions.
helper_names = []
for idx in range(len(matches) - 1, 0, -1):
    m = matches[idx]
    name = f'handleAdditionalIntent{idx+1}'
    helper_names.append(name)
    indent = m.group('indent') or '    '
    replacement = f'{indent}private fun {name}(intent: Intent) {{'
    s = s[:m.start()] + replacement + s[m.end():]
helper_names.reverse()

# Find the one remaining override and call helpers exactly once.
primary = pattern.search(s)
if not primary:
    raise SystemExit('primary onNewIntent not found after merge')
body_start = primary.end()
anchor = 'super.onNewIntent(intent)'
pos = s.find(anchor, body_start)
if pos < 0:
    calls = ''.join(f'\n        {name}(intent)' for name in helper_names)
    s = s[:body_start] + calls + s[body_start:]
else:
    insert_at = pos + len(anchor)
    calls = ''.join(f'\n        {name}(intent)' for name in helper_names)
    s = s[:insert_at] + calls + s[insert_at:]

# Remove super.onNewIntent from helper bodies only, using brace matching.
def block_bounds(text, header_pos):
    open_brace = text.find('{', header_pos)
    if open_brace < 0:
        return None
    depth = 0
    in_string = False
    escape = False
    for i in range(open_brace, len(text)):
        ch = text[i]
        if in_string:
            if escape:
                escape = False
            elif ch == '\\':
                escape = True
            elif ch == '"':
                in_string = False
            continue
        if ch == '"':
            in_string = True
            continue
        if ch == '{':
            depth += 1
        elif ch == '}':
            depth -= 1
            if depth == 0:
                return open_brace, i + 1
    return None

for name in helper_names:
    h = s.find(f'private fun {name}(intent: Intent)')
    if h < 0:
        continue
    bounds = block_bounds(s, h)
    if not bounds:
        continue
    a, b = bounds
    block = s[h:b]
    block = re.sub(r'(?m)^\s*super\.onNewIntent\(intent\)\s*\n?', '', block)
    s = s[:h] + block + s[b:]

# Hard assertion: compilation must see exactly one JVM override.
remaining = list(pattern.finditer(s))
if len(remaining) != 1:
    raise SystemExit(f'expected exactly one onNewIntent override, found {len(remaining)}')

p.write_text(s)
print(f'Merged {len(matches)} onNewIntent handlers into one override + {len(helper_names)} helper(s)')
