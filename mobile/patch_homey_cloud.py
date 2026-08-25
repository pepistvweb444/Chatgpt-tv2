from pathlib import Path
p=Path('mobile/src/main/java/com/jarvis/mobile/MainActivity.kt')
s=p.read_text()

# Add Homey devices to room grouping created by patch_domotics_rooms.py.
needle='''                hcDevices.forEach { entries += RoomEntry(roomForDevice("homeconnect:${it.haId}"), "homeconnect", "homeconnect:${it.haId}", it.name, it) }'''
insert=needle+r'''
                runCatching {
                    val homey = JSONArray(prefs.getString("homey_devices_json", "[]"))
                    for (i in 0 until homey.length()) {
                        val d = homey.optJSONObject(i) ?: continue
                        val id = d.optString("id")
                        val name = d.optString("name").ifBlank { "Homey" }
                        entries += RoomEntry(roomForDevice("homey:$id"), "homey", "homey:$id", name, d)
                    }
                }'''
if needle in s and 'entries += RoomEntry(roomForDevice("homey:' not in s:
    s=s.replace(needle,insert,1)

needle2='''                                "homeconnect" -> addHomeConnectDeviceWidget(e.item as HcDeviceCard)'''
insert2=needle2+r'''
                                "homey" -> {
                                    val d=e.item as JSONObject
                                    val st=d.optJSONObject("state") ?: JSONObject()
                                    val on=if(st.has("onoff")&&!st.isNull("onoff")) st.optBoolean("onoff") else null
                                    val temp=when {
                                        st.has("measure_temperature")&&!st.isNull("measure_temperature") -> " · ${st.optDouble("measure_temperature")} °C"
                                        st.has("target_temperature")&&!st.isNull("target_temperature") -> " · ${st.optDouble("target_temperature")} °C"
                                        else -> ""
                                    }
                                    addTextWidget("home", "${if(on==true) "●" else if(on==false) "○" else "•"} ${e.name}", "Homey${temp}")
                                }'''
if needle2 in s and '"homey" -> {' not in s:
    s=s.replace(needle2,insert2,1)

# Add Homey to top quick-card summary.
start=s.find('    private fun refreshDomoticsQuickCard()')
if start>=0:
    end=s.find('    private fun ',start+30)
    if end<0:end=len(s)
    block=s[start:end]
    marker='''            runOnUiThread {'''
    homey=r'''            runCatching {
                val a=JSONArray(prefs.getString("homey_devices_json", "[]"))
                for(i in 0 until a.length()) {
                    val d=a.optJSONObject(i)?:continue
                    val id=d.optString("id"); val name=d.optString("name").ifBlank{"Homey"}; val st=d.optJSONObject("state")?:JSONObject()
                    val on=if(st.has("onoff")&&!st.isNull("onoff")) st.optBoolean("onoff") else null
                    val state=if(on==true) "●" else if(on==false) "○" else "•"
                    val room=roomForDevice("homey:$id")
                    val temp=when { st.has("measure_temperature")&&!st.isNull("measure_temperature") -> " ${st.optDouble("measure_temperature")}°"; st.has("target_temperature")&&!st.isNull("target_temperature") -> " ${st.optDouble("target_temperature")}°"; else -> "" }
                    lines += "$state ${if(room=="Sin asignar") name else room}$temp"
                }
            }
'''
    if marker in block and 'homey_devices_json' not in block:
        block=block.replace(marker,homey+marker,1)
        s=s[:start]+block+s[end:]

p.write_text(s)
print('Homey Cloud added to rooms and quick card')
