from pathlib import Path

p = Path('mobile/src/main/java/com/jarvis/mobile/MainActivity.kt')
s = p.read_text()

marker = '    private fun jsonArrayStrings(a: JSONArray?): List<String> {'
methods = r'''    private fun handleDirectTadoCommand(message: String): Boolean {
        val q = message.lowercase()
        val linked = prefs.getString("tado_refresh_token", "").orEmpty().isNotBlank()
        if (!linked) return false
        val climate = q.contains("aire acondicionado") || q.contains("termostato") || q.contains("tado") || q.contains("clima") || q.contains("temperatura")
        val action = q.contains("enciend") || q.contains("apag") || q.contains("sube") || q.contains("baja") || q.contains("pon ") || q.contains("ajusta") || q.contains("estado") || q.contains("temperatura")
        if (!climate || !action) return false

        if (q.contains("estado") || (q.contains("temperatura") && !q.contains("pon ") && !q.contains("sube") && !q.contains("baja") && !q.contains("ajusta"))) {
            showTadoDevicesWidget()
            return true
        }

        status.text = "Tado · ejecutando…"
        Thread {
            try {
                val token = refreshTadoTokenIfNeeded()
                if (token.isBlank()) throw IllegalStateException("Sesión Tado no disponible")
                var homeId = prefs.getLong("tado_home_id", -1L)
                if (homeId <= 0) {
                    val (meCode, meRaw) = tadoRequest("GET", "https://my.tado.com/api/v2/me", token)
                    if (meCode !in 200..299) throw IllegalStateException("Tado HTTP $meCode")
                    val homes = JSONObject(meRaw).optJSONArray("homes") ?: JSONArray()
                    homeId = homes.optJSONObject(0)?.optLong("id", -1L) ?: -1L
                    if (homeId <= 0) throw IllegalStateException("No encuentro la casa Tado")
                    prefs.edit().putLong("tado_home_id", homeId).apply()
                }
                val zones = fetchTadoZones(token, homeId)
                val zone = zones.firstOrNull { it.type.equals("AIR_CONDITIONING", true) }
                    ?: zones.firstOrNull { it.type.equals("HEATING", true) }
                    ?: zones.firstOrNull()
                    ?: throw IllegalStateException("Tado no devuelve zonas controlables")

                runOnUiThread {
                    when {
                        q.contains("enciend") -> setTadoZonePower(zone, true)
                        q.contains("apag") -> setTadoZonePower(zone, false)
                        q.contains("sube") -> adjustTadoZoneTemp(zone, 1.0)
                        q.contains("baja") -> adjustTadoZoneTemp(zone, -1.0)
                        else -> {
                            val m = Regex("(\\d{1,2}(?:[.,]\\d+)?)\\s*(?:°|grados?)?").find(q)
                            val requested = m?.groupValues?.getOrNull(1)?.replace(',', '.')?.toDoubleOrNull()
                            if (requested != null) {
                                val base = if (!zone.target.isNaN()) zone.target else if (!zone.current.isNaN()) zone.current else if (zone.type == "AIR_CONDITIONING") 24.0 else 21.0
                                adjustTadoZoneTemp(zone, requested - base)
                            } else showTadoDevicesWidget()
                        }
                    }
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    beginWidgetGroup("Tado")
                    addTextWidget("home", "No se pudo ejecutar la orden", e.message ?: "Error desconocido")
                    status.text = "Jarvis listo"
                }
            }
        }.start()
        return true
    }

'''
if 'private fun handleDirectTadoCommand' not in s:
    if marker not in s:
        raise SystemExit('jsonArrayStrings marker not found')
    s = s.replace(marker, methods + marker, 1)

# Insert as late as possible in sendMessage, after the user message has been stored,
# so climate actions never reach the LLM/backend.
send_start = s.find('    private fun sendMessage() {')
if send_start < 0:
    raise SystemExit('sendMessage not found')
anchor = '        saveHistory("user", message, false)\n'
pos = s.find(anchor, send_start)
if pos >= 0 and 'handleDirectTadoCommand(message)' not in s[send_start:s.find('\n    private fun ', send_start + 10) if s.find('\n    private fun ', send_start + 10) > 0 else len(s)]:
    insert_at = pos + len(anchor)
    s = s[:insert_at] + '        if (handleDirectTadoCommand(message)) return\n' + s[insert_at:]

p.write_text(s)
print('Direct Tado commands routed before LLM')
