from pathlib import Path
p=Path('mobile/src/main/java/com/jarvis/mobile/LocalActionRouter.kt')
s=p.read_text()

# Route 'new messages' before generic WhatsApp/recent-message handlers.
anchor='''            lower.contains("tienes acceso a mis mensajes") || lower.contains("tiene acceso a mis mensajes") || lower.contains("puedes leer mis mensajes") || lower.contains("puede leer mis mensajes") || lower.contains("acceso a los mensajes") || lower.contains("acceso a mis sms") -> return messageAccessStatus()'''
route=anchor+'''\n            (lower.contains("mensaje nuevo") || lower.contains("mensajes nuevos") || lower.contains("nuevos mensajes")) -> return readNewMessagesSinceLastCheck(30)'''
if 'readNewMessagesSinceLastCheck(30)' not in s:
    if anchor not in s: raise SystemExit('message route anchor not found')
    s=s.replace(anchor,route,1)

marker='''    private fun readNotificationMessages(filter: String, limit: Int): Result {'''
method=r'''    private fun readNewMessagesSinceLastCheck(limit: Int): Result {
        val prefs = activity.getSharedPreferences("jarvis_mobile", Activity.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val connectedAt = prefs.getLong("notification_listener_connected_at", 0L)
        val previous = prefs.getLong("jarvis_new_messages_last_check", 0L)
        // On the first explicit check, use at most the last 24 h of captured history,
        // but never before the notification listener was connected.
        val cutoff = if (previous > 0L) previous else maxOf(connectedAt, now - 24L * 60L * 60L * 1000L)
        val feed = notificationFeed()
        val rows = mutableListOf<Triple<Long,String,String>>()
        val seen = mutableSetOf<String>()
        for (i in 0 until feed.length()) {
            val n = feed.optJSONObject(i) ?: continue
            val time = n.optLong("time", 0L)
            if (time <= cutoff || time > now + 60000L) continue
            val pkg = n.optString("package")
            val isMessage = pkg.contains("whatsapp", true) || pkg.contains("telegram", true) ||
                pkg.contains("facebook.orca", true) || pkg.contains("messenger", true) ||
                pkg.contains("instagram", true) || pkg.contains("messag", true) || pkg.contains("sms", true)
            if (!isMessage) continue
            val who = n.optString("conversation").ifBlank { n.optString("title") }.ifBlank { pkg.substringAfterLast('.') }
            val body = n.optString("text").trim()
            if (body.isBlank()) continue
            val app = when {
                pkg.contains("whatsapp", true) -> "WhatsApp"
                pkg.contains("telegram", true) -> "Telegram"
                pkg.contains("facebook.orca", true) || pkg.contains("messenger", true) -> "Messenger"
                pkg.contains("instagram", true) -> "Instagram"
                else -> "Mensajes/RCS"
            }
            val key = "$app|$who|$body"
            if (seen.add(key)) rows += Triple(time, "$app · $who", body.take(900))
        }
        prefs.edit().putLong("jarvis_new_messages_last_check", now).apply()
        val sorted = rows.sortedByDescending { it.first }.take(limit)
        return if (sorted.isEmpty()) {
            Result(true, "__NEW_MESSAGES_EMPTY__|No han llegado mensajes nuevos desde la última revisión de Jarvis.")
        } else {
            Result(true, "__NEW_MESSAGES__|" + sorted.joinToString("\n") { (_, head, body) -> "$head|${body.replace("\n", " ")}" })
        }
    }

'''
if 'private fun readNewMessagesSinceLastCheck' not in s:
    if marker not in s: raise SystemExit('notification method marker not found')
    s=s.replace(marker,method+marker,1)
p.write_text(s)

# MainActivity: render new-message payloads as widgets in conversation order.
p=Path('mobile/src/main/java/com/jarvis/mobile/MainActivity.kt')
s=p.read_text()
needle='''            if (local.message.startsWith("__MESSAGES__|")) {'''
block=r'''            if (local.message.startsWith("__NEW_MESSAGES__|")) {
                beginWidgetGroup("Mensajes nuevos")
                val entries = local.message.substringAfter("__NEW_MESSAGES__|").lines().filter { it.isNotBlank() }
                entries.take(30).forEach { line ->
                    val p = line.split("|", limit = 2)
                    addTextWidget("day", p.getOrElse(0){"Mensaje nuevo"}, p.getOrElse(1){""})
                }
                speakReply("Tienes ${entries.size} mensaje${if(entries.size==1) "" else "s"} nuevo${if(entries.size==1) "" else "s"}.")
                status.text = "Jarvis listo"
            } else if (local.message.startsWith("__NEW_MESSAGES_EMPTY__|")) {
                beginWidgetGroup("Mensajes nuevos")
                addTextWidget("day", "Sin novedades", local.message.substringAfter("|"))
                status.text = "Jarvis listo"
            } else if (local.message.startsWith("__MESSAGES__|")) {'''
if '__NEW_MESSAGES__|' not in s:
    if needle not in s: raise SystemExit('MainActivity messages render anchor not found')
    s=s.replace(needle,block,1)
p.write_text(s)
print('New messages since last Jarvis check applied')
