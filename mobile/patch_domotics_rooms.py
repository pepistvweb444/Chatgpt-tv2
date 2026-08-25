from pathlib import Path

p = Path('mobile/src/main/java/com/jarvis/mobile/MainActivity.kt')
s = p.read_text()

marker = '    private fun jsonArrayStrings(a: JSONArray?): List<String> {'
if marker not in s:
    raise SystemExit('jsonArrayStrings marker not found')

methods = r'''    private val domoticsDefaultRooms = listOf(
        "Salón", "Dormitorio", "Cocina", "Hall", "Paso",
        "Dormitorio 1", "Dormitorio 2", "Dormitorio 3",
        "Baño", "Baño 1", "Baño dormitorio", "Sin asignar"
    )

    private fun domoticsRoomKey(deviceKey: String) = "domotics_room_" + deviceKey

    private fun roomForDevice(deviceKey: String): String =
        prefs.getString(domoticsRoomKey(deviceKey), "Sin asignar").orEmpty().ifBlank { "Sin asignar" }

    private fun showRoomAssignmentDialog(deviceKey: String, deviceName: String) {
        val custom = prefs.getStringSet("domotics_custom_rooms", emptySet()).orEmpty().toList().sorted()
        val rooms = (domoticsDefaultRooms.dropLast(1) + custom + "Sin asignar" + "＋ Nueva estancia").distinct()
        AlertDialog.Builder(this)
            .setTitle("Ubicar · $deviceName")
            .setItems(rooms.toTypedArray()) { _, index ->
                val selected = rooms[index]
                if (selected == "＋ Nueva estancia") {
                    val input = android.widget.EditText(this).apply { hint = "Nombre de la estancia"; setSingleLine(true) }
                    AlertDialog.Builder(this).setTitle("Nueva estancia").setView(input)
                        .setPositiveButton("Guardar") { _, _ ->
                            val name = input.text?.toString()?.trim().orEmpty()
                            if (name.isNotBlank()) {
                                val next = prefs.getStringSet("domotics_custom_rooms", emptySet()).orEmpty().toMutableSet().apply { add(name) }
                                prefs.edit().putStringSet("domotics_custom_rooms", next).putString(domoticsRoomKey(deviceKey), name).apply()
                                Toast.makeText(this, "$deviceName · $name", Toast.LENGTH_SHORT).show()
                                showUnifiedDomoticsWidget()
                                refreshDomoticsQuickCard()
                            }
                        }.setNegativeButton("Cancelar", null).show()
                } else {
                    prefs.edit().putString(domoticsRoomKey(deviceKey), selected).apply()
                    Toast.makeText(this, "$deviceName · $selected", Toast.LENGTH_SHORT).show()
                    showUnifiedDomoticsWidget()
                    refreshDomoticsQuickCard()
                }
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun addDomoticsRoomHeader(room: String, count: Int) {
        addTextWidget("home", room.uppercase(), "$count dispositivo${if (count == 1) "" else "s"}")
    }

'''
if 'private val domoticsDefaultRooms' not in s:
    s = s.replace(marker, methods + marker, 1)

# Add explicit room assignment controls to each provider card when matching controls exist.
s = s.replace(
    'button("Auto") { resetTadoZone(z) }',
    'button("Auto") { resetTadoZone(z) }\n        button("Ubicar") { showRoomAssignmentDialog("tado:${z.id}", z.name) }'
)
s = s.replace(
    'b("Modo") { AlertDialog.Builder(this).setTitle("${d.name} · modo").setItems(arrayOf("cool","heat","auto","dry","fan")){_,i-> sensiboAction(d,"mode",arrayOf("cool","heat","auto","dry","fan")[i])}.show() }',
    'b("Modo") { AlertDialog.Builder(this).setTitle("${d.name} · modo").setItems(arrayOf("cool","heat","auto","dry","fan")){_,i-> sensiboAction(d,"mode",arrayOf("cool","heat","auto","dry","fan")[i])}.show() }\n        b("Ubicar") { showRoomAssignmentDialog("sensibo:${d.id}", d.name) }'
)
s = s.replace(
    'b("Estado") { showHomeConnectDeviceDetails(d) }',
    'b("Estado") { showHomeConnectDeviceDetails(d) }\n        b("Ubicar") { showRoomAssignmentDialog("homeconnect:${d.haId}", d.name) }'
)

def replace_function(text: str, signature: str, replacement: str) -> str:
    start = text.find(signature)
    if start < 0:
        raise SystemExit(f'{signature} not found')
    brace = text.find('{', start)
    depth = 0; in_string = False; escape = False; i = brace
    while i < len(text):
        ch = text[i]
        if in_string:
            if escape: escape = False
            elif ch == '\\': escape = True
            elif ch == '"': in_string = False
        else:
            if ch == '"': in_string = True
            elif ch == '{': depth += 1
            elif ch == '}':
                depth -= 1
                if depth == 0: return text[:start] + replacement + text[i+1:]
        i += 1
    raise SystemExit('function end not found')

replacement = r'''    private fun showUnifiedDomoticsWidget() {
        status.text = "Domótica · actualizando dispositivos…"
        Thread {
            var tadoZones: List<TadoZoneCard> = emptyList()
            var hcDevices: List<HcDeviceCard> = emptyList()
            var sensiboDevices: List<SensiboCard> = emptyList()
            val errors = mutableListOf<String>()

            if (prefs.getString("tado_refresh_token", "").orEmpty().isNotBlank()) {
                runCatching {
                    val token = refreshTadoTokenIfNeeded(); if (token.isBlank()) error("sesión no válida")
                    var homeId = prefs.getLong("tado_home_id", -1L)
                    if (homeId <= 0) {
                        val (c, r) = tadoRequest("GET", "https://my.tado.com/api/v2/me", token)
                        if (c !in 200..299) error("HTTP $c")
                        homeId = JSONObject(r).optJSONArray("homes")?.optJSONObject(0)?.optLong("id", -1L) ?: -1L
                        if (homeId <= 0) error("sin casa")
                        prefs.edit().putLong("tado_home_id", homeId).apply()
                    }
                    tadoZones = fetchTadoZones(token, homeId)
                }.onFailure { errors += "Tado: ${it.message}" }
            }
            if (prefs.getString("homeconnect_refresh_token", "").orEmpty().isNotBlank()) {
                runCatching {
                    val token = refreshHomeConnectTokenIfNeeded(); if (token.isBlank()) error("sesión no válida")
                    hcDevices = fetchHomeConnectDevices(token)
                }.onFailure { errors += "Home Connect: ${it.message}" }
            }
            runCatching { sensiboDevices = fetchSensiboDevices() }
                .onFailure { if (!it.message.orEmpty().contains("no configurada", true)) errors += "Sensibo: ${it.message}" }

            runOnUiThread {
                beginWidgetGroup("Domótica · Casa")
                data class RoomEntry(val room: String, val provider: String, val key: String, val name: String, val item: Any)
                val entries = mutableListOf<RoomEntry>()
                tadoZones.forEach { entries += RoomEntry(roomForDevice("tado:${it.id}"), "tado", "tado:${it.id}", it.name, it) }
                sensiboDevices.forEach { entries += RoomEntry(roomForDevice("sensibo:${it.id}"), "sensibo", "sensibo:${it.id}", it.name, it) }
                hcDevices.forEach { entries += RoomEntry(roomForDevice("homeconnect:${it.haId}"), "homeconnect", "homeconnect:${it.haId}", it.name, it) }

                if (entries.isNotEmpty()) {
                    val organizer = TextView(this).apply {
                        text = "⌂  CONFIGURAR ESTANCIAS\nPulsa aquí para asignar o cambiar la habitación de cada equipo"
                        textSize = 15f
                        setTextColor(Color.WHITE)
                        setPadding(dp(18), dp(15), dp(18), dp(15))
                        background = cardBackground("home")
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) }
                        setOnClickListener {
                            val names = entries.map { "${it.name} · ${if (it.room == "Sin asignar") "Sin asignar" else it.room}" }.toTypedArray()
                            AlertDialog.Builder(this@MainActivity).setTitle("Dispositivo a ubicar").setItems(names) { _, index ->
                                val e = entries[index]
                                showRoomAssignmentDialog(e.key, e.name)
                            }.setNegativeButton("Cerrar", null).show()
                        }
                    }
                    widgetHost.addView(organizer)
                }

                val configured = domoticsDefaultRooms.dropLast(1) + prefs.getStringSet("domotics_custom_rooms", emptySet()).orEmpty().sorted() + "Sin asignar"
                val grouped = entries.groupBy { it.room }
                configured.distinct().forEach { room ->
                    val group = grouped[room].orEmpty()
                    if (group.isNotEmpty()) {
                        addDomoticsRoomHeader(room, group.size)
                        group.forEach { e ->
                            when (e.provider) {
                                "tado" -> addTadoControlWidget(e.item as TadoZoneCard)
                                "sensibo" -> addSensiboControlWidget(e.item as SensiboCard)
                                "homeconnect" -> addHomeConnectDeviceWidget(e.item as HcDeviceCard)
                            }
                        }
                    }
                }
                if (entries.isEmpty()) addTextWidget("home", "Domótica", "No hay dispositivos disponibles todavía. Abre Conexiones para autorizar tus servicios.")
                errors.take(3).forEach { addTextWidget("home", "Aviso de conexión", it) }
                status.text = "Jarvis listo"
                refreshDomoticsQuickCard()
            }
        }.start()
    }
'''
s = replace_function(s, '    private fun showUnifiedDomoticsWidget()', replacement)
p.write_text(s)
print('Domotics room grouping + always-visible room organizer applied')
