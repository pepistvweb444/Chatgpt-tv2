from pathlib import Path

# ---------- Mobile bridge client ----------
p=Path('app/src/main/java/com/jarvis/tv/MobileRemoteClient.kt')
s=p.read_text()
anchor='    fun agenda(): JSONObject = get("/agenda", auth = true)\n'
if 'fun calls()' not in s:
    extra='''    fun calls(): JSONObject = get("/calls", auth = true)\n    fun mobility(): JSONObject = get("/mobility", auth = true)\n    fun domotics(): JSONObject = get("/domotics", auth = true)\n'''
    if anchor not in s: raise SystemExit('MobileRemoteClient agenda anchor not found')
    s=s.replace(anchor,anchor+extra,1)
p.write_text(s)

# ---------- Main TV personal centre ----------
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
            if depth==0:end=i+1;break
    if end<0:return text,False
    return text[:start]+replacement+text[end:],True

notifications=r'''    private fun showNotifications() {
        title.text = "Centro personal"
        subtitle.text = "Agenda · recordatorios · llamadas · pedidos · finanzas"
        personalWidgetContainer.visibility = View.VISIBLE
        personalWidgetContainer.removeAllViews()
        personalWidgetContainer.addView(pText("Jarvis · Centro personal", 22f, true, Color.rgb(205,213,255)).apply { setPadding(pDp(4),pDp(6),pDp(4),pDp(12)) })
        if (!mobileRemote.configured()) {
            val card=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; background=pRounded(Color.rgb(70,45,45)); setPadding(pDp(18),pDp(16),pDp(18),pDp(16)) }
            card.addView(pText("Móvil no emparejado",18f,true))
            card.addView(pText("Empareja Jarvis Mobile para ver agenda, llamadas perdidas, pedidos y dispositivos del teléfono.",15f,false,Color.rgb(235,220,220)))
            personalWidgetContainer.addView(card)
            status.text="● Falta emparejar Jarvis Mobile"
            return
        }
        status.text="● Sincronizando centro personal…"
        Thread {
            val agenda=runCatching{mobileRemote.agenda()}.getOrNull()
            val calls=runCatching{mobileRemote.calls()}.getOrNull()
            val mobility=runCatching{mobileRemote.mobility()}.getOrNull()
            runOnUiThread { renderPersonalDashboard(agenda,calls,mobility); status.text="● Agenda, llamadas y pedidos actualizados" }
            val finance=runCatching{fetchFinanceBriefForTv()}.getOrNull()
            if(!finance.isNullOrBlank()) runOnUiThread { appendFinanceDashboard(finance) }
        }.start()
    }'''
s,ok=replace_function(s,'    private fun showNotifications()',notifications)
if not ok: raise SystemExit('showNotifications not found')

home=r'''    private fun showHomeControls() {
        title.text="Domótica"
        subtitle.text="Mismos dispositivos configurados en Jarvis Mobile"
        personalWidgetContainer.visibility=View.VISIBLE
        personalWidgetContainer.removeAllViews()
        personalWidgetContainer.addView(pText("Jarvis · Domótica sincronizada",22f,true,Color.rgb(205,213,255)).apply{setPadding(pDp(4),pDp(6),pDp(4),pDp(12))})
        if(!mobileRemote.configured()) {
            personalWidgetContainer.addView(pText("Empareja Jarvis Mobile para compartir sus dispositivos y conexiones domóticas.",17f,true))
            status.text="● Móvil no emparejado"; return
        }
        status.text="● Sincronizando dispositivos del móvil…"
        Thread {
            val data=runCatching{mobileRemote.domotics()}.getOrNull()
            val backend=runCatching{fetchBackendDomoticsStatus()}.getOrNull()
            runOnUiThread { renderDomoticsFromMobile(data,backend); status.text="● Domótica sincronizada" }
        }.start()
    }'''
s,ok=replace_function(s,'    private fun showHomeControls()',home)
if not ok: raise SystemExit('showHomeControls not found')

helper_anchor='    private fun updateChatMeta(text: String, role: String) {'
if 'private fun renderPersonalDashboard(' not in s:
    helpers=r'''    private fun addDashboardHeading(text:String) {
        personalWidgetContainer.addView(pText(text,19f,true,Color.rgb(198,207,245)).apply{setPadding(pDp(4),pDp(16),pDp(4),pDp(8))})
    }

    private fun addDashboardCard(titleText:String, bodyText:String, color:Int=Color.rgb(31,40,55)) {
        val card=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL; background=pRounded(color); setPadding(pDp(18),pDp(14),pDp(18),pDp(14))
            layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT).apply{bottomMargin=pDp(9)}
        }
        card.addView(pText(titleText,17f,true))
        if(bodyText.isNotBlank()) card.addView(pText(bodyText,14.5f,false,Color.rgb(220,228,240)).apply{setPadding(0,pDp(5),0,0)})
        personalWidgetContainer.addView(card)
    }

    private fun renderPersonalDashboard(agenda:JSONObject?, calls:JSONObject?, mobility:JSONObject?) {
        personalWidgetContainer.removeAllViews()
        personalWidgetContainer.visibility=View.VISIBLE
        personalWidgetContainer.addView(pText("Jarvis · Centro personal",22f,true,Color.rgb(205,213,255)).apply{setPadding(pDp(4),pDp(6),pDp(4),pDp(10))})

        addDashboardHeading("Agenda y recordatorios")
        val events=agenda?.optJSONArray("events") ?: JSONArray()
        val reminders=agenda?.optJSONArray("reminders") ?: JSONArray()
        if(events.length()==0 && reminders.length()==0) addDashboardCard("Sin eventos próximos","No hay citas ni recordatorios detectados en los próximos días.")
        for(i in 0 until events.length()) {
            val e=events.optJSONObject(i)?:continue
            val whenText=formatSyncTime(e.optLong("begin"),e.optBoolean("allDay"))
            val detail=listOf(whenText,e.optString("calendar"),e.optString("location")).filter{it.isNotBlank()}.joinToString(" · ")
            addDashboardCard(e.optString("title").ifBlank{"Evento"},detail,Color.rgb(55,45,108))
        }
        for(i in 0 until reminders.length()) {
            val r=reminders.optJSONObject(i)?:continue
            val at=r.optLong("time")
            val whenText=if(at>0) formatSyncTime(at,false) else ""
            addDashboardCard("⏰ "+r.optString("title").ifBlank{"Recordatorio"},listOf(whenText,r.optString("text")).filter{it.isNotBlank()}.joinToString(" · "),Color.rgb(42,76,67))
        }

        addDashboardHeading("Llamadas")
        val current=calls?.optJSONObject("current")
        if(current?.optBoolean("active",false)==true) {
            val who=current.optString("name").ifBlank{current.optString("number").ifBlank{"Llamada entrante"}}
            val cls=when(current.optString("classification")){"spam_probable"->"Spam probable · ${current.optInt("spamScore")}%";"possible_spam"->"Posible spam";"contact"->"Contacto";else->"Sin clasificar"}
            addDashboardCard("☎ $who",cls,Color.rgb(88,52,52))
        }
        val callItems=calls?.optJSONArray("items") ?: JSONArray()
        var shownCalls=0
        for(i in 0 until callItems.length()) {
            val c=callItems.optJSONObject(i)?:continue
            val kind=c.optString("kind")
            if(kind!="missed" && kind!="incoming" && kind!="rejected" && !c.optBoolean("pending")) continue
            val label=when { c.optBoolean("pending")->"⚠ Llamada perdida pendiente";kind=="missed"->"Llamada perdida";kind=="rejected"->"Llamada rechazada";else->"Llamada entrante" }
            val time=if(c.optLong("time")>0) formatSyncTime(c.optLong("time"),false) else ""
            val who=c.optString("name").ifBlank{c.optString("number").ifBlank{"Número oculto"}}
            addDashboardCard("$label · $who",listOf(time,c.optString("number").takeIf{it.isNotBlank()&&it!=who}.orEmpty()).filter{it.isNotBlank()}.joinToString(" · "),if(c.optBoolean("pending"))Color.rgb(92,58,38)else Color.rgb(44,48,65))
            shownCalls++; if(shownCalls>=10) break
        }
        val appCalls=calls?.optJSONArray("appCalls") ?: JSONArray()
        for(i in 0 until minOf(appCalls.length(),5)) {
            val c=appCalls.optJSONObject(i)?:continue
            addDashboardCard("Llamada de app · ${c.optString("name")}",c.optString("detail"),Color.rgb(44,48,65))
        }
        if(shownCalls==0 && appCalls.length()==0 && current?.optBoolean("active",false)!=true) addDashboardCard("Sin llamadas pendientes","No hay llamadas perdidas o entrantes recientes pendientes de revisar.")

        addDashboardHeading("Pedidos y movilidad")
        val mItems=mobility?.optJSONArray("items") ?: JSONArray()
        val now=System.currentTimeMillis(); var shownMobility=0
        for(i in mItems.length()-1 downTo 0) {
            if(shownMobility>=10) break
            val m=mItems.optJSONObject(i)?:continue
            val time=m.optLong("time")
            if(time>0 && now-time>72L*60L*60L*1000L) continue
            val source=m.optString("source").ifBlank{"Pedido / movilidad"}
            val kind=m.optString("kind")
            val isDelivery=kind=="delivery" || listOf("glovo","uber eats","just eat","deliveroo").any{source.contains(it,true)}
            val label=(if(isDelivery)"🛍 " else "🚕 ")+source
            val eta=m.optString("etaMinutes").takeIf{it.isNotBlank()}?.let{"ETA $it min"}.orEmpty()
            val body=listOf(m.optString("title"),m.optString("text"),eta).filter{it.isNotBlank()}.joinToString(" · ")
            addDashboardCard(label,body,if(isDelivery)Color.rgb(45,71,60)else Color.rgb(43,55,79))
            shownMobility++
        }
        if(shownMobility==0) addDashboardCard("Sin pedidos o trayectos activos","Glovo, Uber Eats, Bolt, Cabify y otros aparecerán aquí cuando el móvil detecte un estado real.")

        addDashboardHeading("Movimientos económicos")
        addDashboardCard("Rea / Era · consultando…","Jarvis está consultando la misma conexión financiera usada por la versión móvil.",Color.rgb(50,54,76))
        findViewById<android.widget.ScrollView>(R.id.mainScroll).post{findViewById<android.widget.ScrollView>(R.id.mainScroll).fullScroll(android.view.View.FOCUS_DOWN)}
    }

    private fun fetchFinanceBriefForTv(): String {
        val backend=prefs.getString("backendUrl",DEFAULT_BACKEND).orEmpty().ifBlank{DEFAULT_BACKEND}
        val prompt="Consulta mediante la conexión financiera Rea/Era configurada en Jarvis los movimientos económicos más recientes del usuario. Devuelve como máximo 8 movimientos con fecha, comercio/concepto e importe, y un resumen breve de saldos si están disponibles. No inventes ningún dato. Si el conector no está disponible, dilo claramente en una sola frase."
        return postChat(resolveEndpoint(backend,"chat"),prompt,JSONArray(),null).first
    }

    private fun appendFinanceDashboard(text:String) {
        // Remove the temporary 'consultando' card if it is still the last finance card.
        addDashboardCard("Rea / Era · movimientos recientes",text.take(1800),Color.rgb(50,54,76))
        findViewById<android.widget.ScrollView>(R.id.mainScroll).post{findViewById<android.widget.ScrollView>(R.id.mainScroll).fullScroll(android.view.View.FOCUS_DOWN)}
    }

    private fun fetchBackendDomoticsStatus(): JSONObject {
        val backend=prefs.getString("backendUrl",DEFAULT_BACKEND).orEmpty().ifBlank{DEFAULT_BACKEND}
        val c=(URL(resolveEndpoint(backend,"domotics/status")).openConnection() as HttpURLConnection).apply{requestMethod="GET";connectTimeout=6000;readTimeout=10000;setRequestProperty("Accept","application/json")}
        val raw=(if(c.responseCode in 200..299)c.inputStream else c.errorStream)?.bufferedReader()?.use{it.readText()}.orEmpty()
        if(c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
        return JSONObject(raw)
    }

    private fun renderDomoticsFromMobile(data:JSONObject?, backend:JSONObject?) {
        personalWidgetContainer.removeAllViews(); personalWidgetContainer.visibility=View.VISIBLE
        personalWidgetContainer.addView(pText("Jarvis · Domótica sincronizada",22f,true,Color.rgb(205,213,255)).apply{setPadding(pDp(4),pDp(6),pDp(4),pDp(10))})
        var count=0
        fun renderArray(label:String, arr:JSONArray, color:Int) {
            if(arr.length()==0)return
            addDashboardHeading(label)
            for(i in 0 until arr.length()) {
                val d=arr.optJSONObject(i)?:continue
                val name=d.optString("name").ifBlank{"Dispositivo"}
                val state=d.optJSONObject("state")
                val stateText=if(state!=null) {
                    val keys=state.keys(); val parts=mutableListOf<String>(); while(keys.hasNext()&&parts.size<4){val k=keys.next();val v=state.opt(k);if(v!=null&&v.toString()!="null")parts+="$k: $v"};parts.joinToString(" · ")
                } else listOf(d.optString("room"),d.optString("type")).filter{it.isNotBlank()}.joinToString(" · ")
                addDashboardCard(name,stateText,color); count++
            }
        }
        renderArray("Homey",data?.optJSONArray("homey")?:JSONArray(),Color.rgb(39,68,61))
        renderArray("Google Home",data?.optJSONArray("googleHome")?:JSONArray(),Color.rgb(42,62,84))
        renderArray("Home Connect",data?.optJSONArray("homeConnect")?:JSONArray(),Color.rgb(54,58,77))
        val providers=backend?.optJSONObject("providers")
        if(providers!=null) {
            addDashboardHeading("Conexiones de Jarvis")
            val keys=providers.keys(); while(keys.hasNext()) { val id=keys.next(); val o=providers.optJSONObject(id)?:continue; addDashboardCard(o.optString("name",id),if(o.optBoolean("ready"))"Conectado / configurado" else "Requiere conexión",Color.rgb(37,44,58)) }
        }
        if(count==0) addDashboardCard("Sin dispositivos sincronizados","Abre Jarvis Mobile y actualiza sus conectores domóticos. La TV reutiliza esos mismos dispositivos, no crea una configuración independiente.")
    }

'''
    if helper_anchor not in s: raise SystemExit('dashboard helper anchor not found')
    s=s.replace(helper_anchor,helpers+helper_anchor,1)

# Make the 'Now' card open the complete centre too, if present.
card_anchor='        findViewById<Button>(R.id.notificationsButton).setOnClickListener { showNotifications() }\n'
if 'R.id.cardNow).setOnClickListener { showNotifications() }' not in s and card_anchor in s:
    s=s.replace(card_anchor,card_anchor+'        findViewById<android.view.View>(R.id.cardNow).setOnClickListener { showNotifications() }\n',1)

p.write_text(s)
print('TV unified agenda/calls/delivery/finance/domotics dashboard applied')
