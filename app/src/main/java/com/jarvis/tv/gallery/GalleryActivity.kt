package com.jarvis.tv.gallery

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.LruCache
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.util.concurrent.Executors

/** Jarvis Galería for TV: phone over LAN (no login), Google Photos (Picker), OneDrive/Samsung Gallery, Jarvis account. */
class GalleryActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(4)
    private val cache = object : LruCache<String, Bitmap>(48 * 1024 * 1024) { override fun sizeOf(k: String, v: Bitmap) = v.byteCount }
    private lateinit var cfg: GalleryConfig
    private lateinit var store: Store
    private lateinit var status: TextView
    private lateinit var body: FrameLayout
    private var photos: List<GPhoto> = emptyList()
    private var loginToken = 0  // cancels stale polling loops

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GalleryConfig.writeTemplateIfMissing(this)
        cfg = GalleryConfig.load(this); store = Store(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(12, 14, 20)); setPadding(32, 24, 32, 16) }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun tab(label: String, f: () -> Unit) = Button(this).apply { text = label; isAllCaps = false; textSize = 16f; setOnClickListener { f() } }.also { bar.addView(it) }
        tab("📱 Móvil (Wi-Fi)") { openLan() }
        tab("Google Fotos") { openGoogle() }
        tab("OneDrive / Samsung") { openMicrosoft() }
        tab("Cuenta Jarvis") { openAccount() }
        tab("Ajustes") { openSettings() }
        root.addView(bar)
        status = TextView(this).apply { setTextColor(Color.LTGRAY); textSize = 15f; setPadding(8, 8, 8, 8) }
        root.addView(status)
        body = FrameLayout(this); root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        bar.getChildAt(0).requestFocus()
        when { store.get("last") == "google" && GoogleProvider(cfg, store).signedIn() -> openGoogle()
               store.get("last") == "ms" && MicrosoftProvider(cfg, store).signedIn() -> openMicrosoft()
               store.get("last") == "sb" && SupabaseProvider(cfg, store).signedIn() -> openAccount()
               else -> openLan() }
    }

    override fun onDestroy() { loginToken++; io.shutdownNow(); super.onDestroy() }

    private fun say(t: String) = ui.post { status.text = t }
    private fun bg(work: () -> Unit) = io.execute { try { work() } catch (e: Throwable) { say("⚠️ " + (e.message ?: e.javaClass.simpleName)) } }
    private fun show(v: View) { body.removeAllViews(); body.addView(v, FrameLayout.LayoutParams(-1, -1)) }

    /* ---------- sources ---------- */
    private fun openLan() { loginToken++; store.put("last" to "lan"); say("Cargando fotos del móvil por Wi-Fi…"); bg { val l = LanProvider(this).list(); ui.post { grid(l, "Móvil · ${l.size} fotos") } } }

    private fun openGoogle() {
        loginToken++; store.put("last" to "google")
        val g = GoogleProvider(cfg, store)
        if (!cfg.googleReady) return notConfigured("Google Fotos", "googleClientId y googleClientSecret")
        if (!g.signedIn()) return deviceLogin("Google", { g.startLogin() }, { g.poll(it) }) { openGoogle() }
        say("Google Fotos: cargando selección…")
        bg {
            val l = if (g.hasSession()) runCatching { g.list() }.getOrDefault(emptyList()) else emptyList()
            ui.post { if (l.isEmpty()) pickGoogle(g) else grid(l, "Google Fotos · ${l.size} fotos  (Ajustes → Elegir más fotos)") }
        }
    }

    private fun pickGoogle(g: GoogleProvider) {
        val my = ++loginToken
        bg {
            val uri = g.newSession()
            ui.post { show(qrPanel(uri, "Escanea con el móvil para abrir Google Fotos y elige fotos o álbumes.\nPulsa \"Listo\" en el móvil cuando termines.", uri)) }
            say("Esperando tu selección en Google Fotos…")
            while (my == loginToken) { Thread.sleep(5000); if (g.sessionDone()) { val l = g.list(); ui.post { grid(l, "Google Fotos · ${l.size} fotos") }; return@bg } }
        }
    }

    private fun openMicrosoft() {
        loginToken++; store.put("last" to "ms")
        val m = MicrosoftProvider(cfg, store)
        if (!cfg.microsoftReady) return notConfigured("OneDrive", "microsoftClientId")
        if (!m.signedIn()) return deviceLogin("Microsoft", { m.startLogin() }, { m.poll(it) }) { openMicrosoft() }
        say("OneDrive: leyendo Álbum de cámara / Samsung Gallery…")
        bg { val l = m.list(); ui.post { grid(l, "OneDrive · ${l.size} fotos") } }
    }

    private fun openAccount() {
        loginToken++; store.put("last" to "sb")
        val s = SupabaseProvider(cfg, store)
        if (!cfg.supabaseReady) return notConfigured("Cuenta Jarvis", "supabaseUrl y supabaseAnonKey")
        if (s.signedIn()) { say("Cuenta ${s.email()}: cargando…"); bg { val l = s.list(); ui.post { grid(l, "Cuenta ${s.email()} · ${l.size} fotos") } }; return }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, 40, 0, 0) }
        val email = EditText(this).apply { hint = "Email"; inputType = InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS or InputType.TYPE_CLASS_TEXT; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY) }
        val pass = EditText(this).apply { hint = "Contraseña"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY) }
        val row = LinearLayout(this)
        val login = Button(this).apply { text = "Iniciar sesión"; setOnClickListener { bg { s.signIn(email.text.toString().trim(), pass.text.toString()); ui.post { openAccount() } } } }
        val signup = Button(this).apply { text = "Crear cuenta"; setOnClickListener { bg { say(s.signUp(email.text.toString().trim(), pass.text.toString())); if (s.signedIn()) ui.post { openAccount() } } } }
        row.addView(login); row.addView(signup)
        listOf(email, pass).forEach { box.addView(it, LinearLayout.LayoutParams(700, -2)) }; box.addView(row)
        show(box); email.requestFocus(); say("Inicia sesión con tu cuenta Jarvis (email y contraseña)")
    }

    private fun openSettings() {
        loginToken++; cfg = GalleryConfig.load(this)
        val g = GoogleProvider(cfg, store); val m = MicrosoftProvider(cfg, store); val s = SupabaseProvider(cfg, store)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 16, 16, 16) }
        fun line(t: String) = box.addView(TextView(this).apply { text = t; setTextColor(Color.WHITE); textSize = 16f; setPadding(0, 6, 0, 6) })
        fun btn(t: String, f: () -> Unit) = box.addView(Button(this).apply { text = t; isAllCaps = false; setOnClickListener { f() } }, LinearLayout.LayoutParams(-2, -2))
        line("Configuración: ${cfg.source}")
        line("Edita: ${GalleryConfig.candidates(this).joinToString("  o  ") { it.absolutePath }}")
        line("Google: ${if (!cfg.googleReady) "sin configurar" else if (g.signedIn()) "conectado" else "no conectado"}   ·   Microsoft: ${if (!cfg.microsoftReady) "sin configurar" else if (m.signedIn()) "conectado" else "no conectado"}   ·   Cuenta: ${if (!cfg.supabaseReady) "sin configurar" else if (s.signedIn()) s.email() else "no conectada"}")
        line("Móvil por Wi-Fi: ${LanProvider(this).host().ifBlank { "sin emparejar (hazlo en Javistv)" }}")
        if (g.signedIn()) { btn("Google: elegir más fotos") { store.put("last" to "google"); pickGoogle(g) }; btn("Cerrar sesión Google") { g.signOut(); openSettings() } }
        if (m.signedIn()) btn("Cerrar sesión Microsoft") { m.signOut(); openSettings() }
        if (s.signedIn()) btn("Cerrar sesión cuenta Jarvis") { s.signOut(); openSettings() }
        btn("Recargar configuración") { openSettings() }
        show(ScrollView(this).apply { addView(box) }); say("Ajustes de Jarvis Galería")
    }

    private fun notConfigured(name: String, keys: String) {
        show(TextView(this).apply { setTextColor(Color.WHITE); textSize = 18f; setPadding(24, 24, 24, 24)
            text = "$name no está configurado.\n\nAñade $keys en:\n${GalleryConfig.candidates(this@GalleryActivity).first().absolutePath}\n\ny pulsa Ajustes → Recargar configuración." })
        say("$name: falta configuración")
    }

    /* ---------- device-code login with QR ---------- */
    private fun deviceLogin(name: String, start: () -> DeviceLogin, poll: (DeviceLogin) -> Boolean, done: () -> Unit) {
        val my = ++loginToken; say("Conectando con $name…")
        bg {
            val d = start()
            ui.post { show(qrPanel(d.qrUrl, "Inicia sesión en $name: escanea el QR con el móvil (o abre ${d.verificationUrl})\ne introduce este código:", d.userCode)) }
            say("Esperando confirmación de $name en el móvil…")
            var interval = d.intervalSec.coerceAtLeast(3)
            while (my == loginToken && System.currentTimeMillis() < d.expiresAt) {
                Thread.sleep(interval * 1000L)
                if (poll(d)) { say("$name conectado ✅"); ui.post { if (my == loginToken) done() }; return@bg }
            }
            if (my == loginToken) say("El código de $name caducó. Vuelve a pulsar el botón.")
        }
    }

    private fun qrPanel(url: String, text: String, big: String): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        box.addView(ImageView(this).apply { setImageBitmap(qr(url, 520)) }, LinearLayout.LayoutParams(520, 520))
        box.addView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 0, 0, 0)
            addView(TextView(context).apply { this.text = text; setTextColor(Color.WHITE); textSize = 20f })
            addView(TextView(context).apply { this.text = big; setTextColor(Color.rgb(120, 200, 255)); textSize = if (big.length > 20) 14f else 44f; setPadding(0, 24, 0, 0) })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        return box
    }

    private fun qr(text: String, size: Int): Bitmap {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
        val px = IntArray(size * size) { if (m.get(it % size, it / size)) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.RGB_565)
    }

    /* ---------- grid + viewer ---------- */
    private fun grid(list: List<GPhoto>, title: String) {
        photos = list; say(if (list.isEmpty()) "$title — no hay fotos" else "$title   ·   OK = ver, ◀ ▶ = navegar")
        val gv = GridView(this).apply {
            numColumns = 6; horizontalSpacing = 8; verticalSpacing = 8; stretchMode = GridView.STRETCH_COLUMN_WIDTH
            adapter = object : BaseAdapter() {
                override fun getCount() = list.size
                override fun getItem(p: Int) = list[p]
                override fun getItemId(p: Int) = p.toLong()
                override fun getView(p: Int, cv: View?, parent: ViewGroup?): View {
                    val iv = (cv as? ImageView) ?: ImageView(this@GalleryActivity).apply { layoutParams = AbsListView.LayoutParams(-1, 200); scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(Color.DKGRAY) }
                    load(iv, list[p].thumbUrl, list[p].headers, 400)
                    return iv
                }
            }
            setOnItemClickListener { _, _, p, _ -> viewer(p) }
        }
        show(gv); gv.requestFocus()
    }

    private fun load(iv: ImageView, url: String, headers: Map<String, String>, max: Int) {
        iv.tag = url
        cache.get(url)?.let { iv.setImageBitmap(it); return }
        iv.setImageDrawable(null)
        io.execute {
            val bmp = runCatching { decode(Http.bytes(url, headers), max) }.getOrNull() ?: return@execute
            cache.put(url, bmp); ui.post { if (iv.tag == url) iv.setImageBitmap(bmp) }
        }
    }

    private fun decode(b: ByteArray, max: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeByteArray(b, 0, b.size, o)
        var s = 1; while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= max) s *= 2
        return BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = s })
    }

    private fun viewer(start: Int) {
        var i = start
        val iv = ImageView(this).apply { setBackgroundColor(Color.BLACK); scaleType = ImageView.ScaleType.FIT_CENTER; isFocusable = true }
        val dlg = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        var slideshow = false
        val tick = object : Runnable { override fun run() { if (slideshow && dlg.isShowing) { i = (i + 1) % photos.size; load(iv, photos[i].fullUrl, photos[i].headers, 1920); ui.postDelayed(this, 6000) } } }
        load(iv, photos[i].fullUrl, photos[i].headers, 1920)
        dlg.setContentView(iv)
        dlg.setOnKeyListener { _, code, ev ->
            if (ev.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (code) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> { i = (i + 1) % photos.size; load(iv, photos[i].fullUrl, photos[i].headers, 1920); true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { i = (i - 1 + photos.size) % photos.size; load(iv, photos[i].fullUrl, photos[i].headers, 1920); true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { slideshow = !slideshow; Toast.makeText(this, if (slideshow) "Presentación ▶" else "Presentación ⏸", Toast.LENGTH_SHORT).show(); if (slideshow) ui.postDelayed(tick, 6000); true }
                else -> false
            }
        }
        dlg.setOnDismissListener { slideshow = false }
        dlg.show()
    }
}
