from pathlib import Path

p=Path('app/src/main/java/com/jarvis/tv/MainActivity.kt')
s=p.read_text()

# Wire the home card itself, not only the side-menu item.
anchor='        findViewById<Button>(R.id.homeControlButton).setOnClickListener { showHomeControls() }\n'
if 'R.id.cardHome).setOnClickListener' not in s and anchor in s:
    s=s.replace(anchor, anchor+'        findViewById<android.view.View>(R.id.cardHome).setOnClickListener { showHomeControls() }\n',1)

# Replace the placeholder Domotics screen with a real backend status view.
old='    private fun showHomeControls() { title.text = "Casa"; subtitle.text = "Luces, persianas, climatización, escenas y sensores" }'
new=r'''    private fun showHomeControls() {
        title.text = "Domótica"
        subtitle.text = "Dispositivos reales · estado de conexiones"
        status.text = "● Consultando domótica…"
        transcript.text = "DOMÓTICA\n\nConsultando conexiones y dispositivos reales…\n"
        Thread {
            try {
                val backend = prefs.getString("backendUrl", DEFAULT_BACKEND).orEmpty().ifBlank { DEFAULT_BACKEND }
                val c = (URL(resolveEndpoint(backend, "domotics/status")).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"; connectTimeout = 7000; readTimeout = 12000
                    setRequestProperty("Accept", "application/json")
                }
                val raw=(if(c.responseCode in 200..299)c.inputStream else c.errorStream)?.bufferedReader()?.use{it.readText()}.orEmpty()
                if(c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
                val providers=JSONObject(raw).optJSONObject("providers") ?: JSONObject()
                val lines=mutableListOf<String>()
                val keys=providers.keys()
                while(keys.hasNext()){
                    val id=keys.next(); val o=providers.optJSONObject(id) ?: continue
                    val ready=o.optBoolean("ready",false)
                    lines += (if(ready) "● " else "○ ") + o.optString("name",id) + if(ready) " · conectado/configurado" else " · requiere conexión"
                }
                runOnUiThread {
                    transcript.text = "DOMÓTICA\n\n" + if(lines.isEmpty()) "No hay proveedores configurados." else lines.joinToString("\n\n")
                    status.text = "● Domótica actualizada"
                    findViewById<android.widget.ScrollView>(R.id.mainScroll).post { findViewById<android.widget.ScrollView>(R.id.mainScroll).fullScroll(android.view.View.FOCUS_DOWN) }
                }
            } catch(e:Throwable) {
                runOnUiThread { transcript.text="DOMÓTICA\n\nNo se pudo leer el estado real: ${e.message ?: "error"}"; status.text="● Error domótica" }
            }
        }.start()
    }'''
if old in s:
    s=s.replace(old,new,1)

# The on-screen Jarvis microphone must always use Jarvis, including Fire TV.
# The physical Amazon remote microphone is system-reserved and may still invoke Alexa.
s=s.replace('findViewById<Button>(R.id.micButton).setOnClickListener { startVoiceInput() }','findViewById<Button>(R.id.micButton).setOnClickListener { startServerVoiceCapture() }')
s=s.replace('findViewById<Button>(R.id.assistantBubble).setOnClickListener { startVoiceInput() }','findViewById<Button>(R.id.assistantBubble).setOnClickListener { startServerVoiceCapture() }')

# Make Fire TV capture feel immediate and avoid the old fixed seven-second wait.
s=s.replace('status.text = "● Escuchando 7 s · OpenAI…"','status.text = "● Escuchando a Jarvis…"')
s=s.replace('handler.postDelayed({ stopRecorder(true) }, 7000)','handler.postDelayed({ stopRecorder(true) }, 4200)')

# Extend the focus enlargement to the complete side menu as well.
old_ids='''R.id.chatsButton, R.id.visionButton, R.id.settingsButton, R.id.assistantBubble,
            R.id.micButton, R.id.sendButton)'''
new_ids='''R.id.chatsButton, R.id.visionButton, R.id.settingsButton, R.id.assistantBubble,
            R.id.micButton, R.id.sendButton, R.id.homeButton, R.id.chatButton, R.id.connectionsButton,
            R.id.homeControlButton, R.id.routinesButton, R.id.notificationsButton, R.id.cardRoutine)'''
if old_ids in s:
    s=s.replace(old_ids,new_ids,1)

# Stronger visible focus than the previous subtle 1.08 scale.
s=s.replace('scaleX(if (focused) 1.08f else 1f).scaleY(if (focused) 1.08f else 1f)','scaleX(if (focused) 1.13f else 1f).scaleY(if (focused) 1.13f else 1f)')
s=s.replace('view.alpha = if (focused) 1f else 0.92f','view.alpha = if (focused) 1f else 0.82f')

p.write_text(s)
print('TV mobile-parity patch applied')
