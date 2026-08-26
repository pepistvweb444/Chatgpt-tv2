from pathlib import Path

# Local router: only active/unread notifications + unread SMS, including mail/social apps.
p = Path('mobile/src/main/java/com/jarvis/mobile/LocalActionRouter.kt')
s = p.read_text()
s = s.replace('getString("notification_feed", "[]")', 'getString("active_notification_feed", "[]")')
s = s.replace('query(Uri.parse("content://sms/inbox"), arrayOf("address", "body", "date"), null, null, "date DESC")', 'query(Uri.parse("content://sms/inbox"), arrayOf("address", "body", "date"), "read=0", null, "date DESC")')

old_label = '''        pkg.contains("facebook.orca", true) || pkg.contains("messenger", true) -> "Messenger"
        pkg.contains("messag", true) || pkg.contains("sms", true) -> "RCS/Mensajes"
        else -> pkg.substringAfterLast('.').ifBlank { "App" }'''
new_label = '''        pkg.contains("facebook.orca", true) || pkg.contains("messenger", true) -> "Messenger"
        pkg.contains("facebook.katana", true) -> "Facebook"
        pkg.contains("google.android.gm", true) -> "Gmail"
        pkg.contains("outlook", true) -> "Outlook"
        pkg.contains("email", true) || pkg.contains("mail", true) -> "Correo"
        pkg.contains("messag", true) || pkg.contains("sms", true) -> "RCS/Mensajes"
        else -> pkg.substringAfterLast('.').ifBlank { "App" }'''
if old_label in s:
    s = s.replace(old_label, new_label, 1)

old_pkg = '''        pkg.contains("telegram", true) || pkg.contains("facebook.orca", true) || pkg.contains("messenger", true) ||
        pkg.contains("messag", true) || pkg.contains("sms", true)'''
new_pkg = '''        pkg.contains("telegram", true) || pkg.contains("facebook.orca", true) || pkg.contains("messenger", true) ||
        pkg.contains("facebook.katana", true) || pkg.contains("google.android.gm", true) || pkg.contains("outlook", true) ||
        pkg.contains("email", true) || pkg.contains("mail", true) || pkg.contains("messag", true) || pkg.contains("sms", true)'''
if old_pkg in s:
    s = s.replace(old_pkg, new_pkg, 1)

old_empty = '''        return Result(true, if (!listener)
            "__MESSAGES_EMPTY__|Activa Acceso a notificaciones para WhatsApp, RCS, Instagram, TikTok, Telegram y Messenger. Para SMS concede también permiso de SMS."
        else "__MESSAGES_EMPTY__|No hay mensajes capturados todavía. Jarvis conservará los nuevos mensajes que lleguen por notificación; para historiales antiguos puede abrir la app mediante Accesibilidad.")'''
new_empty = '''        return Result(true, if (!listener)
            "__MESSAGES_EMPTY__|Activa Acceso a notificaciones para correo, WhatsApp, RCS, Instagram, TikTok, Facebook/Messenger y Telegram. Para SMS concede también permiso de SMS."
        else "__MESSAGES_REMOTE__|No hay mensajes no leídos visibles en las notificaciones. Revisaré las bandejas de las aplicaciones mediante Accesibilidad.")'''
if old_empty in s:
    s = s.replace(old_empty, new_empty, 1)
p.write_text(s)

# Main UI: one unified widget for all unread messages; use phone agent as fallback.
p = Path('mobile/src/main/java/com/jarvis/mobile/MainActivity.kt')
s = p.read_text()
old_render = '''    private fun renderMessagingWidgets(raw: String) {
        beginWidgetGroup("Mensajes")
        raw.lines().drop(1).filter { it.isNotBlank() }.take(24).forEach { line ->
            val p = line.split("|", limit = 3)
            val app = p.getOrElse(0) { "Mensaje" }
            val who = p.getOrElse(1) { app }
            val body = p.getOrElse(2) { "" }
            addTextWidget("day", "$app · $who", body)
        }
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }'''
new_render = '''    private fun renderMessagingWidgets(raw: String) {
        beginWidgetGroup("Mensajes no leídos")
        val entries = raw.lines().drop(1).filter { it.isNotBlank() }.take(30).map { line ->
            val p = line.split("|", limit = 3)
            val app = p.getOrElse(0) { "Mensaje" }
            val who = p.getOrElse(1) { app }
            val body = p.getOrElse(2) { "" }.replace("\\n", " ")
            "• $app · $who\\n  $body"
        }
        addTextWidget("day", "Pendientes · ${entries.size}", if (entries.isEmpty()) "No hay mensajes sin leer." else entries.joinToString("\\n\\n"))
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }'''
if old_render in s:
    s = s.replace(old_render, new_render, 1)

needle = '''            } else if (local.message.startsWith("__MESSAGES_EMPTY__|") || local.message.startsWith("__AGENDA_EMPTY__|")) {'''
remote = '''            } else if (local.message.startsWith("__MESSAGES_REMOTE__|")) {
                val accessibility = prefs.getBoolean("accessibility_connected", false)
                if (!accessibility) {
                    val msg = "No veo mensajes no leídos en las notificaciones. Activa Jarvis en Accesibilidad para que pueda revisar las bandejas de las aplicaciones."
                    beginWidgetGroup("Mensajes no leídos")
                    addTextWidget("day", "Acceso necesario", msg)
                } else {
                    status.text = "Revisando mensajes no leídos…"
                    phoneAgent.run(
                        "Revisa únicamente mensajes SIN LEER en Gmail/correo, WhatsApp, Instagram, TikTok, Facebook/Messenger, Telegram y la app Mensajes/RCS. Abre las apps necesarias, identifica solo conversaciones o correos marcados como no leídos y al terminar devuelve un resumen breve con aplicación, remitente y último mensaje. No envíes, borres ni marques nada como leído deliberadamente.",
                        onUpdate = { t -> runOnUiThread { status.text = t } },
                        onDone = { result -> runOnUiThread {
                            beginWidgetGroup("Mensajes no leídos")
                            addTextWidget("day", "Revisión del teléfono", result)
                            status.text = "Jarvis listo"
                        } }
                    )
                }
            } else if (local.message.startsWith("__MESSAGES_EMPTY__|") || local.message.startsWith("__AGENDA_EMPTY__|")) {'''
if needle in s and '__MESSAGES_REMOTE__|' not in s:
    s = s.replace(needle, remote, 1)
p.write_text(s)

# Apply final real Android permission check after the unread-inbox transformations.
real_patch = Path('mobile/patch_real_notification_access.py')
if real_patch.exists():
    exec(compile(real_patch.read_text(), str(real_patch), 'exec'))

print('Unified unread inbox patch applied')
