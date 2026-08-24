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
    private lateinit var conversationHost: LinearLayout
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
    private var lastLocation: Location? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        conversationHost = findViewById(R.id.conversationHost)
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
            sendVisualPrompt("home", "Muéstrame el estado de mi domótica. Devuelve una línea separada por cada dispositivo, escena o dato relevante, sin introducción ni conclusión.")
        }
        findViewById<View>(R.id.dayWidget).setOnClickListener {
            sendVisualPrompt("day", "Dame mi resumen del día. Devuelve una línea separada por cada cita, recordatorio, aviso o asunto importante, sin introducción ni conclusión.")
        }
        findViewById<View>(R.id.newsWidget).setOnClickListener { openNewsFast() }
        findViewById<View>(R.id.weatherWidget).setOnClickListener { openWeatherForCurrentLocation() }
        findViewById<View>(R.id.phoneControl).setOnClickListener { closeDrawer(); runCatching { startActivity(Intent(this, DeviceHubActivity::class.java)) } }
        findViewById<View>(R.id.voiceSettings).setOnClickListener { showVoiceSettings() }
        findViewById<View>(R.id.camera).setOnClickListener { closeDrawer(); Toast.makeText(this, "Imágenes y cámara", Toast.LENGTH_SHORT).show() }
        findViewById<View>(R.id.files).setOnClickListener {
            closeDrawer(); runCatching { startActivity(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }) }
        }
        findViewById<View>(R.id.wakeWord).setOnClickListener {
            closeDrawer(); runCatching { startService(Intent(this, WakeWordService::class.java)) }; status.text = "Hola Jarvis · escuchando"
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun bubbleBackground(user: Boolean): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(20).toFloat()
        setColor(if (user) Color.rgb(28, 34, 48) else Color.rgb(17, 24, 36))
        setStroke(dp(1), if (user) Color.rgb(67, 79, 105) else Color.rgb(46, 60, 82))
    }

    private fun avatarBackground(user: Boolean): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(if (user) Color.rgb(68, 82, 116) else Color.rgb(73, 58, 145))
    }

    private fun makeAvatar(role: String): View {
        val user = role == "user"
        val stored = prefs.getString("profile_uri", null)
        if (user && !stored.isNullOrBlank()) {
            val iv = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(10) }
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = avatarBackground(true)
                clipToOutline = true
                runCatching { setImageURI(Uri.parse(stored)) }
                setOnClickListener { chooseProfileImage() }
            }
            return iv
        }
        return TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(10) }
            gravity = Gravity.CENTER
            text = if (user) "A" else "J"
            textSize = 16f
            setTextColor(Color.WHITE)
            background = avatarBackground(user)
            if (user) setOnClickListener { chooseProfileImage() }
        }
    }

    private fun chooseProfileImage() {
        runCatching {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "image/*"; addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }, REQ_PROFILE_IMAGE)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PROFILE_IMAGE && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                prefs.edit().putString("profile_uri", uri.toString()).apply()
                loadConversation()
            }
        }
    }

    private fun renderMessageCard(role: String, text: String, images: List<String> = emptyList(), videos: List<String> = emptyList()) {
        welcomePanel.visibility = View.GONE
        val user = role == "user"
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) }
        }
        row.addView(makeAvatar(role))
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(12), dp(15), dp(13))
            background = bubbleBackground(user)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        card.addView(TextView(this).apply {
            this.text = if (user) "Tú" else "Jarvis"
            textSize = 12f
            setTextColor(if (user) Color.rgb(174, 191, 225) else Color.rgb(164, 183, 255))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        card.addView(TextView(this).apply {
            this.text = text
            textSize = 16f
            setTextColor(Color.rgb(244, 246, 250))
            setPadding(0, dp(5), 0, 0)
            setLineSpacing(0f, 1.12f)
        })
        images.take(4).forEach { imageUrl -> addRemoteImage(card, imageUrl) }
        videos.take(4).forEach { videoUrl ->
            card.addView(TextView(this).apply {
                this.text = "▶  Abrir vídeo"
                textSize = 13f
                setTextColor(Color.WHITE)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Color.rgb(46, 52, 70)) }
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(9) }
                setOnClickListener { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(videoUrl))) } }
            })
        }
        row.addView(card)
        conversationHost.addView(row)
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun addRemoteImage(parent: LinearLayout, url: String) {
        val image = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(190)).apply { topMargin = dp(10) }
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(Color.rgb(25, 31, 43)) }
            clipToOutline = true
            visibility = View.GONE
            setOnClickListener { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }
        }
        parent.addView(image)
        Thread {
            val bmp = runCatching {
                URL(url).openConnection().apply { connectTimeout = 4500; readTimeout = 6500 }.getInputStream().use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
            if (bmp != null) runOnUiThread { image.setImageBitmap(bmp); image.visibility = View.VISIBLE }
        }.start()
    }

    private fun cardBackground(kind: String): GradientDrawable {
        val color = when (kind) {
            "weather" -> Color.rgb(19, 54, 91)
            "home" -> Color.rgb(19, 67, 56)
            "news" -> Color.rgb(76, 43, 25)
            "day" -> Color.rgb(54, 43, 95)
            else -> Color.rgb(23, 29, 40)
        }
        return GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(color); setStroke(dp(1), Color.argb(80, 255, 255, 255)) }
    }

    private fun beginWidgetGroup(title: String) {
        widgetHost.removeAllViews(); widgetHost.visibility = View.VISIBLE; welcomePanel.visibility = View.GONE
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(2), 0, dp(8)) }
        row.addView(makeAvatar("assistant"))
        row.addView(TextView(this).apply { text = "Jarvis · $title"; textSize = 14f; setTextColor(Color.rgb(220, 232, 255)); setTypeface(typeface, android.graphics.Typeface.BOLD) })
        widgetHost.addView(row)
    }

    private fun addTextWidget(kind: String, title: String, body: String) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP; layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) } }
        row.addView(makeAvatar("assistant"))
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(15), dp(18), dp(15)); background = cardBackground(kind); layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        card.addView(TextView(this).apply { text = title; textSize = 14f; setTextColor(Color.WHITE); setTypeface(typeface, android.graphics.Typeface.BOLD) })
        card.addView(TextView(this).apply { text = body; textSize = 16f; setTextColor(Color.rgb(238, 242, 248)); setPadding(0, dp(6), 0, 0) })
        row.addView(card); widgetHost.addView(row)
    }

    private fun splitWidgetItems(reply: String): List<String> {
        val clean = reply.replace(Regex("https?://\\S+"), "").replace(Regex("[*_`#>|]+"), " ").trim()
        val lines = clean.lines().map { it.trim().replace(Regex("^[-•▪◦*\\d.)\\s]+"), "").trim() }.filter { it.length > 2 }
        return if (lines.size >= 2) lines.take(12) else clean.split(Regex("(?<=[.!?])\\s+(?=[A-ZÁÉÍÓÚÑ])")).map { it.trim() }.filter { it.length > 2 }.take(12)
    }

    private fun renderReplyAsWidgets(kind: String, reply: String) {
        val groupTitle = when (kind) { "weather" -> "Tiempo · tu ubicación"; "home" -> "Domótica"; "day" -> "Resumen del día"; else -> "Información" }
        beginWidgetGroup(groupTitle)
        val items = splitWidgetItems(reply)
        if (items.isEmpty()) addTextWidget(kind, groupTitle, "No hay datos disponibles")
        else items.forEachIndexed { i, item ->
            val title = when (kind) { "weather" -> if (i == 0) "Ahora" else "Previsión ${i}"; "home" -> "Estado ${i + 1}"; "day" -> "${i + 1} · Hoy"; else -> "${i + 1}" }
            addTextWidget(kind, title, item)
        }
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun openNewsFast() {
        beginWidgetGroup("Noticias · ahora")
        addTextWidget("news", "Actualizando", "Cargando titulares…")
        status.text = "Cargando noticias…"
        Thread {
            try {
                val fast = readJson("$BACKEND/api/news?fast=1&country=ES&lang=es")
                val items = fast.optJSONArray("items") ?: JSONArray()
                runOnUiThread {
                    beginWidgetGroup("Noticias · ahora")
                    for (i in 0 until minOf(8, items.length())) items.optJSONObject(i)?.let { addNewsWidget(i, it) }
                    if (items.length() == 0) addTextWidget("news", "Noticias", "No hay titulares disponibles")
                    status.text = "Titulares listos"
                    loadNewsMultimedia()
                }
            } catch (e: Throwable) {
                runOnUiThread { beginWidgetGroup("Noticias"); addTextWidget("news", "Error", e.message ?: "No se pudieron cargar") }
            }
        }.start()
    }

    private fun addNewsWidget(index: Int, item: JSONObject) {
        val url = item.optString("url")
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP; layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) } }
        row.addView(makeAvatar("assistant"))
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(14)); background = cardBackground("news"); layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            if (url.isNotBlank()) setOnClickListener { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }
            tag = "news_card_$index"
        }
        card.addView(TextView(this).apply { text = item.optString("title").ifBlank { "Noticia ${index + 1}" }; textSize = 17f; setTextColor(Color.WHITE); setTypeface(typeface, android.graphics.Typeface.BOLD) })
        card.addView(TextView(this).apply { text = item.optString("source").ifBlank { "Abrir noticia" }; textSize = 12f; setTextColor(Color.rgb(215, 190, 170)); setPadding(0, dp(6), 0, 0) })
        row.addView(card); widgetHost.addView(row)
    }

    private fun loadNewsMultimedia() {
        Thread {
            try {
                val full = readJson("$BACKEND/api/news?country=ES&lang=es")
                val items = full.optJSONArray("items") ?: return@Thread
                for (i in 0 until minOf(8, items.length())) {
                    val item = items.optJSONObject(i) ?: continue
                    val image = item.optString("image"); val video = item.optString("video")
                    runOnUiThread {
                        val card = widgetHost.findViewWithTag<LinearLayout>("news_card_$i") ?: return@runOnUiThread
                        if (image.isNotBlank()) addRemoteImage(card, image)
                        if (video.isNotBlank()) card.addView(TextView(this).apply {
                            text = "▶  Vídeo"; textSize = 13f; setTextColor(Color.WHITE); setPadding(dp(12), dp(8), dp(12), dp(8)); background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Color.rgb(38, 44, 58)) }
                            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
                            setOnClickListener { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(video))) } }
                        })
                    }
                }
            } catch (_: Throwable) { }
        }.start()
    }

    private fun readJson(url: String): JSONObject {
        val c = (URL(url).openConnection() as HttpURLConnection).apply { requestMethod = "GET"; connectTimeout = 6000; readTimeout = 12000; setRequestProperty("Accept", "application/json") }
        val raw = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
        return JSONObject(raw)
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

    private fun sendMessage() {
        val message = input.text.toString().trim(); if (message.isBlank()) return
        input.text.clear()
        renderMessageCard("user", message)
        saveHistory("user", message, false)
        when (val kind = classifyVisualRequest(message)) {
            "news" -> openNewsFast()
            "weather" -> openWeatherForCurrentLocation(message)
            "home", "day" -> executeChat("$message. Devuelve cada elemento relevante en una línea separada, sin introducción ni conclusión.", kind)
            else -> executeChat(message, null)
        }
    }

    private fun sendVisualPrompt(kind: String, prompt: String) {
        renderMessageCard("user", prompt)
        saveHistory("user", prompt, true)
        executeChat(prompt, kind)
    }

    private fun executeChat(message: String, widgetKind: String?) {
        status.text = "Pensando…"
        Thread {
            try {
                val c = (URL("$BACKEND/api/chat").openConnection() as HttpURLConnection).apply { requestMethod = "POST"; doOutput = true; connectTimeout = 12000; readTimeout = 60000; setRequestProperty("Content-Type", "application/json") }
                val selected = runCatching { JSONArray(prefs.getString("selected_tools", "[]")) }.getOrElse { JSONArray() }
                val body = JSONObject().put("message", message).put("conversationId", conversationId).put("client", "jarvis-mobile").put("history", history()).put("selectedTools", selected)
                lastLocation?.let { body.put("location", JSONObject().put("latitude", it.latitude).put("longitude", it.longitude).put("accuracyMeters", it.accuracy)) }
                c.outputStream.use { it.write(body.toString().toByteArray()) }
                val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
                val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}: ${raw.take(180)}")
                val json = runCatching { JSONObject(raw) }.getOrElse { JSONObject().put("reply", raw) }
                val reply = json.optString("reply").ifBlank { raw }
                val images = jsonArrayStrings(json.optJSONArray("images"))
                val videos = jsonArrayStrings(json.optJSONArray("videos"))
                runOnUiThread {
                    saveHistory("assistant", reply, widgetKind != null, images, videos)
                    if (widgetKind != null) renderReplyAsWidgets(widgetKind, reply) else renderMessageCard("assistant", reply, images, videos)
                    status.text = "Jarvis listo"
                    safeSpeak(reply)
                }
            } catch (e: Throwable) {
                runOnUiThread { status.text = "Error: ${e.message ?: "No se pudo completar la respuesta"}"; renderMessageCard("assistant", "No he podido completar esta respuesta: ${e.message ?: "error desconocido"}") }
            }
        }.start()
    }

    private fun jsonArrayStrings(a: JSONArray?): List<String> {
        if (a == null) return emptyList()
        return (0 until a.length()).map { a.optString(it) }.filter { it.startsWith("http") }
    }

    private fun history(): JSONArray = runCatching { JSONArray(prefs.getString("chat_$conversationId", "[]")) }.getOrElse { JSONArray() }

    private fun saveHistory(role: String, text: String, visual: Boolean, images: List<String> = emptyList(), videos: List<String> = emptyList()) {
        val a = history()
        a.put(JSONObject().put("role", role).put("content", text).put("visual", visual).put("images", JSONArray(images)).put("videos", JSONArray(videos)))
        prefs.edit().putString("chat_$conversationId", a.toString()).apply()
        if (role == "user") updateConversationTitle(text)
    }

    private fun loadConversation() {
        conversationHost.removeAllViews(); widgetHost.removeAllViews(); widgetHost.visibility = View.GONE
        val a = history()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            if (o.optBoolean("visual", false) && o.optString("role") == "assistant") continue
            renderMessageCard(o.optString("role"), o.optString("content"), jsonArrayStrings(o.optJSONArray("images")), jsonArrayStrings(o.optJSONArray("videos")))
        }
        welcomePanel.visibility = if (a.length() == 0) View.VISIBLE else View.GONE
    }

    private fun hasLocationPermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    private fun warmLocation() { if (hasLocationPermission()) lastLocation = bestLastKnownLocation() }
    private fun bestLastKnownLocation(): Location? {
        if (!hasLocationPermission()) return null
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return runCatching { lm.getProviders(true) }.getOrDefault(emptyList()).mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }.maxByOrNull { it.time }
    }

    private fun openWeatherForCurrentLocation(original: String? = null) {
        if (!hasLocationPermission()) {
            beginWidgetGroup("Tiempo · tu ubicación"); addTextWidget("weather", "Ubicación", "Necesito permiso para mostrar el tiempo donde estás ahora.")
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOCATION); return
        }
        val loc = bestLastKnownLocation() ?: lastLocation
        if (loc == null) { beginWidgetGroup("Tiempo · tu ubicación"); addTextWidget("weather", "Buscando ubicación", "Activa la ubicación del teléfono si está desactivada."); return }
        lastLocation = loc
        val prefix = original ?: "Dime el tiempo actual y la previsión de hoy"
        executeChat("$prefix. Usa mi ubicación exacta latitud ${loc.latitude}, longitud ${loc.longitude}. Devuelve condiciones actuales y una línea por cada periodo o día, sin introducción ni conclusión.", "weather")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION && grantResults.any { it == PackageManager.PERMISSION_GRANTED }) { warmLocation(); openWeatherForCurrentLocation() }
    }

    private fun startNewChat() {
        conversationId = newConversation(); conversationHost.removeAllViews(); widgetHost.removeAllViews(); widgetHost.visibility = View.GONE; welcomePanel.visibility = View.VISIBLE; recentChats.text = "¿En qué puedo ayudarte hoy?"; refreshDrawerRecents()
    }

    private fun newConversation(): String {
        val id = UUID.randomUUID().toString(); val index = runCatching { JSONArray(prefs.getString("chatIndex", "[]")) }.getOrElse { JSONArray() }
        index.put(JSONObject().put("id", id).put("title", "Nuevo chat").put("updated", System.currentTimeMillis()))
        prefs.edit().putString("currentConversation", id).putString("chat_$id", "[]").putString("chatIndex", index.toString()).apply(); return id
    }

    private fun updateConversationTitle(text: String) {
        val arr = runCatching { JSONArray(prefs.getString("chatIndex", "[]")) }.getOrElse { JSONArray() }
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { o -> if (o.optString("id") == conversationId) { if (o.optString("title") == "Nuevo chat") o.put("title", text.take(38)); o.put("updated", System.currentTimeMillis()) } }
        prefs.edit().putString("chatIndex", arr.toString()).apply(); refreshDrawerRecents()
    }

    private fun chatObjects(): List<JSONObject> {
        val a = runCatching { JSONArray(prefs.getString("chatIndex", "[]")) }.getOrElse { JSONArray() }; val out = mutableListOf<JSONObject>()
        for (i in 0 until a.length()) a.optJSONObject(i)?.let { out.add(it) }; return out.sortedByDescending { it.optLong("updated") }
    }

    private fun refreshDrawerRecents() {
        val list = chatObjects().take(8); findViewById<TextView>(R.id.menuRecents).text = if (list.isEmpty()) "Todavía no hay conversaciones" else list.joinToString("\n\n") { it.optString("title").ifBlank { "Chat" } }
    }

    private fun showChats() {
        val list = chatObjects(); if (list.isEmpty()) return
        AlertDialog.Builder(this).setTitle("Chats").setItems(list.map { it.optString("title") }.toTypedArray()) { _, i -> conversationId = list[i].optString("id"); prefs.edit().putString("currentConversation", conversationId).apply(); loadConversation(); closeDrawer() }.setNegativeButton("Cerrar", null).show()
    }

    private fun restoreSelectedTools() {
        val selected = runCatching { JSONArray(prefs.getString("selected_tools", "[]")) }.getOrElse { JSONArray() }; if (selected.length() == 0) return
        val names = (0 until selected.length()).map { selected.optString(it) }.filter { it.isNotBlank() }
        findViewById<TextView>(R.id.selectedTools).text = names.joinToString("   •   "); findViewById<HorizontalScrollView>(R.id.selectedToolsScroll).visibility = if (names.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun showToolPicker() {
        val tools = arrayOf("ChatGPT", "Google Maps", "Home Assistant", "Homey", "Home Connect", "Gmail", "Calendario", "Notion", "WhatsApp", "Otros MCP")
        val stored = runCatching { JSONArray(prefs.getString("selected_tools", "[]")) }.getOrElse { JSONArray() }; val selectedSet = (0 until stored.length()).map { stored.optString(it) }.toSet(); val checked = BooleanArray(tools.size) { selectedSet.contains(tools[it]) }
        AlertDialog.Builder(this).setTitle("Herramientas y complementos").setMultiChoiceItems(tools, checked) { _, which, isChecked -> checked[which] = isChecked }.setPositiveButton("Usar") { _, _ ->
            val selected = tools.filterIndexed { i, _ -> checked[i] }; findViewById<TextView>(R.id.selectedTools).text = selected.joinToString("   •   "); findViewById<HorizontalScrollView>(R.id.selectedToolsScroll).visibility = if (selected.isEmpty()) View.GONE else View.VISIBLE; prefs.edit().putString("selected_tools", JSONArray(selected).toString()).apply(); closeDrawer()
        }.setNeutralButton("Gestionar MCP") { _, _ -> showConnections() }.setNegativeButton("Cerrar", null).show()
    }

    private fun showConnections() { AlertDialog.Builder(this).setTitle("Complementos y MCP").setMessage("Usa el botón + junto al campo de texto para elegir las aplicaciones y MCP con las que quieres hablar.").setPositiveButton("Aceptar", null).show() }
    private fun showVoiceSettings() {
        val voices = arrayOf("coral", "alloy", "ash", "ballad", "echo", "fable", "nova", "onyx", "sage", "shimmer", "verse")
        AlertDialog.Builder(this).setTitle("Voz de Jarvis").setItems(voices) { _, i -> prefs.edit().putString("voice", voices[i]).apply(); safeSpeak("Hola. Esta es mi voz de Jarvis.") }.show()
    }

    private fun openDrawer() { drawerScrim.visibility = View.VISIBLE; sideMenu.visibility = View.VISIBLE; sideMenu.bringToFront() }
    private fun closeDrawer() { sideMenu.visibility = View.GONE; drawerScrim.visibility = View.GONE }
    @Deprecated("Deprecated in Java") override fun onBackPressed() { if (sideMenu.visibility == View.VISIBLE) closeDrawer() else super.onBackPressed() }

    private fun safeSpeak(text: String) {
        val clean = text.replace(Regex("https?://\\S+"), " ").replace(Regex("[*_`#>|]+"), " ").replace(Regex("\\s+"), " ").trim(); if (clean.isBlank()) return
        runCatching { ContextCompat.startForegroundService(this, Intent(this, MobileSpeechService::class.java).putExtra("text", clean).putExtra("voice", prefs.getString("voice", "coral"))) }.onFailure { status.text = "Respuesta lista · voz no disponible" }
    }

    companion object {
        private const val BACKEND = "https://chatgpt-tv2.vercel.app"
        private const val REQ_LOCATION = 1204
        private const val REQ_PROFILE_IMAGE = 1205
    }
}