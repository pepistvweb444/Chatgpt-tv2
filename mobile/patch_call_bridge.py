from pathlib import Path

p=Path('mobile/src/main/java/com/jarvis/mobile/PhoneBridgeService.kt')
s=p.read_text()

# This patch is intentionally run after patch_remote_control.py, which upgrades handle() to receive headers.
if 'private fun handle(path: String, headers: Map<String,String>' not in s:
    raise SystemExit('remote-control header-aware bridge must be applied first')

marker='''        if (path.startsWith("/messages")) {'''
endpoints=r'''        if (path.startsWith("/incoming-call")) {
            val prefs = getSharedPreferences("jarvis_mobile", MODE_PRIVATE)
            val expected = prefs.getString("remote_token", "").orEmpty()
            val bearer = headers["authorization"].orEmpty().removePrefix("Bearer ").trim()
            if (expected.isBlank() || bearer != expected) return 401 to JSONObject().put("error", "unauthorized").toString()
            if (!prefs.getBoolean("remote_control_enabled", false)) return 403 to JSONObject().put("error", "remote-disabled").toString()
            return 200 to CallStateStore.current(this).toString()
        }
        if (path.startsWith("/call-action?")) {
            val prefs = getSharedPreferences("jarvis_mobile", MODE_PRIVATE)
            val expected = prefs.getString("remote_token", "").orEmpty()
            val bearer = headers["authorization"].orEmpty().removePrefix("Bearer ").trim()
            if (expected.isBlank() || bearer != expected) return 401 to JSONObject().put("error", "unauthorized").toString()
            if (!prefs.getBoolean("remote_control_enabled", false)) return 403 to JSONObject().put("error", "remote-disabled").toString()
            val raw = path.substringAfter("action=", "").substringBefore("&")
            val action = URLDecoder.decode(raw, StandardCharsets.UTF_8.name()).trim()
            val result = CallActionManager.perform(this, action)
            return (if (result.first) 200 else 409) to JSONObject().put("ok", result.first).put("message", result.second).put("call", CallStateStore.current(this)).toString()
        }
'''
if '/incoming-call' not in s:
    if marker not in s: raise SystemExit('bridge messages marker not found')
    s=s.replace(marker,endpoints+marker,1)
p.write_text(s)

# Manifest requirements for full-screen incoming-call presentation and explicit action receiver.
p=Path('mobile/src/main/AndroidManifest.xml')
s=p.read_text()
if 'android.permission.USE_FULL_SCREEN_INTENT' not in s:
    s=s.replace('    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />', '    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />\n    <uses-permission android:name="android.permission.USE_FULL_SCREEN_INTENT" />')
if 'android.permission.WAKE_LOCK' not in s:
    s=s.replace('    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />', '    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />\n    <uses-permission android:name="android.permission.WAKE_LOCK" />')
if '.CallActionReceiver' not in s:
    s=s.replace('''        <service android:name=".MobileSpeechService"''', '''        <receiver android:name=".CallActionReceiver" android:exported="false" />\n\n        <service android:name=".MobileSpeechService"''')
p.write_text(s)
print('Authenticated mobile-to-TV call bridge applied')
