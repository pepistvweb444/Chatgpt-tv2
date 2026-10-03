from pathlib import Path

def replace_function(text, signature, replacement):
    start=text.find(signature)
    if start<0: raise SystemExit(signature+" not found")
    brace=text.find("{",start); depth=0; ins=False; esc=False
    for i in range(brace,len(text)):
        ch=text[i]
        if ins:
            if esc: esc=False
            elif ch=="\\": esc=True
            elif ch=='"': ins=False
        else:
            if ch=='"': ins=True
            elif ch=="{": depth+=1
            elif ch=="}":
                depth-=1
                if depth==0:return text[:start]+replacement+text[i+1:]
    raise SystemExit("end not found")

p=Path("app/src/main/java/com/jarvis/tv/MobileRemoteClient.kt")
s=p.read_text()
anchor='    fun permissions(): JSONObject = get("/permissions", auth = true)\n'
if "fun activityHub()" not in s:
    extra=r'''    fun activityHub(): JSONObject = get("/activity-hub", auth = true)
    fun calls(): JSONObject = get("/calls", auth = true)
    fun callContact(name:String,tvAudio:Boolean=true):JSONObject =
        get("/call-contact?name=${URLEncoder.encode(name,"UTF-8")}&tvAudio=${if(tvAudio)1 else 0}",auth=true)
    fun callNumber(number:String,tvAudio:Boolean=true):JSONObject =
        get("/call?number=${URLEncoder.encode(number,"UTF-8")}&tvAudio=${if(tvAudio)1 else 0}",auth=true)
    fun callAudioStatus(): JSONObject = get("/call-audio-status",auth=true)
'''
    if anchor not in s: raise SystemExit("permissions anchor missing")
    s=s.replace(anchor,anchor+extra,1)
p.write_text(s)

p=Path("app/src/main/java/com/jarvis/tv/MainActivity.kt")
s=p.read_text()

hub=r'''    private fun showNotifications() {
        title.text="Resumen del día"
        subtitle.text="Calendario · llamadas · mensajes · redes · correo"
        personalWidgetContainer.visibility=android.view.View.VISIBLE
        personalWidgetContainer.removeAllViews()
        personalWidgetContainer.addView(pText("Jarvis · Resumen del día",24f,true,Color.rgb(205,213,255)).apply {
            setPadding(pDp(4),pDp(8),pDp(4),pDp(12))
        })
        if(!mobileRemote.configured()) {
            val card=LinearLayout(this).apply {
                orientation=LinearLayout.VERTICAL; background=pRounded(Color.rgb(92,58,38))
                setPadding(pDp(18),pDp(14),pDp(18),pDp(14))
          }
            card.addView(pText("Teléfono no enlazado",18f,true))
            card.addView(pText("Abre ☱ → Vincular móvil y empareja Jarvis Mobile con IP + PIN.",15f))
            personalWidgetContainer.addView(card)
            return
        }
        status.text="● Sincronizando hub del teléfono…"
        Thread {
            val agenda=runCatching{mobileRemote.agenda()}.getOrNull()
            val calls=runCatching{mobileRemote.calls()}.getOrNull()
            val hub=runCatching{mobileRemote.activityHub()}.getOrNull()
            runOnUiThread {
                personalWidgetContainer.removeAllViews()
                fun heading(t:String){ personalWidgetContainer.addView(pText(t,19f,true,Color.rgb(198,207,245)).apply{setPadding(pDp(4),pDp(16),pDp(4),pDp(8))}) }
                fun card(t:String,b:String,color:Int=Color.rgb(31,40,55)){
                    val v=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;background=pRounded(color);setPadding(pDp(18),pDp(14),pDp(18),pDp(14));layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT).apply{bottomMargin=pDp(9)}}
                    v.addView(pText(t,17f,true)); if(b.isNotBlank())v<addView(pText(b,14.5f,false,Color.rgb(220,228,240)).apply{setPadding(0,pDp(5),0,0)}); personalWidgetContainer.addView(v)
                }
                personalWidgetContainer.addView(pText("Jarvis · Resumen del día",24f,true,Color.rgb(205,213,255)).apply{setPadding(pDp(4),pDp(8),pDp(4),pDp(12))})

                heading("Calendario")
                val events=agenda?.optJSONArray("events")?:JSONArray()
                if(events.length()==0) card("Sin citas próximas",if(agenda?.optBoolean("calendarPermission")==true)"El calendario del teléfono no devuelve eventos próximos." else "Falta permiso de Calendario en Jarvis Mobile.")
                for(i in 0 until minOf(events.length(),8)){
                    val e=events.optJSONObject(i)?:continue
                    card("📅 "+e.optString("title").ifBlank{"Evento"},listOf(formatSyncTime(e.optLong("begin"),e.optBoolean("allDay")),e.optString("location")).filter{it.isNotBlank()}.joinToString(" · "),Color.rgb(55,45,108))
                }

                heading("Llamadas")
                val callItems=calls?.optJSONArray("items")?:JSONArray(); var shownCalls=0
                for(i in 0 until callItems.length()){
                    if(shownCalls>=10)break
                    val c=callItems.optJSONObject(i)?:continue
                    val kind=c.optString("kind")
                    val label=when(kind){"missed"->"Perdida";"incoming"->"Recibida";"outgoing"->"Realizada";"rejected"->"Rechazada";else->kind}
                    val whenText=if(c.optLong("time")>0)formatSyncTime(c.optLong("time"),false)else""
                    card("☎ "+c.optString("name").ifBlank{c.optString("number").ifBlank{"Llamada"}},listOf(label,whenText,c.optString("number")).filter{it.isNotBlank()}.joinToString(" · "),if(kind=="missed")Color.rgb(92,58,38)Else Color.rgb(44,48,65));shownCalls++
                }
                if(shownCalls==0)card("Sin llamadas recientes","No hay llamadas recientes disponibles o falta permiso de Registro de llamadas.")

                val items=hub?.optJSONArray("items")?:JSONArray()
                fun category(cat:String,label:String,color:Int,limit:Int){
                    heading(label);var shown=0
                    for(i in 0 until items.length()){
                        if(shown>=limit)break
                        val o=items.optJSONObject(i)?:continue
                        if(o.optString("category")!=cat)continue
                        val who=o.optString("conversation").ifBlank{o.optString("title")}.ifBlank{o.optString("source")}
                        val body=o.optString("text").replace("\n"," ").take(420)+(if(o.optBoolean("active")" · pendiente" else "")
                        card(o.optString("source")+" ·"+who,body,color);shown++
                    }
                    if(shown==0)card("Sin novedades",if(hub?.optBoolean("notificationAccess")==true)"No hay actividad reciente en esta categoría." else "Activa Acceso a notificaciones en Jarvis Mobile.")
                }
                category("messages","Mensajes",Color.rgb(37,62,82),12)
                category("social","Redes sociales",Color.rgb(65,45,76),12)
                category("mail","Correo",Color.rgb(45,68,61),10)
                status.text="● Resumen actualizado"
                findViewById<android.widget.ScrollView>(R.id.mainScroll).post{findViewById<android.widget.ScrollView>(R.id.mainScroll).smoothScrollTo(0,0)}
            }
        }.start()
    }'''
s=replace_function(s,"    private fun showNotifications()",hub)

if "private fun handleRemoteCallCommand(" not in s:
    marker="    private fun showSettings()"
    helper=r'''    private fun handleRemoteCallCommand(text:String):Boolean {
        if(!mobileRemote.configured())return false
        val lower=text.lowercase(Locale.ROOT)
        val prefix=listOf("llama a ","llamar a ","llama al ","llamar al ").firstOrNull{lower.startsWith(it)}
        val number=Regex("^((?:llama|llamar)\\\\s+([+0-9][0-9 .-]{5,})$").find(lower)
        if(prefix==null && number==null)return false
        status.text="‏ Iniciando llamada en el teléfono…"
        Thread {
            val result=runCatching {
                if(number!=null)mobileRemote.callNumber(number.groupValues[1],true)
                else mobileRemote.callContact(text.substring(prefix!!.length).trim(),true)
            }
            runOnUiThread {
                if(result.isSuccess){append("assistant","Llamada iniciada en el teléfono. El audio podrá pasar a la TV si Android la ofrece como dispositivo de llamada.",true);status.text="‏ Llamada iniciada"}
                else{append("assistant","No se pudo iniciar la llamada: "+(result.exceptionOrNull()?.message?:"error"),true);status.text="● Error de llamada"}
            }
        }.start()
        return true
    }

'''
    if marker not in s: raise SystemExit("settings marker missing")
    s=s.replace(marker,helper+mkarker,1)

needle='        append("user", text)\n'
if needle in s and "handleRemoteCallCommand(text)" not in s:
    s=s.replace(needle,needle+'        if(handleRemoteCallCommand(text)) return\n',1)

oncreate='        showHome()\n'
if "startTvCommandServiceV26()" not in s and oncreate in s:
    s=s.replace(oncreate,oncreate+'''        startTvCommandServiceV26()
        if(intent?.getBooleanExtra("show_morning_briefing",false)==true) {
            intent.removeExtra("show_morning_briefing")
            handler.postDelayed({showNotifications()},700L)
        }
''',1)
marker="    private fun showSettings()"
if "private fun startTvCommandServiceV26()" not in s and marker in s:
    s=s.replace(marker,r'''    private fun startTvCommandServiceV26() {
        runCatching { ContextCompat.startForegroundService(this,Intent(this,TvCommandService::class.java)) }
    }

'v'''+marker,1)

p.write_text(s)

p=Path("app/src/main/AndroidManifest.xml")
x=p.read_text()
anchor='''        <service
            android:name=".WakeWordService"'''
if ".TvCommandService" not in x and anchor in x:
    x=x.replace(anchor,'''        <service
            android:name=".TvCommandService"
            android:enabled="true"
            android:exported="false"
            android:stopWithTask="false" />

'''+anchor,a)
p.write_text(x)

print("Javistv activity hub and TV-originated call control applied")
