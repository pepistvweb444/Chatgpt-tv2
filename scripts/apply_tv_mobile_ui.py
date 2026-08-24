from pathlib import Path

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

        findViewById<TextView>(R.id.cardNow).setOnClickListener { ask("Dame mi resumen del día con agenda, recordatorios, tareas y asuntos importantes") }
        findViewById<TextView>(R.id.cardHome).setOnClickListener { ask("Muéstrame el estado de mi domótica") }
        findViewById<TextView>(R.id.cardMessages).setOnClickListener { ask("¿Qué me cuentas hoy? Muéstrame las noticias importantes en widgets") }
        findViewById<WeatherWidgetView>(R.id.cardWeather).setOnClickListener { ask("¿Qué tiempo hace en mi ubicación actual?") }

        input.setOnEditorActionListener { _, _, _ -> sendMessage(); true }
    }
'''
if old not in s:
    raise SystemExit('bindUi marker not found')
s = s.replace(old, new, 1)

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
