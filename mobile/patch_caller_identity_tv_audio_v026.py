from pathlib import Path

# Caller identity enrichment.
p=Path("mobile/src/main/java/com/jarvis/mobile/JarvisCallScreeningService.kt")
s=p.read_text()
old='''            val publicMatch = rep.optJSONObject("publicMatch")
            CallStateStore.update(this, id) {
                it.put("classification", cls)
                    .put("spamScore", rep.optInt("score", 0))
                    .put("spamSources", sourceText)
                    .put("publicLabel", publicMatch?.optString("label").orEmpty())
                    .put("publicSource", publicMatch?.optString("source").orEmpty())
                    .put("publicConfidence", publicMatch?.optString("confidence").orEmpty())
                    .put("updatedAt", System.currentTimeMillis())
            }?.let { IncomingCallPresenter.show(this, it) }'''
new='''            val publicMatch = rep.optJSONObject("publicMatch")
            val identity = rep.optJSONObject("identity")
            val identityName = identity?.optString("name").orEmpty().trim()
            val identitySource = identity?.optString("source").orEmpty()
            val identityCarrier = identity?.optString("carrier").orEmpty()
            val identityLineType = identity?.optString("lineType").orEmpty()
            val resolvedClass = if (cls == "unknown" && identityName.isNotBlank()) "identified" else cls
            CallStateStore.update(this, id) {
                if (identityName.isNotBlank()) it.put("name", identityName)
                it.put("classification", resolvedClass)
                    .put("spamScore", rep.optInt("score", 0))
                    .put("spamSources", sourceText)
                    .put("identityName", identityName)
                    .put("identitySource", identitySource)
                    .put("identityCarrier", identityCarrier)
                    .put("identityLineType", identityLineType)
                    .put("publicLabel", publicMatch?.optString("label").orEmpty())
                    .put("publicSource", publicMatch?.optString("source").orEmpty())
                    .put("publicConfidence", publicMatch?.optString("confidence").orEmpty())
                    .put("updatedAt", System.currentTimeMillis())
            }?.let { IncomingCallPresenter.show(this, it) }'''
if old in s:
    s=s.replace(old,new,1)
p.write_text(s)

# Incoming-call UI surfaces.
for fp in [
    Path("mobile/src/main/java/com/jarvis/mobile/IncomingCallPresenter.kt"),
    Path("mobile/src/main/java/com/jarvis/mobile/IncomingCallActivity.kt"),
]:
    t=fp.read_text()
    t=t.replace(
        '''            "contact" -> buildString { append(if (call.optBoolean("priority", false)) "★ Contacto prioritario" else "Contacto guardado"); if (call.optBoolean("recent", false)) append(" · habitual/reciente") }
            else -> if (publicLabel.isNotBlank()) "Coincidencia pública · sin confirmar" else "Número o usuario desconocido"''',
        '''            "contact" -> buildString { append(if (call.optBoolean("priority", false)) "★ Contacto prioritario" else "Contacto guardado"); if (call.optBoolean("recent", false)) append(" · habitual/reciente") }
            "identified" -> "Identificado por " + call.optString("identitySource").ifBlank { "phone intelligence" }
            else -> if (publicLabel.isNotBlank()) "Coincidencia pública · sin confirmar" else "Número o usuario desconocido"'''
    )
    t=t.replace(
        '''            "contact"->buildString{append(if(call.optBoolean("priority",false))"★ Contacto prioritario" else "Contacto guardado");if(call.optBoolean("recent",false))append(" · contacto habitual/reciente")}
            else->if(publicLabel.isNotBlank())"Coincidencia pública en Internet · no confirmada" else "Número o usuario desconocido"''',
        '''            "contact"->buildString{append(if(call.optBoolean("priority",false))"★ Contacto prioritario" else "Contacto guardado");if(call.optBoolean("recent",false))append(" · contacto habitual/reciente")}
            "identified"->"Identificado por "+call.optString("identitySource").ifBlank{"phone intelligence"}
            else->if(publicLabel.isNotBlank())"Coincidencia pública en Internet · no confirmada" else "Número o usuario desconocido"'''
    )
    if fp.name=="IncomingCallPresenter.kt":
        t=t.replace(
            '''                call.optString("publicSource").takeIf { it.isNotBlank() }?.let { append("\\nCoincidencia web: ").append(it) }''',
            '''                call.optString("identityCarrier").takeIf { it.isNotBlank() }?.let { append("\\nOperador: ").append(it) }
                call.optString("identityLineType").takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
                call.optString("publicSource").takeIf { it.isNotBlank() }?.let { append("\\nCoincidencia web: ").append(it) }'''
        )
    else:
        t=t.replace(
            '''call.optString("publicSource").takeIf{it.isNotBlank()}?.let{append("\\nCoincidencia: ").append(it)}''',
            '''call.optString("identityCarrier").takeIf{it.isNotBlank()}?.let{append("\\nOperador: ").append(it)};call.optString("identityLineType").takeIf{it.isNotBlank()}?.let{append(" · ").append(it)};call.optString("publicSource").takeIf{it.isNotBlank()}?.let{append("\\nCoincidencia: ").append(it)}'''
        )
    fp.write_text(t)

# Phone bridge: call a contact by name / request TV-Bluetooth audio.
p=Path("mobile/src/main/java/com/jarvis/mobile/PhoneBridgeService.kt")
s=p.read_text()
if "import android.provider.ContactsContract" not in s:
    s=s.replace("import android.os.IBinder\n","import android.os.IBinder\nimport android.provider.ContactsContract\n",1)

anchor='''        if (path.startsWith("/call?")) {'''
if 'path.startsWith("/call-contact?")' not in s and anchor in s:
    block=r'''        if (path.startsWith("/call-audio-status")) {
            val p=getSharedPreferences("jarvis_mobile",MODE_PRIVATE)
            return 200 to JSONObject()
                .put("ok",true)
                .put("dialerEnabled",p.getBoolean("jarvis_dialer_enabled",false))
                .put("autoRoute",p.getBoolean("tv_call_audio_auto_route",false))
                .put("bluetoothDevices",runCatching{JSONArray(p.getString("call_audio_bluetooth_devices","[]"))}.getOrElse{JSONArray()})
                .put("activeDevice",p.getString("call_audio_active_device","").orEmpty())
                .put("lastRouteStatus",p.getString("call_audio_route_status","").orEmpty())
                .toString()
        }
        if (path.startsWith("/call-contact?")) {
            val name=URLDecoder.decode(path.substringAfter("name=","").substringBefore("&"),StandardCharsets.UTF_8.name()).trim()
            val tvAudio=path.contains("tvAudio=1")
            if(name.isBlank()) return 400 to JSONObject().put("error","name-required").toString()
            val match=findContactNumber(name) ?: return 404 to JSONObject().put("error","contact-not-found").put("name",name).toString()
            val result=startOutgoingCall(match.second,tvAudio)
            return result.first to JSONObject(result.second).put("contact",match.first).put("number",match.second).toString()
        }
'''
    s=s.replace(anchor,block+anchor,1)

old=r'''        if (path.startsWith("/call?")) {
            val raw = path.substringAfter("number=", "").substringBefore("&")
            val number = URLDecoder.decode(raw, StandardCharsets.UTF_8.name()).trim()
            if (number.isBlank()) return 400 to JSONObject().put("error", "number-required").toString()
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) return 403 to JSONObject().put("error", "call-permission-required").toString()
            return try {
                startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(number))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                200 to JSONObject().put("ok", true).put("status", "calling").toString()
            } catch (e: Exception) { 500 to JSONObject().put("error", e.message ?: "call-failed").toString() }
        }'''
new=r'''        if (path.startsWith("/call?")) {
            val raw = path.substringAfter("number=", "").substringBefore("&")
            val number = URLDecoder.decode(raw, StandardCharsets.UTF_8.name()).trim()
            val tvAudio=path.contains("tvAudio=1")
            if (number.isBlank()) return 400 to JSONObject().put("error", "number-required").toString()
            return startOutgoingCall(number,tvAudio)
        }'''
if old in s:
    s=s.replace(old,new,1)

helper_anchor="    override fun onDestroy() {"
if "private fun startOutgoingCall(" not in s and helper_anchor in s:
    helpers=r'''    private fun findContactNumber(query:String):Pair<String,String>? {
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.READ_CONTACTS)!=PackageManager.PERMISSION_GRANTED)return null
        val projection=arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,ContactsContract.CommonDataKinds.Phone.NUMBER)
        val selection=ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME+" LIKE ?"
        contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            selection,
            arrayOf("%"+query+"%"),
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME+" ASC"
        )?.use { c -> if(c.moveToFirst()) return c.getString(0) to c.getString(1) }
        return null
    }

    private fun startOutgoingCall(number:String,tvAudio:Boolean):Pair<Int,String> {
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.CALL_PHONE)!=PackageManager.PERMISSION_GRANTED)
            return 403 to JSONObject().put("error","call-permission-required").toString()
        val clean=number.filter{it.isDigit()||it=='+'}
        if(clean.isBlank())return 400 to JSONObject().put("error","invalid-number").toString()
        getSharedPreferences("jarvis_mobile",MODE_PRIVATE).edit()
            .putBoolean("tv_call_audio_auto_route",tvAudio)
            .putString("pending_outgoing_number",clean)
            .apply()
        return try {
            startActivity(Intent(Intent.ACTION_CALL,Uri.parse("tel:"+Uri.encode(clean))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            200 to JSONObject().put("ok",true).put("status","calling").put("tvAudioRequested",tvAudio).toString()
        } catch(e:Exception) {
            500 to JSONObject().put("error",e.message?:"call-failed").toString()
        }
    }

'''
    s=s.replace(helper_anchor,helpers+helper_anchor,1)
p.write_text(s)

# Optional default-dialer InCallService. Audio can be routed only to Android-recognized call endpoints.
Path("mobile/src/main/java/com/jarvis/mobile/JarvisInCallService.kt").write_text(r'''package com.jarvis.mobile

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import org.json.JSONArray

class JarvisInCallService : InCallService() {
    private val prefs by lazy { getSharedPreferences("jarvis_mobile", MODE_PRIVATE) }
    private val handler=Handler(Looper.getMainLooper())

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        activeCall=call
        instance=this
        prefs.edit().putString("incall_number",call.details.handle?.schemeSpecificPart.orEmpty()).putBoolean("incall_active",true).apply()
        call.registerCallback(object:Call.Callback(){
            override fun onStateChanged(c:Call,state:Int){
                prefs.edit().putInt("incall_state",state).apply()
                if(state==Call.STATE_DISCONNECTED) prefs.edit().putBoolean("incall_active",false).apply()
            }
        })
        runCatching {
            startActivity(Intent(this,OngoingCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        handler.postDelayed({ if(prefs.getBoolean("tv_call_audio_auto_route",false)) routeToBluetooth() },900L)
    }

    override fun onCallRemoved(call: Call) {
        if(activeCall===call) activeCall=null
        prefs.edit().putBoolean("incall_active",false).apply()
        super.onCallRemoved(call)
    }

    @Suppress("DEPRECATION")
    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        super.onCallAudioStateChanged(audioState)
        val devices=audioState?.supportedBluetoothDevices?.toList().orEmpty()
        val names=JSONArray()
        devices.forEach { names.put(it.name ?: it.address ?: "Bluetooth") }
        prefs.edit()
            .putString("call_audio_bluetooth_devices",names.toString())
            .putString("call_audio_active_device",audioState?.activeBluetoothDevice?.name.orEmpty())
            .apply()
        if(prefs.getBoolean("tv_call_audio_auto_route",false) && devices.isNotEmpty() && audioState?.activeBluetoothDevice==null) {
            handler.postDelayed({ routeToBluetooth() },350L)
        }
    }

    @Suppress("DEPRECATION")
    fun routeToBluetooth():Boolean {
        val devices=callAudioState?.supportedBluetoothDevices?.toList().orEmpty()
        if(devices.isEmpty()) {
            prefs.edit().putString("call_audio_route_status","No hay dispositivo Bluetooth disponible para audio de llamada").apply()
            return false
        }
        val preferred=prefs.getString("tv_call_audio_device_name","").orEmpty()
        val target=devices.firstOrNull { preferred.isNotBlank() && (it.name?:it.address.orEmpty()).contains(preferred,true) } ?: devices.first()
        return runCatching {
            requestBluetoothAudio(target)
            prefs.edit()
                .putString("call_audio_route_status","Solicitado: "+(target.name?:target.address))
                .putString("tv_call_audio_device_name",target.name?:target.address.orEmpty())
                .apply()
            true
        }.getOrElse {
            prefs.edit().putString("call_audio_route_status","Error: "+(it.message?:it.javaClass.simpleName)).apply()
            false
        }
    }

    companion object {
        @Volatile var instance:JarvisInCallService?=null
        @Volatile var activeCall:Call?=null
        fun disconnect():Boolean=runCatching{val c=activeCall?:return false;c.disconnect();true}.getOrDefault(false)
        fun answer():Boolean=runCatching{val c=activeCall?:return false;c.answer(0);true}.getOrDefault(false)
        fun routeBluetooth():Boolean=instance?.routeToBluetooth()==true
    }
}
''')

Path("mobile/src/main/java/com/jarvis/mobile/OngoingCallActivity.kt").write_text(r'''package com.jarvis.mobile

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class OngoingCallActivity:Activity(){
    private val handler=Handler(Looper.getMainLooper())
    private lateinit var root:LinearLayout
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setPadding(44,50,44,44);setBackgroundColor(Color.rgb(8,11,16))}
        setContentView(root);render()
    }
    override fun onResume(){super.onResume();handler.post(loop)}
    override fun onPause(){handler.removeCallbacks(loop);super.onPause()}
    private val loop=object:Runnable{override fun run(){render();handler.postDelayed(this,700)}}
    private fun render(){
        val call=JarvisInCallService.activeCall
        if(call==null){finish();return}
        root.removeAllViews()
        val number=call.details.handle?.schemeSpecificPart.orEmpty().ifBlank{"Llamada"}
        val state=when(call.state){Call.STATE_RINGING->"Entrante";Call.STATE_ACTIVE->"En curso";Call.STATE_DIALING->"Llamando…";Call.STATE_CONNECTING->"Conectando…";Call.STATE_HOLDING->"En espera";else->"Llamada"}
        root.addView(TextView(this).apply{text="Jarvis · "+state;textSize=18f;setTextColor(Color.rgb(160,180,255));gravity=Gravity.CENTER})
        root.addView(TextView(this).apply{text=number;textSize=28f;setTextColor(Color.WHITE);gravity=Gravity.CENTER;setPadding(0,18,0,24)})
        if(call.state==Call.STATE_RINGING) root.addView(Button(this).apply{text="CONTESTAR";setOnClickListener{JarvisInCallService.answer()}})
        root.addView(Button(this).apply{text="AUDIO EN TV / BLUETOOTH";setOnClickListener{
            val ok=JarvisInCallService.routeBluetooth()
            val msg=getSharedPreferences("jarvis_mobile",MODE_PRIVATE).getString("call_audio_route_status","").orEmpty()
            Toast.makeText(this@OngoingCallActivity,if(ok)msg else msg.ifBlank{"TV no disponible como audio de llamada"},Toast.LENGTH_LONG).show()
        }})
        root.addView(Button(this).apply{text="COLGAR";setOnClickListener{JarvisInCallService.disconnect();finish()}})
    }
}
''')

Path("mobile/src/main/java/com/jarvis/mobile/JarvisDialerActivity.kt").write_text(r'''package com.jarvis.mobile

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telecom.TelecomManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class JarvisDialerActivity:Activity(){
    private lateinit var number:EditText
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(36,36,36,36)}
        number=EditText(this).apply{hint="Número";setText(intent?.data?.schemeSpecificPart.orEmpty())}
        val call=Button(this).apply{text="LLAMAR";setOnClickListener{place()}}
        box.addView(number);box.addView(call);setContentView(box)
    }
    private fun place(){
        val n=number.text.toString().trim()
        if(n.isBlank())return
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.CALL_PHONE)!=PackageManager.PERMISSION_GRANTED){
            ActivityCompat.requestPermissions(this,arrayOf(Manifest.permission.CALL_PHONE),6001);return
        }
        runCatching{getSystemService(TelecomManager::class.java).placeCall(Uri.parse("tel:"+Uri.encode(n)),Bundle())}
            .onFailure{Toast.makeText(this,it.message,Toast.LENGTH_LONG).show()}
    }
}
''')

# Device hub explicit opt-in to dialer role.
p=Path("mobile/src/main/java/com/jarvis/mobile/DeviceHubActivity.kt")
s=p.read_text()
needle='        add("Activar Jarvis para identificar llamadas") { requestCallScreeningRole() }\n'
if "Activar audio de llamadas en TV" not in s and needle in s:
    extra='''        add("Activar audio de llamadas en TV / Bluetooth") { requestDialerRoleForTvAudio() }
        add("Diagnóstico audio de llamadas") {
            val p=getSharedPreferences("jarvis_mobile",MODE_PRIVATE)
            Toast.makeText(this,
                "Dispositivos: "+p.getString("call_audio_bluetooth_devices","[]")+"\\nActivo: "+p.getString("call_audio_active_device","").orEmpty()+"\\nEstado: "+p.getString("call_audio_route_status","").orEmpty(),
                Toast.LENGTH_LONG).show()
        }
'''
    s=s.replace(needle,needle+extra,1)
method_anchor="    private fun requestCallScreeningRole() {"
if "private fun requestDialerRoleForTvAudio()" not in s and method_anchor in s:
    method=r'''    private fun requestDialerRoleForTvAudio() {
        getSharedPreferences("jarvis_mobile",MODE_PRIVATE).edit().putBoolean("tv_call_audio_auto_route",true).apply()
        if(Build.VERSION.SDK_INT>=29){
            val rm=getSystemService(RoleManager::class.java)
            if(rm.isRoleAvailable(RoleManager.ROLE_DIALER)){
                if(rm.isRoleHeld(RoleManager.ROLE_DIALER)){
                    getSharedPreferences("jarvis_mobile",MODE_PRIVATE).edit().putBoolean("jarvis_dialer_enabled",true).apply()
                    Toast.makeText(this,"Jarvis ya puede gestionar el audio. Empareja la TV como dispositivo Bluetooth de llamadas.",Toast.LENGTH_LONG).show()
                } else startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_DIALER),55)
                return
            }
        }
        Toast.makeText(this,"Android no permite activar el rol de teléfono en este dispositivo.",Toast.LENGTH_LONG).show()
    }

'''
    s=s.replace(method_anchor,method+method_anchor,1)
if "requestCode==55" not in s:
    anchor='if(requestCode==54){'
    if anchor in s:
        s=s.replace(anchor,'''if(requestCode==55){
            val enabled=Build.VERSION.SDK_INT>=29 && runCatching{getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_DIALER)}.getOrDefault(false)
            getSharedPreferences("jarvis_mobile",MODE_PRIVATE).edit().putBoolean("jarvis_dialer_enabled",enabled).apply()
            Toast.makeText(this,if(enabled)"Audio de llamadas Jarvis activado. La TV debe aparecer como Bluetooth de llamada." else "No se activó el rol de teléfono.",Toast.LENGTH_LONG).show()
            refreshStatus()
        }
        if(requestCode==54){''',1)
p.write_text(s)

# Manifest components.
p=Path("mobile/src/main/AndroidManifest.xml")
x=p.read_text()
if 'android.permission.READ_CALL_LOG' not in x:
    x=x.replace('    <uses-permission android:name="android.permission.READ_PHONE_STATE" />','    <uses-permission android:name="android.permission.READ_PHONE_STATE" />\n    <uses-permission android:name="android.permission.READ_CALL_LOG" />',1)
activity_anchor='        <activity android:name=".IncomingCallActivity"'
if ".JarvisDialerActivity" not in x and activity_anchor in x:
    block='''        <activity android:name=".JarvisDialerActivity" android:exported="true">
            <intent-filter><action android:name="android.intent.action.DIAL" /><category android:name="android.intent.category.DEFAULT" /></intent-filter>
            <intent-filter><action android:name="android.intent.action.DIAL" /><category android:name="android.intent.category.DEFAULT" /><data android:scheme="tel" /></intent-filter>
        </activity>
        <activity android:name=".OngoingCallActivity" android:exported="false" android:excludeFromRecents="true" android:showWhenLocked="true" android:turnScreenOn="true" />
'''
    x=x.replace(activity_anchor,block+activity_anchor,1)
service_anchor='        <service android:name=".JarvisCallScreeningService"'
if ".JarvisInCallService" not in x and service_anchor in x:
    block='''        <service android:name=".JarvisInCallService" android:permission="android.permission.BIND_INCALL_SERVICE" android:exported="true">
            <meta-data android:name="android.telecom.IN_CALL_SERVICE_UI" android:value="true" />
            <meta-data android:name="android.telecom.IN_CALL_SERVICE_RINGING" android:value="false" />
            <intent-filter><action android:name="android.telecom.InCallService" /></intent-filter>
        </service>
'''
    x=x.replace(service_anchor,block+service_anchor,1)
p.write_text(x)

print("Jarvis Mobile caller identity and optional TV Bluetooth call audio applied")
