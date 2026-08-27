from pathlib import Path
p=Path('mobile/src/main/java/com/jarvis/mobile/PhoneBridgeService.kt')
s=p.read_text()
start=s.find('    private fun incomingCallCard(): JSONObject {')
end=s.find('    private fun permissionStatus(): JSONObject', start)
if start < 0 or end < 0:
    raise SystemExit('incoming call bridge block not found')
new=r'''    private fun incomingCallCard(): JSONObject {
        val call = CallStateStore.current(this)
        return JSONObject()
            .put("ok", true)
            .put("call", call)
            .put("kind", call.optString("source").ifBlank { "none" })
            .put("active", call.optBoolean("active", false))
            .put("updatedAt", call.optLong("updatedAt", getSharedPreferences("jarvis_mobile", MODE_PRIVATE).getLong("current_call_updated_at", 0L)))
    }

    private fun controlIncomingCall(action: String): Pair<Int,String> {
        val normalized = when (action.lowercase()) {
            "accept" -> "answer"
            "decline" -> "reject"
            "leave", "ring" -> "ignore"
            else -> action.lowercase()
        }
        val (ok, message) = CallActionManager.perform(this, normalized)
        val code = if (ok) 200 else 409
        return code to JSONObject()
            .put("ok", ok)
            .put("status", normalized)
            .put("message", message)
            .put("call", CallStateStore.current(this))
            .toString()
    }

'''
s=s[:start]+new+s[end:]
p.write_text(s)
print('Unified current-call bridge applied')
