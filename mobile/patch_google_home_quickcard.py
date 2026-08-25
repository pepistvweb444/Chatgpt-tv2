from pathlib import Path

p = Path('mobile/src/main/java/com/jarvis/mobile/MainActivity.kt')
s = p.read_text()

start = s.find('    private fun refreshDomoticsQuickCard()')
if start < 0:
    raise SystemExit('refreshDomoticsQuickCard not found')
end = s.find('    private fun ', start + 20)
if end < 0:
    end = len(s)
block = s[start:end]
needle = '            runOnUiThread {\n'
insert = r'''            runCatching {
                val a = JSONArray(prefs.getString("google_home_lights_json", "[]"))
                for (i in 0 until a.length()) {
                    val d = a.optJSONObject(i) ?: continue
                    val on = if (d.has("on") && !d.isNull("on")) d.optBoolean("on") else false
                    val state = if (on) "●" else "○"
                    val room = d.optString("room")
                    val name = d.optString("name").ifBlank { "Luz" }
                    lines += "$state 💡 ${if (room.isBlank()) name else room}"
                }
            }
'''
if needle in block and 'google_home_lights_json' not in block:
    block = block.replace(needle, insert + needle, 1)
    s = s[:start] + block + s[end:]

# Re-read Google Home state whenever MainActivity resumes after the auth/control screen.
if 'override fun onResume()' not in s:
    marker = '    private fun dp(v: Int) = '
    resume = '''    override fun onResume() {\n        super.onResume()\n        runCatching { refreshDomoticsQuickCard() }\n    }\n\n'''
    s = s.replace(marker, resume + marker, 1)

p.write_text(s)
print('Google Home lights added to Domotics quick card')
