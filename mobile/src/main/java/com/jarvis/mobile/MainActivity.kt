package com.jarvis.mobile

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class MainActivity : AppCompatActivity() {
    private lateinit var transcript: TextView
    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var scroll: ScrollView
    private lateinit var recentChats: TextView
    private lateinit var sideMenu: View
    private lateinit var drawerScrim: View
    private lateinit var welcomePanel: View
    private lateinit var widgetHost: View
    private lateinit var widgetTitle: TextView
    private lateinit var widgetBody: TextView
    private val prefs by lazy { getSharedPreferences("jarvis_mobile", MODE_PRIVATE) }
    private var conversationId = ""
    private var activeWidgetKind: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        transcript = findViewById(R.id.transcript)
        input = findViewById(R.id.input)
        status = findViewById(R.id.status)
        scroll = findViewById(R.id.scroll)
        recentChats = findViewById(R.id.recentChats)
        sideMenu = findViewById(R.id.sideMenu)
        drawerScrim = findViewById(R.id.drawerScrim)
        welcomePanel = findViewById(R.id.welcomePanel)
        widgetHost = findViewById(R.id.widgetHost)
        widgetTitle = findViewById(R.id.widgetTitle)
        widgetBody = findViewById(R.id.widgetBody)

        conversationId = prefs.getString("currentConversation", null) ?: newConversation()
        loadConversation()
        refreshDrawerRecents()
        restoreSelectedTools()

        findViewById<View>(R.id.send).setOnClickListener { sendMessage() }
        findViewById<View>(R.id.mic).setOnClickListener { Toast.makeText(this, "Habla con Jarvis", Toast.LENGTH_SHORT).show() }
        findViewById<View>(R.id.chats).setOnClickListener { openDrawer() }
        findViewById<View>(R.id.closeDrawer).setOnClickListener { closeDrawer() }
        drawerScrim.setOnClickListener { closeDrawer() }
        findViewById<View>(R.id.newChat).setOnClickListener { startNewChat() }
        findViewById<View>(R.id.menuNewChat).setOnClickListener { startNewChat(); closeDrawer() }
        findViewById<View>(R.id.menuHistory).setOnClickListener { showChats() }
        findViewById<View>(R.id.connections).setOnClickListener { showConnections() }
        findViewById<View>(R.id.tools).setOnClickListener { showToolPicker() }
        findViewById<View>(R.id.menuPlugins).setOnClickListener { showToolPicker() }
        findViewById<View>(R.id.homeAutomation).setOnClickListener {
            activeWidgetKind = "home"
            showWidget("Domótica", "⌂  Casa conectada\n\nConsultando luces, clima, escenas y energía…")
            sendAutomationPrompt("Muéstrame el estado de mi domótica y los dispositivos de casa")
        }
        findViewById<View>(R.id.dayWidget).setOnClickListener {
            activeWidgetKind = "day"
            showWidget("Resumen del día", "✦  Preparando tu resumen\n\nAgenda · recordatorios · asuntos importantes")
            sendAutomationPrompt("Dame mi resumen del día con agenda, recordatorios y asuntos importantes")
        }
        findViewById<View>(R.id.newsWidget).setOnClickListener {
            activeWidgetKind = "news"
            showWidget("Noticias", "▣  Actualizando noticias importantes…\n\nLas noticias podrán incluir imágenes y vídeos cuando estén disponibles")
            sendAutomationPrompt("Muéstrame las noticias más importantes de hoy con imágenes o vídeos cuando existan")
        }
        findViewById<View>(R.id.weatherWidget).setOnClickListener {
            activeWidgetKind = "weather"
            showWidget("Tiempo", "☁  Consultando tiempo actual y previsión…")
            sendAutomationPrompt("¿Qué tiempo hace ahora y cuál es la previsión de hoy?")
        }
        findViewById<View>(R.id.phoneControl).setOnClickListener { closeDrawer(); runCatching { startActivity(Intent(this, DeviceHubActivity::class.java)) } }
        findViewById<View>(R.id.voiceSettings).setOnClickListener { showVoiceSettings() }
        findViewById<View>(R.id.camera).setOnClickListener { closeDrawer(); Toast.makeText(this, "Imágenes y cámara", Toast.LENGTH_SHORT).show() }
        findViewById<View>(R.id.files).setOnClickListener {
            closeDrawer()
            runCatching { startActivity(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }) }
        }
        findViewById<View>(R.id.wakeWord).setOnClickListener {
            closeDrawer()
            runCatching { startService(Intent(this, WakeWordService::class.java)) }
            status.text = "Hola Jarvis · escuchando"
        }
    }

    private fun openDrawer() {
        drawerScrim.visibility = View.VISIBLE
        sideMenu.visibility = View.VISIBLE
        sideMenu.bringToFront()
    }

    private fun closeDrawer() {
        sideMenu.visibility = View.GONE
        drawerScrim.visibility = View.GONE
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (sideMenu.visibility == View.VISIBLE) closeDrawer() else super.onBackPressed()
    }

    private fun showWidget(title: String, body: String) {
        widgetTitle.text = title
        widgetBody.text = body
        widgetHost.visibility = View.VISIBLE
        scroll.post { scroll.smoothScrollTo(0, widgetHost.top.coerceAtLeast(0)) }
    }

    private fun updateWidgetFromReply(question: String, reply: String) {
        val lower = question.lowercase()
        val kind = activeWidgetKind ?: when {
            lower.contains("tiempo") || lower.contains("previsión") -> "weather"
            lower.contains("domótica") || lower.contains("casa") -> "home"
            lower.contains("noticias") -> "news"
            lower.contains("resumen") || lower.contains("agenda") -> "day"
            else -> null
        }
        if (kind == null) return
        val clean = reply.replace(Regex("https?://\\S+"), "").replace(Regex("[*_`#>|]+"), " ").trim()
        when (kind) {
            "weather" -> showWidget("Tiempo · en tiempo real", "☁  ${clean.take(900)}")
            "home" -> showWidget("Domótica · estado de casa", "⌂  ${clean.take(900)}")
            "news" -> showWidget("Noticias · ahora", "▣  ${clean.take(900)}")
            "day" -> showWidget("Resumen del día", "✦  ${clean.take(900)}")
        }
        activeWidgetKind = kind
    }

    private fun startNewChat() {
        conversationId = newConversation()
        transcript.text = ""
        activeWidgetKind = null
        widgetHost.visibility = View.GONE
        welcomePanel.visibility = View.VISIBLE
        recentChats.text = "¿En qué puedo ayudarte hoy?"
        refreshDrawerRecents()
    }

    private fun newConversation(): String {
        val id = UUID.randomUUID().toString()
        val index = runCatching { JSONArray(prefs.getString("chatIndex", "[]")) }.getOrElse { JSONArray() }
        index.put(JSONObject().put("id", id).put("title", "Nuevo chat").put("updated", System.currentTimeMillis()))
        prefs.edit().putString("currentConversation", id).putString("chat_$id", "[]").putString("chatIndex", index.toString()).apply()
        return id
    }

    private fun history() = runCatching { JSONArray(prefs.getString("chat_$conversationId", "[]")) }.getOrElse { JSONArray() }

    private fun loadConversation() {
        val a = history()
        val b = StringBuilder()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            b.append(if (o.optString("role") == "user") "\nTú\n" else "\nJarvis\n")
                .append(o.optString("content")).append("\n")
        }
        transcript.text = b.toString()
        welcomePanel.visibility = if (a.length() == 0) View.VISIBLE else View.GONE
    }

    private fun append(role: String, text: String) {
        val a = history()
        a.put(JSONObject().put("role", role).put("content", text))
        prefs.edit().putString("chat_$conversationId", a.toString()).apply()
        if (role == "user" && widgetHost.visibility != View.VISIBLE) welcomePanel.visibility = View.GONE
        transcript.append(if (role == "user") "\nTú\n$text\n" else "\nJarvis\n$text\n")
        updateConversationTitle(text, role)
        scroll.post {
            if (widgetHost.visibility == View.VISIBLE) scroll.smoothScrollTo(0, widgetHost.top.coerceAtLeast(0))
            else scroll.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    private fun updateConversationTitle(text: String, role: String) {
        if (role != "user") return
        val arr = runCatching { JSONArray(prefs.getString("chatIndex", "[]")) }.getOrElse { JSONArray() }
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id") == conversationId) {
                if (o.optString("title") == "Nuevo chat") o.put("title", text.take(38))
                o.put("updated", System.currentTimeMillis())
            }
        }
        prefs.edit().putString("chatIndex", arr.toString()).apply()
        refreshDrawerRecents()
    }

    private fun chatObjects(): List<JSONObject> {
        val a = runCatching { JSONArray(prefs.getString("chatIndex", "[]")) }.getOrElse { JSONArray() }
        val out = mutableListOf<JSONObject>()
        for (i in 0 until a.length()) a.optJSONObject(i)?.let { out.add(it) }
        return out.sortedByDescending { it.optLong("updated") }
    }

    private fun refreshDrawerRecents() {
        val list = chatObjects().take(8)
        findViewById<TextView>(R.id.menuRecents).text = if (list.isEmpty()) "Todavía no hay conversaciones" else list.joinToString("\n\n") { it.optString("title").ifBlank { "Chat" } }
    }

    private fun showChats() {
        val list = chatObjects()
        if (list.isEmpty()) return
        AlertDialog.Builder(this).setTitle("Chats").setItems(list.map { it.optString("title") }.toTypedArray()) { _, i ->
            conversationId = list[i].optString("id")
            prefs.edit().putString("currentConversation", conversationId).apply()
            loadConversation()
            closeDrawer()
        }.setNegativeButton("Cerrar", null).show()
    }

    private fun restoreSelectedTools() {
        val selected = runCatching { JSONArray(prefs.getString("selected_tools", "[]")) }.getOrElse { JSONArray() }
        if (selected.length() == 0) return
        val names = (0 until selected.length()).map { selected.optString(it) }.filter { it.isNotBlank() }
        findViewById<TextView>(R.id.selectedTools).text = names.joinToString("   •   ")
        findViewById<HorizontalScrollView>(R.id.selectedToolsScroll).visibility = if (names.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun showToolPicker() {
        val tools = arrayOf("ChatGPT", "Google Maps", "Home Assistant", "Homey", "Home Connect", "Gmail", "Calendario", "Notion", "WhatsApp", "Otros MCP")
        val stored = runCatching { JSONArray(prefs.getString("selected_tools", "[]")) }.getOrElse { JSONArray() }
        val selectedSet = (0 until stored.length()).map { stored.optString(it) }.toSet()
        val checked = BooleanArray(tools.size) { selectedSet.contains(tools[it]) }
        AlertDialog.Builder(this)
            .setTitle("Herramientas y complementos")
            .setMultiChoiceItems(tools, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("Usar") { _, _ ->
                val selected = tools.filterIndexed { i, _ -> checked[i] }
                findViewById<TextView>(R.id.selectedTools).text = selected.joinToString("   •   ")
                findViewById<HorizontalScrollView>(R.id.selectedToolsScroll).visibility = if (selected.isEmpty()) View.GONE else View.VISIBLE
                prefs.edit().putString("selected_tools", JSONArray(selected).toString()).apply()
                closeDrawer()
            }
            .setNeutralButton("Gestionar MCP") { _, _ -> showConnections() }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun sendAutomationPrompt(text: String) {
        input.setText(text)
        sendMessage()
    }

    private fun showConnections() {
        AlertDialog.Builder(this)
            .setTitle("Complementos y MCP")
            .setMessage("Usa el botón + junto al campo de texto para elegir ChatGPT, domótica, Maps, Gmail, Calendario y otros MCP con los que quieras hablar.")
            .setPositiveButton("Aceptar", null)
            .show()
    }

    private fun showVoiceSettings() {
        val voices = arrayOf("coral", "alloy", "ash", "ballad", "echo", "fable", "nova", "onyx", "sage", "shimmer", "verse")
        AlertDialog.Builder(this).setTitle("Voz de Jarvis").setItems(voices) { _, i ->
            prefs.edit().putString("voice", voices[i]).apply()
            safeSpeak("Hola. Esta es mi voz de Jarvis.")
        }.show()
    }

    private fun sendMessage() {
        val m = input.text.toString().trim()
        if (m.isBlank()) return
        input.text.clear()
        append("user", m)
        status.text = "Pensando…"
        Thread {
            try {
                val c = (URL("$BACKEND/api/chat").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 12000
                    readTimeout = 60000
                    setRequestProperty("Content-Type", "application/json")
                }
                val selected = runCatching { JSONArray(prefs.getString("selected_tools", "[]")) }.getOrElse { JSONArray() }
                val body = JSONObject().put("message", m).put("conversationId", conversationId).put("client", "jarvis-mobile").put("history", history()).put("selectedTools", selected).toString()
                c.outputStream.use { it.write(body.toByteArray()) }
                val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
                val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}: ${raw.take(180)}")
                val reply = runCatching { JSONObject(raw).optString("reply") }.getOrDefault(raw).ifBlank { raw }
                runOnUiThread {
                    append("assistant", reply)
                    updateWidgetFromReply(m, reply)
                    status.text = "Jarvis listo"
                    safeSpeak(reply)
                }
            } catch (e: Throwable) {
                runOnUiThread { status.text = "Error: ${e.message ?: "No se pudo completar la respuesta"}" }
            }
        }.start()
    }

    private fun safeSpeak(text: String) {
        val clean = text.replace(Regex("https?://\\S+"), " ").replace(Regex("[*_`#>|]+"), " ").replace(Regex("\\s+"), " ").trim()
        if (clean.isBlank()) return
        runCatching {
            val i = Intent(this, MobileSpeechService::class.java).putExtra("text", clean).putExtra("voice", prefs.getString("voice", "coral"))
            androidx.core.content.ContextCompat.startForegroundService(this, i)
        }.onFailure {
            status.text = "Respuesta lista · voz no disponible"
        }
    }

    companion object { private const val BACKEND = "https://chatgpt-tv2.vercel.app" }
}
