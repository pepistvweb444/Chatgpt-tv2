from pathlib import Path

p = Path('mobile/src/main/java/com/jarvis/mobile/MainActivity.kt')
s = p.read_text()

if 'import android.os.Build' not in s:
    s = s.replace('import android.os.Bundle\n', 'import android.os.Build\nimport android.os.Bundle\n')
if 'import android.provider.Settings' not in s:
    s = s.replace('import android.os.Bundle\n', 'import android.os.Bundle\nimport android.provider.Settings\n')

# Extend settings hub created by patch_agents_tado.py.
s = s.replace(
'''        val items = arrayOf(
            "Agentes · $agentsState",
            "Tado · $tadoState",
            "Voz de Jarvis",
            "Complementos y MCP"
        )''',
'''        val mcpCount = runCatching { JSONArray(prefs.getString("custom_mcps", "[]")).length() }.getOrDefault(0)
        val items = arrayOf(
            "Agentes · $agentsState",
            "Tado · $tadoState",
            "MCPs · $mcpCount configurados",
            "Permisos del teléfono",
            "Voz de Jarvis",
            "Complementos"
        )'''
)
s = s.replace(
'''                when (which) {
                    0 -> showAgentSettings()
                    1 -> showTadoSettings()
                    2 -> showVoiceSettings()
                    3 -> showConnections()
                }''',
'''                when (which) {
                    0 -> showAgentSettings()
                    1 -> showTadoSettings()
                    2 -> showMcpSettings()
                    3 -> showPhonePermissions()
                    4 -> showVoiceSettings()
                    5 -> showToolPicker()
                }'''
)

# Make the existing Complementos/MCP entry open the real MCP manager.
s = s.replace(
'private fun showConnections() { AlertDialog.Builder(this).setTitle("Complementos y MCP").setMessage("Usa el botón + junto al campo de texto para elegir las aplicaciones y MCP con las que quieres hablar.").setPositiveButton("Aceptar", null).show() }',
'private fun showConnections() { showMcpSettings() }'
)

# Send enabled custom MCP servers with every chat request, in addition to Tado.
needle = '                body.put("clientMcps", clientMcps)'
replacement = '''                val customMcps = runCatching { JSONArray(prefs.getString("custom_mcps", "[]")) }.getOrElse { JSONArray() }
                for (i in 0 until customMcps.length()) {
                    val item = customMcps.optJSONObject(i) ?: continue
                    if (!item.optBoolean("enabled", true)) continue
                    val label = item.optString("name").trim()
                    val url = item.optString("url").trim()
                    if (label.isBlank() || !url.startsWith("https://")) continue
                    val server = JSONObject()
                        .put("server_label", label)
                        .put("server_url", url)
                        .put("require_approval", item.optString("approval", "always"))
                    val auth = item.optString("authorization").trim()
                    if (auth.isNotBlank()) server.put("authorization", auth)
                    clientMcps.put(server)
                }
                body.put("clientMcps", clientMcps)'''
if needle in s and 'val customMcps = runCatching' not in s:
    s = s.replace(needle, replacement)

marker = '    private fun showConnections() {'
if marker not in s:
    raise SystemExit('showConnections marker not found')

functions = r'''    private fun showMcpSettings() {
        val arr = runCatching { JSONArray(prefs.getString("custom_mcps", "[]")) }.getOrElse { JSONArray() }
        val labels = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val state = if (o.optBoolean("enabled", true)) "●" else "○"
            labels.add("$state ${o.optString("name", "MCP")}")
        }
        labels.add("＋ Añadir MCP")
        AlertDialog.Builder(this)
            .setTitle("MCPs")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which >= arr.length()) editMcp(-1) else editMcp(which)
            }
            .setNeutralButton("Herramientas") { _, _ -> showToolPicker() }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun editMcp(index: Int) {
        val arr = runCatching { JSONArray(prefs.getString("custom_mcps", "[]")) }.getOrElse { JSONArray() }
        val current = if (index in 0 until arr.length()) arr.optJSONObject(index) ?: JSONObject() else JSONObject()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val enabled = CheckBox(this).apply { text = "Activado"; setTextColor(Color.WHITE); isChecked = current.optBoolean("enabled", true) }
        val name = EditText(this).apply { hint = "Nombre del MCP"; setText(current.optString("name")); setTextColor(Color.WHITE); setHintTextColor(Color.GRAY) }
        val url = EditText(this).apply { hint = "URL HTTPS del servidor MCP"; setText(current.optString("url")); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY) }
        val auth = EditText(this).apply { hint = "Authorization / token (opcional)"; setText(current.optString("authorization")); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY) }
        val approval = CheckBox(this).apply { text = "No pedir aprobación para cada acción"; setTextColor(Color.WHITE); isChecked = current.optString("approval", "always") == "never" }
        box.addView(enabled); box.addView(name); box.addView(url); box.addView(auth); box.addView(approval)
        val builder = AlertDialog.Builder(this)
            .setTitle(if (index < 0) "Añadir MCP" else "Editar MCP")
            .setView(box)
            .setPositiveButton("Guardar") { _, _ ->
                val n = name.text.toString().trim()
                val u = url.text.toString().trim()
                if (n.isBlank() || !u.startsWith("https://")) {
                    Toast.makeText(this, "Indica nombre y una URL HTTPS válida", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val item = JSONObject()
                    .put("name", n)
                    .put("url", u)
                    .put("authorization", auth.text.toString().trim())
                    .put("approval", if (approval.isChecked) "never" else "always")
                    .put("enabled", enabled.isChecked)
                if (index in 0 until arr.length()) arr.put(index, item) else arr.put(item)
                prefs.edit().putString("custom_mcps", arr.toString()).apply()
                Toast.makeText(this, "MCP guardado", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
        if (index in 0 until arr.length()) {
            builder.setNeutralButton("Eliminar") { _, _ ->
                val next = JSONArray()
                for (i in 0 until arr.length()) if (i != index) next.put(arr.optJSONObject(i))
                prefs.edit().putString("custom_mcps", next.toString()).apply()
                Toast.makeText(this, "MCP eliminado", Toast.LENGTH_SHORT).show()
            }
        }
        builder.show()
    }

    private fun showPhonePermissions() {
        val items = arrayOf(
            "Acceso a notificaciones",
            "Accesibilidad · controlar aplicaciones",
            "SMS y RCS",
            "Calendario, citas y recordatorios",
            "Permisos de Jarvis",
            "Notificaciones de Jarvis",
            "Micrófono",
            "Ubicación",
            "Contactos y teléfono"
        )
        AlertDialog.Builder(this)
            .setTitle("Permisos del teléfono")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                    1 -> runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                    2 -> ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS), 1213)
                    3 -> ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR), 1214)
                    4 -> runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
                    5 -> {
                        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1210)
                        } else runCatching { startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)) }
                    }
                    6 -> ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1211)
                    7 -> ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOCATION)
                    8 -> ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.READ_PHONE_STATE, Manifest.permission.CALL_PHONE), 1212)
                }
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

'''
s = s.replace(marker, functions + marker)

p.write_text(s)
print('MCP manager and phone permissions patch applied')