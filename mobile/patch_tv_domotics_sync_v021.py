from pathlib import Path


def replace_function(text, signature, replacement):
    start=text.find(signature)
    if start < 0:
        raise SystemExit(f'{signature} not found')
    brace=text.find('{', start)
    depth=0; in_string=False; escape=False
    for i in range(brace, len(text)):
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
                    return text[:start] + replacement + text[i+1:]
    raise SystemExit(f'function end not found: {signature}')

# ---------------------------------------------------------------------------
# Main mobile UI: maintain one canonical smart-home snapshot for the TV.
# This is populated from the exact same live fetches that feed the mobile UI.
# ---------------------------------------------------------------------------
p=Path('mobile/src/main/java/com/jarvis/mobile/MainActivity.kt')
s=p.read_text()
marker='    private fun jsonArrayStrings(a: JSONArray?): List<String> {'
if marker not in s:
    raise SystemExit('jsonArrayStrings marker missing')

if 'private fun saveTvDomoticsSnapshot(' not in s:
    helper=r'''    private fun saveTvDomoticsSnapshot(
        tadoZones: List<TadoZoneCard>,
        sensiboDevices: List<SensiboCard>,
        hcDevices: List<HcDeviceCard>
    ) {
        val items=JSONArray()
        fun add(provider:String,id:String,name:String,room:String,type:String,state:JSONObject,connected:Boolean=true) {
            items.put(JSONObject()
                .put("provider",provider).put("id",id).put("name",name)
                .put("room",room).put("type",type).put("connected",connected)
                .put("state",state))
        }
        tadoZones.forEach { z ->
            val st=JSONObject().put("power",z.power).put("mode",z.mode).put("online",z.online)
            if(!z.current.isNaN()) st.put("temperature",z.current)
            if(!z.target.isNaN()) st.put("targetTemperature",z.target)
            if(!z.humidity.isNaN()) st.put("humidity",z.humidity)
            add("tado",z.id.toString(),z.name,roomForDevice("tado:${z.id}",z.name),z.type,st,z.online)
        }
        sensiboDevices.forEach { d ->
            val st=JSONObject().put("mode",d.mode).put("fan",d.fan)
            if(d.on!=null) st.put("on",d.on)
            if(!d.current.isNaN()) st.put("temperature",d.current)
            if(!d.target.isNaN()) st.put("targetTemperature",d.target)
            if(!d.humidity.isNaN()) st.put("humidity",d.humidity)
            add("sensibo",d.id,d.name,roomForDevice("sensibo:${d.id}",d.name),"climate",st,d.connected)
        }
        hcDevices.forEach { d ->
            add("homeConnect",d.haId,d.name,roomForDevice("homeconnect:${d.haId}",d.name),d.type,
                JSONObject().put("connected",d.connected),d.connected)
        }

        runCatching {
            val a=JSONArray(prefs.getString("homey_devices_json","[]"))
            for(i in 0 until a.length()) {
                val d=a.optJSONObject(i)?:continue
                val id=d.optString("id"); val name=d.optString("name").ifBlank{"Homey"}
                val st=d.optJSONObject("state") ?: JSONObject()
                add("homey",id,name,roomForDevice("homey:$id",name),d.optString("class").ifBlank{d.optString("type")},st,true)
            }
        }
        runCatching {
            val a=JSONArray(prefs.getString("google_home_lights_json","[]"))
            for(i in 0 until a.length()) {
                val d=a.optJSONObject(i)?:continue
                val id=d.optString("id").ifBlank{i.toString()}; val name=d.optString("name").ifBlank{"Google Home"}
                val st=JSONObject(); if(d.has("on")&&!d.isNull("on")) st.put("on",d.optBoolean("on"))
                if(d.has("brightness")&&!d.isNull("brightness")) st.put("brightness",d.opt("brightness"))
                add("googleHome",id,name,d.optString("room").ifBlank{"Sin asignar"},"light",st,true)
            }
        }
        runCatching {
            val root=JSONObject(prefs.getString("hue_lights_json","{}").orEmpty().ifBlank{"{}"})
            val keys=root.keys()
            while(keys.hasNext()) {
                val id=keys.next(); val d=root.optJSONObject(id)?:continue; val st=d.optJSONObject("state")?:JSONObject()
                val state=JSONObject()
                if(st.has("on")) state.put("on",st.optBoolean("on"))
                if(st.has("bri")) state.put("brightness",((st.optInt("bri").coerceIn(0,254)/254.0)*100.0).toInt())
                add("hue",id,d.optString("name").ifBlank{"Luz Hue $id"},roomForDevice("hue:$id",d.optString("name")),d.optString("type").ifBlank{"light"},state,true)
            }
        }
        runCatching {
            val a=JSONArray(prefs.getString("lg_thinq_devices_json","[]"))
            for(i in 0 until a.length()) {
                val d=a.optJSONObject(i)?:continue; val info=d.optJSONObject("deviceInfo")?:d
                val id=d.optString("deviceId").ifBlank{info.optString("deviceId")}
                val name=info.optString("alias").ifBlank{info.optString("modelName").ifBlank{info.optString("deviceType").ifBlank{"Dispositivo LG"}}}
                add("lgThinQ",id,name,roomForDevice("lgthinq:$id",name),info.optString("deviceType"),JSONObject(),true)
            }
        }

        val providers=JSONObject()
            .put("tado",prefs.getString("tado_refresh_token","").orEmpty().isNotBlank())
            .put("sensibo",true)
            .put("homeConnect",prefs.getString("homeconnect_refresh_token","").orEmpty().isNotBlank())
            .put("homey",prefs.getString("homey_session","").orEmpty().isNotBlank())
            .put("googleHome",runCatching{JSONArray(prefs.getString("google_home_lights_json","[]")).length()>0}.getOrDefault(false))
            .put("hue",prefs.getString("hue_username","").orEmpty().isNotBlank())
            .put("lgThinQ",prefs.getString("lg_thinq_pat","").orEmpty().isNotBlank())
        val snapshot=JSONObject().put("ok",true).put("items",items).put("providers",providers).put("updatedAt",System.currentTimeMillis())
        prefs.edit().putString("domotics_tv_snapshot_json",snapshot.toString()).apply()
    }

'''
    s=s.replace(marker,helper+marker,1)

refresh=r'''    private fun refreshDomoticsQuickCard() {
        Thread {
            var tadoZones: List<TadoZoneCard> = emptyList()
            var sensiboDevices: List<SensiboCard> = emptyList()
            var hcDevices: List<HcDeviceCard> = emptyList()
            val lines=mutableListOf<String>()
            runCatching {
                if(prefs.getString("tado_refresh_token","").orEmpty().isNotBlank()) {
                    val token=refreshTadoTokenIfNeeded(); var homeId=prefs.getLong("tado_home_id",-1L)
                    if(token.isNotBlank()) {
                        if(homeId<=0) {
                            val(c,r)=tadoRequest("GET","https://my.tado.com/api/v2/me",token)
                            if(c in 200..299) { homeId=JSONObject(r).optJSONArray("homes")?.optJSONObject(0)?.optLong("id",-1L)?:-1L; if(homeId>0)prefs.edit().putLong("tado_home_id",homeId).apply() }
                        }
                        if(homeId>0) tadoZones=fetchTadoZones(token,homeId)
                    }
                }
            }
            runCatching { sensiboDevices=fetchSensiboDevices() }
            runCatching {
                if(prefs.getString("homeconnect_refresh_token","").orEmpty().isNotBlank()) {
                    val token=refreshHomeConnectTokenIfNeeded(); if(token.isNotBlank()) hcDevices=fetchHomeConnectDevices(token)
                }
            }
            tadoZones.forEach { z ->
                val room=roomForDevice("tado:${z.id}",z.name); val on=if(z.power.equals("ON",true))"●" else "○"
                val temp=if(!z.current.isNaN())" ${String.format(java.util.Locale.getDefault(),"%.1f°",z.current)}" else ""
                lines += "$on ${if(room=="Sin asignar")z.name else room}$temp"
            }
            sensiboDevices.forEach { d ->
                val room=roomForDevice("sensibo:${d.id}",d.name); val on=if(d.on==true)"●" else "○"
                val temp=if(!d.current.isNaN())" ${String.format(java.util.Locale.getDefault(),"%.1f°",d.current)}" else ""
                lines += "$on ${if(room=="Sin asignar")d.name else room}$temp"
            }
            hcDevices.forEach { d ->
                val room=roomForDevice("homeconnect:${d.haId}",d.name); lines += "${if(d.connected)"●" else "○"} ${if(room=="Sin asignar")d.name else room}"
            }
            runCatching {
                val a=JSONArray(prefs.getString("homey_devices_json","[]")); for(i in 0 until a.length()) {
                    val d=a.optJSONObject(i)?:continue; val id=d.optString("id"); val name=d.optString("name").ifBlank{"Homey"}; val st=d.optJSONObject("state")?:JSONObject()
                    val on=if(st.has("onoff")&&!st.isNull("onoff"))st.optBoolean("onoff") else null; val room=roomForDevice("homey:$id",name)
                    lines += "${if(on==true)"●" else if(on==false)"○" else "•"} ${if(room=="Sin asignar")name else room}"
                }
            }
            saveTvDomoticsSnapshot(tadoZones,sensiboDevices,hcDevices)
            runOnUiThread {
                val home=findViewById<TextView>(R.id.homeAutomation)
                home.text="⌂  Domótica\n" + if(lines.isEmpty())"Control de tu casa" else lines.take(3).joinToString("\n")
            }
        }.start()
    }'''
s=replace_function(s,'    private fun refreshDomoticsQuickCard()',refresh)
p.write_text(s)

# ---------------------------------------------------------------------------
# Cache LG's real device list so the bridge can expose it without a second login.
# ---------------------------------------------------------------------------
p=Path('mobile/src/main/java/com/jarvis/mobile/LgThinQActivity.kt')
t=p.read_text()
needle='''                val devices=mutableListOf<JSONObject>()
                for(i in 0 until arr.length()) arr.optJSONObject(i)?.let{devices+=it}
                runOnUiThread{'''
if needle in t and 'lg_thinq_devices_json' not in t:
    repl='''                val devices=mutableListOf<JSONObject>()
                for(i in 0 until arr.length()) arr.optJSONObject(i)?.let{devices+=it}
                val cached=JSONArray(); devices.forEach{cached.put(it)}
                prefs.edit().putString("lg_thinq_devices_json",cached.toString()).apply()
                runOnUiThread{'''
    t=t.replace(needle,repl,1)
p.write_text(t)

# ---------------------------------------------------------------------------
# Phone bridge: return the canonical snapshot, with legacy caches as fallback.
# ---------------------------------------------------------------------------
p=Path('mobile/src/main/java/com/jarvis/mobile/PhoneBridgeService.kt')
b=p.read_text()
bridge=r'''    private fun domoticsForTv(): JSONObject {
        val prefs=getSharedPreferences("jarvis_mobile",MODE_PRIVATE)
        val saved=prefs.getString("domotics_tv_snapshot_json","").orEmpty()
        if(saved.isNotBlank()) runCatching {
            val o=JSONObject(saved)
            if(o.optJSONArray("items")!=null) return o.put("bridgeAt",System.currentTimeMillis())
        }
        val items=JSONArray()
        fun add(provider:String,id:String,name:String,room:String,type:String,state:JSONObject,connected:Boolean=true) {
            items.put(JSONObject().put("provider",provider).put("id",id).put("name",name).put("room",room).put("type",type).put("connected",connected).put("state",state))
        }
        runCatching {
            val a=JSONArray(prefs.getString("homey_devices_json","[]")); for(i in 0 until a.length()) {
                val d=a.optJSONObject(i)?:continue; add("homey",d.optString("id"),d.optString("name").ifBlank{"Homey"},"Sin asignar",d.optString("class"),d.optJSONObject("state")?:JSONObject(),true)
            }
        }
        runCatching {
            val a=JSONArray(prefs.getString("google_home_lights_json","[]")); for(i in 0 until a.length()) {
                val d=a.optJSONObject(i)?:continue; val st=JSONObject(); if(d.has("on"))st.put("on",d.optBoolean("on")); add("googleHome",d.optString("id").ifBlank{i.toString()},d.optString("name").ifBlank{"Google Home"},d.optString("room").ifBlank{"Sin asignar"},"light",st,true)
            }
        }
        runCatching {
            val root=JSONObject(prefs.getString("hue_lights_json","{}").orEmpty().ifBlank{"{}"}); val keys=root.keys(); while(keys.hasNext()) {
                val id=keys.next(); val d=root.optJSONObject(id)?:continue; val old=d.optJSONObject("state")?:JSONObject(); val st=JSONObject(); if(old.has("on"))st.put("on",old.optBoolean("on")); if(old.has("bri"))st.put("brightness",((old.optInt("bri")/254.0)*100).toInt()); add("hue",id,d.optString("name").ifBlank{"Luz Hue"},"Sin asignar",d.optString("type"),st,true)
            }
        }
        runCatching {
            val a=JSONArray(prefs.getString("lg_thinq_devices_json","[]")); for(i in 0 until a.length()) {
                val d=a.optJSONObject(i)?:continue; val info=d.optJSONObject("deviceInfo")?:d; val id=d.optString("deviceId").ifBlank{info.optString("deviceId")}; val name=info.optString("alias").ifBlank{info.optString("modelName").ifBlank{"Dispositivo LG"}}; add("lgThinQ",id,name,"Sin asignar",info.optString("deviceType"),JSONObject(),true)
            }
        }
        val providers=JSONObject()
            .put("tado",prefs.getString("tado_refresh_token","").orEmpty().isNotBlank())
            .put("homeConnect",prefs.getString("homeconnect_refresh_token","").orEmpty().isNotBlank())
            .put("homey",prefs.getString("homey_session","").orEmpty().isNotBlank())
            .put("hue",prefs.getString("hue_username","").orEmpty().isNotBlank())
            .put("lgThinQ",prefs.getString("lg_thinq_pat","").orEmpty().isNotBlank())
        return JSONObject().put("ok",true).put("items",items).put("providers",providers).put("updatedAt",System.currentTimeMillis())
    }'''
b=replace_function(b,'    private fun domoticsForTv()',bridge)
p.write_text(b)

print('Jarvis Mobile 0.2.21: canonical smart-home snapshot for TV applied')
