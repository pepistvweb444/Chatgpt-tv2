from pathlib import Path

# Persist the phone location so the overlay/wake-word assistant uses the same Jarvis location.
p = Path('mobile/src/main/java/com/jarvis/mobile/MainActivity.kt')
s = p.read_text()
s = s.replace(
'''    private fun warmLocation() { if (hasLocationPermission()) lastLocation = bestLastKnownLocation() }''',
'''    private fun warmLocation() {
        if (hasLocationPermission()) bestLastKnownLocation()?.let { loc ->
            lastLocation = loc
            persistJarvisLocation(loc)
        }
    }
    private fun persistJarvisLocation(loc: Location) {
        prefs.edit()
            .putLong("jarvis_location_lat_bits", java.lang.Double.doubleToRawLongBits(loc.latitude))
            .putLong("jarvis_location_lon_bits", java.lang.Double.doubleToRawLongBits(loc.longitude))
            .putFloat("jarvis_location_accuracy", loc.accuracy)
            .putLong("jarvis_location_time", loc.time.takeIf { it > 0 } ?: System.currentTimeMillis())
            .apply()
    }''')
s = s.replace('''        lastLocation = loc
        val prefix = original ?: "Dime el tiempo actual y la previsión de hoy"''', '''        lastLocation = loc
        persistJarvisLocation(loc)
        val prefix = original ?: "Dime el tiempo actual y la previsión de hoy"''')
p.write_text(s)

# Overlay: add weather/news detection and direct rich widgets using the same saved location.
p = Path('mobile/src/main/java/com/jarvis/mobile/JarvisOverlayService.kt')
s = p.read_text()

old_handle = '''        if (isDomotics(command)) {
            showText(command, "Consultando el dispositivo…")
            Thread { handleDomotics(command) }.start()
            return
        }
        showText(command, "Pensando…")'''
new_handle = '''        if (isWeather(command)) {
            showText(command, "Consultando el tiempo en tu ubicación…")
            Thread { handleWeather(command) }.start()
            return
        }
        if (isNews(command)) {
            showText(command, "Actualizando noticias…")
            Thread { handleNews(command) }.start()
            return
        }
        if (isDomotics(command)) {
            showText(command, "Consultando el dispositivo…")
            Thread { handleDomotics(command) }.start()
            return
        }
        showText(command, "Pensando…")'''
if old_handle not in s:
    raise SystemExit('overlay handleCommand anchor not found')
s = s.replace(old_handle, new_handle, 1)

marker = '''    private fun handleDomotics(command: String) {'''
methods = r'''    private fun isWeather(command: String): Boolean {
        val s = command.lowercase()
        return listOf("tiempo", "clima", "temperatura exterior", "va a llover", "llueve", "previsión", "prevision", "meteorolog").any { s.contains(it) }
    }

    private fun isNews(command: String): Boolean {
        val s = command.lowercase()
        return listOf("noticias", "titulares", "actualidad", "qué ha pasado", "que ha pasado").any { s.contains(it) }
    }

    private fun savedLocation(): Pair<Double,Double>? {
        val prefs = getSharedPreferences("jarvis_mobile", MODE_PRIVATE)
        if (!prefs.contains("jarvis_location_lat_bits") || !prefs.contains("jarvis_location_lon_bits")) return null
        val lat = java.lang.Double.longBitsToDouble(prefs.getLong("jarvis_location_lat_bits", 0L))
        val lon = java.lang.Double.longBitsToDouble(prefs.getLong("jarvis_location_lon_bits", 0L))
        return if (lat in -90.0..90.0 && lon in -180.0..180.0 && !(lat == 0.0 && lon == 0.0)) lat to lon else null
    }

    private fun handleWeather(command: String) {
        val result = runCatching {
            val loc = savedLocation() ?: throw IllegalStateException("Abre Jarvis una vez con permiso de ubicación para guardar tu ubicación")
            httpJson("$backend/api/weather?lat=${loc.first}&lon=${loc.second}")
        }
        main.post {
            result.onSuccess { j -> showWeatherCard(command, j); speak(weatherSpokenSummary(j)) }
                .onFailure { showText(command, "No he podido obtener el tiempo: ${it.message ?: "error"}") }
        }
    }

    private fun weatherSpokenSummary(j: JSONObject): String {
        val place = j.optString("place").ifBlank { "tu ubicación" }
        val t = if (j.has("temperature")) "${j.optDouble("temperature")} grados" else "temperatura no disponible"
        val feels = if (j.has("feelsLike")) ", sensación de ${j.optDouble("feelsLike")} grados" else ""
        return "En $place hay $t$feels."
    }

    private fun weatherIcon(code: Int): String = when (code) {
        0 -> "☀"
        1,2 -> "🌤"
        3 -> "☁"
        45,48 -> "🌫"
        in 51..67, in 80..82 -> "🌧"
        in 71..77, 85, 86 -> "❄"
        in 95..99 -> "⛈"
        else -> "🌤"
    }

    private fun showWeatherCard(userText: String, j: JSONObject) {
        if (Build.VERSION.SDK_INT>=Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) return
        val outer=baseOuter(); addHeader(outer,userText)
        outer.addView(TextView(this).apply {
            text="${weatherIcon(j.optInt("code",0))}  Tiempo · ${j.optString("place").ifBlank { "tu ubicación" }}"
            textSize=18f; setTextColor(Color.rgb(20,29,38)); setTypeface(typeface,android.graphics.Typeface.BOLD); setPadding(0,dp(10),0,0)
        })
        outer.addView(TextView(this).apply {
            val t=if(j.has("temperature")) "${j.optDouble("temperature")} °C" else "—"
            val feels=if(j.has("feelsLike")) "Sensación ${j.optDouble("feelsLike")} °C" else ""
            val humidity=if(j.has("humidity")) "Humedad ${j.optInt("humidity")}%" else ""
            val wind=if(j.has("wind")) "Viento ${j.optDouble("wind")} km/h" else ""
            text=listOf(t,feels,humidity,wind).filter{it.isNotBlank()}.joinToString("   ·   ")
            textSize=16f; setTextColor(Color.rgb(40,48,58)); setPadding(0,dp(8),0,dp(5))
        })
        val days=j.optJSONArray("days") ?: org.json.JSONArray()
        for(i in 0 until minOf(3,days.length())) {
            val d=days.optJSONObject(i) ?: continue
            outer.addView(TextView(this).apply {
                text="${weatherIcon(d.optInt("code",0))} ${d.optString("date")}   ${d.optDouble("min")}° / ${d.optDouble("max")}°   · lluvia ${d.optInt("rain",0)}%"
                textSize=13f; setTextColor(Color.rgb(65,72,84)); setPadding(0,dp(3),0,dp(3))
            })
        }
        attachOverlay(outer,userText)
    }

    private fun handleNews(command: String) {
        val result = runCatching { httpJson("$backend/api/news?fast=1&country=ES&lang=es") }
        main.post {
            result.onSuccess { j -> showNewsCards(command,j); speak(newsSpokenSummary(j)) }
                .onFailure { showText(command,"No he podido cargar las noticias: ${it.message ?: "error"}") }
        }
    }

    private fun newsSpokenSummary(j: JSONObject): String {
        val a=j.optJSONArray("items") ?: org.json.JSONArray()
        if(a.length()==0) return "No encuentro titulares disponibles ahora mismo."
        val titles=(0 until minOf(3,a.length())).mapNotNull{a.optJSONObject(it)?.optString("title")?.takeIf(String::isNotBlank)}
        return "Estos son los titulares. " + titles.joinToString(". ")
    }

    private fun showNewsCards(userText: String, j: JSONObject) {
        if (Build.VERSION.SDK_INT>=Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) return
        val outer=baseOuter(); addHeader(outer,userText)
        outer.addView(TextView(this).apply { text="▣  Noticias · ahora"; textSize=18f; setTextColor(Color.rgb(20,29,38)); setTypeface(typeface,android.graphics.Typeface.BOLD); setPadding(0,dp(10),0,dp(5)) })
        val a=j.optJSONArray("items") ?: org.json.JSONArray()
        if(a.length()==0) outer.addView(TextView(this).apply { text="No hay titulares disponibles"; textSize=15f; setTextColor(Color.DKGRAY) })
        for(i in 0 until minOf(4,a.length())) {
            val item=a.optJSONObject(i) ?: continue
            val url=item.optString("url")
            outer.addView(TextView(this).apply {
                text="${item.optString("title").ifBlank { "Titular" }}\n${item.optString("source")}"; textSize=14f; setTextColor(Color.rgb(35,43,56)); setPadding(dp(10),dp(8),dp(10),dp(8)); background=GradientDrawable().apply{setColor(Color.rgb(235,238,246));cornerRadius=dp(14).toFloat()}
                layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT).apply{bottomMargin=dp(6)}
                if(url.isNotBlank()) setOnClickListener { runCatching { startActivity(Intent(Intent.ACTION_VIEW,android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            })
        }
        attachOverlay(outer,userText)
    }

'''
if marker not in s:
    raise SystemExit('overlay domotics marker not found')
s = s.replace(marker, methods + marker, 1)

# Allow the overlay to be presented over keyguard. This does not unlock the device.
s = s.replace(
'''WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN''',
'''WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON''')
p.write_text(s)

# Wake word: briefly wake the display when the explicit wake phrase is heard while locked.
p = Path('mobile/src/main/java/com/jarvis/mobile/WakeWordService.kt')
s = p.read_text()
if 'private fun wakeForJarvis()' not in s:
    marker = '''    private fun showOverlay(text: String) {'''
    method = r'''    @Suppress("DEPRECATION")
    private fun wakeForJarvis() {
        val pm = getSystemService(android.os.PowerManager::class.java) ?: return
        if (pm.isInteractive) return
        runCatching {
            val wl = pm.newWakeLock(
                android.os.PowerManager.FULL_WAKE_LOCK or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP or android.os.PowerManager.ON_AFTER_RELEASE,
                "Jarvis:WakePhrase"
            )
            wl.acquire(8000L)
        }
    }

'''
    if marker not in s: raise SystemExit('wake overlay marker not found')
    s = s.replace(marker, method + marker, 1)
s = s.replace('''        if (isWakePhrase(raw)) {
            val inlineCommand = stripWakePhrase(raw)''', '''        if (isWakePhrase(raw)) {
            wakeForJarvis()
            val inlineCommand = stripWakePhrase(raw)''')
p.write_text(s)

# Manifest needs wake lock permission.
p = Path('mobile/src/main/AndroidManifest.xml')
s = p.read_text()
if 'android.permission.WAKE_LOCK' not in s:
    s = s.replace('    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />', '    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />\n    <uses-permission android:name="android.permission.WAKE_LOCK" />')
p.write_text(s)

print('Overlay saved-location weather/news widgets and lockscreen wake applied')
