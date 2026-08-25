from pathlib import Path

p = Path('mobile/src/main/java/com/jarvis/mobile/MainActivity.kt')
s = p.read_text()

# Detect compound requests before the single-intent LocalActionRouter/classifier.
needle = '''        saveHistory("user", message, false)\n'''
block = '''        saveHistory("user", message, false)\n\n        val compound = detectRequestedTasks(message)\n        if (compound.size > 1) {\n            runRequestedTasks(message, compound)\n            return\n        }\n'''
idx = s.find('    private fun sendMessage() {')
if idx < 0:
    raise SystemExit('sendMessage not found')
pos = s.find(needle, idx)
if pos >= 0 and 'val compound = detectRequestedTasks(message)' not in s[idx:s.find('    private fun sendVisualPrompt', idx)]:
    s = s[:pos] + s[pos:].replace(needle, block, 1)

marker = '    private fun sendVisualPrompt(kind: String, prompt: String) {'
if 'private fun detectRequestedTasks' not in s:
    helpers = r'''    private fun detectRequestedTasks(message: String): List<String> {
        val q = message.lowercase()
        val out = mutableListOf<String>()
        if (q.contains("mensaje") || q.contains("whatsapp") || q.contains("instagram") || q.contains("tiktok") || q.contains("telegram") || q.contains("messenger") || q.contains("sms") || q.contains("rcs") || q.contains("correo")) out += "messages"
        if (q.contains("agenda") || q.contains("calendario") || q.contains("cita") || q.contains("recordatorio") || q.contains("reunión") || q.contains("reunion") || q.contains("tarea pendiente")) out += "agenda"
        if (q.contains("noticia") || q.contains("titular") || q.contains("actualidad")) out += "news"
        if (q.contains("tiempo") || q.contains("previsión") || q.contains("prevision") || q.contains("meteorología") || q.contains("meteorologia")) out += "weather"
        if (q.contains("domótica") || q.contains("domotica") || q.contains("termostato") || q.contains("tado") || q.contains("luces") || q.contains("persianas") || q.contains("home connect") || q.contains("hue") || q.contains("roborock")) out += "home"
        return out.distinct()
    }

    private fun runRequestedTasks(original: String, tasks: List<String>) {
        status.text = "Jarvis · ${tasks.size} tareas…"
        tasks.forEach { task ->
            when (task) {
                "messages" -> {
                    val r = runCatching { actionRouter.handle("dime qué mensajes tengo") }.getOrNull()
                    if (r?.handled == true) renderAndSpeakLocalResult(r.message)
                }
                "agenda" -> {
                    val r = runCatching { actionRouter.handle("dime qué citas, recordatorios y agenda tengo") }.getOrNull()
                    if (r?.handled == true) renderAndSpeakLocalResult(r.message)
                }
                "news" -> openNewsFast()
                "weather" -> openWeatherForCurrentLocation("Dime el tiempo actual y la previsión de hoy")
                "home" -> executeChat("Muéstrame el estado actual de mi domótica, termostatos, luces y dispositivos conectados. Devuelve cada elemento en una línea separada.", "home")
            }
        }
        status.text = "Jarvis · tareas en curso"
    }

    private fun renderAndSpeakLocalResult(raw: String) {
        when {
            raw.startsWith("__MESSAGES_WIDGET__") -> {
                renderMessagingWidgets(raw)
                val lines = raw.lines().drop(1).filter { it.isNotBlank() }.take(8).map { line ->
                    val p = line.split("|", limit = 3)
                    "${p.getOrElse(0){"Mensaje"}} de ${p.getOrElse(1){"desconocido"}}: ${p.getOrElse(2){""}}"
                }
                val spoken = if (lines.isEmpty()) "No tienes mensajes pendientes detectados." else "Tienes ${lines.size} mensajes pendientes. " + lines.joinToString(". ")
                safeSpeak(spoken)
            }
            raw.startsWith("__AGENDA_WIDGET__") -> {
                renderAgendaWidgets(raw)
                val lines = raw.lines().drop(1).filter { it.isNotBlank() }.take(8).map { line ->
                    val p = line.split("|", limit = 4)
                    "${p.getOrElse(1){""}} ${p.getOrElse(2){"Evento"}}"
                }
                safeSpeak(if (lines.isEmpty()) "No tienes citas o recordatorios próximos." else "En tu agenda: " + lines.joinToString(". "))
            }
            raw.startsWith("__MESSAGES_EMPTY__|") || raw.startsWith("__AGENDA_EMPTY__|") -> {
                val msg = raw.substringAfter("|")
                renderMessageCard("assistant", msg); saveHistory("assistant", msg, false); safeSpeak(msg)
            }
            raw.isNotBlank() -> {
                renderMessageCard("assistant", raw); saveHistory("assistant", raw, false); safeSpeak(raw)
            }
        }
    }

'''
    if marker not in s:
        raise SystemExit('sendVisualPrompt marker not found')
    s = s.replace(marker, helpers + marker, 1)

# Make standalone local widgets spoken too, not just compound requests.
old_local = '''            if (local.message.startsWith("__MESSAGES_WIDGET__")) {\n                renderMessagingWidgets(local.message)\n            } else if (local.message.startsWith("__AGENDA_WIDGET__")) {\n                renderAgendaWidgets(local.message)\n'''
new_local = '''            if (local.message.startsWith("__MESSAGES_WIDGET__") || local.message.startsWith("__AGENDA_WIDGET__")) {\n                renderAndSpeakLocalResult(local.message)\n            } else if (false) {\n                // handled above\n'''
if old_local in s:
    s = s.replace(old_local, new_local, 1)

# Speak actual news headlines after cards are rendered.
news_anchor = '''                    status.text = "Titulares listos"\n                    loadNewsMultimedia()\n'''
news_repl = '''                    status.text = "Titulares listos"\n                    loadNewsMultimedia()\n                    val spoken = (0 until minOf(5, items.length())).mapNotNull { j -> items.optJSONObject(j)?.optString("title")?.takeIf { it.isNotBlank() } }\n                    if (spoken.isNotEmpty()) safeSpeak("Estas son las noticias principales. " + spoken.joinToString(". "))\n'''
if news_anchor in s and 'Estas son las noticias principales' not in s:
    s = s.replace(news_anchor, news_repl, 1)

p.write_text(s)
print('Multi-task orchestration and widget speech applied')
