from pathlib import Path

p = Path('mobile/src/main/java/com/jarvis/mobile/MainActivity.kt')
s = p.read_text()

marker = '    private fun jsonArrayStrings(a: JSONArray?): List<String> {'
if marker not in s:
    raise SystemExit('jsonArrayStrings marker not found')

methods = r'''    private data class HcDeviceCard(
        val haId: String,
        val name: String,
        val type: String,
        val connected: Boolean,
        val statusValues: Map<String, String>,
        val settingValues: Map<String, String>,
        val activeProgram: String,
        val programs: List<Pair<String, String>>
    )

    private fun hcBackend(action: String, extra: JSONObject = JSONObject()): JSONObject {
        val c = (URL("$BACKEND/api/domotics/homeconnect-auth").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 10000; readTimeout = 18000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        val body = extra.put("action", action)
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val raw = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        val json = runCatching { JSONObject(raw) }.getOrElse { JSONObject().put("error", raw.take(250)) }
        if (c.responseCode !in 200..299) throw IllegalStateException(json.optString("error_description").ifBlank { json.optString("error").ifBlank { "HTTP ${c.responseCode}" } })
        return json
    }

    private fun showHomeConnectSettings() {
        val connected = prefs.getString("homeconnect_refresh_token", "").orEmpty().isNotBlank()
        AlertDialog.Builder(this)
            .setTitle("Home Connect")
            .setMessage(if (connected) "Cuenta Home Connect vinculada. Jarvis puede leer los electrodomésticos y usar las funciones que cada aparato publique mediante la API." else "Conecta tu cuenta Bosch/Siemens Home Connect mediante el inicio de sesión oficial. Jarvis no guarda tu contraseña.")
            .setPositiveButton(if (connected) "Ver dispositivos" else "Conectar") { _, _ -> if (connected) showHomeConnectDevicesWidget() else beginHomeConnectLogin() }
            .setNeutralButton(if (connected) "Reconectar" else "Finalizar conexión") { _, _ -> if (connected) beginHomeConnectLogin() else completeHomeConnectLogin() }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun beginHomeConnectLogin() {
        status.text = "Home Connect · preparando acceso…"
        Thread {
            try {
                val j = hcBackend("start")
                val device = j.optString("device_code")
                val code = j.optString("user_code")
                val verify = j.optString("verification_uri_complete").ifBlank { j.optString("verification_uri") }
                if (device.isBlank() || verify.isBlank()) throw IllegalStateException("Home Connect no devolvió el código de autorización")
                prefs.edit().putString("homeconnect_device_code", device).putString("homeconnect_user_code", code)
                    .putLong("homeconnect_device_expires", System.currentTimeMillis() + j.optLong("expires_in", 300L) * 1000L).apply()
                runOnUiThread {
                    status.text = "Home Connect · autoriza tu cuenta"
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(verify))) }
                    AlertDialog.Builder(this).setTitle("Autoriza Home Connect")
                        .setMessage("Se ha abierto la página oficial. Autoriza a Jarvis. Código: $code\n\nCuando termines vuelve aquí y pulsa Finalizar conexión.")
                        .setPositiveButton("Finalizar conexión") { _, _ -> completeHomeConnectLogin() }
                        .setNegativeButton("Más tarde", null).show()
                }
            } catch (e: Throwable) {
                runOnUiThread { status.text = "Jarvis listo"; Toast.makeText(this, "Home Connect: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun completeHomeConnectLogin() {
        val device = prefs.getString("homeconnect_device_code", "").orEmpty()
        if (device.isBlank()) { Toast.makeText(this, "Primero pulsa Conectar", Toast.LENGTH_LONG).show(); return }
        status.text = "Home Connect · verificando…"
        Thread {
            try {
                val j = hcBackend("token", JSONObject().put("deviceCode", device))
                val access = j.optString("access_token")
                val refresh = j.optString("refresh_token")
                if (access.isBlank() || refresh.isBlank()) throw IllegalStateException(j.optString("error_description").ifBlank { j.optString("error").ifBlank { "Autorización pendiente" } })
                prefs.edit().putString("homeconnect_access_token", access).putString("homeconnect_refresh_token", refresh)
                    .putLong("homeconnect_access_expires", System.currentTimeMillis() + j.optLong("expires_in", 86400L) * 1000L - 60000L)
                    .remove("homeconnect_device_code").apply()
                runOnUiThread { status.text = "Jarvis listo"; Toast.makeText(this, "Home Connect conectado", Toast.LENGTH_LONG).show(); showHomeConnectDevicesWidget() }
            } catch (e: Throwable) {
                runOnUiThread { status.text = "Jarvis listo"; Toast.makeText(this, "Home Connect: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun refreshHomeConnectTokenIfNeeded(): String {
        val access = prefs.getString("homeconnect_access_token", "").orEmpty()
        if (access.isNotBlank() && prefs.getLong("homeconnect_access_expires", 0L) > System.currentTimeMillis()) return access
        val refresh = prefs.getString("homeconnect_refresh_token", "").orEmpty()
        if (refresh.isBlank()) return ""
        val j = hcBackend("refresh", JSONObject().put("refreshToken", refresh))
        val nextAccess = j.optString("access_token")
        if (nextAccess.isBlank()) return ""
        val nextRefresh = j.optString("refresh_token").ifBlank { refresh }
        prefs.edit().putString("homeconnect_access_token", nextAccess).putString("homeconnect_refresh_token", nextRefresh)
            .putLong("homeconnect_access_expires", System.currentTimeMillis() + j.optLong("expires_in", 86400L) * 1000L - 60000L).apply()
        return nextAccess
    }

    private fun hcApi(method: String, path: String, token: String, body: JSONObject? = null): Pair<Int, String> {
        val c = (URL("https://api.home-connect.com$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method; connectTimeout = 9000; readTimeout = 14000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/vnd.bsh.sdk.v1+json")
            if (body != null) { doOutput = true; setRequestProperty("Content-Type", "application/vnd.bsh.sdk.v1+json") }
        }
        if (body != null) c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        val raw = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        return code to raw
    }

    private fun hcKeyValues(raw: String, arrayName: String): Map<String, String> {
        val root = runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
        val arr = root.optJSONObject("data")?.optJSONArray(arrayName) ?: JSONArray()
        val out = linkedMapOf<String, String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val key = o.optString("key")
            val v = o.opt("value")?.toString().orEmpty()
            if (key.isNotBlank()) out[key] = v
        }
        return out
    }

    private fun hcFriendlyKey(key: String): String = key.substringAfterLast('.').replace(Regex("([a-z])([A-Z])"), "$1 $2")
    private fun hcFriendlyValue(v: String): String = v.substringAfterLast('.').replace(Regex("([a-z])([A-Z])"), "$1 $2")

    private fun fetchHomeConnectDevices(token: String): List<HcDeviceCard> {
        val (code, raw) = hcApi("GET", "/api/homeappliances", token)
        if (code !in 200..299) throw IllegalStateException("Home Connect HTTP $code")
        val arr = JSONObject(raw).optJSONObject("data")?.optJSONArray("homeappliances") ?: JSONArray()
        val out = mutableListOf<HcDeviceCard>()
        for (i in 0 until arr.length()) {
            val a = arr.optJSONObject(i) ?: continue
            val id = a.optString("haId"); if (id.isBlank()) continue
            val name = a.optString("name").ifBlank { a.optString("brand") + " " + a.optString("type") }
            val type = a.optString("type")
            val connected = a.optBoolean("connected", false)
            val (_, statusRaw) = hcApi("GET", "/api/homeappliances/${java.net.URLEncoder.encode(id, "UTF-8")}/status", token)
            val (_, settingsRaw) = hcApi("GET", "/api/homeappliances/${java.net.URLEncoder.encode(id, "UTF-8")}/settings", token)
            val (activeCode, activeRaw) = hcApi("GET", "/api/homeappliances/${java.net.URLEncoder.encode(id, "UTF-8")}/programs/active", token)
            val active = if (activeCode in 200..299) runCatching { JSONObject(activeRaw).optJSONObject("data")?.optString("key").orEmpty() }.getOrDefault("") else ""
            val (progCode, progRaw) = hcApi("GET", "/api/homeappliances/${java.net.URLEncoder.encode(id, "UTF-8")}/programs/available", token)
            val programs = mutableListOf<Pair<String, String>>()
            if (progCode in 200..299) {
                val pa = runCatching { JSONObject(progRaw).optJSONObject("data")?.optJSONArray("programs") }.getOrNull() ?: JSONArray()
                for (x in 0 until pa.length()) pa.optJSONObject(x)?.let { po -> val k=po.optString("key"); if(k.isNotBlank()) programs += k to hcFriendlyValue(k) }
            }
            out += HcDeviceCard(id, name, type, connected, hcKeyValues(statusRaw, "status"), hcKeyValues(settingsRaw, "settings"), active, programs)
        }
        return out
    }

    private fun showHomeConnectDevicesWidget() {
        status.text = "Home Connect · leyendo electrodomésticos…"
        Thread {
            try {
                val token = refreshHomeConnectTokenIfNeeded()
                if (token.isBlank()) throw IllegalStateException("Vuelve a conectar Home Connect")
                val devices = fetchHomeConnectDevices(token)
                runOnUiThread {
                    beginWidgetGroup("Home Connect · electrodomésticos")
                    if (devices.isEmpty()) addTextWidget("home", "Home Connect", "No hay electrodomésticos disponibles")
                    else devices.forEach { addHomeConnectDeviceWidget(it) }
                    status.text = "Jarvis listo"
                }
            } catch (e: Throwable) {
                runOnUiThread { beginWidgetGroup("Home Connect"); addTextWidget("home", "No puedo leer Home Connect", e.message ?: "Error"); status.text = "Jarvis listo" }
            }
        }.start()
    }

    private fun addHomeConnectDeviceWidget(d: HcDeviceCard) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP; layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) } }
        row.addView(makeAvatar("assistant"))
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16),dp(14),dp(16),dp(14)); background = cardBackground("home"); layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        card.addView(TextView(this).apply { text = d.name; textSize=17f; setTextColor(Color.WHITE); setTypeface(typeface, android.graphics.Typeface.BOLD) })
        val details = mutableListOf<String>()
        details += if (d.connected) "Conectado" else "Sin conexión"
        if (d.type.isNotBlank()) details += d.type
        d.statusValues.entries.take(5).forEach { details += "${hcFriendlyKey(it.key)}: ${hcFriendlyValue(it.value)}" }
        if (d.activeProgram.isNotBlank()) details += "Programa: ${hcFriendlyValue(d.activeProgram)}"
        card.addView(TextView(this).apply { text = details.joinToString(" · "); textSize=13f; setTextColor(Color.rgb(230,238,244)); setPadding(0,dp(6),0,dp(10)) })
        val controls = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.START }
        fun b(label:String, action:()->Unit) = controls.addView(TextView(this).apply { text=label; textSize=12f; setTextColor(Color.WHITE); gravity=Gravity.CENTER; setPadding(dp(10),dp(8),dp(10),dp(8)); background=GradientDrawable().apply{cornerRadius=dp(13).toFloat();setColor(Color.rgb(38,83,70))}; layoutParams=LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{marginEnd=dp(6)}; setOnClickListener{action()} })
        val power = d.settingValues["BSH.Common.Setting.PowerState"].orEmpty()
        if (power.isNotBlank()) b(if (power.endsWith("On")) "Apagar" else "Encender") { setHomeConnectPower(d, !power.endsWith("On")) }
        if (d.programs.isNotEmpty()) b("Programas") { showHomeConnectPrograms(d) }
        b("Estado") { showHomeConnectDeviceDetails(d) }
        card.addView(controls); row.addView(card); widgetHost.addView(row)
    }

    private fun setHomeConnectPower(d: HcDeviceCard, on: Boolean) {
        Thread {
            try {
                val token=refreshHomeConnectTokenIfNeeded(); if(token.isBlank()) throw IllegalStateException("Sesión no disponible")
                val key="BSH.Common.Setting.PowerState"
                val value=if(on) "BSH.Common.EnumType.PowerState.On" else "BSH.Common.EnumType.PowerState.Off"
                val payload=JSONObject().put("data", JSONObject().put("key",key).put("value",value))
                val (code,raw)=hcApi("PUT","/api/homeappliances/${java.net.URLEncoder.encode(d.haId,"UTF-8")}/settings/$key",token,payload)
                if(code !in 200..299) throw IllegalStateException("Home Connect HTTP $code ${raw.take(140)}")
                runOnUiThread { Toast.makeText(this, "${d.name} ${if(on) "encendido" else "apagado"}", Toast.LENGTH_SHORT).show(); showHomeConnectDevicesWidget() }
            } catch(e:Throwable){ runOnUiThread { Toast.makeText(this,"Home Connect: ${e.message}",Toast.LENGTH_LONG).show() } }
        }.start()
    }

    private fun showHomeConnectPrograms(d: HcDeviceCard) {
        val names=d.programs.map{it.second}.toTypedArray()
        AlertDialog.Builder(this).setTitle("${d.name} · programas").setItems(names){_,i ->
            val (key,label)=d.programs[i]
            AlertDialog.Builder(this).setTitle("Iniciar $label").setMessage("Jarvis enviará este programa a ${d.name}. El aparato puede exigir opciones adicionales o Remote Start activado físicamente.")
                .setPositiveButton("Iniciar") { _,_ -> startHomeConnectProgram(d,key,label) }.setNegativeButton("Cancelar",null).show()
        }.setNegativeButton("Cerrar",null).show()
    }

    private fun startHomeConnectProgram(d:HcDeviceCard,key:String,label:String){
        Thread{
            try{
                val token=refreshHomeConnectTokenIfNeeded(); if(token.isBlank()) throw IllegalStateException("Sesión no disponible")
                val payload=JSONObject().put("data",JSONObject().put("key",key))
                val(code,raw)=hcApi("PUT","/api/homeappliances/${java.net.URLEncoder.encode(d.haId,"UTF-8")}/programs/active",token,payload)
                if(code !in 200..299) throw IllegalStateException("HTTP $code ${raw.take(180)}")
                runOnUiThread{Toast.makeText(this,"${d.name}: $label iniciado",Toast.LENGTH_LONG).show();showHomeConnectDevicesWidget()}
            }catch(e:Throwable){runOnUiThread{Toast.makeText(this,"No se pudo iniciar: ${e.message}",Toast.LENGTH_LONG).show()}}
        }.start()
    }

    private fun showHomeConnectDeviceDetails(d:HcDeviceCard){
        val lines=mutableListOf<String>()
        lines += "${d.name} · ${if(d.connected) "conectado" else "sin conexión"}"
        d.statusValues.forEach{(k,v)->lines += "${hcFriendlyKey(k)}: ${hcFriendlyValue(v)}"}
        d.settingValues.forEach{(k,v)->lines += "${hcFriendlyKey(k)}: ${hcFriendlyValue(v)}"}
        if(d.activeProgram.isNotBlank()) lines += "Programa activo: ${hcFriendlyValue(d.activeProgram)}"
        AlertDialog.Builder(this).setTitle(d.name).setMessage(lines.joinToString("\n")).setPositiveButton("Aceptar",null).show()
    }

    private fun isHomeConnectRequest(message:String):Boolean{
        if(prefs.getString("homeconnect_refresh_token","").orEmpty().isBlank()) return false
        val q=message.lowercase()
        return q.contains("home connect")||q.contains("horno")||q.contains("placa")||q.contains("cafetera")||q.contains("frigor")||q.contains("nevera")
    }

    private fun handleHomeConnectCommand(message:String){
        val q=message.lowercase()
        if(!q.contains("enciend")&&!q.contains("apag")){showHomeConnectDevicesWidget();return}
        Thread{
            try{
                val token=refreshHomeConnectTokenIfNeeded(); if(token.isBlank()) throw IllegalStateException("Sesión no disponible")
                val devices=fetchHomeConnectDevices(token)
                val target=devices.firstOrNull{d->
                    val n=(d.name+" "+d.type).lowercase()
                    (q.contains("horno")&&n.contains("oven"))||(q.contains("placa")&&(n.contains("hob")||n.contains("cooktop")))||(q.contains("cafetera")&&n.contains("coffee"))||((q.contains("frigor")||q.contains("nevera"))&&(n.contains("fridge")||n.contains("refriger")))||q.contains(d.name.lowercase())
                } ?: throw IllegalStateException("No encuentro ese aparato en Home Connect")
                val current=target.settingValues["BSH.Common.Setting.PowerState"].orEmpty()
                if(current.isBlank()) throw IllegalStateException("${target.name} no publica control de encendido/apagado remoto")
                runOnUiThread{setHomeConnectPower(target,q.contains("enciend"))}
            }catch(e:Throwable){runOnUiThread{beginWidgetGroup("Home Connect");addTextWidget("home","No se pudo ejecutar",e.message?:"Error")}}
        }.start()
    }

'''
if 'private data class HcDeviceCard' not in s:
    s = s.replace(marker, methods + marker, 1)

# Route Home Connect commands before generic visual/chat handling.
needle = '        saveHistory("user", message, false)\n        when (val kind = classifyVisualRequest(message)) {'
repl = '        saveHistory("user", message, false)\n        if (isHomeConnectRequest(message)) { handleHomeConnectCommand(message); return }\n        when (val kind = classifyVisualRequest(message)) {'
if needle in s and 'if (isHomeConnectRequest(message))' not in s:
    s = s.replace(needle, repl, 1)

# Replace the minimal connections dialog with provider-aware controls.
start = s.find('    private fun showConnections() {')
end = s.find('    private fun showVoiceSettings()', start)
if start >= 0 and end > start:
    replacement = r'''    private fun showConnections() {
        val items = arrayOf("Home Connect", "Tado", "Herramientas / MCP")
        AlertDialog.Builder(this).setTitle("Conexiones").setItems(items) { _, i ->
            when (i) {
                0 -> showHomeConnectSettings()
                1 -> showTadoSettings()
                else -> Toast.makeText(this, "Gestiona los MCP desde Herramientas y complementos", Toast.LENGTH_LONG).show()
            }
        }.setNegativeButton("Cerrar", null).show()
    }
'''
    s = s[:start] + replacement + s[end:]

p.write_text(s)
print('Direct Home Connect OAuth, devices, status and controls applied')
