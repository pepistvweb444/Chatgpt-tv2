from pathlib import Path

p = Path('mobile/src/main/java/com/jarvis/mobile/MainActivity.kt')
s = p.read_text()

old = '''    private fun classifyVisualRequest(text: String): String? {
        val s = text.lowercase()
        return when {
            s.contains("noticia") || s.contains("titular") -> "news"
            s.contains("tiempo") || s.contains("previsión") || s.contains("temperatura") -> "weather"
            s.contains("domótica") || s.contains("luces") || s.contains("persianas") || s.contains("casa") -> "home"
            s.contains("agenda") || s.contains("resumen del día") || s.contains("recordatorio") -> "day"
            else -> null
        }
    }
'''
new = '''    private fun classifyVisualRequest(text: String): String? {
        val s = text.lowercase()
        return when {
            s.contains("noticia") || s.contains("titular") || s.contains("actualidad") -> "news"
            s.contains("tiempo") || s.contains("previsión") || s.contains("meteorolog") -> "weather"
            s.contains("domótica") || s.contains("luces") || s.contains("persianas") || s.contains("termostato") || s.contains("tado") -> "home"
            s.contains("agenda") || s.contains("resumen del día") || s.contains("recordatorio") || s.contains("cita") || s.contains("calendario") -> "day"
            s.contains("televisión") || s.contains("television") || s.contains("la tele") || s.contains(" tv ") || s.startsWith("tv ") ||
                s.contains("programación") || s.contains("programacion") || s.contains("qué echan") || s.contains("que echan") ||
                s.contains("qué hay en la tele") || s.contains("que hay en la tele") || s.contains("está en la tele") || s.contains("esta en la tele") ||
                s.contains("canal") || s.contains("película") || s.contains("pelicula") || s.contains("serie") || s.contains("streaming") ||
                s.contains("netflix") || s.contains("prime video") || s.contains("amazon prime") || s.contains("disney+") || s.contains("disney plus") ||
                s.contains("hbo") || s.contains("max") || s.contains("movistar plus") || s.contains("rtve") || s.contains("antena 3") ||
                s.contains("telecinco") || s.contains("la 1") || s.contains("la 2") || s.contains("cuatro") || s.contains("la sexta") -> "media"
            s.contains("mensaje") || s.contains("whatsapp") || s.contains("instagram") || s.contains("tiktok") || s.contains("correo") || s.contains("email") -> "messages"
            s.contains("busca") || s.contains("buscar") || s.contains("recomienda") || s.contains("recomendación") || s.contains("recomendacion") || s.contains("precio") || s.contains("comprar") || s.contains("compra") || s.contains("producto") -> "results"
            else -> null
        }
    }
'''
if old in s:
    s = s.replace(old, new)
elif 's.contains("streaming")' not in s:
    marker='''            s.contains("televisión") || s.contains("television")'''
    if marker in s:
        s=s.replace(marker, '''            s.contains("televisión") || s.contains("television") || s.contains("la tele") || s.contains("streaming") || s.contains("netflix") || s.contains("prime video") || s.contains("disney+") || s.contains("hbo") || s.contains("movistar plus")''',1)

marker = '    private fun renderReplyAsWidgets(kind: String, reply: String) {'
methods = r'''    private fun widgetGroupTitle(kind: String): String = when (kind) {
        "weather" -> "Tiempo · tu ubicación"
        "home" -> "Domótica"
        "day" -> "Resumen del día"
        "news" -> "Noticias"
        "media" -> "TV y streaming · hoy"
        "messages" -> "Mensajes"
        "results" -> "Resultados"
        else -> "Información"
    }

    private fun addRichInfoWidget(kind: String, title: String, description: String, imageUrl: String? = null, openUrl: String? = null) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
        }
        row.addView(makeAvatar("assistant"))
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = cardBackground(if (kind == "media" || kind == "results") "news" else kind)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            if (!openUrl.isNullOrBlank()) setOnClickListener { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(openUrl))) } }
        }
        val image = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(92), dp(78)).apply { marginEnd = dp(12) }
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Color.rgb(31, 37, 48)) }
            clipToOutline = true
            visibility = if (imageUrl.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        card.addView(image)
        val textBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        textBox.addView(TextView(this).apply {
            text = title
            textSize = 16f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 3
        })
        if (description.isNotBlank()) textBox.addView(TextView(this).apply {
            text = description
            textSize = 13.5f
            setTextColor(Color.rgb(228, 233, 241))
            setPadding(0, dp(5), 0, 0)
            maxLines = 5
        })
        card.addView(textBox)
        row.addView(card)
        widgetHost.addView(row)
        if (!imageUrl.isNullOrBlank()) Thread {
            val bmp = runCatching { URL(imageUrl).openConnection().apply { connectTimeout = 4500; readTimeout = 6500 }.getInputStream().use { BitmapFactory.decodeStream(it) } }.getOrNull()
            if (bmp != null) runOnUiThread { image.setImageBitmap(bmp) }
        }.start()
    }

    private fun renderUniversalWidgets(kind: String, reply: String, images: List<String>, videos: List<String>) {
        beginWidgetGroup(widgetGroupTitle(kind))
        val items = splitWidgetItems(reply)
        if (items.isEmpty()) {
            addRichInfoWidget(kind, widgetGroupTitle(kind), "No hay datos disponibles", images.firstOrNull(), videos.firstOrNull())
        } else {
            items.forEachIndexed { i, item ->
                val cleaned = item.trim()
                val sep = listOf(" — ", " - ", ": ").firstOrNull { cleaned.contains(it) }
                val title = if (sep != null) cleaned.substringBefore(sep).trim().take(120) else cleaned.take(120)
                val body = if (sep != null) cleaned.substringAfter(sep).trim() else if (cleaned.length > 120) cleaned.drop(120).trim() else ""
                addRichInfoWidget(kind, title.ifBlank { "Resultado ${i + 1}" }, body, images.getOrNull(i) ?: images.firstOrNull(), videos.getOrNull(i))
            }
        }
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun shouldRenderAsWidget(kind: String?, reply: String, images: List<String>, videos: List<String>): Boolean {
        if (kind != null) return true
        if (images.isNotEmpty() || videos.isNotEmpty()) return true
        val lines = reply.lines().count { it.trim().length > 2 }
        val bullets = Regex("(?m)^\\s*(?:[-•▪◦*]|\\d+[.)])\\s+").containsMatchIn(reply)
        return lines >= 2 || bullets || reply.length > 420
    }

'''
if 'private fun renderUniversalWidgets' not in s and marker in s:
    s = s.replace(marker, methods + marker, 1)

s = s.replace(
'''                    saveHistory("assistant", reply, widgetKind != null, images, videos)
                    if (widgetKind != null) renderReplyAsWidgets(widgetKind, reply) else renderMessageCard("assistant", reply, images, videos)
                    status.text = "Jarvis listo"''',
'''                    val visual = shouldRenderAsWidget(widgetKind, reply, images, videos)
                    saveHistory("assistant", reply, visual, images, videos)
                    if (visual) {
                        val kind = widgetKind ?: "results"
                        if (kind == "weather" || kind == "home" || kind == "day") renderReplyAsWidgets(kind, reply)
                        else renderUniversalWidgets(kind, reply, images, videos)
                    } else renderMessageCard("assistant", reply, images, videos)
                    status.text = "Jarvis listo"'''
)

p.write_text(s)
print('Universal rich widget renderer applied with TV/streaming detection')
