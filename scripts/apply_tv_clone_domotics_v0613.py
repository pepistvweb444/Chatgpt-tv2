from pathlib import Path


def replace_function(text, signature, replacement):
    start=text.find(signature)
    if start < 0:
        raise SystemExit(f'{signature} not found')
    brace=text.find('{',start)
    depth=0; in_string=False; escape=False
    for i in range(brace,len(text)):
        ch=text[i]
        if in_string:
            if escape: escape=False
            elif ch=='\\': escape=True
            elif ch=='"': in_string=False
        else:
            if ch=='"': in_string=True
            elif ch=='{': depth += 1
            elif ch=='}':
                depth -= 1
                if depth == 0:
                    return text[:start]+replacement+text[i+1:]
    raise SystemExit(f'end not found for {signature}')

p=Path('app/src/main/java/com/jarvis/tv/MainActivity.kt')
s=p.read_text()

# The TV must explicitly request the cloned voice. Do not use the generic Coral/
# Android voice merely because the clone needs more than 900 ms to synthesize.
download=r'''    private fun downloadSpeech(endpoint: String, text: String, outFile: File) {
        val c = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 10000; readTimeout = 90000; doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "audio/mpeg, audio/wav, audio/*")
        }
        val payload = JSONObject()
            .put("text", text)
            .put("provider", "noiz")
            .put("voice", "mi_voz")
            .put("requireClone", true)
            .put("speed", 0.94)
            .toString()
        c.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
        val code=c.responseCode
        if(code !in 200..299) {
            val error=c.errorStream?.bufferedReader()?.use{it.readText()}.orEmpty()
            throw IllegalStateException("Voz clonada HTTP $code ${error.take(120)}")
        }
        c.inputStream.use { inputStream -> outFile.outputStream().use { inputStream.copyTo(it) } }
    }'''
s=replace_function(s,'    private fun downloadSpeech(',download)

speech=r'''    private fun speakWithOpenAI(text: String) {
        val backend=prefs.getString("backendUrl",DEFAULT_BACKEND).orEmpty().ifBlank{DEFAULT_BACKEND}
        Thread {
            val file=File(cacheDir,"jarvis-clone-${System.currentTimeMillis()}.audio")
            try {
                downloadSpeech(resolveEndpoint(backend,"speech"),text,file)
                runOnUiThread {
                    voicePlayer?.stop(); voicePlayer?.release(); voicePlayer=null
                    tts?.stop()
                    voicePlayer=MediaPlayer().apply {
                        setDataSource(file.absolutePath)
                        setOnPreparedListener { player -> status.text="● Jarvis · voz clonada"; player.start() }
                        setOnCompletionListener { player -> player.release(); if(voicePlayer===player)voicePlayer=null; file.delete(); status.text="● Listo" }
                        setOnErrorListener { player,_,_ -> player.release(); if(voicePlayer===player)voicePlayer=null; file.delete(); status.text="● Error reproduciendo voz clonada"; true }
                        prepareAsync()
                    }
                }
            } catch(e:Exception) {
                file.delete()
                runOnUiThread {
                    // Deliberately do NOT switch to Android's stock voice. A failed
                    // clone is preferable to silently changing Jarvis' identity.
                    status.text="● Voz clonada temporalmente no disponible"
                    Toast.makeText(this,"No se pudo cargar la voz clonada de Jarvis: ${e.message}",Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }'''
s=replace_function(s,'    private fun speakWithOpenAI(text: String)',speech)

# Full generic renderer for the canonical snapshot sent by Jarvis Mobile.
domotics=r'''    private fun renderDomoticsFromMobile(data:JSONObject?, backend:JSONObject?) {
        personalWidgetContainer.removeAllViews(); personalWidgetContainer.visibility=View.VISIBLE
        personalWidgetContainer.addView(pText("Jarvis · Domótica del móvil",22f,true,Color.rgb(205,213,255)).apply{setPadding(pDp(4),pDp(6),pDp(4),pDp(10))})
        val items=data?.optJSONArray("items") ?: JSONArray()
        val providerNames=mapOf(
            "tado" to "Tado", "sensibo" to "Sensibo", "homeconnect" to "Home Connect", "homeConnect" to "Home Connect",
            "homey" to "Homey", "googlehome" to "Google Home", "googleHome" to "Google Home", "hue" to "Philips Hue",
            "lgthinq" to "LG ThinQ", "lgThinQ" to "LG ThinQ"
        )
        fun stateText(d:JSONObject):String {
            val st=d.optJSONObject("state") ?: JSONObject()
            val parts=mutableListOf<String>()
            if(d.has("connected")) parts += if(d.optBoolean("connected",true)) "Conectado" else "Sin conexión"
            fun addBool(key:String,on:String="Encendido",off:String="Apagado") { if(st.has(key)&&!st.isNull(key)) parts += if(st.optBoolean(key)) on else off }
            addBool("on"); addBool("onoff")
            val power=st.optString("power"); if(power.isNotBlank()) parts += if(power.equals("ON",true))"Encendido" else if(power.equals("OFF",true))"Apagado" else power
            val mode=st.optString("mode"); if(mode.isNotBlank()) parts += "Modo $mode"
            if(st.has("temperature")&&!st.isNull("temperature")) parts += "${String.format(java.util.Locale.getDefault(),"%.1f",st.optDouble("temperature"))} °C"
            if(st.has("targetTemperature")&&!st.isNull("targetTemperature")) parts += "objetivo ${String.format(java.util.Locale.getDefault(),"%.1f",st.optDouble("targetTemperature"))} °C"
            if(st.has("humidity")&&!st.isNull("humidity")) parts += "${st.optDouble("humidity").toInt()}% humedad"
            if(st.has("brightness")&&!st.isNull("brightness")) parts += "brillo ${st.optInt("brightness")}%"
            val fan=st.optString("fan"); if(fan.isNotBlank()) parts += "ventilador $fan"
            if(parts.isEmpty()) { val type=d.optString("type"); if(type.isNotBlank())parts += type }
            return parts.distinct().take(5).joinToString(" · ")
        }
        if(items.length()>0) {
            val grouped=linkedMapOf<String,MutableList<JSONObject>>()
            for(i in 0 until items.length()) {
                val d=items.optJSONObject(i)?:continue
                val provider=d.optString("provider").ifBlank{"Otro"}
                grouped.getOrPut(provider){mutableListOf()}.add(d)
            }
            grouped.forEach { (provider,list) ->
                addDashboardHeading(providerNames[provider] ?: provider.replaceFirstChar{if(it.isLowerCase())it.titlecase() else it.toString()})
                list.forEach { d ->
                    val name=d.optString("name").ifBlank{"Dispositivo"}
                    val room=d.optString("room").takeIf{it.isNotBlank()&&it!="Sin asignar"}.orEmpty()
                    val state=stateText(d)
                    addDashboardCard(name,listOf(room,state).filter{it.isNotBlank()}.joinToString(" · "),when(provider.lowercase()) {
                        "tado","sensibo" -> Color.rgb(40,63,82)
                        "homey","hue","googlehome" -> Color.rgb(39,68,61)
                        else -> Color.rgb(48,52,70)
                    })
                }
            }
            val updated=data?.optLong("updatedAt",0L)?:0L
            if(updated>0L) addDashboardCard("Sincronización móvil",formatSyncTime(updated,false),Color.rgb(34,40,52))
        } else {
            addDashboardCard("Sin dispositivos recibidos","La TV está conectada al móvil, pero todavía no ha recibido su instantánea domótica. Abre Jarvis Mobile unos segundos para que actualice los dispositivos.")
        }
        val providers=data?.optJSONObject("providers")
        if(providers!=null) {
            addDashboardHeading("Conexiones reutilizadas del móvil")
            val keys=providers.keys(); while(keys.hasNext()) {
                val key=keys.next(); if(providers.optBoolean(key,false)) addDashboardCard(providerNames[key]?:key,"Configurado en Jarvis Mobile",Color.rgb(37,44,58))
            }
        }
        val backendProviders=backend?.optJSONObject("providers")
        if(backendProviders!=null && items.length()==0) {
            val keys=backendProviders.keys(); while(keys.hasNext()) {
                val id=keys.next(); val o=backendProviders.optJSONObject(id)?:continue
                if(o.optBoolean("ready")) addDashboardCard(o.optString("name",id),"Backend conectado",Color.rgb(37,44,58))
            }
        }
    }'''
s=replace_function(s,'    private fun renderDomoticsFromMobile(',domotics)

# If the mobile has just started, allow its startup refresh a brief moment to save
# the canonical snapshot and retry once before rendering an empty screen.
old='''            val data=runCatching{mobileRemote.domotics()}.getOrNull()
            val backend=runCatching{fetchBackendDomoticsStatus()}.getOrNull()
            runOnUiThread { renderDomoticsFromMobile(data,backend); status.text="● Domótica sincronizada" }'''
new='''            var data=runCatching{mobileRemote.domotics()}.getOrNull()
            if((data?.optJSONArray("items")?.length() ?: 0)==0) {
                Thread.sleep(1200L)
                data=runCatching{mobileRemote.domotics()}.getOrNull() ?: data
            }
            val backend=runCatching{fetchBackendDomoticsStatus()}.getOrNull()
            val finalData=data
            runOnUiThread { renderDomoticsFromMobile(finalData,backend); status.text="● Domótica sincronizada desde el móvil" }'''
if old in s: s=s.replace(old,new,1)

s=s.replace('text = "PROBAR VOZ OPENAI"','text = "PROBAR VOZ CLONADA"')
s=s.replace('speakWithOpenAI("Hola. Esta es la voz de Jarvis usando OpenAI.")','speakWithOpenAI("Hola. Esta es la voz clonada de Jarvis.")')
s=s.replace('Ajustes de Jarvis TV v0.6.12','Ajustes de Jarvis TV v0.6.13')
p.write_text(s)
print('Jarvis TV 0.6.13: cloned voice is authoritative + full mobile domotics renderer applied')
