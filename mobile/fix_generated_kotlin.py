from pathlib import Path

# Normalize and harden the generated MainActivity.
p = Path('mobile/src/main/java/com/jarvis/mobile/MainActivity.kt')
s = p.read_text()

# patch_weather_widget.py is a Python generator. A \n inside its triple-quoted
# replacement can become a literal newline inside a Kotlin quoted string.
broken = '''                text = "$label
${weatherIcon(dCode)}
${if (dMax.isNaN()) "—" else dMax.toInt()}° / ${if (dMin.isNaN()) "—" else dMin.toInt()}°"
'''
fixed = '''                text = "$label\\n${weatherIcon(dCode)}\\n${if (dMax.isNaN()) "—" else dMax.toInt()}° / ${if (dMin.isNaN()) "—" else dMin.toInt()}°"
'''
if broken in s:
    s = s.replace(broken, fixed)

# The Day widget must NEVER send a synthetic prompt to the general model first.
# Read CalendarContract locally and render the agenda widget directly.
old_day = '''        findViewById<View>(R.id.dayWidget).setOnClickListener {
            sendVisualPrompt("day", "Dame mi resumen del día. Devuelve una línea separada por cada cita, recordatorio, aviso o asunto importante, sin introducción ni conclusión.")
        }'''
new_day = '''        findViewById<View>(R.id.dayWidget).setOnClickListener {
            val local = runCatching { actionRouter.handle("¿Qué citas, recordatorios, tareas y avisos tengo hoy?") }.getOrNull()
            when {
                local?.message?.startsWith("__AGENDA_WIDGET__") == true -> renderAgendaWidgets(local.message)
                local?.message?.startsWith("__AGENDA_EMPTY__|") == true -> {
                    beginWidgetGroup("Agenda y recordatorios")
                    addTextWidget("day", "Agenda", local.message.substringAfter("|"))
                }
                local?.message?.isNotBlank() == true -> {
                    beginWidgetGroup("Agenda y recordatorios")
                    addTextWidget("day", "Agenda", local.message)
                }
                else -> {
                    beginWidgetGroup("Agenda y recordatorios")
                    addTextWidget("day", "Agenda", "No he podido leer la agenda local.")
                }
            }
            status.text = "Jarvis listo"
        }'''
if old_day in s:
    s = s.replace(old_day, new_day)

# Fail early with a readable message if a known broken multiline Kotlin
# interpolation survives the normalization.
if 'text = "$label\n${weatherIcon(dCode)}' in s:
    raise SystemExit('Generated Kotlin still contains a multiline forecast string')

p.write_text(s)

# Harden LocalActionRouter after all generator patches have run.
p = Path('mobile/src/main/java/com/jarvis/mobile/LocalActionRouter.kt')
s = p.read_text()

# Spoken/typed "resumen del día" is an agenda request too; it must not go to the
# general model before CalendarContract is queried.
agenda_subject = '''        val subject = lower.contains("cita") || lower.contains("citas") || lower.contains("agenda") ||'''
agenda_subject_fixed = '''        val subject = lower.contains("resumen del día") || lower.contains("resumen del dia") ||
            lower.contains("cita") || lower.contains("citas") || lower.contains("agenda") ||'''
if agenda_subject in s and 'lower.contains("resumen del día")' not in s:
    s = s.replace(agenda_subject, agenda_subject_fixed, 1)

# Include events from the beginning of today, not only events starting after the
# exact current minute.
old_now = '        val now = System.currentTimeMillis()\n        val end = now + days * 24L * 60L * 60L * 1000L\n'
new_now = '''        val now = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        val end = now + days * 24L * 60L * 60L * 1000L
'''
if old_now in s:
    s = s.replace(old_now, new_now, 1)

p.write_text(s)
print('Generated Kotlin normalized; day summary routed to local calendar')
