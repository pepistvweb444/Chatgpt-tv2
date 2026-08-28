from pathlib import Path
p=Path('mobile/src/main/java/com/jarvis/mobile/MainActivity.kt')
s=p.read_text()

marker='    private fun jsonArrayStrings(a: JSONArray?): List<String> {'
if marker not in s:
    raise SystemExit('jsonArrayStrings marker not found')

methods=r'''    private data class ThinQDevice(val id:String,val name:String,val type:String,val raw:JSONObject)

    private fun fetchThinQDevices(): List<ThinQDevice> {
        val j=readJson("$BACKEND/api/domotics/thinq")
        val root=j.opt("devices")
        val arr=when(root){
            is JSONArray -> root
            is JSONObject -> root.optJSONArray("devices") ?: root.optJSONArray("items") ?: root.optJSONArray("data") ?: JSONArray()
            else -> JSONArray()
        }
        val out=mutableListOf<ThinQDevice>()
        for(i in 0 until arr.length()){
            val d=arr.optJSONObject(i)?:continue
            val id=d.optString("deviceId").ifBlank{d.optString("id")}
            if(id.isBlank()) continue
            val name=d.optString("deviceName").ifBlank{d.optString("name").ifBlank{"LG ThinQ"}}
            val type=d.optString("deviceType").ifBlank{d.optString("type")}
            out+=ThinQDevice(id,name,type,d)
        }
        return out
    }

    private fun addThinQDeviceWidget(d:ThinQDevice){
        val room=roomForDevice("thinq:${d.id}")
        val detail=buildList{
            add("LG ThinQ API")
            if(d.type.isNotBlank()) add(d.type)
            if(room!="Sin asignar") add(room)
        }.joinToString(" · ")
        addTextWidget("home", "LG · ${d.name}", detail)
    }

    private fun showThinQSettings(){
        Thread{
            try{
                val devices=fetchThinQDevices()
                runOnUiThread{
                    beginWidgetGroup("LG ThinQ")
                    if(devices.isEmpty()) addTextWidget("home","LG ThinQ","No hay dispositivos devueltos por la API oficial.")
                    else devices.forEach{addThinQDeviceWidget(it)}
                    status.text="Jarvis listo"
                }
            }catch(e:Throwable){
                runOnUiThread{
                    beginWidgetGroup("LG ThinQ")
                    addTextWidget("home","LG ThinQ no disponible",e.message?:"Configura LG_THINQ_PAT en Vercel")
                    status.text="Jarvis listo"
                }
            }
        }.start()
    }

'''
if 'private fun fetchThinQDevices' not in s:
    s=s.replace(marker,methods+marker,1)

# Add ThinQ directly where RoomEntry entries are assembled. Do not rely on a
# temporary thinQDevices variable injected into a different lexical scope.
needle='''                hcDevices.forEach { entries += RoomEntry(roomForDevice("homeconnect:${it.haId}"), "homeconnect", "homeconnect:${it.haId}", it.name, it) }'''
insert=needle+r'''
                runCatching { fetchThinQDevices() }.getOrDefault(emptyList()).forEach { d ->
                    entries += RoomEntry(roomForDevice("thinq:${d.id}"), "thinq", "thinq:${d.id}", d.name, d)
                }'''
if needle in s and 'RoomEntry(roomForDevice("thinq:' not in s:
    s=s.replace(needle,insert,1)

needle3='''                                "homeconnect" -> addHomeConnectDeviceWidget(e.item as HcDeviceCard)'''
insert3=needle3+r'''
                                "thinq" -> addThinQDeviceWidget(e.item as ThinQDevice)'''
if needle3 in s and '"thinq" -> addThinQDeviceWidget' not in s:
    s=s.replace(needle3,insert3,1)

s=s.replace('"Homey", "Home Connect", "Gmail"', '"Homey", "Home Connect", "LG ThinQ", "Gmail"')

p.write_text(s)
print('LG ThinQ Connect integrated into domotics/rooms without cross-scope variables')
