from pathlib import Path
p=Path('mobile/src/main/java/com/jarvis/mobile/MainActivity.kt')
s=p.read_text()

# Route TV/streaming requests before the generic chat so they always render as widgets.
marker='''        if (phoneAgent.looksLikePhoneTask(message)) {'''
route='''        if (classifyVisualRequest(message) == "media") {
            openMediaGuide(message)
            return
        }

'''
idx=s.find('    private fun sendMessage() {')
if idx>=0 and 'openMediaGuide(message)' not in s:
    pos=s.find(marker,idx)
    if pos<0:
        local='''        val local = runCatching { actionRouter.handle(message) }.getOrNull()'''
        pos=s.find(local,idx)
    if pos<0: raise SystemExit('sendMessage insertion point not found')
    s=s[:pos]+route+s[pos:]

# Dedicated multimedia renderer using public source cards with thumbnails.
anchor='''    private fun openNewsFast() {'''
methods=r'''    private fun openMediaGuide(query: String) {
        beginWidgetGroup("TV y streaming · hoy")
        addRichInfoWidget("media", "Actualizando programación", "Buscando programación de TV, estrenos y recomendaciones públicas…")
        status.text = "Consultando TV y streaming…"
        Thread {
            try {
                val data = readJson("$BACKEND/api/media?q=${Uri.encode(query)}")
                val items = data.optJSONArray("items") ?: JSONArray()
                val spoken = mutableListOf<String>()
                runOnUiThread {
                    beginWidgetGroup("TV y streaming · hoy")
                    if (items.length() == 0) {
                        addRichInfoWidget("media", "Sin resultados", "No he encontrado programación pública disponible ahora mismo.")
                    } else {
                        for (i in 0 until minOf(items.length(), 12)) {
                            val item = items.optJSONObject(i) ?: continue
                            val section = item.optString("section").ifBlank { "TV" }
                            val title = item.optString("title").ifBlank { "Programación" }
                            val source = item.optString("source")
                            val desc = item.optString("description")
                            val published = item.optString("published")
                            val body = listOf(source, desc, published).filter { it.isNotBlank() }.joinToString(" · ").take(300)
                            addRichInfoWidget("media", "$section · $title", body, item.optString("image"), item.optString("url"))
                            if (spoken.size < 5) spoken += "$section: $title"
                        }
                    }
                    status.text = "Jarvis listo"
                    val summary = if (spoken.isEmpty()) "No he encontrado programación disponible." else spoken.joinToString(". ")
                    saveHistory("assistant", summary, true)
                    safeSpeak(summary)
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    beginWidgetGroup("TV y streaming · hoy")
                    addRichInfoWidget("media", "No se pudo actualizar", e.message ?: "Error consultando programación")
                    status.text = "Jarvis listo"
                }
            }
        }.start()
    }

'''
if 'private fun openMediaGuide(query: String)' not in s:
    if anchor not in s: raise SystemExit('openNewsFast anchor not found')
    s=s.replace(anchor,methods+anchor,1)

p.write_text(s)
print('Dedicated TV/streaming widget guide applied')
