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

# Auto-start persistent embedded wake word when the user enabled it previously.
auto_anchor='''        warmLocation()'''
auto_block='''        warmLocation()
        if (prefs.getBoolean("wake_word_enabled", false) && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            runCatching { ContextCompat.startForegroundService(this, Intent(this, WakeWordService::class.java)) }
        }
        if (intent.getBooleanExtra("open_domotics", false)) {
            window.decorView.postDelayed({ showUnifiedDomoticsWidget() }, 250L)
        }'''
if auto_anchor in s and 'prefs.getBoolean("wake_word_enabled"' not in s:
    s=s.replace(auto_anchor,auto_block,1)

p.write_text(s)

# Add an explicit mobile assistant / overlay / room configuration section.
d=Path('mobile/src/main/java/com/jarvis/mobile/DeviceHubActivity.kt')
t=d.read_text()
if 'Jarvis Mobile · v' not in t:
    t=t.replace(
        'root.addView(TextView(this).apply { text = "Control del teléfono"; textSize = 26f; setTextColor(0xFFFFFFFF.toInt()) })',
        'val versionName = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"\n        root.addView(TextView(this).apply { text = "Jarvis Mobile · v$versionName"; textSize = 26f; setTextColor(0xFFFFFFFF.toInt()) })'
    )

homey_button='''        add("Homey Cloud · luces y dispositivos") { startActivity(Intent(this, HomeyActivity::class.java)) }'''
assistant_buttons=r'''        add("Activar Hola Jarvis / Hola Ale") {
            val prefs = getSharedPreferences("jarvis_mobile", MODE_PRIVATE)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 53)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Primero permite la burbuja sobre otras aplicaciones.", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } else {
                prefs.edit().putBoolean("wake_word_enabled", true).apply()
                runCatching { ContextCompat.startForegroundService(this, Intent(this, WakeWordService::class.java)) }
                Toast.makeText(this, "Hola Jarvis / Hola Ale activado", Toast.LENGTH_LONG).show()
                refreshStatus()
            }
        }
        add("Permitir burbuja sobre otras aplicaciones") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            else Toast.makeText(this, "La burbuja ya está permitida en esta versión de Android", Toast.LENGTH_SHORT).show()
        }
        add("Detener escucha permanente") {
            getSharedPreferences("jarvis_mobile", MODE_PRIVATE).edit().putBoolean("wake_word_enabled", false).apply()
            stopService(Intent(this, WakeWordService::class.java))
            stopService(Intent(this, JarvisOverlayService::class.java))
            Toast.makeText(this, "Escucha permanente desactivada", Toast.LENGTH_SHORT).show()
            refreshStatus()
        }
        add("Domótica · dispositivos y estancias") {
            startActivity(Intent(this, MainActivity::class.java).putExtra("open_domotics", true))
            finish()
        }
'''
if homey_button in t and 'Activar Hola Jarvis / Hola Ale' not in t:
    t=t.replace(homey_button,assistant_buttons+homey_button,1)

old='''        val homey = if (homeyConnected) "CONECTADO" else "NO CONECTADO"
        permissionStatus.text = "Contactos $contacts   Teléfono $phone\\nSMS lectura $smsRead   SMS envío $smsSend\\nWhatsApp/RCS $notifications · lector $listener\\nHomey Cloud $homey"'''
new='''        val homey = if (homeyConnected) "CONECTADO" else "NO CONECTADO"
        val wake = if (prefs.getBoolean("wake_word_enabled", false)) "ACTIVO" else "INACTIVO"
        val overlay = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) "✓" else "✗"
        permissionStatus.text = "Contactos $contacts   Teléfono $phone\\nSMS lectura $smsRead   SMS envío $smsSend\\nWhatsApp/RCS $notifications · lector $listener\\nHomey Cloud $homey\\nHola Jarvis $wake · Burbuja $overlay"'''
if old in t:
    t=t.replace(old,new,1)

d.write_text(t)
print('Homey Cloud + persistent wake/overlay + room settings UI applied')
