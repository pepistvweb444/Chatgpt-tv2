from pathlib import Path
import re


def replace_function(text, signature, replacement):
    start=text.find(signature)
    if start < 0: raise SystemExit(f'{signature} not found')
    brace=text.find('{',start); depth=0; in_string=False; esc=False
    for i in range(brace,len(text)):
        ch=text[i]
        if in_string:
            if esc: esc=False
            elif ch=='\\': esc=True
            elif ch=='"': in_string=False
        else:
            if ch=='"': in_string=True
            elif ch=='{': depth+=1
            elif ch=='}':
                depth-=1
                if depth==0:return text[:start]+replacement+text[i+1:]
    raise SystemExit(f'end not found {signature}')

# ---------------------------------------------------------------------------
# Move dynamic sections directly below the top widgets and above the chat.
# ---------------------------------------------------------------------------
layout=Path('app/src/main/res/layout/activity_main.xml')
x=layout.read_text()
blocks=[]
for view_id in ['personalWidgetContainer','newsWidgetContainer']:
    pattern=re.compile(r'\n\s*<LinearLayout\n\s*android:id="@\+id/'+view_id+r'"[\s\S]*?/>(?:\n)?',re.M)
    m=pattern.search(x)
    if m:
        blocks.append(m.group(0).strip('\n'))
        x=x[:m.start()]+'\n'+x[m.end():]
if blocks:
    anchor='''                <TextView
                    android:id="@+id/currentChatLabel"'''
    if anchor not in x: raise SystemExit('currentChatLabel layout anchor missing')
    detail='''                <TextView
                    android:id="@+id/inlineDetailLabel"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:text=""
                    android:textColor="#A9B2C2"
                    android:textSize="15sp"
                    android:textStyle="bold"
                    android:paddingTop="12dp"
                    android:paddingBottom="6dp"
                    android:visibility="gone" />

'''+''.join(b+'\n\n' for b in blocks)
    x=x.replace(anchor,detail+anchor,1)
layout.write_text(x)

# ---------------------------------------------------------------------------
# MobileRemoteClient command methods.
# ---------------------------------------------------------------------------
p=Path('app/src/main/java/com/jarvis/tv/MobileRemoteClient.kt')
s=p.read_text()
anchor='    fun incomingCall(): JSONObject = get("/incoming-call", auth = true)\n'
if 'fun domoticsCommand(' not in s:
    extra=r'''    fun domoticsCommand(command: String, confirmed: Boolean = false): JSONObject =
        get("/domotics-command?command=${URLEncoder.encode(command, "UTF-8")}&confirmed=$confirmed", auth = true)
    fun domoticsCommandResult(id: String): JSONObject =
        get("/domotics-command-result?id=${URLEncoder.encode(id, "UTF-8")}", auth = true)
'''
    if anchor not in s: raise SystemExit('MobileRemoteClient anchor missing')
    s=s.replace(anchor,anchor+extra,1)
p.write_text(s)

# ---------------------------------------------------------------------------
# MainActivity chat context, commands, inline navigation and briefing.
# ---------------------------------------------------------------------------
p=Path('app/src/main/java/com/jarvis/tv/MainActivity.kt')
s=p.read_text()
field='    private var conversationId: String = ""\n'
if 'private var pendingTvDomoticsCommand' not in s:
    s=s.replace(field,field+'    private var pendingTvDomoticsCommand: String = ""\n',1)

# Every chat call receives the same real home snapshot shown in the widget.
old_payload='''        val payload = JSONObject()
            .put("message", message)
            .put("conversationId", conversationId)
            .put("client", "jarvis-tv")
            .put("assistantName", assistantName())
            .put("history", historyPayload)'''
new_payload='''        val homeContext = runCatching { if (mobileRemote.configured()) mobileRemote.domotics() else null }.getOrNull()
        val payload = JSONObject()
            .put("message", message)
            .put("conversationId", conversationId)
            .put("client", "jarvis-tv")
            .put("assistantName", assistantName())
            .put("history", historyPayload)
            .apply { if (homeContext != null) put("homeContext", homeContext) }'''
if old_payload in s:
    s=s.replace(old_payload,new_payload,1)

# Insert direct action handling immediately after the user's message is appended.
route_anchor='        append("user", text)\n'
if 'handleTvDomoticsChatCommand(text)' not in s:
    route=r'''        append("user", text)
        if (handleTvDomoticsChatCommand(text)) return
'''
    if route_anchor not in s: raise SystemExit('sendMessage append anchor missing')
    s=s.replace(route_anchor,route,1)

helper_anchor='    private fun updateChatMeta(text: String, role: String) {'
if 'private fun handleTvDomoticsChatCommand(' not in s:
    helpers=r'''    private fun looksLikeHomeAction(text:String):Boolean {
        val q=text.lowercase(Locale.ROOT)
        val device=listOf("domótica","domotica","tado","sensibo","aire acondicionado","termostato","climatización","climatizacion","horno","lavadora","lavavajillas","secadora","placa","vitro","inducción","induccion","nevera","frigorífico","frigorifico","home connect").any{q.contains(it)}
        val action=listOf("enciende","encender","apaga","apagar","pon ","ajusta","sube","baja","programa","inicia","iniciar","arranca","activa","desactiva").any{q.contains(it)}
        return device && action
    }

    private fun handleTvDomoticsChatCommand(text:String):Boolean {
        val q=text.trim().lowercase(Locale.ROOT)
        if(pendingTvDomoticsCommand.isNotBlank() && (q=="sí"||q=="si"||q.startsWith("confirma")||q.startsWith("confirmo")||q=="adelante")) {
            val pending=pendingTvDomoticsCommand; pendingTvDomoticsCommand=""
            executeTvDomoticsAction(pending,true); return true
        }
        if(!looksLikeHomeAction(text)) return false
        executeTvDomoticsAction(text,false); return true
    }

    private fun executeTvDomoticsAction(command:String,confirmed:Boolean=false) {
        if(!mobileRemote.configured()) {
            append("assistant","No puedo ejecutar esa orden porque Jarvis TV no está emparejado con Jarvis Mobile.",true); return
        }
        status.text="● Ejecutando domótica en el móvil…"
        Thread {
            try {
                val accepted=mobileRemote.domoticsCommand(command,confirmed)
                val id=accepted.optString("id")
                if(id.isBlank()) throw IllegalStateException("El móvil no devolvió identificador de la acción")
                var result=JSONObject().put("status","pending")
                for(i in 0 until 25) {
                    Thread.sleep(if(i<3)300L else 650L)
                    result=mobileRemote.domoticsCommandResult(id)
                    if(result.optString("status") !in listOf("pending","running")) break
                }
                val state=result.optString("status")
                val message=result.optString("message").ifBlank{"No he recibido confirmación del dispositivo."}
                if(state=="needs_confirmation") pendingTvDomoticsCommand=result.optString("pendingCommand").ifBlank{command}
                runOnUiThread {
                    append("assistant",message,true)
                    status.text=when(state){"done"->"● Domótica actualizada";"needs_confirmation"->"● Esperando confirmación";else->"● Jarvis listo"}
                    if(state=="done") showHomeControls()
                    scrollToLatestAnswer()
                }
            } catch(e:Throwable) {
                runOnUiThread { append("assistant","No he podido ejecutar la orden domótica: ${e.message}",true); status.text="● Error domótica"; scrollToLatestAnswer() }
            }
        }.start()
    }

    private fun detailLabel(text:String) {
        findViewById<TextView>(R.id.inlineDetailLabel)?.apply { this.text=text; visibility=if(text.isBlank())View.GONE else View.VISIBLE }
    }

    private fun domoticsControlButton(label:String,command:String):TextView = pText(label,13f,true).apply {
        gravity=Gravity.CENTER;isFocusable=true;isClickable=true
        background=pRounded(Color.rgb(38,83,70),14);setPadding(pDp(12),pDp(9),pDp(12),pDp(9))
        layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,LinearLayout.LayoutParams.WRAP_CONTENT).apply{marginEnd=pDp(7)}
        setOnClickListener{ append("user",command); executeTvDomoticsAction(command,false) }
    }

    private fun renderDomoticsControlCard(d:JSONObject) {
        val provider=d.optString("provider"); val name=d.optString("name").ifBlank{"Dispositivo"}; val room=d.optString("room")
        val st=d.optJSONObject("state")?:JSONObject(); val parts=mutableListOf<String>()
        if(d.has("connected"))parts+=if(d.optBoolean("connected",true))"Conectado" else "Sin conexión"
        if(st.has("on"))parts+=if(st.optBoolean("on"))"Encendido" else "Apagado"
        if(st.has("onoff"))parts+=if(st.optBoolean("onoff"))"Encendido" else "Apagado"
        st.optString("power").takeIf{it.isNotBlank()}?.let{parts+=it}
        st.optString("mode").takeIf{it.isNotBlank()}?.let{parts+="Modo $it"}
        if(st.has("temperature"))parts+="${String.format(Locale.getDefault(),"%.1f",st.optDouble("temperature"))} °C"
        if(st.has("targetTemperature"))parts+="objetivo ${String.format(Locale.getDefault(),"%.1f",st.optDouble("targetTemperature"))} °C"
        val card=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;background=pRounded(Color.rgb(36,45,60));setPadding(pDp(16),pDp(14),pDp(16),pDp(14));layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT).apply{bottomMargin=pDp(9)}}
        card.addView(pText(name,17f,true)); card.addView(pText(listOf(room,parts.joinToString(" · ")).filter{it.isNotBlank()&&it!="Sin asignar"}.joinToString(" · "),14f,false,Color.rgb(220,228,240)))
        val controls=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.START;setPadding(0,pDp(9),0,0)}
        when(provider.lowercase(Locale.ROOT)) {
            "tado","sensibo" -> { controls.addView(domoticsControlButton("Encender","enciende $name"));controls.addView(domoticsControlButton("Apagar","apaga $name"));controls.addView(domoticsControlButton("−1°","baja un grado $name"));controls.addView(domoticsControlButton("+1°","sube un grado $name")) }
            "homeconnect","home connect" -> { controls.addView(domoticsControlButton("Estado","dime el estado de $name"));controls.addView(domoticsControlButton("Encender","enciende $name"));controls.addView(domoticsControlButton("Apagar","apaga $name"));controls.addView(domoticsControlButton("Programas","programa $name")) }
            else -> controls.addView(domoticsControlButton("Estado","dime el estado de $name"))
        }
        card.addView(controls); personalWidgetContainer.addView(card)
    }

    private fun showMediaDashboard() {
        detailLabel("Noticias, streaming y YouTube")
        personalWidgetContainer.visibility=View.GONE
        newsWidgetContainer.visibility=View.GONE
        showStreamingFavoritesOverview()
        val first=streamingSpecs().firstOrNull{installedPackage(it)!=null}
        if(first!=null) installedPackage(first)?.let{loadStreamingPreview(first,it)}
        status.text="● Contenido multimedia actualizado"
    }

    private fun showMorningBriefing() {
        title.text="Buenos días · Jarvis Briefing"
        subtitle.text="Agenda · pedidos · llamadas · casa · noticias · streaming"
        detailLabel("Resumen automático")
        status.text="● Preparando briefing…"
        Thread {
            val agenda=runCatching{mobileRemote.agenda()}.getOrNull()
            val calls=runCatching{mobileRemote.calls()}.getOrNull()
            val mobility=runCatching{mobileRemote.mobility()}.getOrNull()
            val home=runCatching{mobileRemote.domotics()}.getOrNull()
            runOnUiThread {
                renderPersonalDashboard(agenda,calls,mobility)
                addDashboardHeading("Domótica")
                val devices=home?.optJSONArray("items")?:JSONArray()
                for(i in 0 until minOf(devices.length(),12)) devices.optJSONObject(i)?.let{renderDomoticsControlCard(it)}
                if(devices.length()==0)addDashboardCard("Domótica","No se ha recibido estado de dispositivos desde el móvil.")
                addDashboardHeading("Novedades de TV, streaming y YouTube")
                val personal=streamingSpecs().flatMap{spec->cachedPersonalTitles(spec.provider).take(3).map{"${spec.label} · $it"}}.take(12)
                if(personal.isEmpty()) addDashboardCard("Contenido personalizado","Abre tus aplicaciones con Accesibilidad de Jarvis activa para capturar Continuar viendo, biblioteca y recomendaciones visibles.")
                else personal.forEach{addDashboardCard(it,"Detectado en tu perfil / interfaz de televisión",Color.rgb(40,49,68))}
                status.text="● Briefing listo"
                scrollToView(personalWidgetContainer)
            }
        }.start()
    }

'''
    if helper_anchor not in s: raise SystemExit('helper anchor missing')
    s=s.replace(helper_anchor,helpers+helper_anchor,1)

# Replace final domotics renderer with actionable cards.
domotics=r'''    private fun renderDomoticsFromMobile(data:JSONObject?, backend:JSONObject?) {
        detailLabel("Domótica del móvil")
        personalWidgetContainer.removeAllViews(); personalWidgetContainer.visibility=View.VISIBLE
        personalWidgetContainer.addView(pText("Jarvis · Domótica del móvil",22f,true,Color.rgb(205,213,255)).apply{setPadding(pDp(4),pDp(6),pDp(4),pDp(10))})
        val items=data?.optJSONArray("items")?:JSONArray()
        if(items.length()==0) addDashboardCard("Sin dispositivos recibidos","El móvil está emparejado, pero no ha enviado todavía un inventario domótico.")
        else for(i in 0 until items.length()) items.optJSONObject(i)?.let{renderDomoticsControlCard(it)}
        val updated=data?.optLong("updatedAt",0L)?:0L
        if(updated>0)addDashboardCard("Última sincronización",formatSyncTime(updated,false),Color.rgb(34,40,52))
        scrollToView(personalWidgetContainer)
    }'''
if '    private fun renderDomoticsFromMobile(' in s:
    s=replace_function(s,'    private fun renderDomoticsFromMobile(',domotics)

# Top widgets reveal their details immediately underneath.
bind='        findViewById<Button>(R.id.notificationsButton).setOnClickListener { showNotifications() }\n'
if bind in s:
    pass
# Existing cardNow/cardHome listeners are already created by earlier patches. Add/override news.
ui_anchor='        input.setOnEditorActionListener { _, _, _ -> sendMessage(); true }\n'
if 'R.id.cardMessages).setOnClickListener { showMediaDashboard() }' not in s and ui_anchor in s:
    s=s.replace(ui_anchor,'        findViewById<android.view.View>(R.id.cardMessages).setOnClickListener { showMediaDashboard() }\n'+ui_anchor,1)

# Morning routine intent from the local TV command service.
oncreate='''        if (intent?.getBooleanExtra("start_voice", false) == true) {'''
if 'show_morning_briefing' not in s and oncreate in s:
    s=s.replace(oncreate,'''        if (intent?.getBooleanExtra("show_morning_briefing", false) == true) {
            intent.removeExtra("show_morning_briefing")
            handler.postDelayed({ showMorningBriefing() }, 650L)
        }
'''+oncreate,1)
newintent='''        if (intent.getBooleanExtra("start_voice", false)) {'''
if newintent in s and 'intent.getBooleanExtra("show_morning_briefing"' not in s[s.find('override fun onNewIntent'):s.find('override fun onNewIntent')+800]:
    s=s.replace(newintent,'''        if (intent.getBooleanExtra("show_morning_briefing", false)) {
            intent.removeExtra("show_morning_briefing")
            handler.postDelayed({ showMorningBriefing() }, 250L)
        }
'''+newintent,1)

s=s.replace('Ajustes de Jarvis TV v0.6.13','Ajustes de Jarvis TV v0.6.14')
p.write_text(s)
print('TV 0.6.14 inline dashboard, real home context and controls applied')
