from pathlib import Path

p=Path('mobile/src/main/java/com/jarvis/mobile/MainActivity.kt')
s=p.read_text()

def replace_function(text, signature, replacement):
    start=text.find(signature)
    if start<0:
        print('showUnifiedDomoticsWidget not found; skip safely')
        return text
    brace=text.find('{',start); depth=0; ins=False; esc=False
    for i in range(brace,len(text)):
        ch=text[i]
        if ins:
            if esc: esc=False
            elif ch=='\\': esc=True
            elif ch=='"': ins=False
        else:
            if ch=='"': ins=True
            elif ch=='{': depth+=1
            elif ch=='}':
                depth-=1
                if depth==0: return text[:start]+replacement+text[i+1:]
    return text

replacement=r'''    private fun showUnifiedDomoticsWidget() {
        status.text = "Domótica · actualizando…"
        Thread {
            var tadoZones: List<TadoZoneCard> = emptyList()
            var hcDevices: List<HcDeviceCard> = emptyList()
            var sensiboDevices: List<SensiboCard> = emptyList()
            val errors = mutableListOf<String>()
            runCatching {
                if (prefs.getString("tado_refresh_token", "").orEmpty().isNotBlank()) {
                    val token=refreshTadoTokenIfNeeded(); if(token.isBlank()) error("sesión no válida")
                    var homeId=prefs.getLong("tado_home_id",-1L)
                    if(homeId<=0){ val(c,r)=tadoRequest("GET","https://my.tado.com/api/v2/me",token); if(c !in 200..299) error("HTTP $c"); homeId=JSONObject(r).optJSONArray("homes")?.optJSONObject(0)?.optLong("id",-1L)?:-1L }
                    if(homeId>0) tadoZones=fetchTadoZones(token,homeId)
                }
            }.onFailure{ errors += "Tado: ${it.message}" }
            runCatching {
                if (prefs.getString("homeconnect_refresh_token", "").orEmpty().isNotBlank()) {
                    val token=refreshHomeConnectTokenIfNeeded(); if(token.isBlank()) error("sesión no válida")
                    hcDevices=fetchHomeConnectDevices(token)
                }
            }.onFailure{ errors += "Home Connect: ${it.message}" }
            runCatching { sensiboDevices=fetchSensiboDevices() }.onFailure { if(!it.message.orEmpty().contains("no configurada",true)) errors += "Sensibo: ${it.message}" }

            runOnUiThread {
                beginWidgetGroup("Domótica")
                data class D(val key:String,val room:String,val name:String,val icon:String,val state:String,val click:()->Unit)
                val devices=mutableListOf<D>()
                tadoZones.forEach { z ->
                    val temp=runCatching { z.temperature.toString()+"°" }.getOrDefault("")
                    devices += D("tado:${z.id}",roomForDevice("tado:${z.id}"),z.name,"❄",temp) { addTadoControlWidget(z); scroll.post{scroll.fullScroll(ScrollView.FOCUS_DOWN)} }
                }
                sensiboDevices.forEach { d ->
                    val state=buildString { if(d.on) append("Encendido") else append("Apagado"); if(d.targetTemp!=null) append(" · ${d.targetTemp}°") }
                    devices += D("sensibo:${d.id}",roomForDevice("sensibo:${d.id}"),d.name,"❄",state) { addSensiboControlWidget(d); scroll.post{scroll.fullScroll(ScrollView.FOCUS_DOWN)} }
                }
                hcDevices.forEach { d ->
                    val type=d.type.lowercase()
                    val icon=when { type.contains("washer")||type.contains("washing") -> "🧺"; type.contains("oven") -> "▣"; type.contains("dish") -> "▤"; type.contains("coffee") -> "☕"; type.contains("fridge")||type.contains("freezer") -> "❄"; else -> "⌂" }
                    val state=if(d.connected) "Conectado" else "Sin conexión"
                    devices += D("homeconnect:${d.haId}",roomForDevice("homeconnect:${d.haId}"),d.name,icon,state) { addHomeConnectDeviceWidget(d); scroll.post{scroll.fullScroll(ScrollView.FOCUS_DOWN)} }
                }
                runCatching {
                    val a=JSONArray(prefs.getString("homey_devices_json","[]"))
                    for(i in 0 until a.length()){
                        val o=a.optJSONObject(i)?:continue; val id=o.optString("id"); val name=o.optString("name").ifBlank{"Homey"}; val st=o.optJSONObject("state")?:JSONObject()
                        val on=if(st.has("onoff")&&!st.isNull("onoff")) st.optBoolean("onoff") else null
                        val temp=when { st.has("measure_temperature")&&!st.isNull("measure_temperature") -> "${st.optDouble("measure_temperature")}°"; st.has("target_temperature")&&!st.isNull("target_temperature") -> "${st.optDouble("target_temperature")}°"; else -> "" }
                        val state=(if(on==true) "Encendido" else if(on==false) "Apagado" else "Disponible") + if(temp.isNotBlank()) " · $temp" else ""
                        devices += D("homey:$id",roomForDevice("homey:$id"),name,"💡",state) { startActivity(Intent(this, HomeyActivity::class.java)) }
                    }
                }

                val row=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.TOP; layoutParams=LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{bottomMargin=dp(12)} }
                row.addView(makeAvatar("assistant"))
                val card=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(16),dp(14),dp(16),dp(14)); background=cardBackground("home"); layoutParams=LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f) }
                card.addView(TextView(this).apply { text="⌂  Casa"; textSize=18f; setTextColor(Color.WHITE); setTypeface(typeface,android.graphics.Typeface.BOLD) })
                if(devices.isEmpty()) card.addView(TextView(this).apply { text="No hay dispositivos disponibles"; textSize=14f; setTextColor(Color.rgb(225,235,232)); setPadding(0,dp(10),0,0) })
                devices.forEach { d ->
                    card.addView(TextView(this).apply {
                        text="${d.icon}  ${d.name}   ${d.state}\n     ${d.room}"
                        textSize=14f; setTextColor(Color.WHITE); setPadding(dp(4),dp(10),dp(4),dp(10))
                        setOnClickListener { d.click() }
                    })
                }
                card.addView(TextView(this).apply {
                    text="⚙  Configurar estancias"; textSize=13f; setTextColor(Color.WHITE); setPadding(dp(10),dp(10),dp(10),dp(10)); background=GradientDrawable().apply{cornerRadius=dp(14).toFloat();setColor(Color.rgb(38,83,70))}
                    setOnClickListener {
                        val names=devices.map{"${it.name} · ${it.room}"}.toTypedArray()
                        AlertDialog.Builder(this@MainActivity).setTitle("Dispositivo a ubicar").setItems(names){_,i-> val d=devices[i]; showRoomAssignmentDialog(d.key,d.name)}.setNegativeButton("Cerrar",null).show()
                    }
                })
                errors.take(2).forEach { e -> card.addView(TextView(this).apply{text=e;textSize=11f;setTextColor(Color.LTGRAY);setPadding(0,dp(8),0,0)}) }
                row.addView(card); conversationHost.addView(row)
                scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
                status.text="Jarvis listo"
                refreshDomoticsQuickCard()
            }
        }.start()
    }
'''
s=replace_function(s,'    private fun showUnifiedDomoticsWidget()',replacement)
p.write_text(s)
print('Single unified domotics widget applied')
