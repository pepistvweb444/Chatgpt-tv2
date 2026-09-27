from pathlib import Path

def replace_function(text, signature, replacement):
    start=text.find(signature)
    if start<0:
        raise SystemExit(f'{signature} not found')
    brace=text.find('{',start)
    depth=0
    in_string=False
    escaped=False
    for i in range(brace,len(text)):
        ch=text[i]
        if in_string:
            if escaped:
                escaped=False
            elif ch=='\\':
                escaped=True
            elif ch=='"':
                in_string=False
        else:
            if ch=='"':
                in_string=True
            elif ch=='{':
                depth+=1
            elif ch=='}':
                depth-=1
                if depth==0:
                    return text[:start]+replacement+text[i+1:]
    raise SystemExit(f'end not found for {signature}')

p = Path('app/src/main/java/com/jarvis/tv/MainActivity.kt')
s = p.read_text()

old = '''    private fun bindUi() {
        findViewById<Button>(R.id.sendButton).setOnClickListener { sendMessage() }
        findViewById<Button>(R.id.micButton).setOnClickListener { startVoiceInput() }
        findViewById<Button>(R.id.assistantBubble).setOnClickListener { startVoiceInput() }
        findViewById<Button>(R.id.settingsButton).setOnClickListener { showSettings() }
        findViewById<Button>(R.id.homeButton).setOnClickListener { showHome() }
        findViewById<Button>(R.id.chatButton).setOnClickListener { showChat() }
        findViewById<Button>(R.id.chatsButton).setOnClickListener { showChats() }
        findViewById<Button>(R.id.connectionsButton).setOnClickListener { showConnections() }
        findViewById<Button>(R.id.visionButton).setOnClickListener { showVision() }
        findViewById<Button>(R.id.homeControlButton).setOnClickListener { showHomeControls() }
        findViewById<Button>(R.id.routinesButton).setOnClickListener { showRoutines() }
        findViewById<Button>(R.id.notificationsButton).setOnClickListener { showNotifications() }
        input.setOnEditorActionListener { _, _, _ -> sendMessage(); true }
    }
'''
new = '''    private fun bindUi() {
        fun closeMenu() { findViewById<LinearLayout>(R.id.tvSideMenu).visibility = android.view.View.GONE }
        fun ask(prompt: String) {
            closeMenu()
            input.setText(prompt)
            sendMessage()
        }

        findViewById<Button>(R.id.sendButton).setOnClickListener { sendMessage() }
        findViewById<Button>(R.id.micButton).setOnClickListener { startVoiceInput() }
        findViewById<Button>(R.id.assistantBubble).setOnClickListener { startVoiceInput() }
        findViewById<Button>(R.id.settingsButton).setOnClickListener { showSettings() }
        findViewById<Button>(R.id.chatsButton).setOnClickListener {
            val menu = findViewById<LinearLayout>(R.id.tvSideMenu)
            menu.visibility = if (menu.visibility == android.view.View.VISIBLE) android.view.View.GONE else android.view.View.VISIBLE
            if (menu.visibility == android.view.View.VISIBLE) findViewById<Button>(R.id.homeButton).requestFocus()
        }
        findViewById<Button>(R.id.homeButton).setOnClickListener {
            createConversation(true)
            loadConversation(conversationId)
            showHome()
            closeMenu()
        }
        findViewById<Button>(R.id.chatButton).setOnClickListener { closeMenu(); showChats() }
        findViewById<Button>(R.id.connectionsButton).setOnClickListener { closeMenu(); showConnections() }
        findViewById<Button>(R.id.visionButton).setOnClickListener { showVision() }
        findViewById<Button>(R.id.homeControlButton).setOnClickListener { closeMenu(); showHomeControls(); ask("Muéstrame el estado de mi domótica") }
        findViewById<Button>(R.id.routinesButton).setOnClickListener { closeMenu(); showRoutines() }
        findViewById<Button>(R.id.notificationsButton).setOnClickListener { closeMenu(); showNotifications() }
        findViewById<Button>(R.id.sleepDetectorButton).apply { text = "◉  Sueño · " + if (prefs.getBoolean("sofaVisionEnabled", false)) "ACTIVO" else "APAGADO"; setOnClickListener { closeMenu(); showVision() } }

        findViewById<TextView>(R.id.cardNow).setOnClickListener { closeMenu(); showNotifications() }
        findViewById<TextView>(R.id.cardHome).setOnClickListener { ask("Muéstrame el estado de mi domótica") }
        findViewById<TextView>(R.id.cardMessages).setOnClickListener { ask("¿Qué me cuentas hoy? Muéstrame las noticias importantes en widgets") }
        findViewById<WeatherWidgetView>(R.id.cardWeather).setOnClickListener { ask("¿Qué tiempo hace en mi ubicación actual?") }

        input.setOnEditorActionListener { _, _, _ -> sendMessage(); true }
    }
'''
s = replace_function(s, '    private fun bindUi()', new.rstrip())

old_home = '''    private fun showHome() {
        title.text = "${assistantName()} · Now Brief"
        subtitle.text = if (isFireTv()) "Fire TV · voz directa · web · memoria" else "Información contextual, voz, casa y comunicaciones"
        status.text = "● Listo · ${wakeWord()}"
        if (transcript.text.isBlank()) append("assistant", "Hola. Soy ${assistantName()}. Esta conversación mantiene su contexto. Pulsa Mis chats para recuperar otra.")
    }
'''
new_home = '''    private fun showHome() {
        title.text = assistantName()
        subtitle.text = "AI Companion"
        status.text = "● Jarvis listo"
    }
'''
if old_home in s:
    s = s.replace(old_home, new_home, 1)

p.write_text(s)
print('TV mobile-style UI behavior applied')
