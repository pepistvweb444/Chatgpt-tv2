from pathlib import Path
import re

p = Path('mobile/src/main/java/com/jarvis/mobile/MainActivity.kt')
s = p.read_text()

# Match every Android onNewIntent override variant that compiles to the same JVM
# signature. Capture nullability so generated helper calls remain type-safe.
pattern = re.compile(
    r'(?m)^(?P<indent>\s*)override\s+fun\s+onNewIntent\s*\(\s*intent\s*:\s*(?:android\.content\.)?Intent(?P<nullable>\?)?\s*\)\s*\{'
)
generic = re.compile(r'(?m)^\s*override\s+fun\s+onNewIntent\s*\(')
matches = list(pattern.finditer(s))

if len(matches) == 0:
    raw_count = len(generic.findall(s))
    if raw_count:
        raise SystemExit(f'onNewIntent override(s) found but signature parser matched none: {raw_count}')
    print('No onNewIntent override present')
    raise SystemExit(0)

if len(matches) == 1:
    raw_count = len(generic.findall(s))
    if raw_count != 1:
        raise SystemExit(f'Expected one onNewIntent override, raw source contains {raw_count}')
    print('onNewIntent already unique')
    raise SystemExit(0)

primary_nullable = bool(matches[0].group('nullable'))
helper_meta = []

# Rename duplicate overrides after the first into helpers, preserving each
# original parameter's nullability. Work backwards so offsets remain stable.
for idx in range(len(matches) - 1, 0, -1):
    m = matches[idx]
    name = f'handleAdditionalIntent{idx+1}'
    nullable = bool(m.group('nullable'))
    helper_meta.append((name, nullable))
    indent = m.group('indent') or '    '
    type_text = 'Intent?' if nullable else 'Intent'
    replacement = f'{indent}private fun {name}(intent: {type_text}) {{'
    s = s[:m.start()] + replacement + s[m.end():]
helper_meta.reverse()

# Find the single remaining override and call each helper type-safely.
primary = pattern.search(s)
if not primary:
    raise SystemExit('primary onNewIntent not found after merge')
body_start = primary.end()
anchor = 'super.onNewIntent(intent)'
pos = s.find(anchor, body_start)

call_lines = []
for name, helper_nullable in helper_meta:
    if primary_nullable and not helper_nullable:
        call_lines.append(f'\n        intent?.let {{ {name}(it) }}')
    else:
        call_lines.append(f'\n        {name}(intent)')
calls = ''.join(call_lines)

if pos < 0:
    # Only insert a super call when it is type-compatible with the primary
    # override as written by the Android source currently in the project.
    s = s[:body_start] + '\n        super.onNewIntent(intent)' + calls + s[body_start:]
else:
    insert_at = pos + len(anchor)
    s = s[:insert_at] + calls + s[insert_at:]

# Helpers must not invoke the superclass callback again.
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

for name, nullable in helper_meta:
    type_text = 'Intent?' if nullable else 'Intent'
    h = s.find(f'private fun {name}(intent: {type_text})')
    if h < 0:
        continue
    bounds = block_bounds(s, h)
    if not bounds:
        raise SystemExit(f'Could not find body for {name}')
    _, b = bounds
    block = s[h:b]
    block = re.sub(r'(?m)^\s*super\.onNewIntent\(intent\)\s*\n?', '', block)
    s = s[:h] + block + s[b:]

# Final source-level guard independent of parameter nullability/qualification.
raw_remaining = len(generic.findall(s))
if raw_remaining != 1:
    raise SystemExit(f'expected exactly one onNewIntent override after merge, found {raw_remaining}')

p.write_text(s)
print(f'Merged {len(matches)} onNewIntent handlers into one override + {len(helper_meta)} helper(s); primary nullable={primary_nullable}')
