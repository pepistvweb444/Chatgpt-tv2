from pathlib import Path

p = Path('mobile/src/main/java/com/jarvis/mobile/MainActivity.kt')
s = p.read_text()

old = '''    private fun renderReplyAsWidgets(kind: String, reply: String) {
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
'''
new = '''    private fun renderReplyAsWidgets(kind: String, reply: String) {
        if (kind == "weather") {
            beginWidgetGroup("Tiempo · tu ubicación")
            addTextWidget("weather", "Tiempo", reply.replace(Regex("[*_`#>|]+"), " ").trim())
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
            return
        }
        val groupTitle = when (kind) { "home" -> "Domótica"; "day" -> "Resumen del día"; else -> "Información" }
        beginWidgetGroup(groupTitle)
        val items = splitWidgetItems(reply)
        if (items.isEmpty()) addTextWidget(kind, groupTitle, "No hay datos disponibles")
        else items.forEachIndexed { i, item ->
            val title = when (kind) { "home" -> "Estado ${i + 1}"; "day" -> "${i + 1} · Hoy"; else -> "${i + 1}" }
            addTextWidget(kind, title, item)
        }
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }
'''
if old not in s:
    raise SystemExit('renderReplyAsWidgets block not found')
s = s.replace(old, new)

old2 = '''    private fun openWeatherForCurrentLocation(original: String? = null) {
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
'''
new2 = '''    private fun weatherIcon(code: Int): String = when (code) {
        0 -> "☀️"
        1, 2 -> "🌤️"
        3 -> "☁️"
        45, 48 -> "🌫️"
        in 51..57 -> "🌦️"
        in 61..67 -> "🌧️"
        in 71..77 -> "🌨️"
        in 80..82 -> "🌦️"
        in 85..86 -> "🌨️"
        in 95..99 -> "⛈️"
        else -> "🌤️"
    }

    private fun weatherLabel(code: Int): String = when (code) {
        0 -> "Despejado"
        1 -> "Mayormente despejado"
        2 -> "Parcialmente nublado"
        3 -> "Nublado"
        45, 48 -> "Niebla"
        in 51..57 -> "Llovizna"
        in 61..67 -> "Lluvia"
        in 71..77 -> "Nieve"
        in 80..82 -> "Chubascos"
        in 85..86 -> "Chubascos de nieve"
        in 95..99 -> "Tormenta"
        else -> "Variable"
    }

    private fun renderWeatherWidget(data: JSONObject) {
        widgetHost.removeAllViews()
        widgetHost.visibility = View.VISIBLE
        welcomePanel.visibility = View.GONE

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) }
        }
        row.addView(makeAvatar("assistant"))

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(Color.rgb(22, 67, 108))
                setStroke(dp(1), Color.rgb(59, 104, 148))
            }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val place = data.optString("place").ifBlank { "Ubicación actual" }
        val temperature = data.optDouble("temperature", Double.NaN)
        val feelsLike = data.optDouble("feelsLike", Double.NaN)
        val humidity = data.optInt("humidity", -1)
        val wind = data.optDouble("wind", Double.NaN)
        val code = data.optInt("code", -1)
        val days = data.optJSONArray("days") ?: JSONArray()
        val today = days.optJSONObject(0)
        val todayDate = today?.optString("date").orEmpty()

        card.addView(TextView(this).apply {
            text = place
            textSize = 13f
            setTextColor(Color.rgb(220, 232, 246))
            gravity = Gravity.CENTER
        })

        val main = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(6))
        }
        main.addView(TextView(this).apply {
            text = weatherIcon(code)
            textSize = 54f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(88), dp(88))
        })
        val tempCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tempCol.addView(TextView(this).apply {
            text = if (temperature.isNaN()) "—°" else "${temperature.toInt()}°C"
            textSize = 40f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        tempCol.addView(TextView(this).apply {
            text = weatherLabel(code)
            textSize = 15f
            setTextColor(Color.WHITE)
        })
        if (!feelsLike.isNaN()) tempCol.addView(TextView(this).apply {
            text = "Sensación ${feelsLike.toInt()}°C"
            textSize = 12f
            setTextColor(Color.rgb(205, 220, 238))
            setPadding(0, dp(3), 0, 0)
        })
        main.addView(tempCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(main)

        val metrics = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val max = today?.optDouble("max", Double.NaN) ?: Double.NaN
        val min = today?.optDouble("min", Double.NaN) ?: Double.NaN
        val rain = today?.optInt("rain", -1) ?: -1
        listOf(
            if (max.isNaN()) "↑ —" else "↑ ${max.toInt()}°C",
            if (min.isNaN()) "↓ —" else "↓ ${min.toInt()}°C",
            if (humidity < 0) "💧 —" else "💧 ${humidity}%",
            if (wind.isNaN()) "↝ —" else "↝ ${wind.toInt()} km/h"
        ).forEach { value ->
            metrics.addView(TextView(this).apply {
                text = value
                textSize = 12f
                setTextColor(Color.rgb(231, 239, 249))
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(0, dp(38), 1f))
        }
        card.addView(metrics)

        card.addView(View(this).apply {
            setBackgroundColor(Color.argb(80, 255, 255, 255))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(6); bottomMargin = dp(10) }
        })

        card.addView(TextView(this).apply {
            text = if (todayDate.isBlank()) "Hoy" else "Hoy · $todayDate"
            textSize = 13f
            setTextColor(Color.rgb(224, 233, 246))
            setPadding(0, 0, 0, dp(8))
        })

        val forecast = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        for (i in 0 until minOf(4, days.length())) {
            val d = days.optJSONObject(i) ?: continue
            val dMax = d.optDouble("max", Double.NaN)
            val dMin = d.optDouble("min", Double.NaN)
            val dCode = d.optInt("code", -1)
            val label = when (i) { 0 -> "Hoy"; 1 -> "Mañana"; else -> d.optString("date").takeLast(5) }
            forecast.addView(TextView(this).apply {
                text = "$label\n${weatherIcon(dCode)}\n${if (dMax.isNaN()) "—" else dMax.toInt()}° / ${if (dMin.isNaN()) "—" else dMin.toInt()}°"
                textSize = 12f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setLineSpacing(0f, 1.12f)
            }, LinearLayout.LayoutParams(0, dp(74), 1f))
        }
        card.addView(forecast)

        if (rain >= 0) card.addView(TextView(this).apply {
            text = "Probabilidad máxima de precipitación hoy: ${rain}%"
            textSize = 12f
            setTextColor(Color.rgb(202, 220, 240))
            setPadding(0, dp(8), 0, 0)
        })

        row.addView(card)
        widgetHost.addView(row)
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun openWeatherForCurrentLocation(original: String? = null) {
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
        beginWidgetGroup("Tiempo · tu ubicación")
        addTextWidget("weather", "Actualizando", "Consultando el tiempo en tu ubicación actual…")
        status.text = "Actualizando tiempo…"
        Thread {
            try {
                val data = readJson("$BACKEND/api/weather?lat=${loc.latitude}&lon=${loc.longitude}")
                runOnUiThread {
                    renderWeatherWidget(data)
                    status.text = "Tiempo actualizado"
                    val temp = data.optDouble("temperature", Double.NaN)
                    val code = data.optInt("code", -1)
                    if (!temp.isNaN()) safeSpeak("Ahora hay ${temp.toInt()} grados. ${weatherLabel(code)}.")
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    beginWidgetGroup("Tiempo · tu ubicación")
                    addTextWidget("weather", "Error", e.message ?: "No se pudo cargar el tiempo")
                    status.text = "Error al cargar el tiempo"
                }
            }
        }.start()
    }
'''
if old2 not in s:
    raise SystemExit('openWeatherForCurrentLocation block not found')
s = s.replace(old2, new2)
p.write_text(s)
print('Weather widget patch applied')
