from pathlib import Path

p=Path('mobile/src/main/java/com/jarvis/mobile/LocalActionRouter.kt')
s=p.read_text()

# Required imports for checking the actual Android notification-listener grant.
if 'import android.content.ComponentName' not in s:
    s=s.replace('import android.content.Intent\n','import android.content.Intent\nimport android.content.ComponentName\n')

marker='''    private fun messageAccessStatus(): Result {'''
helper=r'''    private fun notificationAccessGranted(): Boolean {
        val enabled = Settings.Secure.getString(activity.contentResolver, "enabled_notification_listeners").orEmpty()
        val mine = ComponentName(activity, JarvisNotificationListener::class.java).flattenToString()
        return enabled.contains(mine, true) || enabled.contains(activity.packageName, true)
    }

    private fun ensureNotificationReaderBound(): Boolean {
        val granted = notificationAccessGranted()
        if (granted) {
            runCatching {
                android.service.notification.NotificationListenerService.requestRebind(
                    ComponentName(activity, JarvisNotificationListener::class.java)
                )
            }
        }
        return granted
    }

'''
if 'private fun notificationAccessGranted()' not in s:
    if marker not in s: raise SystemExit('messageAccessStatus marker not found')
    s=s.replace(marker,helper+marker,1)

# messageAccessStatus must use the system grant, not only a stale SharedPreferences flag.
old='''        val listener = prefs.getBoolean("notification_listener_connected", false)'''
new='''        val listener = ensureNotificationReaderBound()
        val listenerBound = prefs.getBoolean("notification_listener_connected", false)'''
s=s.replace(old,new,1)
s=s.replace('''Acceso a notificaciones: ${if (listener) "conectado" else "no conectado"}. Capturados:''',
            '''Acceso a notificaciones: ${if (listener) "AUTORIZADO" else "NO AUTORIZADO"} · lector: ${if (listenerBound) "conectado" else if (listener) "reconectando" else "desconectado"}. Capturados:''',1)

# Specific WhatsApp reads should also trust the real Android setting and trigger a rebind.
old2='''        val connected = activity.getSharedPreferences("jarvis_mobile", Activity.MODE_PRIVATE).getBoolean("notification_listener_connected", false)
        return Result(true, if (out.isEmpty()) "No encuentro mensajes de $filter. Acceso a notificaciones: ${if (connected) "conectado" else "NO conectado"}. Activa Jarvis en Acceso a notificaciones y haz que llegue una notificación nueva para probar." else "Mensajes recientes:\\n\\n" + out.joinToString("\\n\\n"))'''
new2='''        val granted = ensureNotificationReaderBound()
        val bound = activity.getSharedPreferences("jarvis_mobile", Activity.MODE_PRIVATE).getBoolean("notification_listener_connected", false)
        return Result(true, if (out.isEmpty()) {
            if (!granted) "No tengo autorizado el Acceso a notificaciones. Abre Control del teléfono > Activar acceso a WhatsApp / RCS / redes / correo y habilita Jarvis."
            else "Jarvis SÍ tiene acceso a notificaciones${if (bound) "" else " y está reconectando el lector"}, pero todavía no hay mensajes de $filter capturados. Puedo abrir la app mediante Accesibilidad para revisar su interfaz."
        } else "Mensajes recientes:\\n\\n" + out.joinToString("\\n\\n"))'''
if old2 in s:
    s=s.replace(old2,new2,1)

# Broaden explicit app routing so Jarvis doesn't fall through to an LLM that claims no access.
wa='''            lower.contains("whatsapp") && (lower.contains("lee") || lower.contains("léeme") || lower.contains("leeme") || lower.contains("mensajes") || lower.contains("escrito")) -> return readNotificationMessages("whatsapp", 12)'''
more=wa+'''\n            lower.contains("instagram") && (lower.contains("lee") || lower.contains("léeme") || lower.contains("leeme") || lower.contains("mensajes") || lower.contains("escrito")) -> return readNotificationMessages("instagram", 12)\n            lower.contains("telegram") && (lower.contains("lee") || lower.contains("léeme") || lower.contains("leeme") || lower.contains("mensajes") || lower.contains("escrito")) -> return readNotificationMessages("telegram", 12)\n            (lower.contains("messenger") || lower.contains("facebook")) && (lower.contains("lee") || lower.contains("léeme") || lower.contains("leeme") || lower.contains("mensajes") || lower.contains("escrito")) -> return readNotificationMessages("messenger", 12)'''
if 'readNotificationMessages("instagram", 12)' not in s and wa in s:
    s=s.replace(wa,more,1)

# Expand package matching in readNotificationMessages.
s=s.replace('''                "messages" -> pkg.contains("messag", true) || pkg.contains("sms", true)
                else -> "$pkg $who $body".contains(q, true)''',
'''                "messages" -> pkg.contains("messag", true) || pkg.contains("sms", true)
                "instagram" -> pkg.contains("instagram", true)
                "telegram" -> pkg.contains("telegram", true)
                "messenger" -> pkg.contains("facebook.orca", true) || pkg.contains("messenger", true)
                else -> "$pkg $who $body".contains(q, true)''')

p.write_text(s)
print('Real Android notification access + app message routing applied')
