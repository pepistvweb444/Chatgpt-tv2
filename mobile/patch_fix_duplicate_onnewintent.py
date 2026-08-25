from pathlib import Path

p = Path('mobile/src/main/java/com/jarvis/mobile/MainActivity.kt')
s = p.read_text()
needle = 'override fun onNewIntent(intent: Intent)'
positions = []
start = 0
while True:
    i = s.find(needle, start)
    if i < 0:
        break
    positions.append(i)
    start = i + len(needle)

if len(positions) <= 1:
    print('onNewIntent already unique')
    raise SystemExit(0)

# Rename every duplicate after the first into a private helper. This preserves the
# functionality added by independent patches while leaving only one Android override.
offset = 0
helper_names = []
for n, original_pos in enumerate(positions[1:], start=2):
    pos = original_pos + offset
    line_start = s.rfind('\n', 0, pos) + 1
    prefix = s[line_start:pos]
    name = f'handleAdditionalIntent{n}'
    helper_names.append(name)
    old_header = prefix + needle
    new_header = '    private fun ' + name + '(intent: Intent)'
    s = s[:line_start] + new_header + s[pos + len(needle):]
    offset += len(new_header) - len(old_header)

# Wire every helper into the primary override. Put the calls immediately after
# super.onNewIntent(intent), before any patch-specific handling.
first = s.find(needle)
body_open = s.find('{', first)
insert_anchor = 'super.onNewIntent(intent)'
anchor_pos = s.find(insert_anchor, body_open)
if anchor_pos >= 0:
    insert_at = anchor_pos + len(insert_anchor)
    calls = ''.join(f'\n        {name}(intent)' for name in helper_names)
    s = s[:insert_at] + calls + s[insert_at:]
else:
    calls = ''.join(f'\n        {name}(intent)' for name in helper_names)
    s = s[:body_open + 1] + calls + s[body_open + 1:]

# Helpers must not call AppCompatActivity.onNewIntent a second time. Remove that
# statement only from helper method bodies.
for name in helper_names:
    h = s.find(f'private fun {name}(intent: Intent)')
    if h < 0:
        continue
    nxt = s.find('\n    private fun ', h + 1)
    if nxt < 0:
        nxt = s.find('\n    companion object', h + 1)
    if nxt < 0:
        nxt = len(s)
    block = s[h:nxt].replace('        super.onNewIntent(intent)\n', '')
    s = s[:h] + block + s[nxt:]

p.write_text(s)
print(f'Merged {len(positions)} onNewIntent handlers into one override + {len(helper_names)} helper(s)')
