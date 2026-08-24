from pathlib import Path

p = Path('mobile/src/main/java/com/jarvis/mobile/LocalActionRouter.kt')
s = p.read_text()

# Never treat Jarvis' own foreground/TTS notifications as calendar reminders.
needle = '''            val pkg = n.optString("package")
            val blob = (pkg + " " + n.optString("title") + " " + n.optString("text")).lowercase()
            if (!(blob.contains("calendar") || blob.contains("calendario") || blob.contains("task") || blob.contains("tarea") || blob.contains("reminder") || blob.contains("recordatorio") || blob.contains("todo") || blob.contains("keep"))) continue
'''
replacement = '''            val pkg = n.optString("package")
            if (pkg == activity.packageName) continue
            val pkgLower = pkg.lowercase()
            val knownAgendaApp = pkgLower.contains("calendar") || pkgLower.contains("calendario") ||
                pkgLower.contains("tasks") || pkgLower.contains("task") || pkgLower.contains("todo") ||
                pkgLower.contains("keep") || pkgLower.contains("reminder") ||
                pkgLower.contains("samsung.android.app.reminder") || pkgLower.contains("microsoft.todos")
            if (!knownAgendaApp) continue
            val blob = (pkg + " " + n.optString("title") + " " + n.optString("text")).lowercase()
'''
if needle in s:
    s = s.replace(needle, replacement, 1)

old_empty = 'else Result(true, "__AGENDA_EMPTY__|No encuentro citas, recordatorios o tareas en el teléfono para los próximos $days días. Para Google Tasks/Microsoft To Do activa también su integración o MCP.")'
new_empty = '''else {
            val calendarCount = runCatching {
                activity.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, arrayOf(CalendarContract.Calendars._ID), null, null, null)?.use { it.count } ?: 0
            }.getOrDefault(0)
            Result(true, if (calendarCount == 0)
                "__AGENDA_EMPTY__|Jarvis tiene permiso de Calendario, pero Android no expone ningún calendario sincronizado. Activa la sincronización de Google/Samsung Calendar en el teléfono."
            else
                "__AGENDA_EMPTY__|Jarvis tiene acceso al Calendario ($calendarCount calendarios visibles), pero no hay citas o eventos en los próximos $days días. Los recordatorios de apps externas aparecerán cuando Android los exponga por calendario o notificación.")
        }'''
if old_empty in s:
    s = s.replace(old_empty, new_empty, 1)

p.write_text(s)
print('Agenda hardening applied: own notifications excluded, calendar diagnostics added')
