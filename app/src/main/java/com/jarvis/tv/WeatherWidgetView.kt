package com.jarvis.tv

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class WeatherWidgetView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isFocusable = true
        setOnClickListener { refresh() }
        refresh()
    }

    private fun refresh() {
        text = "TIEMPO\n\n⏳ Actualizando…"
        Thread {
            try {
                val c = (URL("https://chatgpt-tv2.vercel.app/api/weather").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 6000
                    readTimeout = 8000
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("User-Agent", "JarvisTV/0.6")
                }
                if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
                val json = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                val icon = weatherIcon(json.optInt("code", 2))
                val temp = json.optDouble("temperature").toInt()
                val feels = json.optDouble("feelsLike").toInt()
                val place = json.optString("place", "Ubicación actual")
                val days = json.optJSONArray("days")
                val forecast = mutableListOf<String>()
                if (days != null) {
                    for (i in 0 until minOf(3, days.length())) {
                        val d = days.optJSONObject(i) ?: continue
                        forecast += "${weatherIcon(d.optInt("code"))} ${d.optDouble("min").toInt()}°/${d.optDouble("max").toInt()}°"
                    }
                }
                post {
                    text = "TIEMPO  $icon\n\n$temp°  ·  $place\nSensación $feels°\n\n${forecast.joinToString("   ")}\n\nPulsa para actualizar"
                }
            } catch (_: Exception) {
                post { text = "TIEMPO  🌤️\n\nNo disponible ahora\n\nPulsa para reintentar" }
            }
        }.start()
    }

    private fun weatherIcon(code: Int): String = when (code) {
        0 -> "☀️"
        1, 2 -> "🌤️"
        3 -> "☁️"
        45, 48 -> "🌫️"
        in 51..67 -> "🌧️"
        in 71..77 -> "🌨️"
        in 80..82 -> "🌦️"
        in 95..99 -> "⛈️"
        else -> "🌤️"
    }
}
