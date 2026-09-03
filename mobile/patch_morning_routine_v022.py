from pathlib import Path

# ---------------------------------------------------------------------------
# Phone bridge: remember the TV address automatically after pairing.
# ---------------------------------------------------------------------------
p=Path('mobile/src/main/java/com/jarvis/mobile/PhoneBridgeService.kt')
s=p.read_text()
anchor='        if (path.startsWith("/permissions")) return 200 to permissionStatus().toString()\n'
if 'path.startsWith("/register-tv?")' not in s:
    block=r'''        if (path.startsWith("/register-tv?")) {
            val host=URLDecoder.decode(path.substringAfter("host=","").substringBefore("&"),StandardCharsets.UTF_8.name()).trim()
            if(host.isBlank()) return 400 to JSONObject().put("error","host-required").toString()
            getSharedPreferences("jarvis_mobile",MODE_PRIVATE).edit().putString("tv_remote_host",host).putLong("tv_registered_at",System.currentTimeMillis()).apply()
            return 200 to JSONObject().put("ok",true).put("host",host).toString()
        }
'''
    if anchor not in s: raise SystemExit('PhoneBridge permissions anchor missing')
    s=s.replace(anchor,block+anchor,1)
p.write_text(s)

# ---------------------------------------------------------------------------
# MainActivity: replace placeholder Programadas with a real morning routine UI.
# ---------------------------------------------------------------------------
p=Path('mobile/src/main/java/com/jarvis/mobile/MainActivity.kt')
s=p.read_text()
imports='import android.widget.EditText\n'
for imp in ['import android.widget.CheckBox\n','import android.widget.TimePicker\n']:
    if imp not in s:
        if imports not in s: raise SystemExit('EditText import anchor missing')
        s=s.replace(imports,imports+imp,1)

start=s.find('    private fun showScheduledTasks() {')
if start<0: raise SystemExit('showScheduledTasks missing')
brace=s.find('{',start);depth=0;end=-1
for i in range(brace,len(s)):
    if s[i]=='{':depth+=1
    elif s[i]=='}':
        depth-=1
        if depth==0:end=i+1;break
if end<0: raise SystemExit('showScheduledTasks end missing')
method=r'''    private fun showScheduledTasks() {
        val enabled=CheckBox(this).apply {
            text="Activar rutina de mañana"
            setTextColor(Color.WHITE)
            isChecked=prefs.getBoolean("morning_routine_enabled",false)
        }
        val picker=TimePicker(this).apply {
            setIs24HourView(true)
            hour=prefs.getInt("morning_routine_hour",8)
            minute=prefs.getInt("morning_routine_minute",0)
        }
        val alarm=CheckBox(this).apply {
            text="Hacer sonar la alarma en el móvil"
            setTextColor(Color.WHITE)
            isChecked=prefs.getBoolean("morning_routine_alarm",true)
        }
        val tv=CheckBox(this).apply {
            text="Encender/despertar Fire TV y abrir Jarvis Briefing"
            setTextColor(Color.WHITE)
            isChecked=prefs.getBoolean("morning_routine_tv",true)
        }
        val info=TextView(this).apply {
            val host=prefs.getString("tv_remote_host","").orEmpty()
            text=if(host.isBlank())"La TV se registrará automáticamente la próxima vez que la emparejes con Jarvis Mobile." else "TV registrada: $host"
            setTextColor(Color.LTGRAY);textSize=13f;setPadding(0,dp(8),0,dp(8))
        }
        val box=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(8),dp(20),0)
            addView(enabled);addView(picker);addView(alarm);addView(tv);addView(info)
            addView(TextView(this@MainActivity).apply {
                text="El briefing de TV reúne agenda, pedidos, recordatorios, llamadas perdidas, estado y controles de domótica y novedades de streaming/YouTube detectadas por Jarvis."
                setTextColor(Color.rgb(205,213,230));textSize=13f;setPadding(0,dp(8),0,0)
            })
        }
        AlertDialog.Builder(this)
            .setTitle("Programadas · Rutina de mañana")
            .setView(box)
            .setPositiveButton("Guardar") { _,_ ->
                prefs.edit()
                    .putBoolean("morning_routine_enabled",enabled.isChecked)
                    .putInt("morning_routine_hour",picker.hour)
                    .putInt("morning_routine_minute",picker.minute)
                    .putBoolean("morning_routine_alarm",alarm.isChecked)
                    .putBoolean("morning_routine_tv",tv.isChecked)
                    .apply()
                if(enabled.isChecked) RoutineScheduler.scheduleMorning(this) else RoutineScheduler.cancelMorning(this)
                val next=prefs.getLong("morning_routine_next",0L)
                val msg=if(enabled.isChecked&&next>0)"Rutina programada para ${java.text.SimpleDateFormat("EEE d MMM · HH:mm",java.util.Locale("es","ES")).format(java.util.Date(next))}" else "Rutina desactivada"
                Toast.makeText(this,msg,Toast.LENGTH_LONG).show()
            }
            .setNeutralButton("Probar ahora") { _,_ ->
                prefs.edit().putBoolean("morning_routine_alarm",false).apply()
                runCatching { ContextCompat.startForegroundService(this,Intent(this,MorningRoutineService::class.java)) }
            }
            .setNegativeButton("Cerrar",null)
            .show()
    }'''
s=s[:start]+method+s[end:]
p.write_text(s)

# ---------------------------------------------------------------------------
# Manifest: alarm receiver, boot restore and foreground service.
# ---------------------------------------------------------------------------
p=Path('mobile/src/main/AndroidManifest.xml')
x=p.read_text()
if 'android.permission.RECEIVE_BOOT_COMPLETED' not in x:
    x=x.replace('    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />','    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />\n    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />',1)
receiver_anchor='        <receiver android:name=".CallActionReceiver" android:exported="false" />\n'
if 'MorningRoutineReceiver' not in x:
    block='''        <receiver android:name=".MorningRoutineReceiver" android:exported="false" />
        <receiver android:name=".RoutineBootReceiver" android:enabled="true" android:exported="false">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
                <action android:name="android.intent.action.LOCKED_BOOT_COMPLETED" />
            </intent-filter>
        </receiver>
'''
    if receiver_anchor not in x: raise SystemExit('manifest receiver anchor missing')
    x=x.replace(receiver_anchor,receiver_anchor+block,1)
service_anchor='        <service android:name=".PhoneBridgeService" android:exported="false" android:foregroundServiceType="dataSync" />\n'
if 'MorningRoutineService' not in x:
    block='        <service android:name=".MorningRoutineService" android:exported="false" android:foregroundServiceType="mediaPlayback|dataSync" />\n'
    if service_anchor not in x: raise SystemExit('manifest service anchor missing')
    x=x.replace(service_anchor,service_anchor+block,1)
p.write_text(x)
print('Jarvis Mobile morning routine + automatic TV registration support applied')
