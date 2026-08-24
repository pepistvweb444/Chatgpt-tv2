from pathlib import Path

p = Path('mobile/src/main/java/com/jarvis/mobile/PhoneBridgeService.kt')
s = p.read_text()

marker = '        if (path.startsWith("/ping")) return 200 to JSONObject().put("ok", true).put("device", "jarvis-phone").toString()\n'
if marker not in s:
    raise SystemExit('ping marker not found')

block = r'''        if (path.startsWith("/unread")) {
            val prefs = getSharedPreferences("jarvis_mobile", MODE_PRIVATE)
            val expected = prefs.getString("remote_token", "").orEmpty()
            val supplied = headers["authorization"].orEmpty().removePrefix("Bearer ").trim()
            if (expected.isBlank() || supplied != expected) return 401 to JSONObject().put("error", "unauthorized").toString()
            if (!prefs.getBoolean("remote_control_enabled", false)) return 403 to JSONObject().put("error", "remote-disabled").toString()
            return 200 to unreadForTv().toString()
        }
'''
if '/unread' not in s:
    s = s.replace(marker, marker + block, 1)

anchor = '    private fun permissionStatus(): JSONObject = JSONObject()\n'
helper = r'''    private fun unreadForTv(): JSONObject {
        val prefs = getSharedPreferences("jarvis_mobile", MODE_PRIVATE)
        val items = JSONArray()
        val active = runCatching { JSONArray(prefs.getString("active_notification_feed", "[]")) }.getOrElse { JSONArray() }
        val seen = mutableSetOf<String>()
        fun sourceName(pkg: String): String = when {
            pkg.contains("whatsapp", true) -> "WhatsApp"
            pkg.contains("instagram", true) -> "Instagram"
            pkg.contains("tiktok", true) -> "TikTok"
            pkg.contains("facebook", true) || pkg.contains("orca", true) -> "Facebook Messenger"
            pkg.contains("telegram", true) -> "Telegram"
            pkg.contains("gmail", true) || pkg.contains("email", true) || pkg.contains("outlook", true) -> "Correo"
            pkg.contains("messag", true) -> "Mensajes / RCS"
            else -> ""
        }
        for (i in active.length() - 1 downTo 0) {
            val n = active.optJSONObject(i) ?: continue
            val pkg = n.optString("package")
            val source = sourceName(pkg)
            if (source.isBlank()) continue
            val who = n.optString("conversation").ifBlank { n.optString("title") }.ifBlank { source }
            val body = n.optString("text").trim()
            if (body.isBlank()) continue
            val key = "$source|$who|$body"
            if (!seen.add(key)) continue
            items.put(JSONObject().put("source", source).put("from", who).put("text", body.take(1000)).put("time", n.optLong("time")))
            if (items.length() >= 30) break
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) {
            runCatching {
                contentResolver.query(Uri.parse("content://sms/inbox"), arrayOf("address", "body", "date", "read"), "read=0", null, "date DESC")?.use { c ->
                    val ai = c.getColumnIndex("address"); val bi = c.getColumnIndex("body"); val di = c.getColumnIndex("date")
                    while (c.moveToNext() && items.length() < 40) {
                        val who = if (ai >= 0) c.getString(ai).orEmpty() else "SMS"
                        val body = if (bi >= 0) c.getString(bi).orEmpty() else ""
                        val key = "SMS|$who|$body"
                        if (body.isNotBlank() && seen.add(key)) items.put(JSONObject().put("source", "SMS").put("from", who).put("text", body.take(1000)).put("time", if (di >= 0) c.getLong(di) else 0L))
                    }
                }
            }
        }
        return JSONObject()
            .put("ok", true)
            .put("unreadCount", items.length())
            .put("items", items)
            .put("notificationAccess", prefs.getBoolean("notification_listener_connected", false))
    }

'''
if 'private fun unreadForTv()' not in s:
    if anchor not in s:
        raise SystemExit('permissionStatus anchor not found')
    s = s.replace(anchor, helper + anchor, 1)

p.write_text(s)
print('TV sync unread endpoint applied')
