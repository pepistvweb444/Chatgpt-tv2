from pathlib import Path

p=Path("app/src/main/java/com/jarvis/tv/MobileRemoteClient.kt")
s=p.read_text()
anchor="""    fun permissions(): JSONObject = get("/permissions", auth = true)
"""
if "fun callContact(" not in s and anchor in s:
    extra=r"""    fun calls(): JSONObject = get("/calls", auth = true)
    fun callContact(name:String, tvAudio:Boolean=true):JSONObject =
        get("/call-contact?name=${URLEncoder.encode(name,"UTF-8")}&tvAudio=${if(tvAudio)1 else 0}",auth=true)
    fun callNumber(number:String, tvAudio:Boolean=true):JSONObject =
        get("/call?number=${URLEncoder.encode(number,"UTF-8")}&tvAudio=${if(tvAudio)1 else 0}",auth=true)
    fun callAudioStatus():JSONObject = get("/call-audio-status",auth=true)
    fun registerTv(host:String):JSONObject =
        get("/register-tv?host=${URLEncoder.encode(host,"UTF-8")}",auth=true)
"""
    s=s.replace(anchor,anchor+extra,1)
p.write_text(s)

p=Path("app/src/main/java/com/jarvis/tv/MainActivity.kt")
s=p.read_text()
import_anchor="import android.net.Uri\n"
for imp in ["import android.provider.OpenableColumns\n","import android.util.Base64\n","import java.net.NetworkInterface\n","import java.net.Inet4Address\n"]:
    if imp not in s:
        s=s.replace(import_anchor,import_anchor+imp,1)

field_anchor='    private var conversationId: String = ""\n'
if "private val pendingAttachments" not in s and field_anchor in s:
    s=s.replace(field_anchor,field_anchor+'    private val pendingAttachments = JSONArray()\n',1)

s=s.replace('findViewById<Button>(R.id.assistantBubble).setOnClickListener { startVoiceInput() }',
            'findViewById<Button>(R.id.assistantBubble).setOnClickListener { openAttachmentPicker() }',1)

oncreate='        showHome()\n'
if "startTvCommandBridge()" not in s and oncreate in s:
    s=s.replace(oncreate,oncreate+"""        startTvCommandBridge()
        if(intent?.getBooleanExtra("show_morning_briefing",false)==true) {
            intent.removeExtra("show_morning_briefing")
            handler.postDelayed({ showMorningNowBrief() },700L)
        }
""",1)

newintent="""    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)"""
if newintent in s:
    pos=s.find(newintent)
    end=s.find("    }",pos)
    block=s[pos:end] if end>pos else ""
    if "show_morning_briefing" not in block:
        s=s.replace(newintent,newintent+"""
        if(intent.getBooleanExtra("show_morning_briefing",false)) {
            intent.removeExtra("show_morning_briefing")
            handler.postDelayed({ showMorningNowBrief() },350L)
        }""",1)

needle="""        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.text.clear()
        append("user", text)"""
if needle in s:
    replacement="""        val text=input.text.toString().trim()
        if(text.isEmpty() && pendingAttachments.length()==0)return
        val attachments=JSONArray(pendingAttachments.toString())
        while(pendingAttachments.length()>0) pendingAttachments.remove(pendingAttachments.length()-1)
        input.text.clear()
        val userText=if(text.isNotBlank())text else "Analiza los archivos adjuntos"
        val attachLabel=attachmentSummary(attachments)
        append("user",userText+(if(attachLabel.isNotBlank()) "\n📎 "+attachLabel else ""))
        if(handleTvCallCommand(userText)) return"""
    s=s.replace(needle,replacement,1)

s=s.replace('val result = postChat(resolveEndpoint(backend, "chat"), text, history, previous)',
            'val result = postChat(resolveEndpoint(backend, "chat"), userText, history, previous, attachments)',1)
s=s.replace('private fun postChat(endpoint: String, message: String, history: JSONArray, previousResponseId: String?): Pair<String, String?>',
            'private fun postChat(endpoint: String, message: String, history: JSONArray, previousResponseId: String?, attachments: JSONArray = JSONArray()): Pair<String, String?>',1)
payload_anchor='.put("history", historyPayload)\n'
if payload_anchor in s and '.put("attachments", attachments)' not in s:
    s=s.replace(payload_anchor,payload_anchor+'            .put("attachments", attachments)\n',1)

result_anchor='        super.onActivityResult(requestCode, resultCode, data)\n'
if "requestCode==REQ_ATTACHMENT" not in s and result_anchor in s:
    block="""        if(requestCode==REQ_ATTACHMENT && resultCode==RESULT_OK) {
            val uris=mutableListOf<Uri>()
            data?.data?.let{uris.add(it)}
            data?.clipData?.let { clip -> for(i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri) }
            var accepted=0
            for(uri in uris.distinct().take(4)) if(addAttachment(uri))accepted++
            if(accepted>0) {
                status.text="● Adjuntos preparados · "+pendingAttachments.length()
                Toast.makeText(this,"Adjuntos listos para enviar a Jarvis",Toast.LENGTH_LONG).show()
            }
            return
        }
"""
    s=s.replace(result_anchor,result_anchor+block,1)

marker="    private fun showSettings()"
if "private fun openAttachmentPicker()" not in s and marker in s:
    helpers=r"""    private fun openAttachmentPicker() {
        runCatching {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type="*/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true)
            },REQ_ATTACHMENT)
        }.onFailure { Toast.makeText(this,"No se pudo abrir el selector de archivos",Toast.LENGTH_LONG).show() }
    }

    private fun attachmentName(uri:Uri):String {
        var name="archivo"
        runCatching {
            contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { c ->
                if(c.moveToFirst())name=c.getString(0).orEmpty().ifBlank{"archivo"}
            }
        }
        return name.take(180)
    }

    private fun addAttachment(uri:Uri):Boolean {
        if(pendingAttachments.length()>=4)return false
        val bytes=runCatching{contentResolver.openInputStream(uri)?.use{it.readBytes()}}.getOrNull()?:return false
        if(bytes.size>3_000_000) {
            Toast.makeText(this,attachmentName(uri)+" supera 3 MB",Toast.LENGTH_LONG).show()
            return false
        }
        pendingAttachments.put(JSONObject()
            .put("name",attachmentName(uri))
            .put("mime",contentResolver.getType(uri).orEmpty().ifBlank{"application/octet-stream"})
            .put("dataBase64",Base64.encodeToString(bytes,Base64.NO_WRAP)))
        return true
    }

    private fun attachmentSummary(a:JSONArray):String =
        (0 until a.length()).mapNotNull{a.optJSONObject(it)?.optString("name")}.filter{it.isNotBlank()}.joinToString(", ")

    private fun localTvIp():String = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap{it.inetAddresses.toList()}
            .firstOrNull{!it.isLoopbackAddress && it is Inet4Address}
            ?.hostAddress ?: ""
    }.getOrDefault("")

    private fun startTvCommandBridge() {
        runCatching { ContextCompat.startForegroundService(this,Intent(this,TvCommandService::class.java)) }
    }

    private fun handleTvCallCommand(text:String):Boolean {
        if(!mobileRemote.configured())return false
        val lower=text.lowercase(Locale.ROOT)
        val prefixes=listOf("llama a ","llamar a ","llama al ","llamar al ")
        val callPrefix=prefixes.firstOrNull{lower.startsWith(it)}
        val numberMatch=Regex("""^(?:llama|llamar)\s+([+0-9][0-9 .-]{5,})$""").find(lower)
        if(callPrefix==null && numberMatch==null)return false
        status.text="● Enviando llamada al teléfono…"
        Thread {
            val result=runCatching {
                if(numberMatch!=null) mobileRemote.callNumber(numberMatch.groupValues[1],true)
                else mobileRemote.callContact(text.substring(callPrefix!!.length).trim(),true)
            }
            runOnUiThread {
                if(result.isSuccess) {
                    append("assistant","He iniciado la llamada en el teléfono. Usaré la televisión como audio si Android la reconoce como dispositivo Bluetooth de llamadas.",true)
                    status.text="● Llamada iniciada"
                } else {
                    append("assistant","No he podido iniciar la llamada: "+(result.exceptionOrNull()?.message?:"error"),true)
                    status.text="● Error de llamada"
                }
            }
        }.start()
        return true
    }

    private fun addMorningWidget(titleText:String, bodyText:String, accent:Int) {
        val card=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(20,16,20,16)
            setBackgroundColor(accent)
            layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT).apply{bottomMargin=12}
        }
        card.addView(TextView(this).apply{text=titleText;textSize=19f;setTextColor(0xFFFFFFFF.toInt());setTypeface(typeface,android.graphics.Typeface.BOLD)})
        card.addView(TextView(this).apply{text=bodyText.ifBlank{"Sin novedades"};textSize=15f;setTextColor(0xFFE7EBF2.toInt());setPadding(0,7,0,0)})
        personalWidgetContainer.addView(card)
    }

    private fun showMorningNowBrief() {
        title.text="Jarvis · Now Brief"
        subtitle.text="Agenda · llamadas · mensajes · redes sociales"
        personalWidgetContainer.visibility=android.view.View.VISIBLE
        personalWidgetContainer.removeAllViews()
        personalWidgetContainer.addView(TextView(this).apply {
            text="Buenos días · tu resumen";textSize=26f;setTextColor(0xFFFFFFFF.toInt());setTypeface(typeface,android.graphics.Typeface.BOLD);setPadding(4,8,4,16)
        })
        if(!mobileRemote.configured()) {
            addMorningWidget("Teléfono no conectado","Vincula Jarvis Mobile para cargar el briefing real.",0xFF5C3A26.toInt())
            return
        }
        status.text="● Preparando Now Brief…"
        Thread {
            val agenda=runCatching{mobileRemote.agenda()}.getOrNull()
            val calls=runCatching{mobileRemote.calls()}.getOrNull()
            val inbox=runCatching{mobileRemote.unreadMessages()}.getOrNull()
            runOnUiThread {
                val events=agenda?.optJSONArray("events")?:JSONArray()
                val eventLines=mutableListOf<String>()
                for(i in 0 until minOf(events.length(),8)) {
                    val o=events.optJSONObject(i)?:continue
                    eventLines += o.optString("title").ifBlan{{"Evento"}+" · "+formatSyncTime(o.optLong("begin"),o.optBoolean("allDay"))
                }
                addMorningWidget("📅 Agenda",eventLines.joinToString("\n").ifBlank{"Sin citas próximas"},0xFF362B5F.toInt())

                val callItems=calls?.optJSONArray("items")?:JSONArray()
                val callLines=mutableListOf<String>()
                for(i in callItems.length()-1 downTo 0) {
                    if(callLines.size>=6)break
                    val o=callItems.optJSONObject(i)?:continue
                    val kind=o.optString("kind")
                    if(kind!="missed" && kind!="incoming" && kind!="rejected" && !o.optBoolean("pending"))continue
                    callLines += o.optString("name").ifBlan{{o.optString("number").ifBlan{{"Llamada"}}+" · "+kind
                }
                addMorningWidget("☎ Llamadas",callLines.joinToString("\n").ifBlank{"Sin llamadas pendientes"},0xFF50343C.toInt())

                val messages=inbox?.optJSONArray("items")?:inbox?.optJSONArray("messages")?:JSONArray()
                val msgLines=mutableListOf<String>()
                for(i in messages.length()-1 downTo 0) {
                    if(msgLines.size>=10)break
                    val o=messages.optJSONObject(i)?:continue
                    val source=o.optString("source").ifBlank{"Mensaje"}
                    val who=o.optString("from").ifBlank{o.optString("title")}
                    val body=o.optString("text").replace("\n"," ").take(100)
                    if(body.isNotBlank())msgLines += source+" · "+who+": "+body
                }
                addMorningWidget("💬 Mensajes y redes",msgLines.joinToString("\n").ifBlank{"Sin mensajes pendientes"},0xFF263F4C.toInt())
                status.text="● Now Brief actualizado"
            }
        }.start()
    }

"""
    s=s.replace(marker,helpers+marker,1)

s=s.replace("mobileRemote.pair(pinValue)\n                        mobileRemote.ping()",
            "mobileRemote.pair(pinValue)\n                        localTvIp().takeIf{it.isNotBlank()}?.let{mobileRemote.registerTv(it)}\n                        mobileRemote.ping()",1)
s=s.replace("mobileRemote.pair(pin)\n                        val ping=mobileRemote.ping()",
            "mobileRemote.pair(pin)\n                        localTvIp().takeIf{it.isNotBlank()}?.let{mobileRemote.registerTv(it)}\n                        val ping=mobileRemote.ping()",1)
s=s.replace("        private const val REQ_OVERLAY = 30",
            "        private const val REQ_OVERLAY = 30\n        private const val REQ_ATTACHMENT = 31",1)
p.write_text(s)

p=Path("app/src/main/AndroidManifest.xml")
x=p.read_text()
service_anchor="""        <service
            android:name=".WakeWordService""""
if ".TvCommandService" not in x and service_anchor in x:
    block="""        <service
            android:name=".TvCommandService"
            android:enabled="true"
            android:exported="false"
            android:stopWithTask="false" />

"""
    x=x.replace(service_anchor,block+service_anchor,1)
p.write_text(x)

print("Javistv calls attachments and morning briefing hotfix applied")
