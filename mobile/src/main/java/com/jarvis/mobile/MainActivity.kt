package com.jarvis.mobile

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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
    private lateinit var widgetHost: LinearLayout

    private val prefs by lazy { getSharedPreferences("jarvis_mobile", MODE_PRIVATE) }
    private var conversationId = ""
    private var activeWidgetKind: String? = null
    private var lastLocation: Location? = null

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

        conversationId = prefs.getString("currentConversation", null) ?: newConversation()
        loadConversation()
        refreshDrawerRecents()
        restoreSelectedTools()
        warmLocation()

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
            sendWidgetPrompt("home", "Muéstrame el estado de mi domótica. Devuelve una línea separada por cada dispositivo, escena o dato relevante, sin introducción ni conclusión.")
        }
        findViewById<View>(R.id.dayWidget).setOnClickListener {
            sendWidgetPrompt("day", "Dame mi resumen del día. Devuelve una línea separada por cada cita, recordatorio, aviso o asunto importante, sin introducción ni conclusión.")
        }
        findViewById<View>(R.id.newsWidget).setOnClickListener { openNewsFast() }
        findViewById<View>(R.id.weatherWidget).setOnClickListener { openWeatherForCurrentLocation() }

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

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun cardBackground(kind: String): GradientDrawable {
        val color = when (kind) {
            "weather" -> Color.rgb(19, 54, 91)
            "home" -> Color.rgb(19, 67, 56)
            "news" -> Color.rgb(76, 43, 25)
            "day" -> Color.rgb(54, 43, 95)
            else -> Color.rgb(23, 29, 40)
        }
        return GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(color)
            setStroke(dp(1), Color.argb(80, 255, 255, 255))
        }
    }

    private fun beginWidgetGroup(title: String) {
        widgetHost.removeAllViews()
        widgetHost.visibility = View.VISIBLE
        val header = TextView(this).apply {
            text = title
            textSize = 15f
            setTextColor(Color.rgb(220, 232, 255))
            setPadding(dp(2), dp(4), dp(2), dp(8))
        }
        widgetHost.addView(header)
        welcomePanel.visibility = View.GONE
    }

    private fun addTextWidget(kind: String, title: String, body: String) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = cardBackground(kind)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            }
        }
        val t = TextView(this).apply {
            text = title
            textSize = 14f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val b = TextView(this).apply {
            text = body
            textSize = 16f
            setTextColor(Color.rgb(238, 242, 248))
            setPadding(0, dp(6), 0, 0)
        }
        card.addView(t)
        card.addView(b)
        widgetHost.addView(card)
    }

    private fun splitWidgetItems(reply: String): List<String> {
        val clean = reply
            .replace(Regex("https?://\\S+"), "")
            .replace(Regex("[*_`#>|]+"), " ")
            .trim()
        val byLines = clean.lines()
            .map { it.trim().replace(Regex("^[-•▪◦*\\d.)\\s]+"), "").trim() }
            .filter { it.length > 2 }
        if (byLines.size >= 2) return byLines.take(12)
        return clean.split(Regex("\\n\\s*\\n|(?<=[.!?])\\s+(?=[A-ZÁÉÍÓÚÑ])"))
            .map { it.trim() }
            .filter { it.length > 2 }
            .take(12)
    }

    private fun renderReplyAsWidgets(kind: String, reply: String) {
        val title = when (kind) {
            "weather" -> "Tiempo · tu ubicación actual"
            "home" -> "Domótica · estado de casa"
            "day" -> "Resumen del día"
            else -> "Jarvis"
        }
        beginWidgetGroup(title)
        val items = splitWidgetItems(reply)
        if (items.isEmpty()) {
            addTextWidget(kind, title, "No hay datos disponibles")
        } else {
            items.forEachIndexed { index, item ->
                val cardTitle = when (kind) {
                    "weather" -> if (index == 0) "Ahora" else "Previsión ${index}"
                    "home" -> "Dispositivo / estado ${index + 1}"
                    "day" -> "${index + 1} · Hoy"
                    else -> "${index + 1}"
                }
                addTextWidget(kind, cardTitle, item)
            }
        }
        scroll.post { scroll.smoothScrollTo(0, widgetHost.top.coerceAtLeast(0)) }
    }

    private fun addNewsCard(index: Int, item: JSONObject): Triple<ImageView, TextView, String> {
        val url = item.optString("url")
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(14))
            background = cardBackground("news")
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) }
            isClickable = url.isNotBlank()
            setOnClickListener { if (url.isNotBlank()) runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }
        }
        val image = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(180))
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
            clipToOutline = true
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(Color.rgb(27, 30, 38)) }
        }
        val title = TextView(this).apply {
            text = item.optString("title").ifBlank { "Noticia ${index + 1}" }
            textSize = 17f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(4), dp(10), dp(4), dp(5))
        }
        val source = TextView(this).apply {
            val s = item.optString("source")
            text = if (s.isBlank()) "Abrir noticia" else s
            textSize = 12f
            setTextColor(Color.rgb(215, 190, 170))
            setPadding(dp(4), 0, dp(4), 0)
        }
        val videoBadge = TextView(this).apply {
            text = "▶  Vídeo"
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Color.rgb(38, 44, 58)) }
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        }
        card.addView(image)
        card.addView(title)
        card.addView(source)
        card.addView(videoBadge)
        widgetHost.addView(card)
        return Triple(image, videoBadge, url)
    }

    private fun openNewsFast() {
        activeWidgetKind = "news"
        beginWidgetGroup("Noticias · ahora")
        addTextWidget("news", "Actualizando", "Cargando titulares…")
        status.text = "Cargando noticias…"
        Thread {
            try {
                val fast = readJson("$BACKEND/api/news?fast=1&country=ES&lang=es")
                val items = fast.optJSONArray("items") ?: JSONArray()
                runOnUiThread {
                    beginWidgetGroup("Noticias · ahora")
                    val views = mutableListOf<Triple<ImageView, TextView, String>>()
                    for (i in 0 until minOf(8, items.length())) {
                        items.optJSONObject(i)?.let { views += addNewsCard(i, it) }
                    }
                    if (views.isEmpty()) addTextWidget("news", "Noticias", "No hay titulares disponibles")
                    status.text = "Titulares listos"
                    loadNewsMultimedia(views)
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    beginWidgetGroup("Noticias · ahora")
                    addTextWidget("news", "Error", "No se pudieron cargar las noticias: ${e.message}")
                }
            }
        }.start()
    }

    private fun loadNewsMultimedia(views: List<Triple<ImageView, TextView, String>>) {
        Thread {
            try {
                val full = readJson("$BACKEND/api/news?country=ES&lang=es")
                val items = full.optJSONArray("items") ?: return@Thread
                for (i in 0 until minOf(views.size, items.length())) {
                    val item = items.optJSONObject(i) ?: continue
                    val imageUrl = item.optString("image")
                    val videoUrl = item.optString("video")
                    val imageView = views[i].first
                    val videoBadge = views[i].second
                    if (imageUrl.isNotBlank()) {
                        val bmp = runCatching {
                            URL(imageUrl).openConnection().apply { connectTimeout = 4500; readTimeout = 6500 }
                                .getInputStream().use { BitmapFactory.decodeStream(it) }
                        }.getOrNull()
                        if (bmp != null) runOnUiThread { imageView.setImageBitmap(bmp); imageView.visibility = View.VISIBLE }
                    }
                    if (videoUrl.isNotBlank()) runOnUiThread { videoBadge.visibility = View.VISIBLE }
                }
            } catch (_: Throwable) { }
        }.start()
    }

    private fun readJson(url: String): JSONObject {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 6000
            readTimeout = 12000
            setRequestProperty("Accept", "application/json")
        }
        val raw = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
        return JSONObject(raw)
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun warmLocation() { if (hasLocationPermission()) lastLocation = bestLastKnownLocation() }

    private fun bestLastKnownLocation(): Location? {
        if (!hasLocationPermission()) return null
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return runCatching { lm.getProviders(true) }.getOrDefault(emptyList())
            .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    private fun openWeatherForCurrentLocation() {
        activeWidgetKind = "weather"
        if (!hasLocationPermission()) {
            beginWidgetGroup("Tiempo · tu ubicación")
            addTextWidget("weather", "Ubicación", "Necesito permiso para mostrar el tiempo donde estás ahora.")
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOCATION)
            return
        }
        val loc = bestLastKnownLocation() ?: lastLocation
        if (loc == null) {
            beginWidgetGroup("Tiempo · tu ubicación")
            addTextWidget("weather", "Buscando ubicación", "Activa la ubicación del teléfono si está desactivada.")
            return
        }
        lastLocation = loc
        sendWidgetPrompt(
            "weather",
            "Dime el tiempo actual y la previsión para mi ubicación exacta latitud ${loc.latitude}, longitud ${loc.longitude}. Devuelve una línea separada para condiciones actuales y una línea por cada periodo o día previsto, sin introducción ni conclusión."
        )
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION && grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
            warmLocation(); openWeatherForCurrentLocation()
        }
    }

    private fun classifyVisualRequest(text: String): String? {
        val s = text.lowercase()
        return when {
            s.contains("noticia") || s.contains("titular") -> "news"
            s.contains("tiempo") || s.contains("previsión") || s.contains("temperatura") -> "weather"
            s.contains("domótica") || s.contains("luces") || s.contains("persianas") || s.contains("casa") -> "home"
            s.contains("agenda") || s.contains("resumen del día") || s.contains("recordatorio") -> "day"
            else -> null
        }
    }

    private fun sendWidgetPrompt(kind: String, prompt: String) {
        activeWidgetKind = kind
        beginWidgetGroup(
            when (kind) {
                "weather" -> "Tiempo · tu ubicación actual"
                "home" -> "Domótica · estado de casa"
                "day" -> "Resumen del día"
                else -> "Jarvis"
            }
        )
        addTextWidget(kind, "Actualizando", "Jarvis está preparando los datos…")
        saveHistory("user", prompt, visual = true)
        executeChat(prompt, kind)
    }

    private fun sendMessage() {
        val message = input.text.toString().trim()
        if (message.isBlank()) return
        input.text.clear()
        val visualKind = classifyVisualRequest(message)
        if (visualKind == "news") {
            saveHistory("user", message, visual = true)
            openNewsFast()
            return
        }
        if (visualKind == "weather" && hasLocationPermission()) {
            val loc = bestLastKnownLocation() ?: lastLocation
            if (loc != null) {
                lastLocation = loc
                sendWidgetPrompt("weather", "$message. Usa mi ubicación actual exacta: latitud ${loc.latitude}, longitud ${loc.longitude}. Devuelve cada dato o periodo en una línea separada, sin introducción ni conclusión.")
                return
            }
        }
        if (visualKind != null) {
            sendWidgetPrompt(visualKind, "$message. Devuelve cada elemento relevante en una línea separada, sin introducción ni conclusión.")
            return
        }
        saveHistory("user", message, visual = false)
        appendVisible("user", message)
        executeChat(message, null)
    }

    private fun executeChat(message: String, widgetKind: String?) {
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
                val body = JSONObject()
                    .put("message", message)
                    .put("conversationId", conversationId)
                    .put("client", "jarvis-mobile")
                    .put("history", history())
                    .put("selectedTools", selected)
                lastLocation?.let {
                    body.put("location", JSONObject().put("latitude", it.latitude).put("longitude", it.longitude).put("accuracyMeters", it.accuracy))
                }
                c.outputStream.use { it.write(body.toString().toByteArray()) }
                val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
                val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}: ${raw.take(180)}")
                val reply = runCatching { JSONObject(raw).optString("reply") }.getOrDefault(raw).ifBlank { raw }
                runOnUiThread {
                    if (widgetKind != null) {
                        saveHistory("assistant", reply, visual = true)
                        renderReplyAsWidgets(widgetKind, reply)
                    } else {
                        saveHistory("assistant", reply, visual = false)
                        appendVisible("assistant", reply)
                    }
                    status.text = "Jarvis listo"
                    safeSpeak(reply)
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    status.text = "Error: ${e.message ?: "No se pudo completar la respuesta"}"
                    if (widgetKind != null) {
                        beginWidgetGroup("Jarvis")
                        addTextWidget(widgetKind, "Error", e.message ?: "No se pudo cargar la información")
                    }
                }
            }
        }.start()
    }

    private fun history(): JSONArray = runCatching { JSONArray(prefs.getString("chat_$conversationId", "[]")) }.getOrElse { JSONArray() }

    private fun saveHistory(role: String, text: String, visual: Boolean) {
        val a = history()
        a.put(JSONObject().put("role", role).put("content", text).put("visual", visual))
        prefs.edit().putString("chat_$conversationId", a.toString()).apply()
        if (role == "user") updateConversationTitle(text)
    }

    private fun appendVisible(role: String, text: String) {
        welcomePanel.visibility = View.GONE
        transcript.append(if (role == "user") "\nTú\n$text\n" else "\nJarvis\n$text\n")
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun loadConversation() {
        val a = history()
        val b = StringBuilder()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            if (o.optBoolean("visual", false)) continue
            b.append(if (o.optString("role") == "user") "\nTú\n" else "\nJarvis\n")
                .append(o.optString("content")).append("\n")
        }
        transcript.text = b.toString()
        welcomePanel.visibility = if (b.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun startNewChat() {
        conversationId = newConversation()
        transcript.text = ""
        activeWidgetKind = null
        widgetHost.removeAllViews()
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

    private fun updateConversationTitle(text: String) {
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
            widgetHost.removeAllViews(); widgetHost.visibility = View.GONE
            loadConversation(); closeDrawer()
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

    private fun safeSpeak(text: String) {
        val clean = text.replace(Regex("https?://\\S+"), " ").replace(Regex("[*_`#>|]+"), " ").replace(Regex("\\s+"), " ").trim()
        if (clean.isBlank()) return
        runCatching {
            val i = Intent(this, MobileSpeechService::class.java)
                .putExtra("text", clean)
                .putExtra("voice", prefs.getString("voice", "coral"))
            ContextCompat.startForegroundService(this, i)
        }.onFailure { status.text = "Respuesta lista · voz no disponible" }
    }

    companion object {
        private const val BACKEND = "https://chatgpt-tv2.vercel.app"
        private const val REQ_LOCATION = 1204
    }
}
