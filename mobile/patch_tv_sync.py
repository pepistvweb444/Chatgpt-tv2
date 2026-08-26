from pathlib import Path

p = Path('mobile/src/main/java/com/jarvis/mobile/PhoneBridgeService.kt')
s = p.read_text()

marker = '        if (path.startsWith("/ping")) return 200 to JSONObject().put("ok", true).put("device", "jarvis-phone").toString()\n'
if marker not in s:
    raise SystemExit('ping marker not found')

unread_block = r'''        if (path.startsWith("/unread")) {
            return 200 to unreadForTv().toString()
        }
'''
if '/unread' not in s:
    s = s.replace(marker, marker + unread_block, 1)

agenda_block = r'''        if (path.startsWith("/agenda")) {
            return 200 to agendaForTv().toString()
        }
'''
if '/agenda' not in s:
    s = s.replace(marker, marker + agenda_block, 1)

helpers = r'''    private fun unreadForTv(): JSONObject {
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
        return JSONObject().put("ok", true).put("unreadCount", items.length()).put("items", items)
            .put("notificationAccess", prefs.getBoolean("notification_listener_connected", false))
    }

    private fun agendaForTv(): JSONObject {
        val now = System.currentTimeMillis()
        val end = now + 7L * 24L * 60L * 60L * 1000L
        val events = JSONArray()
        val reminders = JSONArray()
        val hasCalendar = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
        if (hasCalendar) {
            runCatching {
                val builder = android.provider.CalendarContract.Instances.CONTENT_URI.buildUpon()
                android.content.ContentUris.appendId(builder, now); android.content.ContentUris.appendId(builder, end)
                val projection = arrayOf(android.provider.CalendarContract.Instances.TITLE, android.provider.CalendarContract.Instances.BEGIN, android.provider.CalendarContract.Instances.END, android.provider.CalendarContract.Instances.EVENT_LOCATION, android.provider.CalendarContract.Instances.ALL_DAY, android.provider.CalendarContract.Instances.CALENDAR_DISPLAY_NAME)
                contentResolver.query(builder.build(), projection, null, null, android.provider.CalendarContract.Instances.BEGIN + " ASC")?.use { c ->
                    val ti=c.getColumnIndex(android.provider.CalendarContract.Instances.TITLE); val bi=c.getColumnIndex(android.provider.CalendarContract.Instances.BEGIN); val ei=c.getColumnIndex(android.provider.CalendarContract.Instances.END); val li=c.getColumnIndex(android.provider.CalendarContract.Instances.EVENT_LOCATION); val ai=c.getColumnIndex(android.provider.CalendarContract.Instances.ALL_DAY); val ci=c.getColumnIndex(android.provider.CalendarContract.Instances.CALENDAR_DISPLAY_NAME)
                    while (c.moveToNext() && events.length() < 50) {
                        val title=if(ti>=0)c.getString(ti).orEmpty() else "Evento"; if(title.isBlank()) continue
                        events.put(JSONObject().put("title",title).put("begin",if(bi>=0)c.getLong(bi) else 0L).put("end",if(ei>=0)c.getLong(ei) else 0L).put("location",if(li>=0)c.getString(li).orEmpty() else "").put("allDay",ai>=0&&c.getInt(ai)!=0).put("calendar",if(ci>=0)c.getString(ci).orEmpty() else ""))
                    }
                }
            }
        }
        val prefs=getSharedPreferences("jarvis_mobile",MODE_PRIVATE)
        val active=runCatching{JSONArray(prefs.getString("active_notification_feed","[]"))}.getOrElse{JSONArray()}
        for(i in active.length()-1 downTo 0){
            val n=active.optJSONObject(i)?:continue; val pkg=n.optString("package").lowercase(); val title=n.optString("title").trim(); val text=n.optString("text").trim(); val combined="$title $text".lowercase()
            val relevant=pkg.contains("calendar")||pkg.contains("tasks")||pkg.contains("todo")||pkg.contains("reminder")||combined.contains("recordatorio")||combined.contains("reminder")||combined.contains("cita")||combined.contains("evento")
            if(!relevant||(title.isBlank()&&text.isBlank())) continue
            reminders.put(JSONObject().put("title",title.ifBlank{"Recordatorio"}).put("text",text).put("time",n.optLong("time"))); if(reminders.length()>=20) break
        }
        return JSONObject().put("ok",true).put("calendarPermission",hasCalendar).put("events",events).put("reminders",reminders).put("eventCount",events.length()).put("reminderCount",reminders.length())
    }

'''

if 'private fun unreadForTv()' not in s or 'private fun agendaForTv()' not in s:
    anchors = [
        '    private fun permissionStatus(): JSONObject',
        '    private fun recentMessages(source: String): JSONObject',
        '    override fun onDestroy()'
    ]
    pos = -1
    for a in anchors:
        pos = s.find(a)
        if pos >= 0:
            break
    if pos < 0:
        raise SystemExit('helper insertion anchor not found')
    missing = ''
    if 'private fun unreadForTv()' not in s:
        missing += helpers.split('    private fun agendaForTv(): JSONObject',1)[0]
    if 'private fun agendaForTv()' not in s:
        missing += '    private fun agendaForTv(): JSONObject' + helpers.split('    private fun agendaForTv(): JSONObject',1)[1]
    s = s[:pos] + missing + s[pos:]

p.write_text(s)
print('TV sync unread + agenda endpoints applied to current PhoneBridgeService')
