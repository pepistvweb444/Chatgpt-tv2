from pathlib import Path

p = Path('mobile/src/main/java/com/jarvis/mobile/MainActivity.kt')
s = p.read_text()

# patch_weather_widget.py is a Python generator. A \n inside its triple-quoted
# replacement can become a literal newline inside a Kotlin quoted string.
# Normalize the forecast TextView string back to escaped Kotlin newlines.
broken = '''                text = "$label
${weatherIcon(dCode)}
${if (dMax.isNaN()) "—" else dMax.toInt()}° / ${if (dMin.isNaN()) "—" else dMin.toInt()}°"
'''
fixed = '''                text = "$label\\n${weatherIcon(dCode)}\\n${if (dMax.isNaN()) "—" else dMax.toInt()}° / ${if (dMin.isNaN()) "—" else dMin.toInt()}°"
'''
if broken in s:
    s = s.replace(broken, fixed)

# Fail early with a readable message if a known broken multiline Kotlin
# interpolation survives the normalization.
if 'text = "$label\n${weatherIcon(dCode)}' in s:
    raise SystemExit('Generated Kotlin still contains a multiline forecast string')

p.write_text(s)
print('Generated Kotlin strings normalized')
