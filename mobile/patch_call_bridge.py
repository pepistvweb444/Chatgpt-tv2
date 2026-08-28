from pathlib import Path

p=Path('mobile/src/main/java/com/jarvis/mobile/PhoneBridgeService.kt')
s=p.read_text()

# Current bridge already supports header-aware authentication. Accept either
# the explicit default argument form or the older signature produced by patches.
header_aware = (
    'private fun handle(path: String, headers: Map<String,String> = emptyMap())' in s or
    'private fun handle(path: String, headers: Map<String,String>)' in s or
    'private fun handle(path: String, headers: Map<String, String>' in s
)
if not header_aware:
    raise SystemExit('PhoneBridgeService is not header-aware; patch_remote_control must run first')

# Add call endpoints only when an older bridge does not already contain them.
if '/incoming-call' not in s:
    marker='''        if (path.startsWith("/messages")) {'''
    endpoints=r'''        if (path.startsWith("/incoming-call")) {
            return 200 to JSONObject().put("ok", true).put("call", CallStateStore.current(this)).toString()
        }
        if (path.startsWith("/incoming-call-action") || path.startsWith("/call-action?")) {
            val action = URLDecoder.decode(path.substringAfter("action=", "ignore").substringBefore("&"), StandardCharsets.UTF_8.name()).lowercase()
            val (ok, message) = CallActionManager.perform(this, action)
            return (if (ok) 200 else 409) to JSONObject().put("ok", ok).put("message", message).put("call", CallStateStore.current(this)).toString()
        }
'''
    if marker not in s:
        raise SystemExit('bridge messages marker not found')
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

# Also force missed-call queries to use the real Android CallLog and request READ_CALL_LOG.
exec(Path('mobile/patch_real_call_log.py').read_text(), {'__name__':'__main__'})
print('Authenticated mobile-to-TV call bridge + real Android call log applied')
