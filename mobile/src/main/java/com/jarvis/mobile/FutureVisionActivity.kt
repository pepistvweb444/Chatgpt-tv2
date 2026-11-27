package com.jarvis.mobile

import android.Manifest
import androidx.appcompat.app.AppCompatActivity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.jarvis.logic.CallButton
import com.jarvis.logic.CallFixtures
import com.jarvis.logic.presentCall
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FutureVisionActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("jarvis_mobile", MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var regular: Typeface
    private lateinit var semi: Typeface
    private lateinit var home: LinearLayout
    private lateinit var dialer: LinearLayout
    private lateinit var callHost: LinearLayout
    private lateinit var dateLine: TextView
    private lateinit var greeting: TextView
    private lateinit var subtitle: TextView
    private lateinit var contextLine: TextView
    private lateinit var agendaBody: TextView
    private lateinit var weatherTemp: TextView
    private lateinit var weatherDetail: TextView
    private lateinit var weatherPlace: TextView
    private lateinit var houseValue: TextView
    private lateinit var houseDetail: TextView
    private lateinit var bottomBar: LinearLayout
    private lateinit var promptTitle: TextView
    private lateinit var promptDetail: TextView
    private var dialerBuilt = false
    private var spokenFor = ""
    private var simIndex = 0
    private var tts: TextToSpeech? = null

    private val tick = object : Runnable {
        override fun run() {
            renderCall()
            dateLine.text = dateText()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        regular = runCatching { Typeface.createFromAsset(assets, "fonts/Inter-Regular.ttf") }.getOrDefault(Typeface.SANS_SERIF)
        semi = runCatching { Typeface.createFromAsset(assets, "fonts/Inter-SemiBold.ttf") }.getOrDefault(Typeface.DEFAULT_BOLD)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = true
        val root = FrameLayout(this).apply { setBackgroundColor(BG) }
        home = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(12), dp(18), dp(12)) }
        val scroll = ScrollView(this).apply { addView(home); isFillViewport = true }
        root.addView(scroll, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        dialer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG); visibility = View.GONE }
        root.addView(dialer, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)
        buildHome()
        tts = TextToSpeech(this) { }
        loadAgenda()
        loadWeather()
        loadHouse()
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
        CallSessionWatcher.refresh(this)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (dialer.visibility == View.VISIBLE) { dialer.visibility = View.GONE; return }
        super.onBackPressed()
    }

    private fun buildHome() {
        home.addView(header())
        dateLine = label(dateText(), 12f, MUTED, semi).apply { setPadding(0, dp(18), 0, 0) }
        greeting = label(greetingText(), 32f, INK, semi)
        subtitle = label("", 15f, MUTED, regular).apply { setPadding(0, dp(4), 0, dp(12)) }
        home.addView(dateLine); home.addView(greeting); home.addView(subtitle)
        callHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        home.addView(callHost)
        home.addView(dashboard())
        bottomBar = bottomBar()
        home.addView(bottomBar)
    }

    private fun header(): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(circleButton("☰") { showMenu() })
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) }
        }
        titles.addView(label("JARVIS", 18f, INK, semi))
        contextLine = label(contextText(), 12f, MUTED, regular)
        titles.addView(contextLine)
        row.addView(titles)
        row.addView(pill("Llamar", LIME, INK) { showDialer() })
        return row
    }

    private fun dashboard(): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        val agenda = card().apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.15f).apply { marginEnd = dp(10) } }
        agenda.addView(sectionTitle("AGENDA", "Calendar"))
        agendaBody = label("Leyendo el calendario…", 14f, INK, regular)
        agenda.addView(agendaBody)
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        val weather = card()
        weather.addView(sectionTitle("TIEMPO", city()))
        weatherTemp = label("—", 36f, INK, semi)
        weatherDetail = label("", 13f, MUTED, regular)
        weatherPlace = weatherDetail
        weather.addView(weatherTemp); weather.addView(weatherDetail)
        val house = card().apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(10) }
        house.addView(sectionTitle("CASA", ""))
        houseValue = label("—", 36f, INK, semi)
        houseDetail = label("Sin lectura de consumo", 13f, MUTED, regular)
        house.addView(houseValue); house.addView(houseDetail)
        right.addView(weather); right.addView(house)
        row.addView(agenda); row.addView(right)
        return row
    }

    private fun bottomBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = round(LIME_SOFT, 28)
            setPadding(dp(16), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) }
            visibility = View.GONE
        }
        val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        promptTitle = label("", 14f, INK, semi)
        promptDetail = label("", 12f, INK, regular)
        text.addView(promptTitle); text.addView(promptDetail)
        bar.addView(text)
        bar.addView(pill("Sí", INK, Color.WHITE) { act("leave_ai") }.apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(8) })
        bar.addView(pill("Coger", Color.WHITE, INK) { act("answer") }.apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(8) })
        bar.addView(circleButton("🎤") { startActivity(Intent(this, MainActivity::class.java)) }.apply { background = round(LIME, 28) })
        return bar
    }

    private fun renderCall() {
        val raw = CallStateStore.current(this)
        val model = presentCall(raw.toCallSnapshot())
        callHost.removeAllViews()
        subtitle.text = if (model.visible) model.homeSubtitle else "¿En qué puedo ayudarte?"
        bottomBar.visibility = if (model.visible && model.bottomPrompt != null) View.VISIBLE else View.GONE
        if (model.visible && model.bottomPrompt != null) {
            promptTitle.text = model.bottomPrompt
            promptDetail.text = model.bottomDetail.orEmpty()
        }
        if (!model.visible) return
        maybeSpeak(raw.optString("id"), model.homeSubtitle, model.bottomPrompt)
        val card = card().apply {
            background = round(Color.WHITE, 22, if (model.border == "orange") ORANGE else LIME)
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(pill(model.badge, if (model.badgeTone == "orange") ORANGE_SOFT else LIME, INK))
        top.addView(label(model.heading, 12f, MUTED, semi).apply { setPadding(dp(8), 0, 0, 0); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
        top.addView(label(model.meta, 12f, MUTED, regular))
        card.addView(top)
        val who = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(14), 0, 0) }
        who.addView(avatar(raw.optString("photoData"), model.name))
        val nameCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        nameCol.addView(label(model.name, 26f, INK, semi))
        if (model.numberLine.isNotBlank()) nameCol.addView(label(model.numberLine, 14f, MUTED, regular))
        model.detailLine?.let { nameCol.addView(label(it, 13f, if (model.border == "lime") LIME_INK else MUTED, semi)) }
        model.address?.let { nameCol.addView(label(it, 13f, MUTED, regular)) }
        who.addView(nameCol)
        card.addView(who)
        if (model.showVideoFrame || model.videoNote != null) card.addView(videoBox(raw.optString("videoFrame"), model.videoNote.orEmpty(), model.showVideoFrame))
        card.addView(label("▮▮▮  ${model.aiLine}", 14f, LIME_INK, semi).apply { setPadding(0, dp(10), 0, 0) })
        model.banner?.let { card.addView(banner(it)) }
        model.transcript.forEach { line ->
            val bubble = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = round(if (line.speaker.contains("IA")) Color.parseColor("#F4F7EF") else Color.parseColor("#F0F1EE"), 16)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
            }
            bubble.addView(label("${line.speaker} · ${line.timeLabel}", 11f, MUTED, semi))
            bubble.addView(label(line.text, 14f, INK, regular))
            card.addView(bubble)
        }
        model.askLine?.let { card.addView(label(it, 16f, INK, semi).apply { setPadding(0, dp(12), 0, 0) }) }
        card.addView(buttons(model.buttons))
        callHost.addView(card)
    }

    private fun buttons(items: List<com.jarvis.logic.LabeledButton>): LinearLayout {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(12), 0, 0) }
        val primary = items.firstOrNull()
        if (primary != null && items.size != 2) {
            wrap.addView(wideButton(primary.label, LIME, INK) { act(primary.action.wireName()) })
        }
        val rest = if (items.size == 2) items else items.drop(1)
        if (rest.isNotEmpty()) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
            rest.forEachIndexed { index, button ->
                val danger = button.action == CallButton.HANGUP
                val view = wideButton(button.label, if (danger) RED else Color.WHITE, if (danger) Color.WHITE else INK) { act(button.action.wireName()) }
                view.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { if (index == 0) marginEnd = dp(8) }
                if (!danger) view.background = round(Color.WHITE, 24, Color.parseColor("#D9DDD4"))
                row.addView(view)
            }
            wrap.addView(row)
        }
        if (items.size == 2) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            items.forEachIndexed { index, button ->
                val filled = index == 0
                val view = wideButton(button.label, if (filled) LIME else Color.WHITE, INK) { act(button.action.wireName()) }
                view.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { if (index == 0) marginEnd = dp(8) }
                if (!filled) view.background = round(Color.WHITE, 24, Color.parseColor("#D9DDD4"))
                row.addView(view)
            }
            return row
        }
        return wrap
    }

    private fun videoBox(frame: String, note: String, live: Boolean): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        val frameBox = FrameLayout(this).apply {
            background = round(Color.parseColor("#1C1F1A"), 16)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(180))
        }
        if (frame.isNotBlank()) {
            val image = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            runCatching {
                val bytes = Base64.decode(frame, Base64.DEFAULT)
                image.setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            }
            frameBox.addView(image, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        if (live) frameBox.addView(pill("EN DIRECTO", RED, Color.WHITE).apply { setPadding(dp(10), dp(8), dp(10), dp(8)) })
        box.addView(frameBox)
        box.addView(label(note, 12f, MUTED, regular).apply { setPadding(0, dp(6), 0, 0) })
        return box
    }

    private fun showDialer() {
        if (!dialerBuilt) buildDialer()
        dialer.visibility = View.VISIBLE
        loadDialerContacts("")
    }

    private fun buildDialer() {
        dialerBuilt = true
        dialer.setPadding(dp(18), dp(16), dp(18), dp(16))
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(circleButton("←") { dialer.visibility = View.GONE })
        top.addView(label("Llamar desde Jarvis", 22f, INK, semi).apply { setPadding(dp(12), 0, 0, 0) })
        dialer.addView(top)
        val note = card().apply { background = round(LIME_SOFT, 16); setPadding(dp(14), dp(12), dp(14), dp(12)) }
        val line = InitTpvClient(this).displayNumber().let { if (it.isBlank()) "el número de InitTPV" else it }
        note.addView(label("Las llamadas salen con $line, la misma línea de la alarma que usa ahora la IA. El destinatario verá ese número, no el del móvil.", 13f, INK, regular))
        dialer.addView(note)
        val search = EditText(this).apply {
            hint = "Buscar contacto o número"
            background = round(Color.WHITE, 18)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            inputType = InputType.TYPE_CLASS_TEXT
        }
        dialer.addView(search)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "dialer-list" }
        val scroller = ScrollView(this).apply { addView(list); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f) }
        dialer.addView(scroller)
        val typed = label("", 28f, INK, semi).apply { gravity = Gravity.CENTER; tag = "typed-number" }
        dialer.addView(typed)
        dialer.addView(keypad { digit ->
            typed.text = typed.text.toString() + digit
            search.setText("")
        })
        val callRow = LinearLayout(this).apply { gravity = Gravity.CENTER }
        callRow.addView(circleButton("📞") {
            val number = typed.text.toString().ifBlank { search.text.toString().trim() }
            placeOutgoing(number)
        }.apply { background = round(LIME, 32); layoutParams = LinearLayout.LayoutParams(dp(64), dp(64)) })
        dialer.addView(callRow)
        search.setOnEditorActionListener { _, _, _ -> loadDialerContacts(search.text.toString()); true }
    }

    private fun loadDialerContacts(query: String) {
        val list = dialer.findViewWithTag<LinearLayout>("dialer-list") ?: return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_CONTACTS), 41)
            return
        }
        Thread {
            val rows = mutableListOf<Pair<String, String>>()
            val feed = runCatching { JSONArray(prefs.getString("call_feed", "[]")) }.getOrElse { JSONArray() }
            for (i in feed.length() - 1 downTo 0) {
                if (rows.size >= 3) break
                val item = feed.optJSONObject(i) ?: continue
                val number = item.optString("number")
                val name = item.optString("name").ifBlank { number }
                if (number.isBlank()) continue
                if (query.isNotBlank() && !"$name $number".contains(query, true)) continue
                rows += name to number
            }
            runCatching {
                contentResolver.query(
                    android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER),
                    null, null, android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                )?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    val numberIndex = cursor.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER)
                    while (cursor.moveToNext() && rows.size < 12) {
                        val name = if (nameIndex >= 0) cursor.getString(nameIndex).orEmpty() else ""
                        val number = if (numberIndex >= 0) cursor.getString(numberIndex).orEmpty() else ""
                        if (query.isNotBlank() && !"$name $number".contains(query, true)) continue
                        if (rows.any { it.second.filter { ch -> ch.isDigit() } == number.filter { ch -> ch.isDigit() } }) continue
                        rows += name to number
                    }
                }
            }
            runOnUiThread {
                list.removeAllViews()
                list.addView(label("RECIENTES Y CONTACTOS", 11f, MUTED, semi).apply { setPadding(0, dp(8), 0, dp(6)) })
                if (rows.isEmpty()) list.addView(label("Sin contactos para esa búsqueda.", 14f, MUTED, regular))
                rows.forEach { (name, number) ->
                    val row = card()
                    row.addView(label(name, 16f, INK, semi))
                    row.addView(label(number, 13f, MUTED, regular))
                    row.setOnClickListener { placeOutgoing(number) }
                    list.addView(row)
                }
            }
        }.start()
    }

    private fun placeOutgoing(number: String) {
        val clean = number.trim()
        if (clean.isBlank()) { Toast.makeText(this, "Escribe un número o elige un contacto.", Toast.LENGTH_SHORT).show(); return }
        val client = InitTpvClient(this)
        if (!client.configured()) {
            Toast.makeText(this, "Conecta la línea InitTPV en el menú. Jarvis no usa el número del móvil para esta llamada.", Toast.LENGTH_LONG).show()
            askInitTpv()
            return
        }
        Thread {
            val ok = client.outbound(clean)
            runOnUiThread {
                Toast.makeText(this, if (ok) "Llamando a $clean por InitTPV." else "InitTPV no ha aceptado la llamada.", Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun act(action: String) {
        val (ok, message) = CallActionManager.perform(this, action)
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        if (ok && (action == "hangup" || action == "reject")) IncomingCallPresenter.dismiss(this)
        renderCall()
    }

    private fun maybeSpeak(id: String, subtitleText: String, prompt: String?) {
        if (id.isBlank() || spokenFor == id || prompt.isNullOrBlank()) return
        spokenFor = id
        val text = "$subtitleText $prompt"
        tts?.language = Locale("es", "ES")
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    private fun showMenu() {
        val items = arrayOf("Chat", "Línea InitTPV", "Cuentas de compra", "Simular tarjetas de llamada", "Remote del teléfono")
        AlertDialog.Builder(this).setTitle("Jarvis").setItems(items) { _, which ->
            when (which) {
                0 -> startActivity(Intent(this, MainActivity::class.java))
                1 -> askInitTpv()
                2 -> askStore()
                3 -> simulate()
                4 -> startActivity(Intent(this, DeviceHubActivity::class.java))
            }
        }.show()
    }

    private fun simulate() {
        val samples = listOf(CallFixtures.lauraIncoming(), CallFixtures.iratiUnknown(), CallFixtures.lauraLive(), CallFixtures.lauraVideoUnavailable(), CallFixtures.lauraVideoLive())
        val sample = samples[simIndex % samples.size]
        simIndex++
        val json = JSONObject()
            .put("id", "sim-$simIndex").put("active", true).put("state", "ringing").put("phase", sample.phase.name.lowercase())
            .put("source", "inittpv").put("app", if (sample.video) "WhatsApp" else "Teléfono")
            .put("channelLabel", sample.channelLabel).put("name", sample.name).put("number", sample.number)
            .put("knownContact", sample.knownContact).put("sourceLabel", sample.sourceLabel)
            .put("ratingText", sample.ratingText ?: "").put("reviewText", sample.reviewText ?: "")
            .put("address", sample.address ?: "").put("video", sample.video)
            .put("videoPreview", sample.videoPreview.name.lowercase()).put("aiConnected", sample.aiConnected)
            .put("aiStatus", sample.aiStatus).put("ageLabel", sample.ageLabel).put("wantsUser", sample.wantsUser)
            .put("time", System.currentTimeMillis()).put("updatedAt", System.currentTimeMillis())
        val lines = JSONArray()
        sample.transcript.forEach { lines.put(JSONObject().put("speaker", it.speaker).put("text", it.text).put("timeLabel", it.timeLabel)) }
        json.put("transcript", lines)
        CallStateStore.save(this, json)
        spokenFor = ""
        renderCall()
    }

    private fun askInitTpv() {
        val url = EditText(this).apply { hint = "https://tu-linea-inittpv"; setText(prefs.getString("inittpv_base_url", "")) }
        val token = EditText(this).apply { hint = "Token de tu línea"; inputType = InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val caller = EditText(this).apply { hint = "Número que verá el destino"; setText(prefs.getString("inittpv_caller_id", "")) }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), 0); addView(url); addView(token); addView(caller) }
        AlertDialog.Builder(this).setTitle("Línea InitTPV").setView(box).setPositiveButton("Guardar") { _, _ ->
            prefs.edit().putString("inittpv_base_url", url.text.toString().trim()).putString("inittpv_token", token.text.toString().trim()).putString("inittpv_caller_id", caller.text.toString().trim()).apply()
            Toast.makeText(this, "Línea guardada en el teléfono.", Toast.LENGTH_SHORT).show()
        }.setNegativeButton("Cerrar", null).show()
    }

    private fun askStore() {
        val stores = arrayOf("glovo", "uber eats", "just eat", "deliveroo")
        AlertDialog.Builder(this).setTitle("Cuenta con la que compras").setItems(stores.map { DeliveryCoordinator.storeName(it) }.toTypedArray()) { _, which ->
            startActivity(Intent(this, StoreLoginActivity::class.java).putExtra("store", stores[which]))
        }.show()
    }

    private fun loadAgenda() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            agendaBody.text = "Concede el calendario para ver la agenda de hoy."
            return
        }
        Thread {
            val text = runCatching {
                val start = System.currentTimeMillis()
                val end = start + 36 * 60 * 60 * 1000
                val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().appendPath(start.toString()).appendPath(end.toString()).build()
                val rows = mutableListOf<String>()
                contentResolver.query(uri, arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.EVENT_LOCATION), null, null, CalendarContract.Instances.BEGIN)?.use { cursor ->
                    val title = cursor.getColumnIndex(CalendarContract.Instances.TITLE)
                    val begin = cursor.getColumnIndex(CalendarContract.Instances.BEGIN)
                    val where = cursor.getColumnIndex(CalendarContract.Instances.EVENT_LOCATION)
                    val hour = SimpleDateFormat("HH:mm", Locale("es", "ES"))
                    while (cursor.moveToNext() && rows.size < 3) {
                        val whenText = if (begin >= 0) hour.format(Date(cursor.getLong(begin))) else ""
                        val place = if (where >= 0) cursor.getString(where).orEmpty() else ""
                        rows += "$whenText  ${if (title >= 0) cursor.getString(title).orEmpty() else "Cita"}\n${place.ifBlank { "Sin lugar" }}"
                    }
                }
                if (rows.isEmpty()) "No hay citas en las próximas horas." else rows.joinToString("\n\n")
            }.getOrDefault("No he podido leer el calendario.")
            runOnUiThread { agendaBody.text = text }
        }.start()
    }

    private fun loadWeather() {
        Thread {
            val text = runCatching {
                val connection = (URL("https://chatgpt-tv2.vercel.app/api/weather?place=${java.net.URLEncoder.encode(city(), "UTF-8")}").openConnection() as HttpURLConnection)
                connection.connectTimeout = 5000; connection.readTimeout = 7000
                val raw = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(raw)
                val temp = json.optDouble("temperature", Double.NaN)
                val code = json.optInt("code", -1)
                if (temp.isNaN()) null else "${temp.toInt()}°" to weatherWords(code)
            }.getOrNull()
            runOnUiThread {
                if (text == null) { weatherTemp.text = "—"; weatherDetail.text = "Sin lectura · ${city()}" }
                else { weatherTemp.text = text.first; weatherDetail.text = "${text.second} · ${city()}" }
            }
        }.start()
    }

    private fun loadHouse() {
        val percent = prefs.getInt("energy_percent", -1)
        if (percent in 0..100) { houseValue.text = "$percent%"; houseDetail.text = "del consumo medio" }
        else { houseValue.text = "—"; houseDetail.text = "Sin lectura de consumo" }
    }

    private fun weatherWords(code: Int) = when (code) {
        0 -> "Despejado"; 1, 2 -> "Poco nuboso"; 3 -> "Nublado"; 45, 48 -> "Niebla"
        51, 53, 55, 61, 63, 65 -> "Lluvia"; 71, 73, 75 -> "Nieve"; 95 -> "Tormenta"; else -> "Tiempo local"
    }

    private fun keypad(onDigit: (String) -> Unit): LinearLayout {
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        listOf(listOf("1" to "", "2" to "ABC", "3" to "DEF"), listOf("4" to "GHI", "5" to "JKL", "6" to "MNO"), listOf("7" to "PQRS", "8" to "TUV", "9" to "WXYZ"), listOf("*" to "", "0" to "+", "#" to "")).forEach { line ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            line.forEach { (digit, letters) ->
                val key = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(dp(88), dp(64))
                    setOnClickListener { onDigit(digit) }
                }
                key.addView(label(digit, 26f, INK, semi).apply { gravity = Gravity.CENTER })
                if (letters.isNotBlank()) key.addView(label(letters, 10f, MUTED, regular).apply { gravity = Gravity.CENTER })
                row.addView(key)
            }
            grid.addView(row)
        }
        return grid
    }

    private fun avatar(photo: String, name: String): TextView {
        val view = TextView(this).apply {
            text = com.jarvis.logic.initialsOf(name)
            gravity = Gravity.CENTER
            textSize = 22f
            setTextColor(INK)
            typeface = semi
            background = round(LIME, 28)
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
        }
        if (photo.isNotBlank()) {
            runCatching {
                val bytes = Base64.decode(photo, Base64.DEFAULT)
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return view
                val image = ImageView(this).apply {
                    setImageBitmap(bitmap); scaleType = ImageView.ScaleType.CENTER_CROP
                    background = round(LIME, 28); layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)); clipToOutline = true
                }
                // The caller photo replaces the initials when we have one. The initials view stays as the fallback.
                view.background = BitmapDrawableSafe.circle(bitmap, dp(56))
                view.text = ""
                image.visibility = View.GONE
            }
        }
        return view
    }

    private fun banner(text: String) = label(text, 13f, Color.parseColor("#8A5A12"), semi).apply {
        background = round(ORANGE_SOFT, 14)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
    }

    private fun sectionTitle(left: String, right: String): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(label(left, 11f, MUTED, semi).apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
        if (right.isNotBlank()) row.addView(label(right, 11f, MUTED, regular))
        return row
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = round(Color.WHITE, 18)
        setPadding(dp(14), dp(14), dp(14), dp(14))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
    }

    private fun wideButton(text: String, fill: Int, ink: Int, click: () -> Unit) = TextView(this).apply {
        this.text = text; gravity = Gravity.CENTER; textSize = 15f; setTextColor(ink); typeface = semi
        background = round(fill, 24); setPadding(dp(12), dp(14), dp(12), dp(14)); setOnClickListener { click() }
    }

    private fun pill(text: String, fill: Int, ink: Int, click: (() -> Unit)? = null) = TextView(this).apply {
        this.text = text; textSize = 13f; setTextColor(ink); typeface = semi; gravity = Gravity.CENTER
        background = round(fill, 20); setPadding(dp(14), dp(8), dp(14), dp(8))
        if (click != null) setOnClickListener { click() }
    }

    private fun circleButton(glyph: String, click: () -> Unit) = TextView(this).apply {
        text = glyph; gravity = Gravity.CENTER; textSize = 18f; setTextColor(INK)
        background = round(Color.WHITE, 24); layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)); setOnClickListener { click() }
    }

    private fun label(text: String, size: Float, color: Int, face: Typeface) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color); typeface = face
    }

    private fun round(color: Int, radiusDp: Int, stroke: Int? = null) = GradientDrawable().apply {
        cornerRadius = dp(radiusDp).toFloat(); setColor(color)
        if (stroke != null) setStroke(dp(2), stroke)
    }

    private fun dateText(): String {
        val locale = Locale("es", "ES")
        return SimpleDateFormat("EEEE d MMM", locale).format(Date()).uppercase(locale) + " · " + city().uppercase(locale)
    }

    private fun greetingText(): String {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val hello = when (hour) { in 6..11 -> "Buenos días"; in 12..20 -> "Buenas tardes"; else -> "Buenas noches" }
        return "$hello, ${prefs.getString("user_name", "Alberto")}"
    }

    private fun contextText(): String {
        val provider = prefs.getString("ai_provider", "Gemini").orEmpty().replaceFirstChar { it.uppercase() }.ifBlank { "Gemini" }
        return "● Contexto activo · $provider"
    }

    private fun city() = prefs.getString("home_city", "Pamplona").orEmpty().ifBlank { "Pamplona" }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private val BG = 0xFFF3F4F1.toInt()
        private val LIME = 0xFFA4D21A.toInt()
        private val LIME_SOFT = 0xFFE7F6BE.toInt()
        private val LIME_INK = 0xFF4E6B09.toInt()
        private val INK = 0xFF17191C.toInt()
        private val MUTED = 0xFF6B7268.toInt()
        private val ORANGE = 0xFFE8943A.toInt()
        private val ORANGE_SOFT = 0xFFFFF1DE.toInt()
        private val RED = 0xFFFF5A36.toInt()
    }
}

private object BitmapDrawableSafe {
    fun circle(bitmap: android.graphics.Bitmap, size: Int): android.graphics.drawable.Drawable {
        val scaled = android.graphics.Bitmap.createScaledBitmap(bitmap, size, size, true)
        return android.graphics.drawable.BitmapDrawable(scaled).apply { setBounds(0, 0, size, size) }
    }
}
