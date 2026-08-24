from pathlib import Path

p = Path('app/src/main/java/com/jarvis/tv/MainActivity.kt')
s = p.read_text()

field = '    private val prefs by lazy { getSharedPreferences("jarvis", MODE_PRIVATE) }\n'
if 'private val tvApps by lazy' not in s:
    s = s.replace(field, field + '    private val tvApps by lazy { TvAppController(this) }\n    private val mobileRemote by lazy { MobileRemoteClient(this) }\n', 1)

# Route local TV app/media commands and explicit phone commands before the backend.
old = '''        input.text.clear()
        append("user", text)
        val backend = prefs.getString("backendUrl", DEFAULT_BACKEND)?.trim().orEmpty().ifBlank { DEFAULT_BACKEND }.trimEnd('/')'''
new = '''        input.text.clear()
        append("user", text)

        val localTv = runCatching { tvApps.handle(text) }.getOrNull()
        if (localTv?.handled == true) {
            append("assistant", localTv.message, true)
            status.text = "● TV · acción completada"
            return
        }

        val lower = text.lowercase()
        val phoneIntent = lower.contains("en el móvil") || lower.contains("en el movil") || lower.contains("del móvil") || lower.contains("del movil") || lower.startsWith("móvil ") || lower.startsWith("movil ")
        if (phoneIntent) {
            if (!mobileRemote.configured()) {
                append("assistant", "Primero configura el móvil emparejado en Ajustes de Jarvis TV.", true)
                status.text = "● Móvil no configurado"
                return
            }
            status.text = "● Enviando orden al móvil…"
            Thread {
                try {
                    val result = mobileRemote.sendTask(text)
                    val reply = if (result.optBoolean("ok")) "He enviado la tarea al móvil y Jarvis Mobile la ha aceptado." else result.toString()
                    runOnUiThread { append("assistant", reply, true); status.text = "● Móvil conectado" }
                } catch (e: Exception) {
                    runOnUiThread { append("assistant", "No he podido contactar con el móvil: ${e.message}", true); status.text = "● Error conexión móvil" }
                }
            }.start()
            return
        }

        if ((lower.contains("mensajes") || lower.contains("no leídos") || lower.contains("no leidos")) && mobileRemote.configured()) {
            status.text = "● Consultando mensajes no leídos del móvil…"
            Thread {
                try {
                    val result = mobileRemote.unreadMessages()
                    val items = result.optJSONArray("items") ?: JSONArray()
                    val lines = mutableListOf<String>()
                    for (i in 0 until items.length()) {
                        val item = items.optJSONObject(i) ?: continue
                        lines += "${item.optString("source")} · ${item.optString("from")}: ${item.optString("text")}"
                    }
                    val reply = if (lines.isEmpty()) "No tienes mensajes pendientes detectados en el móvil." else "Mensajes no leídos del móvil:\n\n" + lines.joinToString("\n\n")
                    runOnUiThread { append("assistant", reply, true); status.text = "● Móvil · ${items.length()} pendientes" }
                } catch (e: Exception) {
                    runOnUiThread { append("assistant", "No he podido consultar el móvil: ${e.message}", true); status.text = "● Error conexión móvil" }
                }
            }.start()
            return
        }

        val backend = prefs.getString("backendUrl", DEFAULT_BACKEND)?.trim().orEmpty().ifBlank { DEFAULT_BACKEND }.trimEnd('/')'''
if old in s:
    s = s.replace(old, new, 1)

# Add paired mobile fields and test button to TV settings.
old_settings = '''        val backend = edit("URL de Jarvis Backend", prefs.getString("backendUrl", DEFAULT_BACKEND).orEmpty().ifBlank { DEFAULT_BACKEND })
        val testBackendButton = Button(this).apply { text = "PROBAR BACKEND / OPENAI"; setOnClickListener { testBackend(backend.text.toString()) } }'''
new_settings = '''        val backend = edit("URL de Jarvis Backend", prefs.getString("backendUrl", DEFAULT_BACKEND).orEmpty().ifBlank { DEFAULT_BACKEND })
        val mobileHost = edit("IP o nombre del móvil emparejado", prefs.getString("mobile_remote_host", "").orEmpty())
        val mobileToken = edit("Token Remote de Jarvis Mobile", prefs.getString("mobile_remote_token", "").orEmpty())
        val testBackendButton = Button(this).apply { text = "PROBAR BACKEND / OPENAI"; setOnClickListener { testBackend(backend.text.toString()) } }
        val testMobileButton = Button(this).apply { text = "PROBAR CONEXIÓN CON MÓVIL"; setOnClickListener {
            prefs.edit().putString("mobile_remote_host", mobileHost.text.toString().trim()).putString("mobile_remote_token", mobileToken.text.toString().trim()).apply()
            Thread { runCatching { mobileRemote.ping() }.onSuccess { runOnUiThread { Toast.makeText(this, "Jarvis Mobile conectado", Toast.LENGTH_LONG).show() } }.onFailure { e -> runOnUiThread { Toast.makeText(this, "Móvil: ${e.message}", Toast.LENGTH_LONG).show() } } }.start()
        } }'''
if old_settings in s:
    s = s.replace(old_settings, new_settings, 1)

s = s.replace('box.addView(name); box.addView(wake); box.addView(backend); box.addView(testBackendButton);', 'box.addView(name); box.addView(wake); box.addView(backend); box.addView(mobileHost); box.addView(mobileToken); box.addView(testBackendButton); box.addView(testMobileButton);')

old_save = '''prefs.edit().putString("assistantName", name.text.toString().trim().ifBlank { "Jarvis" }).putString("wakeWord", wake.text.toString().trim().ifBlank { "Hola ChatGPT" }).putString("backendUrl", backend.text.toString().trim().ifBlank { DEFAULT_BACKEND }).apply(); showHome()'''
new_save = '''prefs.edit().putString("assistantName", name.text.toString().trim().ifBlank { "Jarvis" }).putString("wakeWord", wake.text.toString().trim().ifBlank { "Hola ChatGPT" }).putString("backendUrl", backend.text.toString().trim().ifBlank { DEFAULT_BACKEND }).putString("mobile_remote_host", mobileHost.text.toString().trim()).putString("mobile_remote_token", mobileToken.text.toString().trim()).apply(); showHome()'''
if old_save in s:
    s = s.replace(old_save, new_save, 1)

p.write_text(s)
print('TV companion: local apps/media + authenticated mobile remote wired')
