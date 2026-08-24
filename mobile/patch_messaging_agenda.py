from pathlib import Path

# --- LocalActionRouter: broad messaging + calendar/agenda routing ---
p = Path('mobile/src/main/java/com/jarvis/mobile/LocalActionRouter.kt')
s = p.read_text()

if 'import android.provider.CalendarContract' not in s:
    s = s.replace('import android.provider.ContactsContract\n', 'import android.provider.ContactsContract\nimport android.provider.CalendarContract\n')

when_anchor = '        when {\n'
route = '''        when {\n            isAgendaQuery(lower) -> return readAgenda(7)\n            isGeneralMessageQuery(lower) -> return readUnifiedMessages(18)\n            lower.contains("instagram") && isMessageReadIntent(lower) -> return readNotificationMessages("instagram", 12)\n            lower.contains("tiktok") && isMessageReadIntent(lower) -> return readNotificationMessages("tiktok", 12)\n            lower.contains("telegram") && isMessageReadIntent(lower) -> return readNotificationMessages("telegram", 12)\n            lower.contains("messenger") && isMessageReadIntent(lower) -> return readNotificationMessages("messenger", 12)\n'''
if 'isAgendaQuery(lower)' not in s:
    s = s.replace(when_anchor, route, 1)

marker = '    private fun messageAccessStatus(): Result {'
if marker not in s:
    raise SystemExit('messageAccessStatus marker not found')

methods = r'''    private fun isMessageReadIntent(lower: String): Boolean =
        lower.contains("mensaje") || lower.contains("mensajes") || lower.contains("lee") ||
        lower.contains("léeme") || lower.contains("leeme") || lower.contains("escrito") ||
        lower.contains("recibido") || lower.contains("notificacion") || lower.contains("notificación")

    private fun isGeneralMessageQuery(lower: String): Boolean {
        val mentions = lower.contains("mensaje") || lower.contains("mensajes") || lower.contains("sms") || lower.contains("rcs")
        val asks = lower.contains("qué") || lower.startsWith("que ") || lower.contains(" tengo") ||
            lower.contains("cuáles") || lower.contains("cuales") || lower.contains("últimos") ||
            lower.contains("ultimos") || lower.contains("lee") || lower.contains("léeme") ||
            lower.contains("leeme") || lower.contains("recibido")
        val appSpecific = listOf("whatsapp", "instagram", "tiktok", "telegram", "messenger").any { lower.contains(it) }
        return mentions && asks && !appSpecific && !lower.contains("acceso a") && !lower.contains("puedes leer") && !lower.contains("tienes acceso")
    }

    private fun isAgendaQuery(lower: String): Boolean {
        val subject = lower.contains("cita") || lower.contains("citas") || lower.contains("agenda") ||
            lower.contains("calendario") || lower.contains("recordatorio") || lower.contains("recordatorios") ||
            lower.contains("evento") || lower.contains("eventos") || lower.contains("reunión") || lower.contains("reunion") ||
            lower.contains("tarea") || lower.contains("tareas") || lower.contains("pendiente") || lower.contains("pendientes")
        val asks = lower.contains("qué") || lower.startsWith("que ") || lower.contains(" tengo") ||
            lower.contains("hoy") || lower.contains("mañana") || lower.contains("proxim") || lower.contains("dime") ||
            lower.contains("muéstr") || lower.contains("muestr") || lower.contains("cuáles") || lower.contains("cuales")
        return subject && asks
    }

    private fun appLabel(pkg: String): String = when {
        pkg.contains("whatsapp", true) -> "WhatsApp"
        pkg.contains("instagram", true) -> "Instagram"
        pkg.contains("tiktok", true) -> "TikTok"
        pkg.contains("telegram", true) -> "Telegram"
        pkg.contains("facebook.orca", true) || pkg.contains("messenger", true) -> "Messenger"
        pkg.contains("messag", true) || pkg.contains("sms", true) -> "RCS/Mensajes"
        else -> pkg.substringAfterLast('.').ifBlank { "App" }
    }

    private fun isMessagingPackage(pkg: String): Boolean =
        pkg.contains("whatsapp", true) || pkg.contains("instagram", true) || pkg.contains("tiktok", true) ||
        pkg.contains("telegram", true) || pkg.contains("facebook.orca", true) || pkg.contains("messenger", true) ||
        pkg.contains("messag", true) || pkg.contains("sms", true)

    private fun readUnifiedMessages(limit: Int): Result {
        val out = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) {
            runCatching {
                activity.contentResolver.query(Uri.parse("content://sms/inbox"), arrayOf("address", "body", "date"), null, null, "date DESC")?.use { c ->
                    val ai = c.getColumnIndex("address"); val bi = c.getColumnIndex("body")
                    while (c.moveToNext() && out.size < 8) {
                        val address = if (ai >= 0) c.getString(ai).orEmpty() else ""
                        val body = if (bi >= 0) c.getString(bi).orEmpty() else ""
                        if (body.isBlank()) continue
                        val who = contactNameForNumber(address) ?: address.ifBlank { "Desconocido" }
                        val key = "SMS|$who|$body"
                        if (seen.add(key)) out += "SMS|$who|${body.replace("\n", " ").take(700)}"
                    }
                }
            }
        } else {
            ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS), 72)
        }

        val feed = notificationFeed()
        for (i in feed.length() - 1 downTo 0) {
            if (out.size >= limit) break
            val n = feed.optJSONObject(i) ?: continue
            val pkg = n.optString("package")
            if (!isMessagingPackage(pkg)) continue
            val body = n.optString("text").trim()
            if (body.isBlank()) continue
            val who = n.optString("conversation").ifBlank { n.optString("title") }.ifBlank { appLabel(pkg) }
            val app = appLabel(pkg)
            val key = "$app|$who|$body"
            if (seen.add(key)) out += "$app|$who|${body.replace("\n", " ").take(700)}"
        }

        if (out.isNotEmpty()) return Result(true, "__MESSAGES_WIDGET__\n" + out.joinToString("\n"))
        val listener = activity.getSharedPreferences("jarvis_mobile", Activity.MODE_PRIVATE).getBoolean("notification_listener_connected", false)
        return Result(true, if (!listener)
            "__MESSAGES_EMPTY__|Activa Acceso a notificaciones para WhatsApp, RCS, Instagram, TikTok, Telegram y Messenger. Para SMS concede también permiso de SMS."
        else "__MESSAGES_EMPTY__|No hay mensajes capturados todavía. Jarvis conservará los nuevos mensajes que lleguen por notificación; para historiales antiguos puede abrir la app mediante Accesibilidad.")
    }

    private fun readAgenda(days: Int): Result {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.READ_CALENDAR), 74)
            return Result(true, "__AGENDA_EMPTY__|Necesito permiso de Calendario. Concédelo y vuelve a preguntar.")
        }
        val now = System.currentTimeMillis()
        val end = now + days * 24L * 60L * 60L * 1000L
        val out = mutableListOf<String>()
        val sdf = SimpleDateFormat("EEE dd/MM HH:mm", Locale.getDefault())
        runCatching {
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            android.content.ContentUris.appendId(uri, now)
            android.content.ContentUris.appendId(uri, end)
            activity.contentResolver.query(
                uri.build(),
                arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.ALL_DAY),
                null, null, CalendarContract.Instances.BEGIN + " ASC"
            )?.use { c ->
                val ei = c.getColumnIndex(CalendarContract.Instances.EVENT_ID)
                val ti = c.getColumnIndex(CalendarContract.Instances.TITLE)
                val bi = c.getColumnIndex(CalendarContract.Instances.BEGIN)
                val li = c.getColumnIndex(CalendarContract.Instances.EVENT_LOCATION)
                val ai = c.getColumnIndex(CalendarContract.Instances.ALL_DAY)
                while (c.moveToNext() && out.size < 20) {
                    val eventId = if (ei >= 0) c.getLong(ei) else -1L
                    val title = if (ti >= 0) c.getString(ti).orEmpty() else "Evento"
                    val begin = if (bi >= 0) c.getLong(bi) else now
                    val place = if (li >= 0) c.getString(li).orEmpty() else ""
                    val allDay = ai >= 0 && c.getInt(ai) == 1
                    val whenText = if (allDay) SimpleDateFormat("EEE dd/MM · todo el día", Locale.getDefault()).format(Date(begin)) else sdf.format(Date(begin))
                    var reminder = ""
                    if (eventId >= 0) {
                        runCatching {
                            activity.contentResolver.query(CalendarContract.Reminders.CONTENT_URI, arrayOf(CalendarContract.Reminders.MINUTES), "${CalendarContract.Reminders.EVENT_ID}=?", arrayOf(eventId.toString()), null)?.use { rc ->
                                if (rc.moveToFirst()) {
                                    val mi = rc.getColumnIndex(CalendarContract.Reminders.MINUTES)
                                    if (mi >= 0) reminder = " · aviso ${rc.getInt(mi)} min antes"
                                }
                            }
                        }
                    }
                    out += "Calendario|$whenText|${title.replace("|", " ")}|${(place + reminder).replace("|", " ")}"
                }
            }
        }

        // Task/reminder apps usually expose due items through notifications rather than CalendarContract.
        val feed = notificationFeed()
        for (i in feed.length() - 1 downTo 0) {
            if (out.size >= 24) break
            val n = feed.optJSONObject(i) ?: continue
            val pkg = n.optString("package")
            val blob = (pkg + " " + n.optString("title") + " " + n.optString("text")).lowercase()
            if (!(blob.contains("calendar") || blob.contains("calendario") || blob.contains("task") || blob.contains("tarea") || blob.contains("reminder") || blob.contains("recordatorio") || blob.contains("todo") || blob.contains("keep"))) continue
            val title = n.optString("title").ifBlank { "Recordatorio" }
            val body = n.optString("text").replace("|", " ").replace("\n", " ")
            out += "Recordatorio|Ahora|${title.replace("|", " ")}|${body.take(500)}"
        }

        return if (out.isNotEmpty()) Result(true, "__AGENDA_WIDGET__\n" + out.distinct().joinToString("\n"))
        else Result(true, "__AGENDA_EMPTY__|No encuentro citas, recordatorios o tareas en el teléfono para los próximos $days días. Para Google Tasks/Microsoft To Do activa también su integración o MCP.")
    }

'''
if 'private fun readUnifiedMessages' not in s:
    s = s.replace(marker, methods + marker)

# Improve specific notification package matching.
s = s.replace('''                "messages" -> pkg.contains("messag", true) || pkg.contains("sms", true)
                else -> "$pkg $who $body".contains(q, true)''', '''                "messages" -> pkg.contains("messag", true) || pkg.contains("sms", true)
                "instagram" -> pkg.contains("instagram", true)
                "tiktok" -> pkg.contains("tiktok", true)
                "telegram" -> pkg.contains("telegram", true)
                "messenger" -> pkg.contains("facebook.orca", true) || pkg.contains("messenger", true)
                else -> "$pkg $who $body".contains(q, true)''')

p.write_text(s)

# --- MainActivity generated code: render local results as widgets ---
p = Path('mobile/src/main/java/com/jarvis/mobile/MainActivity.kt')
s = p.read_text()

local_old = '''        if (local?.handled == true) {
            if (local.message.isNotBlank()) {
                renderMessageCard("assistant", local.message)
                saveHistory("assistant", local.message, false)
                safeSpeak(local.message)
            }
            status.text = "Acción del teléfono"
            return
        }'''
local_new = '''        if (local?.handled == true) {
            if (local.message.startsWith("__MESSAGES_WIDGET__")) {
                renderMessagingWidgets(local.message)
            } else if (local.message.startsWith("__AGENDA_WIDGET__")) {
                renderAgendaWidgets(local.message)
            } else if (local.message.startsWith("__MESSAGES_EMPTY__|") || local.message.startsWith("__AGENDA_EMPTY__|")) {
                val msg = local.message.substringAfter("|")
                renderMessageCard("assistant", msg)
                saveHistory("assistant", msg, false)
                safeSpeak(msg)
            } else if (local.message.isNotBlank()) {
                renderMessageCard("assistant", local.message)
                saveHistory("assistant", local.message, false)
                safeSpeak(local.message)
            }
            status.text = "Jarvis listo"
            return
        }'''
if local_old in s:
    s = s.replace(local_old, local_new)

render_marker = '    private fun jsonArrayStrings(a: JSONArray?): List<String> {'
render_methods = r'''    private fun renderMessagingWidgets(raw: String) {
        beginWidgetGroup("Mensajes")
        raw.lines().drop(1).filter { it.isNotBlank() }.take(24).forEach { line ->
            val p = line.split("|", limit = 3)
            val app = p.getOrElse(0) { "Mensaje" }
            val who = p.getOrElse(1) { app }
            val body = p.getOrElse(2) { "" }
            addTextWidget("day", "$app · $who", body)
        }
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun renderAgendaWidgets(raw: String) {
        beginWidgetGroup("Agenda y recordatorios")
        raw.lines().drop(1).filter { it.isNotBlank() }.take(24).forEach { line ->
            val p = line.split("|", limit = 4)
            val source = p.getOrElse(0) { "Agenda" }
            val whenText = p.getOrElse(1) { "" }
            val title = p.getOrElse(2) { "Evento" }
            val detail = p.getOrElse(3) { "" }
            addTextWidget("day", "$whenText · $title", if (detail.isBlank()) source else "$source · $detail")
        }
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

'''
if 'private fun renderMessagingWidgets' not in s:
    if render_marker not in s:
        raise SystemExit('jsonArrayStrings marker not found')
    s = s.replace(render_marker, render_methods + render_marker)

p.write_text(s)

# --- Manifest: calendar access ---
p = Path('mobile/src/main/AndroidManifest.xml')
s = p.read_text()
if 'android.permission.READ_CALENDAR' not in s:
    s = s.replace('    <uses-permission android:name="android.permission.READ_CONTACTS" />\n', '    <uses-permission android:name="android.permission.READ_CONTACTS" />\n    <uses-permission android:name="android.permission.READ_CALENDAR" />\n    <uses-permission android:name="android.permission.WRITE_CALENDAR" />\n')
p.write_text(s)
print('Messaging and agenda patch applied')