from pathlib import Path
import re

p = Path('mobile/src/main/java/com/jarvis/mobile/MainActivity.kt')
s = p.read_text()
manifest = Path('mobile/src/main/AndroidManifest.xml')
ms = manifest.read_text()

marker = '    private fun jsonArrayStrings(a: JSONArray?): List<String> {'
if marker not in s:
    raise SystemExit('jsonArrayStrings marker not found')

methods = r'''    private data class SensiboCard(
        val id: String,
        val name: String,
        val connected: Boolean,
        val on: Boolean?,
        val mode: String,
        val current: Double,
        val target: Double,
        val humidity: Double,
        val fan: String
    )

    private fun sensiboBackend(method: String = "GET", body: JSONObject? = null): JSONObject {
        val c = (URL("$BACKEND/api/domotics/sensibo").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 9000
            readTimeout = 14000
            setRequestProperty("Accept", "application/json")
            if (body != null) { doOutput = true; setRequestProperty("Content-Type", "application/json") }
        }
        if (body != null) c.outputStream.use { it.write(body.toString().toByteArray()) }
        val raw = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        val json = runCatching { JSONObject(raw) }.getOrElse { JSONObject().put("error", raw.take(250)) }
        if (c.responseCode !in 200..299) throw IllegalStateException(json.optString("error").ifBlank { "Sensibo HTTP ${c.responseCode}" })
        return json
    }

    private fun fetchSensiboDevices(): List<SensiboCard> {
        val arr = sensiboBackend().optJSONArray("devices") ?: JSONArray()
        val out = mutableListOf<SensiboCard>()
        for (i in 0 until arr.length()) {
            val d = arr.optJSONObject(i) ?: continue
            val st = d.optJSONObject("state") ?: JSONObject()
            val m = d.optJSONObject("measurements") ?: JSONObject()
            out += SensiboCard(
                d.optString("id"), d.optString("name").ifBlank { "Sensibo ${i+1}" }, d.optBoolean("connected", true),
                if (st.has("on") && !st.isNull("on")) st.optBoolean("on") else null,
                st.optString("mode"), m.optDouble("temperature", Double.NaN), st.optDouble("targetTemperature", Double.NaN),
                m.optDouble("humidity", Double.NaN), st.optString("fanLevel")
            )
        }
        return out
    }

    private fun sensiboAction(d: SensiboCard, action: String, value: Any? = null) {
        Thread {
            try {
                val body = JSONObject().put("id", d.id).put("action", action)
                when (action) {
                    "power" -> body.put("on", value as Boolean)
                    "temperature" -> body.put("value", value as Double)
                    "mode", "fan" -> body.put("value", value.toString())
                }
                sensiboBackend("POST", body)
                runOnUiThread { Toast.makeText(this, "${d.name} actualizado", Toast.LENGTH_SHORT).show(); showUnifiedDomoticsWidget() }
            } catch (e: Throwable) {
                runOnUiThread { Toast.makeText(this, "Sensibo: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun addSensiboControlWidget(d: SensiboCard) {
        val row = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.TOP; layoutParams=LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{bottomMargin=dp(10)} }
        row.addView(makeAvatar("assistant"))
        val card = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(18),dp(15),dp(18),dp(15)); background=cardBackground("home"); layoutParams=LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f) }
        card.addView(TextView(this).apply { text="${d.name} · Sensibo"; textSize=17f; setTextColor(Color.WHITE); setTypeface(typeface,android.graphics.Typeface.BOLD) })
        val details = buildString {
            append(if(d.connected) "En línea" else "Sin conexión")
            d.on?.let { append(if(it) " · Encendido" else " · Apagado") }
            if(d.mode.isNotBlank()) append(" · ${d.mode}")
            if(!d.current.isNaN()) append(" · ${String.format(java.util.Locale.getDefault(),"%.1f",d.current)} °C")
            if(!d.target.isNaN()) append(" · objetivo ${String.format(java.util.Locale.getDefault(),"%.1f",d.target)} °C")
            if(!d.humidity.isNaN()) append(" · ${d.humidity.toInt()}% humedad")
            if(d.fan.isNotBlank()) append(" · ventilador ${d.fan}")
        }
        card.addView(TextView(this).apply { text=details; textSize=14f; setTextColor(Color.rgb(232,240,244)); setPadding(0,dp(6),0,dp(10)) })
        val controls=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.START}
        fun b(label:String, action:()->Unit)=controls.addView(TextView(this).apply{text=label;textSize=12f;setTextColor(Color.WHITE);gravity=Gravity.CENTER;setPadding(dp(10),dp(8),dp(10),dp(8));background=GradientDrawable().apply{cornerRadius=dp(13).toFloat();setColor(Color.rgb(38,83,70))};layoutParams=LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{marginEnd=dp(6)};setOnClickListener{action()}})
        b(if(d.on==true) "Apagar" else "Encender") { sensiboAction(d,"power",d.on!=true) }
        b("−1°") { sensiboAction(d,"temperature",((if(!d.target.isNaN())d.target else if(!d.current.isNaN())d.current else 24.0)-1.0).coerceIn(16.0,30.0)) }
        b("+1°") { sensiboAction(d,"temperature",((if(!d.target.isNaN())d.target else if(!d.current.isNaN())d.current else 24.0)+1.0).coerceIn(16.0,30.0)) }
        b("Modo") { AlertDialog.Builder(this).setTitle("${d.name} · modo").setItems(arrayOf("cool","heat","auto","dry","fan")){_,i-> sensiboAction(d,"mode",arrayOf("cool","heat","auto","dry","fan")[i])}.show() }
        card.addView(controls); row.addView(card); widgetHost.addView(row)
    }

    private fun showUnifiedDomoticsWidget() {
        status.text = "Domótica · actualizando dispositivos…"
        Thread {
            var tadoZones: List<TadoZoneCard> = emptyList()
            var hcDevices: List<HcDeviceCard> = emptyList()
            var sensiboDevices: List<SensiboCard> = emptyList()
            val errors = mutableListOf<String>()

            if (prefs.getString("tado_refresh_token", "").orEmpty().isNotBlank()) {
                runCatching {
                    val token = refreshTadoTokenIfNeeded(); if(token.isBlank()) error("sesión no válida")
                    var homeId = prefs.getLong("tado_home_id", -1L)
                    if(homeId<=0){ val (c,r)=tadoRequest("GET","https://my.tado.com/api/v2/me",token); if(c !in 200..299) error("HTTP $c"); homeId=JSONObject(r).optJSONArray("homes")?.optJSONObject(0)?.optLong("id",-1L)?:-1L; if(homeId<=0) error("sin casa"); prefs.edit().putLong("tado_home_id",homeId).apply() }
                    tadoZones = fetchTadoZones(token,homeId)
                }.onFailure { errors += "Tado: ${it.message}" }
            }

            if (prefs.getString("homeconnect_refresh_token", "").orEmpty().isNotBlank()) {
                runCatching { val token=refreshHomeConnectTokenIfNeeded(); if(token.isBlank()) error("sesión no válida"); hcDevices=fetchHomeConnectDevices(token) }
                    .onFailure { errors += "Home Connect: ${it.message}" }
            }

            runCatching { sensiboDevices=fetchSensiboDevices() }.onFailure { if(!it.message.orEmpty().contains("no configurada",true)) errors += "Sensibo: ${it.message}" }

            runOnUiThread {
                beginWidgetGroup("Domótica · dispositivos")
                var count=0
                if(tadoZones.isNotEmpty()){ addTextWidget("home","Tado","${tadoZones.size} zona(s) conectada(s)"); tadoZones.forEach{addTadoControlWidget(it);count++} }
                if(sensiboDevices.isNotEmpty()){ addTextWidget("home","Sensibo","${sensiboDevices.size} equipo(s) conectado(s)"); sensiboDevices.forEach{addSensiboControlWidget(it);count++} }
                if(hcDevices.isNotEmpty()){ addTextWidget("home","Home Connect","${hcDevices.size} electrodoméstico(s)"); hcDevices.forEach{addHomeConnectDeviceWidget(it);count++} }
                if(count==0) addTextWidget("home","Domótica","No hay dispositivos disponibles todavía. Abre Conexiones para autorizar Tado, Home Connect, Sensibo o Google Home.")
                errors.take(3).forEach { addTextWidget("home","Aviso de conexión",it) }
                status.text="Jarvis listo"
            }
        }.start()
    }

    private fun showGoogleHomeLightsSettings() {
        AlertDialog.Builder(this).setTitle("Google Home · luces")
            .setMessage("Google Home queda reservado únicamente para iluminación. La API key/Project ID identifica el proyecto, pero Android todavía necesita autorización OAuth y permiso de acceso a la casa mediante Home APIs. Cuando se complete ese permiso, Jarvis mostrará aquí todas las luces con encendido, brillo y color disponibles.")
            .setPositiveButton("Aceptar",null).show()
    }

'''
if 'private data class SensiboCard' not in s:
    s = s.replace(marker, methods + marker, 1)

# Unified Domotica quick card replaces the old Tado-only fallback.
patterns = [
'''findViewById<View>(R.id.homeAutomation).setOnClickListener {\n            if (prefs.getString("tado_refresh_token", "").orEmpty().isNotBlank()) showTadoDevicesWidget()\n            else sendVisualPrompt("home", "Muéstrame el estado de mi domótica. Devuelve una línea separada por cada dispositivo, escena o dato relevante, sin introducción ni conclusión.")\n        }''',
'''findViewById<View>(R.id.homeAutomation).setOnClickListener {\n            sendVisualPrompt("home", "Muéstrame el estado de mi domótica. Devuelve una línea separada por cada dispositivo, escena o dato relevante, sin introducción ni conclusión.")\n        }'''
]
for old in patterns:
    if old in s:
        s = s.replace(old, 'findViewById<View>(R.id.homeAutomation).setOnClickListener { showUnifiedDomoticsWidget() }', 1)
        break

# Replace connection chooser after Home Connect patch.
start=s.find('    private fun showConnections() {')
end=s.find('    private fun showVoiceSettings()',start)
if start>=0 and end>start:
    repl=r'''    private fun showConnections() {
        val items=arrayOf("Domótica · todos los dispositivos","Tado","Home Connect","Sensibo","Google Home · luces","Herramientas / MCP")
        AlertDialog.Builder(this).setTitle("Conexiones").setItems(items){_,i->
            when(i){
                0->showUnifiedDomoticsWidget()
                1->showTadoSettings()
                2->showHomeConnectSettings()
                3->showUnifiedDomoticsWidget()
                4->showGoogleHomeLightsSettings()
                else->Toast.makeText(this,"Gestiona los MCP desde Herramientas y complementos",Toast.LENGTH_LONG).show()
            }
        }.setNegativeButton("Cerrar",null).show()
    }
'''
    s=s[:start]+repl+s[end:]

p.write_text(s)
manifest.write_text(ms)
print('Unified domotics hub and Sensibo controls applied')
