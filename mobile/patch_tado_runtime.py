from pathlib import Path

p = Path('mobile/src/main/java/com/jarvis/mobile/MainActivity.kt')
s = p.read_text()

s = s.replace(
    'val tadoState = if (prefs.getBoolean("tado_enabled", false)) "conectado" else "desactivado"',
    'val tadoState = if (prefs.getString("tado_refresh_token", "").orEmpty().isNotBlank()) "conectado" else "no conectado"'
)

anchor = '''        val local = runCatching { actionRouter.handle(message) }.getOrNull()\n'''
insert = '''        if (isTadoRequest(message)) {\n            handleTadoRequest(message)\n            return\n        }\n\n        val local = runCatching { actionRouter.handle(message) }.getOrNull()\n'''
if 'if (isTadoRequest(message))' not in s and anchor in s:
    s = s.replace(anchor, insert, 1)

marker = '    private fun jsonArrayStrings(a: JSONArray?): List<String> {'
methods = r'''    private fun isTadoRequest(message: String): Boolean {
        val q = message.lowercase()
        val climate = q.contains("aire") || q.contains("clima") || q.contains("temperatura") || q.contains("tado") || q.contains("termostato")
        val intent = q.contains("estado") || q.contains("encend") || q.contains("apag") || q.contains("pon ") ||
            q.contains("ajusta") || q.contains("sube") || q.contains("baja") || q.contains("temperatura") || q.contains("tado") || q.contains("termostato")
        return climate && intent && (q.contains("tado") || prefs.getString("tado_refresh_token", "").orEmpty().isNotBlank())
    }

    private fun tadoGet(url: String, token: String): Pair<Int, String> {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 9000; readTimeout = 15000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
        }
        val code = c.responseCode
        val raw = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        return code to raw
    }

    private fun handleTadoRequest(message: String) {
        if (prefs.getString("tado_refresh_token", "").orEmpty().isBlank()) {
            beginWidgetGroup("Tado")
            addTextWidget("home", "Tado no conectado", "Abre Ajustes de Jarvis → Tado y pulsa Conectar con Tado.")
            status.text = "Jarvis listo"; return
        }
        status.text = "Tado · consultando…"
        Thread {
            try {
                var token = refreshTadoTokenIfNeeded()
                if (token.isBlank()) throw IllegalStateException("No se pudo renovar el acceso a Tado. La cuenta sigue guardada; prueba de nuevo.")
                var meResult = tadoGet("https://my.tado.com/api/v2/me", token)
                if (meResult.first == 401 || meResult.first == 403) {
                    // Never erase the durable refresh token because one access token was rejected.
                    // Force exactly one refresh and retry. Tado rotates refresh tokens, and
                    // refreshTadoTokenIfNeeded persists the replacement atomically.
                    prefs.edit().remove("tado_access_token").putLong("tado_access_expires", 0L).commit()
                    token = refreshTadoTokenIfNeeded()
                    if (token.isNotBlank()) meResult = tadoGet("https://my.tado.com/api/v2/me", token)
                }
                val (meCode, meRaw) = meResult
                if (meCode !in 200..299) throw IllegalStateException("Tado respondió HTTP $meCode. La vinculación se conserva para volver a intentarlo.")
                prefs.edit().putBoolean("tado_enabled", true).commit()
                val me = JSONObject(meRaw)
                val homes = me.optJSONArray("homes") ?: JSONArray()
                if (homes.length() == 0) throw IllegalStateException("Tu cuenta Tado no tiene hogares disponibles")
                val homeId = homes.optJSONObject(0)?.optLong("id", -1L) ?: -1L
                if (homeId <= 0) throw IllegalStateException("No se pudo identificar el hogar Tado")
                prefs.edit().putLong("tado_home_id", homeId).commit()

                val (zonesCode, zonesRaw) = tadoGet("https://my.tado.com/api/v2/homes/$homeId/zones", token)
                val (statesCode, statesRaw) = tadoGet("https://my.tado.com/api/v2/homes/$homeId/zoneStates", token)
                if (zonesCode !in 200..299 || statesCode !in 200..299) throw IllegalStateException("Tado está conectado, pero no se pudieron leer las zonas ($zonesCode/$statesCode)")
                val zones = JSONArray(zonesRaw); val states = JSONObject(statesRaw)
                val cards = mutableListOf<Triple<String, String, String>>()
                for (i in 0 until zones.length()) {
                    val zone = zones.optJSONObject(i) ?: continue
                    val id = zone.optInt("id", -1); if (id < 0) continue
                    val name = zone.optString("name").ifBlank { "Zona ${i + 1}" }
                    val type = zone.optString("type")
                    val state = states.optJSONObject(id.toString()) ?: continue
                    val setting = state.optJSONObject("setting") ?: JSONObject()
                    val power = setting.optString("power").ifBlank { "—" }
                    val temp = state.optJSONObject("sensorDataPoints")?.optJSONObject("insideTemperature")?.optDouble("celsius", Double.NaN) ?: Double.NaN
                    val target = setting.optJSONObject("temperature")?.optDouble("celsius", Double.NaN) ?: Double.NaN
                    val humidity = state.optJSONObject("sensorDataPoints")?.optJSONObject("humidity")?.optDouble("percentage", Double.NaN) ?: Double.NaN
                    val mode = setting.optString("mode")
                    val detail = buildString {
                        append(if (power == "ON") "Encendido" else if (power == "OFF") "Apagado" else power)
                        if (mode.isNotBlank()) append(" · $mode")
                        if (!temp.isNaN()) append(" · ${String.format(Locale.getDefault(), "%.1f", temp)} °C")
                        if (!target.isNaN()) append(" · objetivo ${String.format(Locale.getDefault(), "%.1f", target)} °C")
                        if (!humidity.isNaN()) append(" · ${humidity.toInt()}% humedad")
                    }
                    cards += Triple(name, if (type.isBlank()) "Tado" else type.replace('_', ' ').lowercase().replaceFirstChar { it.titlecase() }, detail)
                }
                runOnUiThread {
                    beginWidgetGroup("Tado · climatización")
                    if (cards.isEmpty()) addTextWidget("home", "Tado conectado", "Cuenta vinculada. No se han encontrado zonas visibles en esta cuenta.")
                    else cards.forEach { (name, type, detail) -> addTextWidget("home", name, "$type · $detail") }
                    status.text = "Jarvis listo"
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    beginWidgetGroup("Tado")
                    addTextWidget("home", "Tado sigue vinculado", e.message ?: "Error temporal")
                    status.text = "Jarvis listo"
                }
            }
        }.start()
    }

'''
if 'private fun isTadoRequest' not in s:
    if marker not in s: raise SystemExit('jsonArrayStrings marker not found')
    s = s.replace(marker, methods + marker, 1)

p.write_text(s)
print('Tado durable OAuth session and thermostat widgets applied')
