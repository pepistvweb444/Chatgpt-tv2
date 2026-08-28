from pathlib import Path
import re
p=Path('mobile/src/main/java/com/jarvis/mobile/MainActivity.kt')
s=p.read_text()
# Generated domotics snippets from older patches may still reference legacy
# Sensibo/Tado property names. Normalize them to the current data classes.
s=s.replace('.temperature', '.current')
s=s.replace('.targetTemp', '.target')
# Nullable power state must be compared explicitly.
s=re.sub(r'if\s*\(\s*([A-Za-z_][A-Za-z0-9_]*)\.on\s*\)', r'if (\1.on == true)', s)
s=re.sub(r'if\s*\(\s*!\s*([A-Za-z_][A-Za-z0-9_]*)\.on\s*\)', r'if (\1.on != true)', s)
p.write_text(s)
print('Final domotics compile normalization applied')
