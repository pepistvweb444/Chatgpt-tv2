from pathlib import Path

p=Path("mobile/src/main/java/com/jarvis/mobile/MainActivity.kt")
s=p.read_text()

# Imports for attachment picker/base64.
anchor="import android.net.Uri\n"
for imp in [
    "import android.database.Cursor\n",
    "import android.provider.OpenableColumns\n",
    "import android.util.Base64\n",
]:
    if imp not in s:
        s=s.replace(anchor,anchor+imp,1)

# Pending attachments live only until the next send.
field_anchor='    private var lastLocation: Location? = null\n'
if "private val pendingAttachments" not in s and field_anchor in s:
    s=s.replace(field_anchor,field_anchor+'    private val pendingAttachments = JSONArray()\n',1)

# Replace the +/files action with a real attachment picker.
old='''        findViewById<View>(R.id.files).setOnClickListener {
            closeDrawer(); runCatching { startActivity(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }) }
        }'''
new='''        findViewById<View>(R.id.files).setOnClickListener {
            closeDrawer()
            openAttachmentPicker()
        }'''
if old in s:
    s=s.replace(old,new,1)

# Add picker/result handling before the existing profile image result branch.
result_anchor='''        super.onActivityResult(requestCode, resultCode, data)
'''
if "requestCode == REQ_ATTACHMENT" not in s and result_anchor in s:
    block='''        if (requestCode == REQ_ATTACHMENT && resultCode == RESULT_OK) {
            val uris=mutableListOf<Uri>()
            data?.data?.let { uris.add(it) }
            val clip=data?.clipData
            if(clip!=null) for(i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
            var accepted=0
            for(uri in uris.distinct().take(4)) {
                if(addAttachment(uri)) accepted++
            }
            if(accepted>0) {
                status.text="Adjuntos preparados · "+pendingAttachments.length()
                Toast.makeText(this,"Adjuntos listos. Escribe una pregunta o pulsa enviar.",Toast.LENGTH_LONG).show()
            }
            return
        }
'''
    s=s.replace(result_anchor,result_anchor+block,1)

# Helpers before dp().
marker="    private fun dp(v: Int)"
if "private fun openAttachmentPicker()" not in s and marker in s:
    helpers=r'''    private fun openAttachmentPicker() {
        runCatching {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type="*/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true)
                putExtra(Intent.EXTRA_MIME_TYPES,arrayOf(
                    "image/*","application/pdf","text/plain","text/csv",
                    "application/json","application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                ))
            },REQ_ATTACHMENT)
        }.onFailure { Toast.makeText(this,"No se pudo abrir el selector de archivos",Toast.LENGTH_LONG).show() }
    }

    private fun attachmentName(uri:Uri):String {
        var name="archivo"
        runCatching {
            contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { c ->
                if(c.moveToFirst()) name=c.getString(0).orEmpty().ifBlank{"archivo"}
            }
        }
        return name.take(180)
    }

    private fun addAttachment(uri:Uri):Boolean {
        if(pendingAttachments.length()>=4) {
            Toast.makeText(this,"Máximo 4 adjuntos por mensaje",Toast.LENGTH_LONG).show()
            return false
        }
        val mime=contentResolver.getType(uri).orEmpty().ifBlank{"application/octet-stream"}
        val bytes=runCatching { contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() ?: return false
        if(bytes.size>3_000_000) {
            Toast.makeText(this,attachmentName(uri)+" supera 3 MB",Toast.LENGTH_LONG).show()
            return false
        }
        pendingAttachments.put(JSONObject()
            .put("name",attachmentName(uri))
            .put("mime",mime)
            .put("dataBase64",Base64.encodeToString(bytes,Base64.NO_WRAP)))
        return true
    }

    private fun attachmentSummary(a:JSONArray):String {
        if(a.length()==0)return ""
        val names=(0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("name") }.filter{it.isNotBlank()}
        return names.joinToString(", ")
    }

'''
    s=s.replace(marker,helpers+marker,1)

# Send can be attachment-only, and captures pending attachments for this request.
old='''    private fun sendMessage() {
        val message = input.text.toString().trim(); if (message.isBlank()) return
        input.text.clear()
        renderMessageCard("user", message)
        saveHistory("user", message, false)
        when (val kind = classifyVisualRequest(message)) {
            "news" -> openNewsFast()
            "weather" -> openWeatherForCurrentLocation(message)
            "home", "day" -> executeChat("$message. Devuelve cada elemento relevante en una línea separada, sin introducción ni conclusión.", kind)
            else -> executeChat(message, null)
        }
    }'''
new='''    private fun sendMessage() {
        val message=input.text.toString().trim()
        if(message.isBlank() && pendingAttachments.length()==0)return
        val attachments=JSONArray(pendingAttachments.toString())
        while(pendingAttachments.length()>0) pendingAttachments.remove(pendingAttachments.length()-1)
        input.text.clear()
        val shown=if(message.isNotBlank()) message else "Analiza los archivos adjuntos"
        val summary=attachmentSummary(attachments)
        renderMessageCard("user",shown+(if(summary.isNotBlank()) "\\n📎 "+summary else ""))
        saveHistory("user",shown,false)
        if(attachments.length()>0) {
            executeChat(shown,null,attachments)
            return
        }
        when (val kind = classifyVisualRequest(message)) {
            "news" -> openNewsFast()
            "weather" -> openWeatherForCurrentLocation(message)
            "home", "day" -> executeChat("$message. Devuelve cada elemento relevante en una línea separada, sin introducción ni conclusión.", kind)
            else -> executeChat(message, null)
        }
    }'''
if old in s:
    s=s.replace(old,new,1)

# Add optional attachments argument to chat call.
s=s.replace(
    "    private fun executeChat(message: String, widgetKind: String?) {",
    "    private fun executeChat(message: String, widgetKind: String?, attachments: JSONArray = JSONArray()) {",
    1
)
body_anchor='''                val body = JSONObject().put("message", message).put("conversationId", conversationId).put("client", "jarvis-mobile").put("history", history()).put("selectedTools", selected)'''
if body_anchor in s:
    s=s.replace(body_anchor,body_anchor+'.put("attachments",attachments)',1)

# Natural morning-alarm commands: Jarvis alarm always triggers the TV briefing.
send_anchor='''    private fun sendMessage() {'''
# Handle before chat by inserting into sendMessage after message/blank check.
needle='''        if(message.isBlank() && pendingAttachments.length()==0)return
        val attachments=JSONArray(pendingAttachments.toString())'''
if "handleMorningAlarmCommand(message)" not in s and needle in s:
    replacement='''        if(message.isBlank() && pendingAttachments.length()==0)return
        if(pendingAttachments.length()==0 && handleMorningAlarmCommand(message)) return
        val attachments=JSONArray(pendingAttachments.toString())'''
    s=s.replace(needle,replacement,1)

marker="    private fun classifyVisualRequest(text: String): String?"
if "private fun handleMorningAlarmCommand(" not in s and marker in s:
    helper=r'''    private fun handleMorningAlarmCommand(text:String):Boolean {
        val lower=text.lowercase(java.util.Locale.ROOT)
        val alarmIntent = lower.contains("alarma") || lower.contains("despiértame") || lower.contains("despiertame") ||
            lower.contains("despertador") || lower.contains("me voy a dormir") || lower.contains("me voy a acostar")
        if(!alarmIntent)return false
        val rx=Regex("""(?:a las|para las|las)\s+(\d{1,2})(?::(\d{2}))?""").find(lower)
        if(rx==null)return false
        val hour=rx.groupValues[1].toIntOrNull()?.coerceIn(0,23) ?: return false
        val minute=rx.groupValues.getOrNull(2)?.toIntOrNull()?.coerceIn(0,59) ?: 0
        prefs.edit()
            .putBoolean("morning_routine_enabled",true)
            .putBoolean("morning_routine_alarm",true)
            .putBoolean("morning_routine_tv",true)
            .putInt("morning_routine_hour",hour)
            .putInt("morning_routine_minute",minute)
            .apply()
        RoutineScheduler.scheduleMorning(this)
        val next=prefs.getLong("morning_routine_next",0L)
        val whenText=if(next>0) java.text.SimpleDateFormat("EEE d MMM · HH:mm",java.util.Locale("es","ES")).format(java.util.Date(next)) else "%02d:%02d".format(hour,minute)
        val reply="Alarma de mañana activada para "+whenText+". Al despertar abriré el Now Brief en la televisión con agenda, llamadas y mensajes."
        renderMessageCard("user",text)
        saveHistory("user",text,false)
        renderMessageCard("assistant",reply)
        saveHistory("assistant",reply,false)
        safeSpeak(reply)
        status.text="Rutina de mañana programada"
        return true
    }

'''
    s=s.replace(marker,helper+marker,1)

# Constants.
s=s.replace(
    "        private const val REQ_PROFILE_IMAGE = 1205",
    "        private const val REQ_PROFILE_IMAGE = 1205\n        private const val REQ_ATTACHMENT = 1206",
    1
)
p.write_text(s)

# Morning routine service: request a complete TV briefing on every Jarvis wake alarm.
p=Path("mobile/src/main/java/com/jarvis/mobile/MorningRoutineService.kt")
s=p.read_text()
s=s.replace(
    '.setContentText("Abriendo el briefing en la televisión")',
    '.setContentText("Preparando agenda, llamadas y mensajes en la televisión")',
    1
)
p.write_text(s)

print("Jarvis Mobile attachments and morning Now Brief trigger applied")
