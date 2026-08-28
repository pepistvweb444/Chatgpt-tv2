from pathlib import Path

p=Path('mobile/src/main/java/com/jarvis/mobile/MainActivity.kt')
s=p.read_text()
marker='    private fun jsonArrayStrings(a: JSONArray?): List<String> {'
if marker not in s:
    raise SystemExit('jsonArrayStrings marker not found')

methods=r'''    private data class HueLightCard(val id:String,val name:String,val type:String,val on:Boolean,val bri:Int)

    private fun fetchHueLights(): List<HueLightCard> {
        val ip=prefs.getString("hue_bridge_ip","").orEmpty()
        val user=prefs.getString("hue_username","").orEmpty()
        if(ip.isBlank()||user.isBlank()) return emptyList()
        val c=(URL("http://$ip/api/$user/lights").openConnection() as HttpURLConnection).apply { connectTimeout=4500;readTimeout=6500 }
        val raw=c.inputStream.bufferedReader().use{it.readText()}
        val root=JSONObject(raw)
        prefs.edit().putString("hue_lights_json",root.toString()).apply()
        val out=mutableListOf<HueLightCard>()
        root.keys().forEach { id ->
            val o=root.optJSONObject(id)?:return@forEach
            val st=o.optJSONObject("state")?:JSONObject()
            out += HueLightCard(id,o.optString("name").ifBlank{"Luz Hue $id"},o.optString("type"),st.optBoolean("on",false),st.optInt("bri",0).coerceIn(0,254))
        }
        return out.sortedBy{it.name}
    }

    private fun hueSetState(d:HueLightCard,body:JSONObject) {
        val ip=prefs.getString("hue_bridge_ip","").orEmpty(); val user=prefs.getString("hue_username","").orEmpty()
        if(ip.isBlank()||user.isBlank()) return
        Thread {
            try {
                val c=(URL("http://$ip/api/$user/lights/${d.id}/state").openConnection() as HttpURLConnection).apply { requestMethod="PUT";doOutput=true;connectTimeout=4500;readTimeout=6500;setRequestProperty("Content-Type","application/json") }
                c.outputStream.use{it.write(body.toString().toByteArray())}; c.inputStream.close()
                runOnUiThread { showUnifiedDomoticsWidget() }
            } catch(e:Throwable) { runOnUiThread { Toast.makeText(this,"Hue: ${e.message}",Toast.LENGTH_LONG).show() } }
        }.start()
    }

    private fun deviceGlyph(provider:String,name:String,item:Any): String {
        val blob=(provider+" "+name+" "+item.toString()).lowercase()
        return when {
            blob.contains("light")||blob.contains("luz")||blob.contains("hue")||blob.contains("lamp") -> "💡"
            blob.contains("thermost")||blob.contains("tado") -> "🌡"
            blob.contains("air")||blob.contains("clima")||blob.contains("sensibo")||blob.contains("condition") -> "❄"
            blob.contains("washer")||blob.contains("washing")||blob.contains("lavadora") -> "🧺"
            blob.contains("oven")||blob.contains("horno") -> "🔥"
            blob.contains("dish")||blob.contains("lavavaj") -> "🍽"
            blob.contains("fridge")||blob.contains("refriger")||blob.contains("nevera") -> "🧊"
            blob.contains("plug")||blob.contains("socket")||blob.contains("enchufe") -> "🔌"
            blob.contains("vacuum")||blob.contains("roborock")||blob.contains("aspir") -> "🤖"
            blob.contains("tv")||blob.contains("television") -> "📺"
            else -> "⌂"
        }
    }

    private fun deviceStateText(provider:String,item:Any): String = when(provider) {
        "hue" -> (item as HueLightCard).let { if(it.on) "Encendida · ${((it.bri/254.0)*100).toInt()}%" else "Apagada" }
        "homeconnect" -> (item as HcDeviceCard).let { if(it.connected) "Conectado" else "Sin conexión" }
        "homey" -> (item as JSONObject).optJSONObject("state")?.let { st -> when { st.has("onoff") -> if(st.optBoolean("onoff")) "Encendido" else "Apagado"; st.has("measure_temperature") -> "${st.optDouble("measure_temperature")} °C"; else -> "Disponible" } } ?: "Disponible"
        else -> "Disponible"
    }

    private fun addDeviceThumbnailRow(provider:String,name:String,room:String,item:Any) {
        val row=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(14),dp(10),dp(14),dp(10));background=cardBackground("home");layoutParams=LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{bottomMargin=dp(7)} }
        row.addView(TextView(this).apply { text=deviceGlyph(provider,name,item);textSize=27f;setPadding(0,0,dp(12),0) })
        row.addView(TextView(this).apply { text="$name\n${provider.replaceFirstChar{if(it.isLowerCase())it.titlecase() else it.toString()}} · $room · ${deviceStateText(provider,item)}";textSize=13f;setTextColor(Color.WHITE) },LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
        row.setOnClickListener { showRoomAssignmentDialog(when(provider){"hue"->"hue:${(item as HueLightCard).id}";"homeconnect"->"homeconnect:${(item as HcDeviceCard).haId}";"homey"->"homey:${(item as JSONObject).optString("id")}";else->"$provider:$name"},name) }
        conversationHost.addView(row)
    }

    private fun addHueControlWidget(d:HueLightCard) {
        val row=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL;gravity=Gravity.TOP;layoutParams=LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{bottomMargin=dp(10)} }
        row.addView(makeAvatar("assistant"))
        val card=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(14),dp(16),dp(14));background=cardBackground("home");layoutParams=LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f) }
        card.addView(TextView(this).apply { text="💡 ${d.name}";textSize=17f;setTextColor(Color.WHITE);setTypeface(typeface,android.graphics.Typeface.BOLD) })
        card.addView(TextView(this).apply { text="Philips Hue · ${if(d.on) "Encendida" else "Apagada"} · brillo ${((d.bri/254.0)*100).toInt()}%";textSize=13f;setTextColor(Color.rgb(230,238,244));setPadding(0,dp(5),0,dp(9)) })
        val controls=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        fun b(label:String,action:()->Unit)=controls.addView(TextView(this).apply { text=label;textSize=12f;setTextColor(Color.WHITE);gravity=Gravity.CENTER;setPadding(dp(10),dp(8),dp(10),dp(8));background=GradientDrawable().apply{cornerRadius=dp(13).toFloat();setColor(Color.rgb(38,83,70))};layoutParams=LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{marginEnd=dp(6)};setOnClickListener{action()} })
        b(if(d.on)"Apagar" else "Encender") { hueSetState(d,JSONObject().put("on",!d.on)) }
        b("−") { hueSetState(d,JSONObject().put("on",true).put("bri",(d.bri-35).coerceAtLeast(1))) }
        b("+") { hueSetState(d,JSONObject().put("on",true).put("bri",(d.bri+35).coerceAtMost(254))) }
        b("Ubicar") { showRoomAssignmentDialog("hue:${d.id}",d.name) }
        card.addView(controls);row.addView(card);conversationHost.addView(row)
    }

'''
if 'private data class HueLightCard' not in s:
    s=s.replace(marker,methods+marker,1)

# Extend unified domotics loader with direct Hue Bridge lights.
s=s.replace('''            var sensiboDevices: List<SensiboCard> = emptyList()''','''            var sensiboDevices: List<SensiboCard> = emptyList()
            var hueLights: List<HueLightCard> = emptyList()''',1)
fetch_anchor='''            runCatching { sensiboDevices = fetchSensiboDevices() }
                .onFailure { if (!it.message.orEmpty().contains("no configurada", true)) errors += "Sensibo: ${it.message}" }'''
if fetch_anchor in s and 'hueLights = fetchHueLights()' not in s:
    s=s.replace(fetch_anchor,fetch_anchor+'''\n            if (prefs.getString("hue_bridge_ip", "").orEmpty().isNotBlank() && prefs.getString("hue_username", "").orEmpty().isNotBlank()) {
                runCatching { hueLights = fetchHueLights() }.onFailure { errors += "Philips Hue: ${it.message}" }
            }''',1)
entry_anchor='''                hcDevices.forEach { entries += RoomEntry(roomForDevice("homeconnect:${it.haId}"), "homeconnect", "homeconnect:${it.haId}", it.name, it) }'''
if entry_anchor in s and 'RoomEntry(roomForDevice("hue:' not in s:
    s=s.replace(entry_anchor,entry_anchor+'''\n                hueLights.forEach { entries += RoomEntry(roomForDevice("hue:${it.id}"), "hue", "hue:${it.id}", it.name, it) }''',1)
when_anchor='''                                "homeconnect" -> addHomeConnectDeviceWidget(e.item as HcDeviceCard)'''
if when_anchor in s and '"hue" -> addHueControlWidget' not in s:
    s=s.replace(when_anchor,when_anchor+'''\n                                "hue" -> addHueControlWidget(e.item as HueLightCard)''',1)
# Add thumbnail/type row before each detailed control card.
when_line='''                            when (e.provider) {'''
if when_line in s and 'addDeviceThumbnailRow(e.provider' not in s:
    s=s.replace(when_line,'''                            addDeviceThumbnailRow(e.provider, e.name, e.room, e.item)
                            when (e.provider) {''',1)

p.write_text(s)
print('Direct Hue provider + unified device thumbnails applied')
