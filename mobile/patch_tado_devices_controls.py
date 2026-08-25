from pathlib import Path

p = Path('mobile/src/main/java/com/jarvis/mobile/MainActivity.kt')
s = p.read_text()

# Add a richer, fault-tolerant Tado device renderer and controller after the existing runtime patch.
marker = '    private fun jsonArrayStrings(a: JSONArray?): List<String> {'
if marker not in s:
    raise SystemExit('jsonArrayStrings marker not found')

methods = r'''    private data class TadoZoneCard(
        val id: Int,
        val name: String,
        val type: String,
        val power: String,
        val mode: String,
        val current: Double,
        val target: Double,
        val humidity: Double,
        val online: Boolean
    )

    private fun tadoRequest(method: String, url: String, token: String, body: JSONObject? = null): Pair<Int, String> {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 9000
            readTimeout = 15000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json;charset=UTF-8")
            }
        }
        if (body != null) c.outputStream.use { it.write(body.toString().toByteArray()) }
        val raw = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        return c.responseCode to raw
    }

    private fun fetchTadoZones(token: String, homeId: Long): List<TadoZoneCard> {
        val (zonesCode, zonesRaw) = tadoRequest("GET", "https://my.tado.com/api/v2/homes/$homeId/zones", token)
        if (zonesCode !in 200..299) throw IllegalStateException("No se pudieron leer las zonas de Tado (HTTP $zonesCode)")
        val zones = JSONArray(zonesRaw)
        val out = mutableListOf<TadoZoneCard>()
        for (i in 0 until zones.length()) {
            val zone = zones.optJSONObject(i) ?: continue
            val id = zone.optInt("id", -1)
            if (id < 0) continue
            val name = zone.optString("name").ifBlank { "Zona ${i + 1}" }
            val type = zone.optString("type").ifBlank { "TADO" }
            val (stateCode, stateRaw) = tadoRequest("GET", "https://my.tado.com/api/v2/homes/$homeId/zones/$id/state", token)
            val state = if (stateCode in 200..299) runCatching { JSONObject(stateRaw) }.getOrElse { JSONObject() } else JSONObject()
            val setting = state.optJSONObject("setting") ?: JSONObject()
            val sensors = state.optJSONObject("sensorDataPoints") ?: JSONObject()
            val temp = sensors.optJSONObject("insideTemperature")?.optDouble("celsius", Double.NaN) ?: Double.NaN
            val humidity = sensors.optJSONObject("humidity")?.optDouble("percentage", Double.NaN) ?: Double.NaN
            val target = setting.optJSONObject("temperature")?.optDouble("celsius", Double.NaN) ?: Double.NaN
            val power = setting.optString("power").ifBlank { "—" }
            val mode = setting.optString("mode")
            val online = state.optJSONObject("link")?.optString("state").orEmpty().equals("ONLINE", true) || stateCode in 200..299
            out += TadoZoneCard(id, name, type, power, mode, temp, target, humidity, online)
        }
        return out
    }

    private fun showTadoDevicesWidget() {
        status.text = "Tado · leyendo dispositivos…"
        Thread {
            try {
                var token = refreshTadoTokenIfNeeded()
                if (token.isBlank()) throw IllegalStateException("No hay una sesión Tado válida")
                var homeId = prefs.getLong("tado_home_id", -1L)
                if (homeId <= 0) {
                    val (meCode, meRaw) = tadoRequest("GET", "https://my.tado.com/api/v2/me", token)
                    if (meCode !in 200..299) throw IllegalStateException("Tado respondió HTTP $meCode")
                    val homes = JSONObject(meRaw).optJSONArray("homes") ?: JSONArray()
                    homeId = homes.optJSONObject(0)?.optLong("id", -1L) ?: -1L
                    if (homeId <= 0) throw IllegalStateException("No encuentro ninguna Casa Tado")
                    prefs.edit().putLong("tado_home_id", homeId).apply()
                }
                val zones = fetchTadoZones(token, homeId)
                runOnUiThread {
                    beginWidgetGroup("Tado · dispositivos")
                    if (zones.isEmpty()) {
                        addTextWidget("home", "Tado conectado", "La cuenta responde correctamente, pero Tado no devuelve zonas V3+/anteriores. Si tu equipo es tado° X, habrá que conectarlo mediante Matter/local en lugar del REST clásico.")
                    } else {
                        zones.forEach { z -> addTadoControlWidget(z) }
                    }
                    status.text = "Jarvis listo"
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    beginWidgetGroup("Tado")
                    addTextWidget("home", "No puedo leer los dispositivos", e.message ?: "Error desconocido")
                    status.text = "Jarvis listo"
                }
            }
        }.start()
    }

    private fun addTadoControlWidget(z: TadoZoneCard) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
        }
        row.addView(makeAvatar("assistant"))
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(15), dp(18), dp(15))
            background = cardBackground("home")
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        card.addView(TextView(this).apply {
            text = z.name
            textSize = 17f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        val details = buildString {
            append(if (z.online) "En línea" else "Sin conexión")
            append(" · ")
            append(if (z.power.equals("ON", true)) "Encendido" else if (z.power.equals("OFF", true)) "Apagado" else z.power)
            if (z.mode.isNotBlank()) append(" · ${z.mode}")
            if (!z.current.isNaN()) append(" · ${String.format(Locale.getDefault(), "%.1f", z.current)} °C")
            if (!z.target.isNaN()) append(" · objetivo ${String.format(Locale.getDefault(), "%.1f", z.target)} °C")
            if (!z.humidity.isNaN()) append(" · ${z.humidity.toInt()}% humedad")
        }
        card.addView(TextView(this).apply { text = details; textSize = 14f; setTextColor(Color.rgb(232, 240, 244)); setPadding(0, dp(6), 0, dp(10)) })
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.START }
        fun button(label: String, action: () -> Unit) = controls.addView(TextView(this).apply {
            text = label; textSize = 13f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Color.rgb(38, 83, 70)) }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(7) }
            setOnClickListener { action() }
        })
        button(if (z.power.equals("ON", true)) "Apagar" else "Encender") { setTadoZonePower(z, !z.power.equals("ON", true)) }
        button("−1°") { adjustTadoZoneTemp(z, -1.0) }
        button("+1°") { adjustTadoZoneTemp(z, 1.0) }
        button("Auto") { resetTadoZone(z) }
        card.addView(controls)
        row.addView(card)
        widgetHost.addView(row)
    }

    private fun setTadoZonePower(z: TadoZoneCard, on: Boolean) {
        Thread {
            try {
                val token = refreshTadoTokenIfNeeded(); val homeId = prefs.getLong("tado_home_id", -1L)
                if (token.isBlank() || homeId <= 0) throw IllegalStateException("Sesión Tado no disponible")
                val setting = JSONObject().put("type", z.type).put("power", if (on) "ON" else "OFF")
                if (on && z.type == "AIR_CONDITIONING") {
                    setting.put("mode", z.mode.ifBlank { "COOL" })
                    val target = if (!z.target.isNaN()) z.target else if (!z.current.isNaN()) z.current else 24.0
                    setting.put("temperature", JSONObject().put("celsius", target))
                } else if (on && z.type == "HEATING") {
                    val target = if (!z.target.isNaN()) z.target else if (!z.current.isNaN()) z.current else 21.0
                    setting.put("temperature", JSONObject().put("celsius", target))
                }
                val payload = JSONObject().put("setting", setting).put("termination", JSONObject().put("typeSkillBasedApp", "MANUAL"))
                val (code, raw) = tadoRequest("PUT", "https://my.tado.com/api/v2/homes/$homeId/zones/${z.id}/overlay", token, payload)
                if (code !in 200..299) throw IllegalStateException("Tado HTTP $code ${raw.take(120)}")
                runOnUiThread { Toast.makeText(this, if (on) "${z.name} encendido" else "${z.name} apagado", Toast.LENGTH_SHORT).show(); showTadoDevicesWidget() }
            } catch (e: Throwable) { runOnUiThread { Toast.makeText(this, "Tado: ${e.message}", Toast.LENGTH_LONG).show() } }
        }.start()
    }

    private fun adjustTadoZoneTemp(z: TadoZoneCard, delta: Double) {
        val base = if (!z.target.isNaN()) z.target else if (!z.current.isNaN()) z.current else if (z.type == "AIR_CONDITIONING") 24.0 else 21.0
        val target = (base + delta).coerceIn(5.0, 30.0)
        Thread {
            try {
                val token = refreshTadoTokenIfNeeded(); val homeId = prefs.getLong("tado_home_id", -1L)
                if (token.isBlank() || homeId <= 0) throw IllegalStateException("Sesión Tado no disponible")
                val setting = JSONObject().put("type", z.type).put("power", "ON").put("temperature", JSONObject().put("celsius", target))
                if (z.type == "AIR_CONDITIONING") setting.put("mode", z.mode.ifBlank { "COOL" })
                val payload = JSONObject().put("setting", setting).put("termination", JSONObject().put("typeSkillBasedApp", "MANUAL"))
                val (code, raw) = tadoRequest("PUT", "https://my.tado.com/api/v2/homes/$homeId/zones/${z.id}/overlay", token, payload)
                if (code !in 200..299) throw IllegalStateException("Tado HTTP $code ${raw.take(120)}")
                runOnUiThread { Toast.makeText(this, "${z.name}: ${String.format(Locale.getDefault(), "%.0f", target)} °C", Toast.LENGTH_SHORT).show(); showTadoDevicesWidget() }
            } catch (e: Throwable) { runOnUiThread { Toast.makeText(this, "Tado: ${e.message}", Toast.LENGTH_LONG).show() } }
        }.start()
    }

    private fun resetTadoZone(z: TadoZoneCard) {
        Thread {
            try {
                val token = refreshTadoTokenIfNeeded(); val homeId = prefs.getLong("tado_home_id", -1L)
                if (token.isBlank() || homeId <= 0) throw IllegalStateException("Sesión Tado no disponible")
                val (code, raw) = tadoRequest("DELETE", "https://my.tado.com/api/v2/homes/$homeId/zones/${z.id}/overlay", token)
                if (code !in 200..299 && code != 204) throw IllegalStateException("Tado HTTP $code ${raw.take(120)}")
                runOnUiThread { Toast.makeText(this, "${z.name}: programación automática", Toast.LENGTH_SHORT).show(); showTadoDevicesWidget() }
            } catch (e: Throwable) { runOnUiThread { Toast.makeText(this, "Tado: ${e.message}", Toast.LENGTH_LONG).show() } }
        }.start()
    }

'''
if 'private data class TadoZoneCard' not in s:
    s = s.replace(marker, methods + marker, 1)

# Force Domotica quick card to use the real Tado device UI whenever Tado is linked.
old = 'findViewById<View>(R.id.homeAutomation).setOnClickListener {\n            sendVisualPrompt("home", "Muéstrame el estado de mi domótica. Devuelve una línea separada por cada dispositivo, escena o dato relevante, sin introducción ni conclusión.")\n        }'
new = 'findViewById<View>(R.id.homeAutomation).setOnClickListener {\n            if (prefs.getString("tado_refresh_token", "").orEmpty().isNotBlank()) showTadoDevicesWidget()\n            else sendVisualPrompt("home", "Muéstrame el estado de mi domótica. Devuelve una línea separada por cada dispositivo, escena o dato relevante, sin introducción ni conclusión.")\n        }'
if old in s:
    s = s.replace(old, new)

# Connection test should also show the rich device controls.
s = s.replace('handleTadoRequest("Dime el estado del aire acondicionado de Tado")', 'showTadoDevicesWidget()')

p.write_text(s)
print('Tado devices and controls patch applied')
