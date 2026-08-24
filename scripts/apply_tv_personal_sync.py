from pathlib import Path

layout = Path('app/src/main/res/layout/activity_main.xml')
x = layout.read_text()
if '@+id/personalWidgetContainer' not in x:
    anchor = '''                <HorizontalScrollView
                    android:layout_width="match_parent"
                    android:layout_height="52dp"'''
    block = '''                <LinearLayout
                    android:id="@+id/personalWidgetContainer"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:orientation="vertical"
                    android:visibility="gone"
                    android:paddingTop="10dp"
                    android:paddingBottom="10dp" />

'''
    if anchor not in x:
        raise SystemExit('TV layout anchor for personal widgets not found')
    x = x.replace(anchor, block + anchor, 1)
    layout.write_text(x)

p = Path('app/src/main/java/com/jarvis/tv/MainActivity.kt')
s = p.read_text()

insert_after = 'import android.content.pm.PackageManager\n'
for imp in [
    'import android.graphics.Color\n',
    'import android.graphics.drawable.GradientDrawable\n',
    'import android.view.Gravity\n',
    'import android.view.View\n',
]:
    if imp not in s:
        s = s.replace(insert_after, insert_after + imp, 1)

field_anchor = '    private lateinit var currentChatLabel: TextView\n'
if 'private lateinit var personalWidgetContainer: LinearLayout' not in s:
    s = s.replace(field_anchor, field_anchor + '    private lateinit var personalWidgetContainer: LinearLayout\n', 1)

bind_anchor = '        currentChatLabel = findViewById(R.id.currentChatLabel)\n'
if 'personalWidgetContainer = findViewById(R.id.personalWidgetContainer)' not in s:
    s = s.replace(bind_anchor, bind_anchor + '        personalWidgetContainer = findViewById(R.id.personalWidgetContainer)\n', 1)

lower_anchor = '        val lower = text.lowercase()\n'
agenda_block = r'''        val lower = text.lowercase()
        val asksAgenda = lower.contains("agenda") || lower.contains("calendario") || lower.contains("cita") || lower.contains("citas") || lower.contains("recordatorio") || lower.contains("recordatorios") || lower.contains("resumen del día") || lower.contains("resumen del dia")
        if (asksAgenda && mobileRemote.configured()) {
            status.text = "● Sincronizando agenda del móvil…"
            Thread {
                try {
                    val result = mobileRemote.agenda()
                    runOnUiThread {
                        renderAgendaSync(result)
                        status.text = "● Móvil · agenda sincronizada"
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        status.text = "● Error agenda móvil"
                        Toast.makeText(this, "Agenda: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
            return
        }

        val asksUnread = lower.contains("mensajes") || lower.contains("no leídos") || lower.contains("no leidos") || lower.contains("whatsapp") || lower.contains("instagram") || lower.contains("tiktok") || lower.contains("facebook") || lower.contains("telegram") || lower.contains("sms") || lower.contains("rcs") || lower.contains("correo")
        if (asksUnread && mobileRemote.configured()) {
            status.text = "● Sincronizando mensajes no leídos del móvil…"
            Thread {
                try {
                    val result = mobileRemote.unreadMessages()
                    runOnUiThread {
                        renderUnreadSync(result)
                        status.text = "● Móvil · ${result.optInt("unreadCount")} pendientes"
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        status.text = "● Error mensajes móvil"
                        Toast.makeText(this, "Mensajes: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
            return
        }
'''
if 'val asksAgenda =' not in s:
    if lower_anchor not in s:
        raise SystemExit('lowercase anchor not found for TV personal sync')
    s = s.replace(lower_anchor, agenda_block, 1)

helper_anchor = '    private fun updateChatMeta(text: String, role: String) {\n'
if 'private fun renderAgendaSync(data: JSONObject)' not in s:
    helpers = r'''    private fun pDp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun pRounded(color: Int, radiusDp: Int = 18): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = pDp(radiusDp).toFloat()
        setStroke(pDp(1), Color.rgb(62, 73, 94))
    }

    private fun pText(value: String, size: Float, bold: Boolean = false, color: Int = Color.WHITE): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setLineSpacing(0f, 1.08f)
    }

    private fun formatSyncTime(ms: Long, allDay: Boolean = false): String {
        if (ms <= 0L) return ""
        val pattern = if (allDay) "EEE d MMM" else "EEE d MMM · HH:mm"
        return java.text.SimpleDateFormat(pattern, Locale("es", "ES")).format(java.util.Date(ms))
    }

    private fun renderAgendaSync(data: JSONObject) {
        personalWidgetContainer.removeAllViews()
        personalWidgetContainer.visibility = View.VISIBLE
        val events = data.optJSONArray("events") ?: JSONArray()
        val reminders = data.optJSONArray("reminders") ?: JSONArray()
        val permission = data.optBoolean("calendarPermission")

        personalWidgetContainer.addView(pText("Jarvis · Agenda y recordatorios", 20f, true, Color.rgb(205, 213, 255)).apply {
            setPadding(pDp(4), pDp(6), pDp(4), pDp(12))
        })

        if (!permission) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = pRounded(Color.rgb(60, 44, 92))
                setPadding(pDp(18), pDp(16), pDp(18), pDp(16))
            }
            card.addView(pText("Calendario sin permiso en el móvil", 18f, true))
            card.addView(pText("Concede a Jarvis Mobile acceso al calendario y vuelve a consultar desde la TV.", 15f, false, Color.rgb(225, 220, 240)))
            personalWidgetContainer.addView(card)
            return
        }

        if (events.length() == 0 && reminders.length() == 0) {
            val empty = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = pRounded(Color.rgb(31, 40, 55))
                setPadding(pDp(18), pDp(16), pDp(18), pDp(16))
            }
            empty.addView(pText("Sin citas ni recordatorios próximos", 18f, true))
            personalWidgetContainer.addView(empty)
            return
        }

        for (i in 0 until events.length()) {
            val e = events.optJSONObject(i) ?: continue
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = pRounded(Color.rgb(55, 45, 108))
                setPadding(pDp(18), pDp(14), pDp(18), pDp(14))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = pDp(10) }
            }
            card.addView(pText(e.optString("title").ifBlank { "Evento" }, 18f, true))
            val whenText = formatSyncTime(e.optLong("begin"), e.optBoolean("allDay"))
            if (whenText.isNotBlank()) card.addView(pText(whenText, 15f, false, Color.rgb(210, 211, 244)))
            val location = e.optString("location")
            if (location.isNotBlank()) card.addView(pText("📍 $location", 14f, false, Color.rgb(190, 194, 220)))
            personalWidgetContainer.addView(card)
        }

        for (i in 0 until reminders.length()) {
            val r = reminders.optJSONObject(i) ?: continue
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = pRounded(Color.rgb(42, 76, 67))
                setPadding(pDp(18), pDp(14), pDp(18), pDp(14))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = pDp(10) }
            }
            card.addView(pText("⏰ ${r.optString("title").ifBlank { "Recordatorio" }}", 17f, true))
            val body = r.optString("text")
            if (body.isNotBlank()) card.addView(pText(body, 15f, false, Color.rgb(220, 236, 230)))
            personalWidgetContainer.addView(card)
        }
    }

    private fun renderUnreadSync(data: JSONObject) {
        personalWidgetContainer.removeAllViews()
        personalWidgetContainer.visibility = View.VISIBLE
        val items = data.optJSONArray("items") ?: JSONArray()
        personalWidgetContainer.addView(pText("Jarvis · Mensajes no leídos · ${items.length()}", 20f, true, Color.rgb(205, 213, 255)).apply {
            setPadding(pDp(4), pDp(6), pDp(4), pDp(12))
        })
        if (items.length() == 0) {
            val empty = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = pRounded(Color.rgb(31, 40, 55))
                setPadding(pDp(18), pDp(16), pDp(18), pDp(16))
            }
            empty.addView(pText("No hay mensajes pendientes detectados", 18f, true))
            personalWidgetContainer.addView(empty)
            return
        }
        for (i in 0 until items.length()) {
            val m = items.optJSONObject(i) ?: continue
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = pRounded(Color.rgb(28, 39, 57))
                setPadding(pDp(18), pDp(14), pDp(18), pDp(14))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = pDp(10) }
            }
            card.addView(pText("${m.optString("source")} · ${m.optString("from")}", 17f, true))
            card.addView(pText(m.optString("text"), 15f, false, Color.rgb(225, 231, 242)))
            personalWidgetContainer.addView(card)
        }
    }

'''
    if helper_anchor not in s:
        raise SystemExit('helper anchor not found for TV personal sync')
    s = s.replace(helper_anchor, helpers + helper_anchor, 1)

p.write_text(s)
print('TV personal sync widgets applied')
