from pathlib import Path

# --- Replace native call screening with enriched incoming call state + overlay ---
p=Path('mobile/src/main/java/com/jarvis/mobile/JarvisCallScreeningService.kt')
p.write_text(r'''package com.jarvis.mobile

import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.CallScreeningService
import org.json.JSONObject

class JarvisCallScreeningService : CallScreeningService() {
    override fun onScreenCall(callDetails: Call.Details) {
        val number = callDetails.handle?.schemeSpecificPart.orEmpty()
        val info = lookupContact(number)
        val call = JSONObject()
            .put("id", "tel:${System.currentTimeMillis()}")
            .put("source", "phone")
            .put("number", number)
            .put("name", info.first)
            .put("photo", info.second)
            .put("knownContact", info.first.isNotBlank())
            .put("video", callDetails.videoState != 0)
            .put("state", "ringing")
            .put("time", System.currentTimeMillis())
        getSharedPreferences("jarvis_mobile", MODE_PRIVATE).edit().putString("incoming_call_json", call.toString()).apply()
        runCatching {
            startService(Intent(this, JarvisOverlayService::class.java)
                .setAction(JarvisOverlayService.ACTION_INCOMING_CALL)
                .putExtra(JarvisOverlayService.EXTRA_CALL_JSON, call.toString()))
        }
        respondToCall(callDetails, CallResponse.Builder().setDisallowCall(false).setRejectCall(false).setSilenceCall(false).build())
    }

    private fun lookupContact(number: String): Pair<String,String> {
        if (number.isBlank()) return "" to ""
        val uri=Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        return runCatching {
            contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME, ContactsContract.PhoneLookup.PHOTO_URI), null, null, null)?.use { c ->
                if(c.moveToFirst()) (c.getString(0).orEmpty() to c.getString(1).orEmpty()) else ("" to "")
            } ?: ("" to "")
        }.getOrDefault("" to "")
    }
}
''')

# --- Notification listener: detect VoIP ringing notifications and keep actionable PendingIntents in memory ---
p=Path('mobile/src/main/java/com/jarvis/mobile/JarvisNotificationListener.kt')
s=p.read_text()
if 'import android.app.PendingIntent' not in s:
    s=s.replace('import android.app.Notification\n','import android.app.Notification\nimport android.app.PendingIntent\nimport android.content.Intent\n')
if 'companion object {' not in s:
    s=s.replace('class JarvisNotificationListener : NotificationListenerService() {', '''class JarvisNotificationListener : NotificationListenerService() {
    companion object {
        @Volatile var instance: JarvisNotificationListener? = null
        private val voipActions = linkedMapOf<String, PendingIntent>()
        fun performVoipAction(action: String): Boolean = runCatching {
            val p = synchronized(voipActions) { voipActions[action.lowercase()] } ?: return false
            p.send(); true
        }.getOrDefault(false)
    }

    override fun onCreate() { super.onCreate(); instance=this }
''',1)

needle='''        val packageName = source.packageName.orEmpty()
'''
voip=r'''        val packageName = source.packageName.orEmpty()
        val blob = "$title $text $bigText $subText $conversation".lowercase()
        val isVoipPkg = packageName.contains("whatsapp",true) || packageName.contains("facebook.orca",true) ||
            packageName.contains("instagram",true) || packageName.contains("zoom",true) || packageName.contains("teams",true) ||
            packageName.contains("telegram",true) || packageName.contains("meet",true)
        val looksRinging = listOf("llamada entrante","incoming call","videollamada","video call","calling","te está llamando","te esta llamando").any { blob.contains(it) }
        if (isVoipPkg && looksRinging) {
            synchronized(voipActions) { voipActions.clear() }
            n.actions?.forEach { a ->
                val label=a.title?.toString().orEmpty().lowercase()
                val key=when {
                    label.contains("contestar")||label.contains("answer")||label.contains("aceptar") -> "answer"
                    label.contains("rechazar")||label.contains("decline")||label.contains("reject")||label.contains("colgar") -> "reject"
                    else -> ""
                }
                if(key.isNotBlank() && a.actionIntent!=null) synchronized(voipActions){ voipActions[key]=a.actionIntent }
            }
            val sourceName = when {
                packageName.contains("whatsapp",true) -> "WhatsApp"
                packageName.contains("instagram",true) -> "Instagram"
                packageName.contains("facebook.orca",true) -> "Messenger"
                packageName.contains("zoom",true) -> "Zoom"
                packageName.contains("teams",true) -> "Teams"
                packageName.contains("telegram",true) -> "Telegram"
                else -> "Videollamada"
            }
            val call=JSONObject().put("id",source.key).put("source",sourceName).put("number","")
                .put("name", title.ifBlank{conversation}).put("photo","").put("knownContact",false)
                .put("video", blob.contains("video")||blob.contains("videollamada")).put("state","ringing")
                .put("canAnswer", synchronized(voipActions){voipActions.containsKey("answer")})
                .put("canReject", synchronized(voipActions){voipActions.containsKey("reject")})
                .put("time",System.currentTimeMillis())
            val prefs=getSharedPreferences("jarvis_mobile",MODE_PRIVATE)
            prefs.edit().putString("incoming_call_json",call.toString()).apply()
            runCatching { startService(Intent(this,JarvisOverlayService::class.java).setAction(JarvisOverlayService.ACTION_INCOMING_CALL).putExtra(JarvisOverlayService.EXTRA_CALL_JSON,call.toString())) }
        }
'''
if 'val isVoipPkg' not in s and needle in s:
    s=s.replace(needle,voip,1)

# clear current VoIP call when matching notification is removed
old='''    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val source = sbn ?: return
'''
new='''    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val source = sbn ?: return
        val prefs0=getSharedPreferences("jarvis_mobile",MODE_PRIVATE)
        val current=runCatching{JSONObject(prefs0.getString("incoming_call_json","{}"))}.getOrElse{JSONObject()}
        if(current.optString("id")==source.key){ prefs0.edit().remove("incoming_call_json").apply(); synchronized(voipActions){voipActions.clear()} }
'''
if old in s:
    s=s.replace(old,new,1)
s=s.replace('''    override fun onListenerDisconnected() {''','''    override fun onDestroy(){ if(instance===this) instance=null; super.onDestroy() }

    override fun onListenerDisconnected() {''',1)
p.write_text(s)

# --- Overlay: render incoming call card and execute actions ---
p=Path('mobile/src/main/java/com/jarvis/mobile/JarvisOverlayService.kt')
s=p.read_text()
if 'import android.telecom.TelecomManager' not in s:
    s=s.replace('import android.provider.Settings\n','import android.provider.Settings\nimport android.telecom.TelecomManager\nimport android.net.Uri\nimport android.widget.ImageView\n')
s=s.replace('''            ACTION_COMMAND -> handleCommand(intent.getStringExtra(EXTRA_COMMAND).orEmpty())
            else ->''','''            ACTION_COMMAND -> handleCommand(intent.getStringExtra(EXTRA_COMMAND).orEmpty())
            ACTION_INCOMING_CALL -> showIncomingCall(runCatching { JSONObject(intent.getStringExtra(EXTRA_CALL_JSON).orEmpty()) }.getOrElse { JSONObject() })
            else ->''',1)

marker='''    private fun speak(reply:String)'''
methods=r'''    private fun showIncomingCall(call: JSONObject) {
        if (Build.VERSION.SDK_INT>=Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) return
        val outer=baseOuter(); addHeader(outer,"")
        val name=call.optString("name").ifBlank { call.optString("number").ifBlank { "Llamada entrante" } }
        val source=call.optString("source").ifBlank{"Teléfono"}
        val photo=call.optString("photo")
        if(photo.isNotBlank()) runCatching { outer.addView(ImageView(this).apply { layoutParams=LinearLayout.LayoutParams(dp(72),dp(72)); scaleType=ImageView.ScaleType.CENTER_CROP; setImageURI(Uri.parse(photo)) }) }
        outer.addView(TextView(this).apply { text="☎  $name"; textSize=20f; setTextColor(Color.rgb(20,29,38)); setTypeface(typeface,android.graphics.Typeface.BOLD); setPadding(0,dp(8),0,0) })
        val subtitle=buildString { append(source); if(call.optString("number").isNotBlank()) append(" · ${call.optString("number")}"); if(call.optBoolean("video")) append(" · videollamada") }
        outer.addView(TextView(this).apply { text=subtitle; textSize=14f; setTextColor(Color.rgb(70,75,88)); setPadding(0,dp(5),0,dp(8)) })
        val controls=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        fun b(label:String,act:String)=controls.addView(Button(this).apply { text=label; setOnClickListener { performCallAction(call,act) } },LinearLayout.LayoutParams(0,dp(50),1f))
        b("Contestar","answer"); b("Rechazar","reject"); b("Dejar sonar","ignore")
        outer.addView(controls); attachOverlay(outer,"")
    }

    private fun performCallAction(call:JSONObject, action:String){
        if(action=="ignore"){ hide(); return }
        val source=call.optString("source")
        var ok=false
        if(source=="phone"){
            val tm=getSystemService(TELECOM_SERVICE) as TelecomManager
            ok=runCatching { if(action=="answer") { if(Build.VERSION.SDK_INT>=26) tm.acceptRingingCall(); true } else { @Suppress("DEPRECATION") tm.endCall() } }.getOrDefault(false)
        } else {
            ok=JarvisNotificationListener.performVoipAction(action)
        }
        if(ok){ getSharedPreferences("jarvis_mobile",MODE_PRIVATE).edit().remove("incoming_call_json").apply(); hide() }
        else showText("", "No puedo ejecutar ${if(action=="answer") "Contestar" else "Rechazar"} en $source desde Android. Hazlo manualmente en el teléfono.")
    }

'''
if 'private fun showIncomingCall' not in s and marker in s:
    s=s.replace(marker,methods+marker,1)
s=s.replace('''        const val ACTION_SHOW="com.jarvis.mobile.overlay.SHOW"; const val ACTION_HIDE="com.jarvis.mobile.overlay.HIDE"; const val ACTION_COMMAND="com.jarvis.mobile.overlay.COMMAND"
        const val EXTRA_TEXT="text"; const val EXTRA_COMMAND="command"''','''        const val ACTION_SHOW="com.jarvis.mobile.overlay.SHOW"; const val ACTION_HIDE="com.jarvis.mobile.overlay.HIDE"; const val ACTION_COMMAND="com.jarvis.mobile.overlay.COMMAND"; const val ACTION_INCOMING_CALL="com.jarvis.mobile.overlay.INCOMING_CALL"
        const val EXTRA_TEXT="text"; const val EXTRA_COMMAND="command"; const val EXTRA_CALL_JSON="call_json"''',1)
p.write_text(s)

# --- Bridge: TV can read and act on current incoming call ---
p=Path('mobile/src/main/java/com/jarvis/mobile/PhoneBridgeService.kt')
s=p.read_text()
if 'import android.telecom.TelecomManager' not in s:
    s=s.replace('import android.os.IBinder\n','import android.os.IBinder\nimport android.telecom.TelecomManager\n')
anchor='''        if (path.startsWith("/messages")) {'''
block='''        if (path.startsWith("/incoming-call")) {
            val raw=getSharedPreferences("jarvis_mobile",MODE_PRIVATE).getString("incoming_call_json","").orEmpty()
            return 200 to (if(raw.isBlank()) JSONObject().put("ringing",false) else JSONObject(raw).put("ringing",true)).toString()
        }
        if (path.startsWith("/call-action?")) {
            val action=URLDecoder.decode(path.substringAfter("action=","").substringBefore("&"),StandardCharsets.UTF_8.name()).lowercase()
            val raw=getSharedPreferences("jarvis_mobile",MODE_PRIVATE).getString("incoming_call_json","{}").orEmpty()
            val call=runCatching{JSONObject(raw)}.getOrElse{JSONObject()}
            val source=call.optString("source")
            val ok=if(source=="phone") {
                val tm=getSystemService(TELECOM_SERVICE) as TelecomManager
                runCatching { if(action=="answer") { if(Build.VERSION.SDK_INT>=26) tm.acceptRingingCall(); true } else if(action=="reject") { @Suppress("DEPRECATION") tm.endCall() } else true }.getOrDefault(false)
            } else if(action=="answer"||action=="reject") JarvisNotificationListener.performVoipAction(action) else true
            if(ok && action!="ignore") getSharedPreferences("jarvis_mobile",MODE_PRIVATE).edit().remove("incoming_call_json").apply()
            return 200 to JSONObject().put("ok",ok).put("action",action).toString()
        }
'''
if '/incoming-call' not in s and anchor in s:
    s=s.replace(anchor,block+anchor,1)
p.write_text(s)

print('Unified native + VoIP incoming call cards and TV bridge actions applied')
