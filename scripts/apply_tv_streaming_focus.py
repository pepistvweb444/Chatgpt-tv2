from pathlib import Path

# ---------- Layout: installed streaming apps + focused content preview ----------
layout = Path('app/src/main/res/layout/activity_main.xml')
x = layout.read_text()
if '@+id/streamingAppsRow' not in x:
    anchor = '''                <TextView
                    android:id="@+id/currentChatLabel"'''
    block = '''                <TextView
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:text="Tus aplicaciones"
                    android:textColor="#FFFFFF"
                    android:textSize="20sp"
                    android:textStyle="bold"
                    android:paddingTop="18dp"
                    android:paddingBottom="10dp" />

                <HorizontalScrollView
                    android:layout_width="match_parent"
                    android:layout_height="132dp"
                    android:clipToPadding="false"
                    android:scrollbars="none">
                    <LinearLayout
                        android:id="@+id/streamingAppsRow"
                        android:layout_width="wrap_content"
                        android:layout_height="match_parent"
                        android:orientation="horizontal"
                        android:gravity="center_vertical"
                        android:paddingStart="6dp"
                        android:paddingEnd="16dp" />
                </HorizontalScrollView>

                <TextView
                    android:id="@+id/streamingPreviewTitle"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:text="Selecciona una app para ver contenido"
                    android:textColor="#A9B2C2"
                    android:textSize="16sp"
                    android:paddingTop="8dp"
                    android:paddingBottom="8dp" />

                <HorizontalScrollView
                    android:layout_width="match_parent"
                    android:layout_height="190dp"
                    android:clipToPadding="false"
                    android:scrollbars="none">
                    <LinearLayout
                        android:id="@+id/streamingPreviewHost"
                        android:layout_width="wrap_content"
                        android:layout_height="match_parent"
                        android:orientation="horizontal"
                        android:gravity="center_vertical"
                        android:paddingStart="6dp"
                        android:paddingEnd="16dp" />
                </HorizontalScrollView>

'''
    if anchor not in x:
        raise SystemExit('currentChatLabel layout anchor not found')
    x = x.replace(anchor, block + anchor, 1)
    layout.write_text(x)

# ---------- Main activity: TV app agent, app icons, focus effects, previews ----------
p = Path('app/src/main/java/com/jarvis/tv/MainActivity.kt')
s = p.read_text()

import_anchor = 'import android.content.pm.PackageManager\n'
for imp in [
    'import android.graphics.BitmapFactory\n',
    'import android.graphics.Color\n',
    'import android.graphics.drawable.GradientDrawable\n',
    'import android.view.View\n',
    'import android.view.ViewGroup\n',
    'import android.widget.ImageView\n',
]:
    if imp not in s:
        s = s.replace(import_anchor, import_anchor + imp, 1)

field_anchor = '    private var conversationId: String = ""\n'
if 'private val tvAppAgent by lazy' not in s:
    s = s.replace(field_anchor, field_anchor + '    private val tvAppAgent by lazy { TvAppAgentController(this) }\n', 1)

if 'setupStreamingApps()' not in s:
    oncreate_anchor = '        bindUi()\n'
    if oncreate_anchor not in s:
        raise SystemExit('bindUi onCreate anchor not found')
    s = s.replace(oncreate_anchor, oncreate_anchor + '        setupStreamingApps()\n        setupTvFocusEffects()\n', 1)

# Route streaming commands through the real TV accessibility agent before generic chat.
route_anchor = '        append("user", text)\n'
if 'tvAppAgent.looksLikeTvTask(text)' not in s:
    route = '''        append("user", text)
        if (tvAppAgent.looksLikeTvTask(text)) {
            status.text = "● Controlando aplicación de TV…"
            tvAppAgent.run(text,
                onUpdate = { message -> runOnUiThread { status.text = "● $message" } },
                onDone = { result ->
                    append("assistant", result, true)
                    status.text = "● Jarvis listo"
                }
            )
            return
        }
'''
    if route_anchor not in s:
        raise SystemExit('sendMessage append anchor not found')
    s = s.replace(route_anchor, route, 1)

helper_anchor = '    private fun resolveEndpoint(base: String, endpoint: String): String {'
if 'private data class StreamingAppSpec' not in s:
    helpers = r'''    private data class StreamingAppSpec(val label: String, val provider: String, val packages: List<String>)

    private fun tvDp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun focusCard(view: View, focused: Boolean) {
        view.animate().cancel()
        view.animate().scaleX(if (focused) 1.08f else 1f).scaleY(if (focused) 1.08f else 1f).setDuration(120).start()
        view.elevation = if (focused) tvDp(20).toFloat() else tvDp(3).toFloat()
        view.translationZ = if (focused) tvDp(16).toFloat() else 0f
        view.alpha = if (focused) 1f else 0.92f
    }

    private fun setupTvFocusEffects() {
        val ids = intArrayOf(R.id.cardNow, R.id.cardHome, R.id.cardMessages, R.id.cardWeather,
            R.id.chatsButton, R.id.visionButton, R.id.settingsButton, R.id.assistantBubble,
            R.id.micButton, R.id.sendButton)
        ids.forEach { id ->
            findViewById<View>(id)?.let { v ->
                v.isFocusable = true
                v.setOnFocusChangeListener { view, hasFocus -> focusCard(view, hasFocus) }
            }
        }
    }

    private fun streamingSpecs(): List<StreamingAppSpec> = listOf(
        StreamingAppSpec("Netflix", "Netflix", listOf("com.netflix.ninja", "com.netflix.mediaclient")),
        StreamingAppSpec("Prime Video", "Prime Video", listOf("com.amazon.avod.thirdpartyclient", "com.amazon.avod")),
        StreamingAppSpec("Apple TV", "Apple TV+", listOf("com.apple.atve.androidtv.appletv", "com.apple.atve.sony.appletv")),
        StreamingAppSpec("Max", "Max", listOf("com.wbd.stream", "com.hbo.hbonow")),
        StreamingAppSpec("Disney+", "Disney+", listOf("com.disney.disneyplus")),
        StreamingAppSpec("YouTube", "YouTube", listOf("com.google.android.youtube.tv")),
        StreamingAppSpec("Movistar Plus+", "Movistar Plus+", listOf("es.plus.yomvi")),
        StreamingAppSpec("Spotify", "Spotify", listOf("com.spotify.tv.android"))
    )

    private fun installedPackage(spec: StreamingAppSpec): String? {
        val pm = packageManager
        for (pkg in spec.packages) {
            val ok = runCatching { pm.getApplicationInfo(pkg, 0); true }.getOrDefault(false)
            if (ok) return pkg
        }
        val leanback = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        val normalized = spec.label.lowercase().replace("+", "")
        return pm.queryIntentActivities(leanback, PackageManager.MATCH_ALL).firstOrNull { ri ->
            val label = ri.loadLabel(pm)?.toString().orEmpty().lowercase().replace("+", "")
            label.contains(normalized) || normalized.contains(label)
        }?.activityInfo?.packageName
    }

    private fun launchStreamingPackage(pkg: String): Boolean {
        val launch = packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: packageManager.getLaunchIntentForPackage(pkg) ?: return false
        return runCatching { startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
    }

    private fun tileBackground(focused: Boolean = false): GradientDrawable = GradientDrawable().apply {
        cornerRadius = tvDp(18).toFloat()
        setColor(if (focused) Color.rgb(42, 50, 70) else Color.rgb(21, 26, 36))
        setStroke(tvDp(if (focused) 3 else 1), if (focused) Color.rgb(123, 149, 255) else Color.rgb(56, 65, 82))
    }

    private fun setupStreamingApps() {
        val row = findViewById<LinearLayout>(R.id.streamingAppsRow)
        row.removeAllViews()
        streamingSpecs().forEach { spec ->
            val pkg = installedPackage(spec) ?: return@forEach
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                isFocusable = true
                isClickable = true
                background = tileBackground(false)
                setPadding(tvDp(14), tvDp(10), tvDp(14), tvDp(10))
                layoutParams = LinearLayout.LayoutParams(tvDp(150), tvDp(112)).apply { marginEnd = tvDp(12) }
            }
            val icon = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(tvDp(58), tvDp(58))
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageDrawable(runCatching { packageManager.getApplicationIcon(pkg) }.getOrNull())
            }
            val label = TextView(this).apply {
                text = spec.label; textSize = 14f; setTextColor(Color.WHITE); gravity = android.view.Gravity.CENTER
                setPadding(0, tvDp(5), 0, 0); maxLines = 1
            }
            tile.addView(icon); tile.addView(label)
            tile.setOnFocusChangeListener { view, hasFocus ->
                view.background = tileBackground(hasFocus)
                focusCard(view, hasFocus)
                if (hasFocus) loadStreamingPreview(spec, pkg)
            }
            tile.setOnClickListener { launchStreamingPackage(pkg) }
            row.addView(tile)
        }
    }

    private fun cachedPersonalTitles(provider: String): List<String> {
        val key = "streaming_cache_" + provider.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
        val a = runCatching { JSONArray(prefs.getString(key, "[]")) }.getOrElse { JSONArray() }
        return (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }.distinct().take(8)
    }

    private fun addPreviewTextCard(host: LinearLayout, title: String, subtitle: String, pkg: String) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = true; isClickable = true
            setPadding(tvDp(14), tvDp(12), tvDp(14), tvDp(12))
            background = tileBackground(false)
            layoutParams = LinearLayout.LayoutParams(tvDp(250), tvDp(168)).apply { marginEnd = tvDp(12) }
            setOnFocusChangeListener { view, focused -> view.background = tileBackground(focused); focusCard(view, focused) }
            setOnClickListener { launchStreamingPackage(pkg) }
        }
        card.addView(TextView(this).apply { text = title; textSize = 17f; setTextColor(Color.WHITE); setTypeface(typeface, android.graphics.Typeface.BOLD); maxLines = 3 })
        if (subtitle.isNotBlank()) card.addView(TextView(this).apply { text = subtitle; textSize = 13f; setTextColor(Color.rgb(190, 200, 218)); setPadding(0, tvDp(8), 0, 0); maxLines = 3 })
        host.addView(card)
    }

    private fun addPreviewMediaCard(host: LinearLayout, item: JSONObject, pkg: String) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = true; isClickable = true
            setPadding(tvDp(8), tvDp(8), tvDp(8), tvDp(10))
            background = tileBackground(false)
            layoutParams = LinearLayout.LayoutParams(tvDp(270), tvDp(178)).apply { marginEnd = tvDp(12) }
            setOnFocusChangeListener { view, focused -> view.background = tileBackground(focused); focusCard(view, focused) }
            setOnClickListener { launchStreamingPackage(pkg) }
        }
        val imageUrl = item.optString("image")
        val image = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, tvDp(98))
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.rgb(30, 35, 46))
        }
        card.addView(image)
        card.addView(TextView(this).apply { text = item.optString("title").ifBlank { "Contenido" }; textSize = 14f; setTextColor(Color.WHITE); setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(tvDp(3), tvDp(7), tvDp(3), 0); maxLines = 2 })
        host.addView(card)
        if (imageUrl.isNotBlank()) Thread {
            val bmp = runCatching { URL(imageUrl).openConnection().apply { connectTimeout = 3500; readTimeout = 5000 }.getInputStream().use { BitmapFactory.decodeStream(it) } }.getOrNull()
            if (bmp != null) runOnUiThread { image.setImageBitmap(bmp) }
        }.start()
    }

    private fun loadStreamingPreview(spec: StreamingAppSpec, pkg: String) {
        val title = findViewById<TextView>(R.id.streamingPreviewTitle)
        val host = findViewById<LinearLayout>(R.id.streamingPreviewHost)
        host.removeAllViews()
        val cached = cachedPersonalTitles(spec.provider)
        if (cached.isNotEmpty()) {
            title.text = "${spec.label} · tu contenido detectado"
            cached.forEach { addPreviewTextCard(host, it, "Detectado en tu perfil/interfaz · abre la app para continuar", pkg) }
        } else {
            title.text = "${spec.label} · estrenos y recomendaciones públicas"
            addPreviewTextCard(host, "Cargando ${spec.label}…", "Si la app expone Favoritos/Continuar viendo a Accesibilidad, Jarvis los guardará aquí.", pkg)
        }
        Thread {
            try {
                val url = resolveEndpoint(prefs.getString("backendUrl", DEFAULT_BACKEND).orEmpty().ifBlank { DEFAULT_BACKEND }, "media") + "?q=" + Uri.encode(spec.provider + " España estrenos")
                val c = (URL(url).openConnection() as HttpURLConnection).apply { requestMethod = "GET"; connectTimeout = 6000; readTimeout = 12000 }
                val raw = c.inputStream.bufferedReader().use { it.readText() }
                val items = JSONObject(raw).optJSONArray("items") ?: JSONArray()
                val chosen = mutableListOf<JSONObject>()
                for (i in 0 until items.length()) {
                    val o = items.optJSONObject(i) ?: continue
                    if (o.optString("section").contains(spec.provider.substringBefore('+'), true) || spec.provider.contains(o.optString("section"), true)) chosen += o
                    if (chosen.size >= 6) break
                }
                if (chosen.isNotEmpty()) runOnUiThread {
                    if (cached.isEmpty()) host.removeAllViews()
                    chosen.forEach { addPreviewMediaCard(host, it, pkg) }
                }
            } catch (_: Throwable) { }
        }.start()
    }

'''
    if helper_anchor not in s:
        raise SystemExit('resolveEndpoint helper anchor not found')
    s = s.replace(helper_anchor, helpers + helper_anchor, 1)

p.write_text(s)

# ---------- Accessibility: cache visible personalized rows/favorites/continue-watching labels ----------
a = Path('app/src/main/java/com/jarvis/tv/JarvisAccessibilityService.kt')
t = a.read_text()
if 'cacheStreamingContent' not in t:
    event_old = '''    override fun onAccessibilityEvent(event: AccessibilityEvent?) { getSharedPreferences("jarvis_tv",MODE_PRIVATE).edit().putString("foreground_package",event?.packageName?.toString().orEmpty()).apply(); persistSnapshot() }'''
    event_new = '''    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString().orEmpty()
        getSharedPreferences("jarvis_tv",MODE_PRIVATE).edit().putString("foreground_package",pkg).apply()
        persistSnapshot()
        cacheStreamingContent(pkg)
    }

    private fun streamingProvider(pkg: String): String? = when {
        pkg.contains("netflix", true) -> "Netflix"
        pkg.contains("amazon.avod", true) -> "Prime Video"
        pkg.contains("apple.atve", true) -> "Apple TV+"
        pkg.contains("wbd.stream", true) || pkg.contains("hbo", true) -> "Max"
        pkg.contains("disney", true) -> "Disney+"
        pkg.contains("youtube", true) -> "YouTube"
        pkg.contains("yomvi", true) -> "Movistar Plus+"
        else -> null
    }

    private fun cacheStreamingContent(pkg: String) {
        val provider = streamingProvider(pkg) ?: return
        val blocked = listOf("inicio","buscar","search","configuración","settings","perfil","profiles","salir","atrás","home","play","pause","netflix","prime video","apple tv","max","disney+","youtube")
        val titles = nodes().map { label(it).trim() }
            .filter { it.length in 3..90 && blocked.none { b -> it.equals(b, true) } }
            .filterNot { it.matches(Regex("^[0-9:.,%+ -]+$")) }
            .distinct().take(24)
        if (titles.isEmpty()) return
        val key = "streaming_cache_" + provider.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
        getSharedPreferences("jarvis", MODE_PRIVATE).edit().putString(key, JSONArray(titles).toString()).apply()
    }'''
    if event_old not in t:
        raise SystemExit('TV accessibility event anchor not found')
    t = t.replace(event_old, event_new, 1)
    a.write_text(t)

# ---------- Manifest: package visibility for TV launchers ----------
m = Path('app/src/main/AndroidManifest.xml')
ms = m.read_text()
if '<queries>' not in ms:
    q = '''
    <queries>
        <intent>
            <action android:name="android.intent.action.MAIN" />
            <category android:name="android.intent.category.LEANBACK_LAUNCHER" />
        </intent>
        <package android:name="com.netflix.ninja" />
        <package android:name="com.amazon.avod.thirdpartyclient" />
        <package android:name="com.apple.atve.androidtv.appletv" />
        <package android:name="com.wbd.stream" />
        <package android:name="com.disney.disneyplus" />
        <package android:name="com.google.android.youtube.tv" />
        <package android:name="com.spotify.tv.android" />
    </queries>

'''
    ms = ms.replace('    <application\n', q + '    <application\n', 1)
    m.write_text(ms)

print('TV streaming app launcher, personalized cache, accessibility agent routing and focus elevation applied')
