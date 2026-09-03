from pathlib import Path

p=Path('app/src/main/java/com/jarvis/tv/MainActivity.kt')
s=p.read_text()

def replace_function(text, signature, replacement):
    start=text.find(signature)
    if start<0:return text,False
    brace=text.find('{',start)
    if brace<0:return text,False
    depth=0;end=-1
    for i in range(brace,len(text)):
        if text[i]=='{':depth+=1
        elif text[i]=='}':
            depth-=1
            if depth==0:
                end=i+1;break
    if end<0:return text,False
    return text[:start]+replacement+text[end:],True

# Keep track of the preview that currently owns the streaming row, so a slow
# network response from Netflix cannot overwrite YouTube after focus moved.
field_anchor='    private val tvAppAgent by lazy { TvAppAgentController(this) }\n'
if 'private var streamingPreviewProvider' not in s:
    if field_anchor in s:
        s=s.replace(field_anchor,field_anchor+'    private var streamingPreviewProvider: String = ""\n',1)
    else:
        conv='    private var conversationId: String = ""\n'
        if conv not in s: raise SystemExit('streaming preview field anchor not found')
        s=s.replace(conv,conv+'    private var streamingPreviewProvider: String = ""\n',1)

# Chat scroll target: the transcript is above widgets/integration chips. Scrolling
# to the absolute bottom left users looking at widgets rather than Jarvis' answer.
helper_anchor='    private fun updateChatMeta(text: String, role: String) {'
if 'private fun scrollToLatestAnswer(' not in s:
    helper=r'''    private fun scrollToLatestAnswer(focusTranscript: Boolean = false) {
        if (!::transcript.isInitialized) return
        val mainScroll=findViewById<android.widget.ScrollView>(R.id.mainScroll)
        transcript.post {
            val target=(transcript.bottom-mainScroll.height+pDp(34)).coerceAtLeast(0)
            mainScroll.smoothScrollTo(0,target)
            if(focusTranscript) {
                transcript.isFocusable=true
                transcript.isFocusableInTouchMode=true
                transcript.requestFocus()
            }
        }
    }

'''
    if helper_anchor not in s: raise SystemExit('chat scroll helper anchor not found')
    s=s.replace(helper_anchor,helper+helper_anchor,1)

append_fn=r'''    private fun append(role: String, text: String, speak: Boolean = false) {
        val arr = historyArray(); arr.put(JSONObject().put("role", role).put("content", text))
        prefs.edit().putString("chat_$conversationId", arr.toString()).apply()
        transcript.append(if (role == "user") "\nTÚ\n$text\n" else "\n${assistantName().uppercase()}\n$text\n")
        updateChatMeta(text, role)
        handler.post { scrollToLatestAnswer(false) }
        if (role == "assistant" && speak) speakWithOpenAI(text)
    }'''
s,ok=replace_function(s,'    private fun append(role: String, text: String, speak: Boolean = false)',append_fn)
if not ok: raise SystemExit('append function not found')

# Loading a previous chat should also land on its latest answer.
load_sig='    private fun loadConversation(id: String)'
start=s.find(load_sig)
if start<0: raise SystemExit('loadConversation not found')
end_marker='        currentChatLabel.text = title\n'
pos=s.find(end_marker,start)
if pos>=0 and 'scrollToLatestAnswer(false)' not in s[pos:pos+180]:
    s=s[:pos+len(end_marker)]+ '        handler.post { scrollToLatestAnswer(false) }\n' + s[pos+len(end_marker):]

# Opening Chat explicitly shows the latest response, while keeping the composer fixed.
old_show='    private fun showChat() { title.text = "ChatGPT"; subtitle.text = "Conversación con memoria y búsqueda web"; input.requestFocus() }'
new_show='    private fun showChat() { title.text = "ChatGPT"; subtitle.text = "Conversación con memoria y búsqueda web"; scrollToLatestAnswer(false); input.requestFocus() }'
if old_show in s:s=s.replace(old_show,new_show,1)

# Dashboard cards are D-pad focusable. Clicking an informational widget returns to
# the latest Jarvis response, matching the requested TV navigation behaviour.
card_fn=r'''    private fun addDashboardCard(titleText:String, bodyText:String, color:Int=Color.rgb(31,40,55)): LinearLayout {
        val card=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL; background=pRounded(color); setPadding(pDp(18),pDp(14),pDp(18),pDp(14))
            layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT).apply{bottomMargin=pDp(9)}
            isFocusable=true; isClickable=true
            setOnFocusChangeListener { view, focused -> focusCard(view,focused) }
            setOnClickListener { scrollToLatestAnswer(true) }
        }
        card.addView(pText(titleText,17f,true))
        if(bodyText.isNotBlank()) card.addView(pText(bodyText,14.5f,false,Color.rgb(220,228,240)).apply{setPadding(0,pDp(5),0,0)})
        personalWidgetContainer.addView(card)
        return card
    }'''
s,ok=replace_function(s,'    private fun addDashboardCard(titleText:String, bodyText:String, color:Int=Color.rgb(31,40,55))',card_fn)
if not ok: raise SystemExit('addDashboardCard not found')

# Agenda requests from chat should include delivery/ride activity too, not just
# CalendarContract + reminder notifications.
old_agenda_fetch='''                    val result = mobileRemote.agenda()
                    runOnUiThread {
                        renderAgendaSync(result)
                        status.text = "● Móvil · agenda sincronizada"
                    }'''
new_agenda_fetch='''                    val result = mobileRemote.agenda()
                    val mobility = runCatching { mobileRemote.mobility() }.getOrNull()
                    runOnUiThread {
                        renderAgendaSync(result, mobility)
                        status.text = "● Móvil · agenda y actividad sincronizadas"
                    }'''
if old_agenda_fetch in s:
    s=s.replace(old_agenda_fetch,new_agenda_fetch,1)

agenda_fn=r'''    private fun renderAgendaSync(data: JSONObject, mobility: JSONObject? = null) {
        personalWidgetContainer.removeAllViews()
        personalWidgetContainer.visibility = View.VISIBLE
        val events=data.optJSONArray("events") ?: JSONArray()
        val reminders=data.optJSONArray("reminders") ?: JSONArray()
        val activity=mobility?.optJSONArray("items") ?: JSONArray()
        val permission=data.optBoolean("calendarPermission")
        val now=System.currentTimeMillis()
        var shown=0

        personalWidgetContainer.addView(pText("Jarvis · Agenda, pedidos y recordatorios",20f,true,Color.rgb(205,213,255)).apply{setPadding(pDp(4),pDp(6),pDp(4),pDp(12))})

        // Active/recent delivery and mobility states belong in the same personal
        // timeline because they are time-sensitive items the user must act on.
        for(i in activity.length()-1 downTo 0) {
            val a=activity.optJSONObject(i)?:continue
            val time=a.optLong("time")
            if(time>0 && now-time>72L*60L*60L*1000L) continue
            val source=a.optString("source").ifBlank{"Actividad"}
            val kind=a.optString("kind")
            val icon=if(kind=="delivery")"🛍" else if(kind=="ride")"🚕" else "●"
            val eta=a.optString("etaMinutes").takeIf{it.isNotBlank()}?.let{"ETA $it min"}.orEmpty()
            val whenText=if(time>0)formatSyncTime(time,false)else""
            val body=listOf(a.optString("title"),a.optString("text"),eta,whenText).filter{it.isNotBlank()}.joinToString(" · ")
            addDashboardCard("$icon $source",body,if(kind=="delivery")Color.rgb(35,91,70)else Color.rgb(43,55,79))
            shown++; if(shown>=8)break
        }

        if(!permission) addDashboardCard("Calendario sin permiso en el móvil","Concede a Jarvis Mobile acceso al calendario. Los pedidos y otros estados del teléfono seguirán apareciendo aquí.",Color.rgb(60,44,92))

        for(i in 0 until events.length()) {
            val e=events.optJSONObject(i)?:continue
            val whenText=formatSyncTime(e.optLong("begin"),e.optBoolean("allDay"))
            val detail=listOf(whenText,e.optString("calendar"),e.optString("location")).filter{it.isNotBlank()}.joinToString(" · ")
            addDashboardCard("📅 "+e.optString("title").ifBlank{"Evento"},detail,Color.rgb(55,45,108)); shown++
        }
        for(i in 0 until reminders.length()) {
            val r=reminders.optJSONObject(i)?:continue
            val at=r.optLong("time")
            val whenText=if(at>0)formatSyncTime(at,false)else""
            addDashboardCard("⏰ "+r.optString("title").ifBlank{"Recordatorio"},listOf(r.optString("text"),whenText).filter{it.isNotBlank()}.joinToString(" · "),Color.rgb(42,76,67)); shown++
        }
        if(shown==0) addDashboardCard("Sin actividad próxima","No hay citas, recordatorios, pedidos o trayectos recientes detectados.")
    }'''
s,ok=replace_function(s,'    private fun renderAgendaSync(data: JSONObject)',agenda_fn)
if not ok:
    # It may already carry a modified signature in a rerun; tolerate that form.
    s,ok=replace_function(s,'    private fun renderAgendaSync(data: JSONObject, mobility: JSONObject? = null)',agenda_fn)
if not ok: raise SystemExit('renderAgendaSync not found')

# Combined personal centre: delivery states appear immediately with agenda items,
# while the lower Mobility section keeps only rides/transit to avoid duplicates.
dashboard_fn=r'''    private fun renderPersonalDashboard(agenda:JSONObject?, calls:JSONObject?, mobility:JSONObject?) {
        personalWidgetContainer.removeAllViews()
        personalWidgetContainer.visibility=View.VISIBLE
        personalWidgetContainer.addView(pText("Jarvis · Centro personal",22f,true,Color.rgb(205,213,255)).apply{setPadding(pDp(4),pDp(6),pDp(4),pDp(10))})

        val events=agenda?.optJSONArray("events") ?: JSONArray()
        val reminders=agenda?.optJSONArray("reminders") ?: JSONArray()
        val mItems=mobility?.optJSONArray("items") ?: JSONArray()
        val now=System.currentTimeMillis()

        addDashboardHeading("Agenda, pedidos y recordatorios")
        var shownDeliveries=0
        for(i in mItems.length()-1 downTo 0) {
            if(shownDeliveries>=8) break
            val m=mItems.optJSONObject(i)?:continue
            val time=m.optLong("time")
            if(time>0 && now-time>72L*60L*60L*1000L) continue
            val source=m.optString("source").ifBlank{"Pedido"}
            val kind=m.optString("kind")
            val isDelivery=kind=="delivery" || listOf("glovo","uber eats","just eat","deliveroo").any{source.contains(it,true)}
            if(!isDelivery) continue
            val eta=m.optString("etaMinutes").takeIf{it.isNotBlank()}?.let{"ETA $it min"}.orEmpty()
            val whenText=if(time>0)formatSyncTime(time,false)else""
            val body=listOf(m.optString("title"),m.optString("text"),eta,whenText).filter{it.isNotBlank()}.joinToString(" · ")
            addDashboardCard("🛍 $source",body,Color.rgb(35,91,70)); shownDeliveries++
        }
        for(i in 0 until events.length()) {
            val e=events.optJSONObject(i)?:continue
            val whenText=formatSyncTime(e.optLong("begin"),e.optBoolean("allDay"))
            val detail=listOf(whenText,e.optString("calendar"),e.optString("location")).filter{it.isNotBlank()}.joinToString(" · ")
            addDashboardCard("📅 "+e.optString("title").ifBlank{"Evento"},detail,Color.rgb(55,45,108))
        }
        for(i in 0 until reminders.length()) {
            val r=reminders.optJSONObject(i)?:continue
            val at=r.optLong("time")
            val whenText=if(at>0)formatSyncTime(at,false)else""
            addDashboardCard("⏰ "+r.optString("title").ifBlank{"Recordatorio"},listOf(r.optString("text"),whenText).filter{it.isNotBlank()}.joinToString(" · "),Color.rgb(42,76,67))
        }
        if(events.length()==0 && reminders.length()==0 && shownDeliveries==0) addDashboardCard("Sin actividad próxima","No hay citas, recordatorios o pedidos recientes detectados.")

        addDashboardHeading("Llamadas")
        val current=calls?.optJSONObject("current")
        if(current?.optBoolean("active",false)==true) {
            val who=current.optString("name").ifBlank{current.optString("number").ifBlank{"Llamada entrante"}}
            val cls=when(current.optString("classification")){"spam_probable"->"Spam probable · ${current.optInt("spamScore")}%";"possible_spam"->"Posible spam";"contact"->"Contacto";else->"Sin clasificar"}
            addDashboardCard("☎ $who",cls,Color.rgb(88,52,52))
        }
        val callItems=calls?.optJSONArray("items") ?: JSONArray(); var shownCalls=0
        for(i in 0 until callItems.length()) {
            val c=callItems.optJSONObject(i)?:continue; val kind=c.optString("kind")
            if(kind!="missed" && kind!="incoming" && kind!="rejected" && !c.optBoolean("pending")) continue
            val label=when{c.optBoolean("pending")->"⚠ Llamada perdida pendiente";kind=="missed"->"Llamada perdida";kind=="rejected"->"Llamada rechazada";else->"Llamada entrante"}
            val time=if(c.optLong("time")>0)formatSyncTime(c.optLong("time"),false)else""
            val who=c.optString("name").ifBlank{c.optString("number").ifBlank{"Número oculto"}}
            addDashboardCard("$label · $who",listOf(time,c.optString("number").takeIf{it.isNotBlank()&&it!=who}.orEmpty()).filter{it.isNotBlank()}.joinToString(" · "),if(c.optBoolean("pending"))Color.rgb(92,58,38)else Color.rgb(44,48,65))
            shownCalls++; if(shownCalls>=10)break
        }
        val appCalls=calls?.optJSONArray("appCalls") ?: JSONArray()
        for(i in 0 until minOf(appCalls.length(),5)) { val c=appCalls.optJSONObject(i)?:continue; addDashboardCard("Llamada de app · ${c.optString("name")}",c.optString("detail"),Color.rgb(44,48,65)) }
        if(shownCalls==0 && appCalls.length()==0 && current?.optBoolean("active",false)!=true) addDashboardCard("Sin llamadas pendientes","No hay llamadas perdidas o entrantes recientes pendientes de revisar.")

        addDashboardHeading("Movilidad")
        var shownMobility=0
        for(i in mItems.length()-1 downTo 0) {
            if(shownMobility>=8)break
            val m=mItems.optJSONObject(i)?:continue
            val time=m.optLong("time"); if(time>0 && now-time>72L*60L*60L*1000L)continue
            val source=m.optString("source").ifBlank{"Movilidad"}; val kind=m.optString("kind")
            val isDelivery=kind=="delivery" || listOf("glovo","uber eats","just eat","deliveroo").any{source.contains(it,true)}
            if(isDelivery)continue
            val eta=m.optString("etaMinutes").takeIf{it.isNotBlank()}?.let{"ETA $it min"}.orEmpty()
            val body=listOf(m.optString("title"),m.optString("text"),eta).filter{it.isNotBlank()}.joinToString(" · ")
            addDashboardCard((if(kind=="transit")"🚌 " else "🚕 ")+source,body,Color.rgb(43,55,79)); shownMobility++
        }
        if(shownMobility==0)addDashboardCard("Sin trayectos activos","Bolt, Cabify, Uber, Villavesa y otros aparecerán aquí cuando exista un estado reciente.")

        addDashboardHeading("Movimientos económicos")
        addDashboardCard("Rea / Era · consultando…","Jarvis está consultando la misma conexión financiera usada por la versión móvil.",Color.rgb(50,54,76))
    }'''
s,ok=replace_function(s,'    private fun renderPersonalDashboard(agenda:JSONObject?, calls:JSONObject?, mobility:JSONObject?)',dashboard_fn)
if not ok: raise SystemExit('renderPersonalDashboard not found')

finance_fn=r'''    private fun appendFinanceDashboard(text:String) {
        addDashboardCard("Rea / Era · movimientos recientes",text.take(1800),Color.rgb(50,54,76))
    }'''
s,ok=replace_function(s,'    private fun appendFinanceDashboard(text:String)',finance_fn)
if not ok: raise SystemExit('appendFinanceDashboard not found')

# Provider-specific streaming endpoint: no provider can be starved by the old
# global 20-result cap. Never leave a permanent 'Cargando...' tile on error/empty.
streaming_fn=r'''    private fun loadStreamingPreview(spec: StreamingAppSpec, pkg: String) {
        streamingPreviewProvider=spec.provider
        val title=findViewById<TextView>(R.id.streamingPreviewTitle)
        val host=findViewById<LinearLayout>(R.id.streamingPreviewHost)
        host.removeAllViews()
        val cached=cachedPersonalTitles(spec.provider)
        if(cached.isNotEmpty()) {
            title.text="${spec.label} · tu contenido y novedades"
            cached.forEach{addPreviewTextCard(host,it,"Detectado en Favoritos / Continuar viendo de la app",pkg)}
        } else {
            title.text="${spec.label} · actualizando recomendaciones…"
            addPreviewTextCard(host,"Actualizando ${spec.label}…","Buscando contenido público reciente mientras Jarvis espera datos personalizados de la app.",pkg)
        }
        Thread {
            try {
                val backend=prefs.getString("backendUrl",DEFAULT_BACKEND).orEmpty().ifBlank{DEFAULT_BACKEND}
                val url=resolveEndpoint(backend,"media")+"?provider="+Uri.encode(spec.provider)+"&q="+Uri.encode("España estrenos recomendaciones")
                val c=(URL(url).openConnection() as HttpURLConnection).apply{requestMethod="GET";connectTimeout=6500;readTimeout=14000;setRequestProperty("Accept","application/json")}
                val code=c.responseCode
                val raw=(if(code in 200..299)c.inputStream else c.errorStream)?.bufferedReader()?.use{it.readText()}.orEmpty()
                if(code !in 200..299)throw IllegalStateException("HTTP $code")
                val items=JSONObject(raw).optJSONArray("items") ?: JSONArray()
                runOnUiThread {
                    if(streamingPreviewProvider!=spec.provider)return@runOnUiThread
                    if(cached.isEmpty())host.removeAllViews()
                    var count=0
                    for(i in 0 until items.length()) {
                        val o=items.optJSONObject(i)?:continue
                        addPreviewMediaCard(host,o,pkg); count++
                        if(count>=7)break
                    }
                    if(count==0 && cached.isEmpty())addPreviewTextCard(host,"Sin novedades disponibles ahora","Pulsa para abrir ${spec.label}. Al usar la app, Jarvis seguirá capturando Favoritos y Continuar viendo cuando sean visibles.",pkg)
                    title.text=if(cached.isNotEmpty())"${spec.label} · Favoritos / Continuar viendo + novedades" else "${spec.label} · recomendaciones recientes"
                }
            } catch(e:Throwable) {
                runOnUiThread {
                    if(streamingPreviewProvider!=spec.provider)return@runOnUiThread
                    if(cached.isEmpty()) {
                        host.removeAllViews()
                        addPreviewTextCard(host,"No se pudo actualizar ${spec.label}","Pulsa para abrir la app. Jarvis volverá a intentarlo cuando cambies de aplicación.",pkg)
                    }
                    title.text="${spec.label} · contenido de la app"
                }
            }
        }.start()
    }'''
s,ok=replace_function(s,'    private fun loadStreamingPreview(spec: StreamingAppSpec, pkg: String)',streaming_fn)
if not ok: raise SystemExit('loadStreamingPreview not found')

p.write_text(s)
print('TV v0.6.12 usability: provider loading, activity agenda, clickable widgets and answer auto-scroll applied')
