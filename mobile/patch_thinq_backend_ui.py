from pathlib import Path
p=Path('mobile/src/main/java/com/jarvis/mobile/LgThinQActivity.kt')
s=p.read_text()

# Route ThinQ through Jarvis backend so PAT/API key can live in Vercel, not the APK.
s=s.replace('''    private fun api(path:String): JSONObject {
        val token=prefs.getString("lg_thinq_pat","").orEmpty(); if(token.isBlank()) throw IllegalStateException("Configura primero el token LG ThinQ")
        val country=prefs.getString("lg_thinq_country","ES").orEmpty().ifBlank{"ES"}
        val c=(URL("https://api-aic.lgthinq.com$path").openConnection() as HttpURLConnection).apply{
            requestMethod="GET";connectTimeout=7000;readTimeout=12000
            setRequestProperty("Authorization","Bearer $token")
            setRequestProperty("x-message-id",UUID.randomUUID().toString())
            setRequestProperty("country",country)
            setRequestProperty("Accept","application/json")
        }
        val raw=(if(c.responseCode in 200..299)c.inputStream else c.errorStream)?.bufferedReader()?.use{it.readText()}.orEmpty()
        if(c.responseCode !in 200..299) throw IllegalStateException("LG ThinQ HTTP ${c.responseCode}: ${raw.take(180)}")
        return JSONObject(raw)
    }''','''    private fun api(path:String): JSONObject {
        val id = if (path.contains("/devices/") && path.endsWith("/state")) path.substringAfter("/devices/").substringBefore("/state") else ""
        val url = if (id.isBlank()) "https://chatgpt-tv2.vercel.app/api/domotics/thinq" else "https://chatgpt-tv2.vercel.app/api/domotics/thinq?id=${Uri.encode(id)}"
        val c=(URL(url).openConnection() as HttpURLConnection).apply{
            requestMethod="GET";connectTimeout=7000;readTimeout=15000;setRequestProperty("Accept","application/json")
        }
        val raw=(if(c.responseCode in 200..299)c.inputStream else c.errorStream)?.bufferedReader()?.use{it.readText()}.orEmpty()
        if(c.responseCode !in 200..299) throw IllegalStateException("LG ThinQ HTTP ${c.responseCode}: ${raw.take(220)}")
        return JSONObject(raw)
    }''')

# Make Vercel-backed setup explicit.
s=s.replace('add("Configurar token LG ThinQ") { configureToken() }','add("Configurar LG ThinQ") { configureToken() }')
s=s.replace('''            .setMessage("Crea un PAT en el portal oficial de LG con permisos de ver dispositivos, estados y control. Jarvis no inventará equipos si LG devuelve una lista vacía.")''','''            .setMessage("Recomendado: configura LG_THINQ_PAT (y si LG los entrega, LG_THINQ_API_KEY / LG_THINQ_CLIENT_ID) en Vercel. Este campo local queda solo como diagnóstico y no es necesario para el backend.")''')

# Parse backend wrapper {devices: ...}.
s=s.replace('''                val root=api("/devices")
                val arr=root.optJSONArray("response") ?: root.optJSONObject("result")?.optJSONArray("devices") ?: JSONArray()''','''                val root=api("/devices")
                val wrapped=root.opt("devices")
                val arr=when(wrapped){
                    is JSONArray -> wrapped
                    is JSONObject -> wrapped.optJSONArray("devices") ?: wrapped.optJSONArray("items") ?: wrapped.optJSONArray("data") ?: JSONArray()
                    else -> root.optJSONArray("response") ?: root.optJSONObject("result")?.optJSONArray("devices") ?: JSONArray()
                }''')

p.write_text(s)
print('LG ThinQ UI routed through Jarvis official backend')

# Apply Philips Hue direct provider after the unified domotics/room/Homey/ThinQ patches
# have already built the final device widget. Keeping this chained here avoids a
# separate workflow step being accidentally lost in later workflow edits.
hue_patch=Path('mobile/patch_hue_unified_widget.py')
if hue_patch.exists():
    exec(compile(hue_patch.read_text(), str(hue_patch), 'exec'))
