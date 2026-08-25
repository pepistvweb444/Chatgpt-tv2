from pathlib import Path

p = Path('mobile/src/main/java/com/jarvis/mobile/MainActivity.kt')
s = p.read_text()

marker = '    private fun jsonArrayStrings(a: JSONArray?): List<String> {'
if marker not in s:
    raise SystemExit('jsonArrayStrings marker not found')

methods = r'''    private fun showDomoticsActionResult(deviceKey: String, deviceName: String, icon: String, stateText: String) {
        val room = roomForDevice(deviceKey)
        beginWidgetGroup("Domótica · ${if (room == "Sin asignar") "Casa" else room}")
        addTextWidget("home", "$icon  $deviceName", buildString {
            if (room != "Sin asignar") append("$room · ")
            append(stateText)
        })
        refreshDomoticsQuickCard()
        status.text = "Jarvis listo"
    }

    private fun refreshDomoticsQuickCard() {
        Thread {
            val lines = mutableListOf<String>()
            runCatching {
                if (prefs.getString("tado_refresh_token", "").orEmpty().isNotBlank()) {
                    val token = refreshTadoTokenIfNeeded()
                    var homeId = prefs.getLong("tado_home_id", -1L)
                    if (token.isNotBlank()) {
                        if (homeId <= 0) {
                            val (c, r) = tadoRequest("GET", "https://my.tado.com/api/v2/me", token)
                            if (c in 200..299) {
                                homeId = JSONObject(r).optJSONArray("homes")?.optJSONObject(0)?.optLong("id", -1L) ?: -1L
                                if (homeId > 0) prefs.edit().putLong("tado_home_id", homeId).apply()
                            }
                        }
                        if (homeId > 0) fetchTadoZones(token, homeId).forEach { z ->
                            val room = roomForDevice("tado:${z.id}")
                            val state = if (z.power.equals("ON", true)) "●" else "○"
                            val temp = if (!z.current.isNaN()) " ${String.format(java.util.Locale.getDefault(), "%.1f°", z.current)}" else ""
                            lines += "$state ${if (room == "Sin asignar") z.name else room}$temp"
                        }
                    }
                }
            }
            runCatching {
                fetchSensiboDevices().forEach { d ->
                    val room = roomForDevice("sensibo:${d.id}")
                    val state = if (d.on == true) "●" else "○"
                    val temp = if (!d.current.isNaN()) " ${String.format(java.util.Locale.getDefault(), "%.1f°", d.current)}" else ""
                    lines += "$state ${if (room == "Sin asignar") d.name else room}$temp"
                }
            }
            runCatching {
                if (prefs.getString("homeconnect_refresh_token", "").orEmpty().isNotBlank()) {
                    val token = refreshHomeConnectTokenIfNeeded()
                    if (token.isNotBlank()) fetchHomeConnectDevices(token).forEach { d ->
                        val room = roomForDevice("homeconnect:${d.haId}")
                        val state = if (d.connected) "●" else "○"
                        lines += "$state ${if (room == "Sin asignar") d.name else room}"
                    }
                }
            }
            runOnUiThread {
                val home = findViewById<TextView>(R.id.homeAutomation)
                val summary = if (lines.isEmpty()) "Control de tu casa" else lines.take(3).joinToString("\n")
                home.text = "⌂  Domótica\n$summary"
            }
        }.start()
    }

'''
if 'private fun refreshDomoticsQuickCard()' not in s:
    s = s.replace(marker, methods + marker, 1)

listener = 'findViewById<View>(R.id.homeAutomation).setOnClickListener { showUnifiedDomoticsWidget() }'
if listener in s:
    s = s.replace(listener, listener + '\n        refreshDomoticsQuickCard()', 1)

start = s.find('    private fun showUnifiedDomoticsWidget()')
if start >= 0:
    end = s.find('    private fun ', start + 20)
    if end < 0: end = len(s)
    block = s[start:end]
    block = block.replace('status.text = "Jarvis listo"', 'status.text = "Jarvis listo"\n                refreshDomoticsQuickCard()', 1)

    old = '''                } else if (grouped["Sin asignar"].orEmpty().isNotEmpty()) {
                    addTextWidget("home", "Organiza tu casa", "Pulsa Ubicar en cada dispositivo para asignarlo a Salón, Dormitorio, Cocina, Hall, Paso, baños u otra estancia.")
                }'''
    new = r'''                } else if (grouped["Sin asignar"].orEmpty().isNotEmpty()) {
                    val pending = entries.filter { it.room == "Sin asignar" }
                    val organize = TextView(this).apply {
                        text = "⌂  Organiza tu casa\n\nPulsa aquí para asignar los ${pending.size} dispositivo(s) a una estancia"
                        textSize = 16f
                        setTextColor(Color.WHITE)
                        setPadding(dp(18), dp(16), dp(18), dp(16))
                        background = cardBackground("home")
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
                        setOnClickListener {
                            val names = pending.map { e ->
                                when (e.provider) {
                                    "tado" -> (e.item as TadoZoneCard).name
                                    "sensibo" -> (e.item as SensiboCard).name
                                    "homeconnect" -> (e.item as HcDeviceCard).name
                                    else -> e.key
                                }
                            }.toTypedArray()
                            AlertDialog.Builder(this@MainActivity).setTitle("Selecciona dispositivo").setItems(names) { _, index ->
                                val e = pending[index]
                                showRoomAssignmentDialog(e.key, names[index])
                            }.setNegativeButton("Cerrar", null).show()
                        }
                    }
                    widgetHost.addView(organize)
                }'''
    if old in block:
        block = block.replace(old, new, 1)
    s = s[:start] + block + s[end:]

# Replace provider success refreshes with a single confirmation widget for the affected device.
s = s.replace(
    'Toast.makeText(this, if (on) "${z.name} encendido" else "${z.name} apagado", Toast.LENGTH_SHORT).show(); showTadoDevicesWidget()',
    'Toast.makeText(this, if (on) "${z.name} encendido" else "${z.name} apagado", Toast.LENGTH_SHORT).show(); showDomoticsActionResult("tado:${z.id}", z.name, if (on) "●" else "○", if (on) "Encendido" else "Apagado")'
)
s = s.replace(
    'Toast.makeText(this, "${z.name}: ${String.format(Locale.getDefault(), "%.0f", target)} °C", Toast.LENGTH_SHORT).show(); showTadoDevicesWidget()',
    'Toast.makeText(this, "${z.name}: ${String.format(Locale.getDefault(), "%.0f", target)} °C", Toast.LENGTH_SHORT).show(); showDomoticsActionResult("tado:${z.id}", z.name, "🌡", "Objetivo ${String.format(Locale.getDefault(), "%.1f", target)} °C")'
)
s = s.replace(
    'Toast.makeText(this, "${z.name}: programación automática", Toast.LENGTH_SHORT).show(); showTadoDevicesWidget()',
    'Toast.makeText(this, "${z.name}: programación automática", Toast.LENGTH_SHORT).show(); showDomoticsActionResult("tado:${z.id}", z.name, "↻", "Programación automática")'
)
s = s.replace(
    'Toast.makeText(this, "${d.name} actualizado", Toast.LENGTH_SHORT).show(); showUnifiedDomoticsWidget()',
    'Toast.makeText(this, "${d.name} actualizado", Toast.LENGTH_SHORT).show(); showDomoticsActionResult("sensibo:${d.id}", d.name, if (action == "power" && value == false) "○" else if (action == "temperature") "🌡" else "●", when (action) { "power" -> if (value == true) "Encendido" else "Apagado"; "temperature" -> "Objetivo ${value} °C"; "mode" -> "Modo ${value}"; "fan" -> "Ventilador ${value}"; else -> "Actualizado" })'
)
s = s.replace(
    'Toast.makeText(this, "${d.name} ${if(on) "encendido" else "apagado"}", Toast.LENGTH_SHORT).show(); showHomeConnectDevicesWidget()',
    'Toast.makeText(this, "${d.name} ${if(on) "encendido" else "apagado"}", Toast.LENGTH_SHORT).show(); showDomoticsActionResult("homeconnect:${d.haId}", d.name, if (on) "●" else "○", if (on) "Encendido" else "Apagado")'
)

p.write_text(s)
print('Domotics quick card + actionable rooms + single action result widgets applied')
