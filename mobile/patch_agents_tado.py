from pathlib import Path

p = Path('mobile/src/main/java/com/jarvis/mobile/MainActivity.kt')
s = p.read_text()

# Imports used by the settings panels.
if 'import android.text.InputType' not in s:
    s = s.replace('import android.os.Bundle\n', 'import android.os.Bundle\nimport android.text.InputType\n')
if 'import android.widget.CheckBox' not in s:
    s = s.replace('import android.widget.EditText\n', 'import android.widget.CheckBox\nimport android.widget.EditText\n')

# Ajustes must open the full Jarvis settings hub, not only voice settings.
s = s.replace(
    'findViewById<View>(R.id.voiceSettings).setOnClickListener { showVoiceSettings() }',
    'findViewById<View>(R.id.voiceSettings).setOnClickListener { showSettingsHub() }'
)

# Tado must be selectable as a Jarvis tool again.
s = s.replace(
    'val tools = arrayOf("ChatGPT", "Google Maps", "Home Assistant", "Homey", "Home Connect", "Gmail", "Calendario", "Notion", "WhatsApp", "Otros MCP")',
    'val tools = arrayOf("ChatGPT", "Tado", "Google Maps", "Home Assistant", "Homey", "Home Connect", "Gmail", "Calendario", "Notion", "WhatsApp", "Otros MCP")'
)

# Add agent preferences and Tado integration context to every chat call.
old_body = 'val body = JSONObject().put("message", message).put("conversationId", conversationId).put("client", "jarvis-mobile").put("history", history()).put("selectedTools", selected)'
new_body = '''val body = JSONObject()
                    .put("message", message)
                    .put("conversationId", conversationId)
                    .put("client", "jarvis-mobile")
                    .put("history", history())
                    .put("selectedTools", selected)
                    .put("agentsEnabled", prefs.getBoolean("agents_enabled", true))
                    .put("agentsConfig", JSONObject()
                        .put("research", prefs.getBoolean("agent_research", true))
                        .put("news", prefs.getBoolean("agent_news", true))
                        .put("home", prefs.getBoolean("agent_home", true)))
                val clientMcps = JSONArray()
                if (prefs.getBoolean("tado_enabled", false)) {
                    val tadoUrl = prefs.getString("tado_mcp_url", "").orEmpty().trim()
                    val tadoAuth = prefs.getString("tado_mcp_auth", "").orEmpty().trim()
                    if (tadoUrl.startsWith("https://")) {
                        val tado = JSONObject()
                            .put("server_label", "tado")
                            .put("server_url", tadoUrl)
                            .put("require_approval", "never")
                        if (tadoAuth.isNotBlank()) tado.put("authorization", tadoAuth)
                        clientMcps.put(tado)
                    }
                }
                body.put("clientMcps", clientMcps)'''
if old_body in s:
    s = s.replace(old_body, new_body)

marker = '    private fun showConnections() {'
if marker not in s:
    raise SystemExit('showConnections marker not found')

functions = r'''    private fun showSettingsHub() {
        val tadoState = if (prefs.getBoolean("tado_enabled", false)) "conectado" else "desactivado"
        val agentsState = if (prefs.getBoolean("agents_enabled", true)) "activos" else "desactivados"
        val items = arrayOf(
            "Agentes · $agentsState",
            "Tado · $tadoState",
            "Voz de Jarvis",
            "Complementos y MCP"
        )
        AlertDialog.Builder(this)
            .setTitle("Ajustes de Jarvis")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showAgentSettings()
                    1 -> showTadoSettings()
                    2 -> showVoiceSettings()
                    3 -> showConnections()
                }
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun showAgentSettings() {
        val labels = arrayOf(
            "Activar sistema multiagente",
            "Agente de investigación y web",
            "Agente de noticias",
            "Agente de domótica"
        )
        val checked = booleanArrayOf(
            prefs.getBoolean("agents_enabled", true),
            prefs.getBoolean("agent_research", true),
            prefs.getBoolean("agent_news", true),
            prefs.getBoolean("agent_home", true)
        )
        AlertDialog.Builder(this)
            .setTitle("Agentes de Jarvis")
            .setMultiChoiceItems(labels, checked) { _, which, value -> checked[which] = value }
            .setPositiveButton("Guardar") { _, _ ->
                prefs.edit()
                    .putBoolean("agents_enabled", checked[0])
                    .putBoolean("agent_research", checked[1])
                    .putBoolean("agent_news", checked[2])
                    .putBoolean("agent_home", checked[3])
                    .apply()
                Toast.makeText(this, "Configuración de agentes guardada", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showTadoSettings() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val enabled = CheckBox(this).apply {
            text = "Activar Tado para climatización"
            setTextColor(Color.WHITE)
            isChecked = prefs.getBoolean("tado_enabled", false)
        }
        val url = EditText(this).apply {
            hint = "URL HTTPS del MCP/bridge de Tado"
            setText(prefs.getString("tado_mcp_url", prefs.getString("tado_url", "")))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
        }
        val auth = EditText(this).apply {
            hint = "Authorization/token (opcional)"
            setText(prefs.getString("tado_mcp_auth", prefs.getString("tado_token", "")))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
        }
        box.addView(enabled)
        box.addView(TextView(this).apply {
            text = "Jarvis usará Tado automáticamente cuando le pidas encender, apagar o ajustar el aire acondicionado. Si Tado ya está configurado en el servidor de Jarvis, puedes activar esta opción sin introducir credenciales locales."
            setTextColor(Color.LTGRAY); textSize = 13f; setPadding(0, dp(8), 0, dp(8))
        })
        box.addView(url)
        box.addView(auth)
        AlertDialog.Builder(this)
            .setTitle("Tado · aire acondicionado")
            .setView(box)
            .setPositiveButton("Guardar") { _, _ ->
                prefs.edit()
                    .putBoolean("tado_enabled", enabled.isChecked)
                    .putString("tado_mcp_url", url.text.toString().trim())
                    .putString("tado_mcp_auth", auth.text.toString().trim())
                    .apply()
                val selected = runCatching { JSONArray(prefs.getString("selected_tools", "[]")) }.getOrElse { JSONArray() }
                val names = (0 until selected.length()).map { selected.optString(it) }.filter { it.isNotBlank() }.toMutableList()
                if (enabled.isChecked && !names.contains("Tado")) names.add("Tado")
                if (!enabled.isChecked) names.remove("Tado")
                prefs.edit().putString("selected_tools", JSONArray(names).toString()).apply()
                restoreSelectedTools()
                Toast.makeText(this, if (enabled.isChecked) "Tado activado" else "Tado desactivado", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Probar") { _, _ ->
                input.setText("Dime el estado del aire acondicionado de Tado")
                sendMessage()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

'''
s = s.replace(marker, functions + marker)

p.write_text(s)
print('Agents and Tado settings patch applied')
