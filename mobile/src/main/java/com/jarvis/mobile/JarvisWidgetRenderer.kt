package com.jarvis.mobile

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

object JarvisWidgetRenderer {
    fun render(activity: Activity, container: LinearLayout, query: String, answer: String) {
        val q = query.lowercase()
        val kind = when {
            q.contains("alarma") || q.contains("despiert") || q.contains("avisa") -> "⏰  ALARMA"
            q.contains("whatsapp") || q.contains("sms") || q.contains("mensaje") -> "💬  MENSAJES"
            q.contains("tiempo") || q.contains("temperatura") || q.contains("lluv") -> "🌤️  TIEMPO"
            q.contains("aire acondicionado") || q.contains("sensibo") || q.contains("tado") || q.contains("clima") -> "❄️  CLIMATIZACIÓN"
            q.contains("smartthings") || q.contains("hue") || q.contains("home connect") || q.contains("roborock") || q.contains("domot") -> "🏠  DOMÓTICA"
            q.contains("llama") || q.contains("llamar") || q.contains("telefono") -> "📞  TELÉFONO"
            else -> return
        }
        container.removeAllViews()
        container.visibility = LinearLayout.VISIBLE
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(34, 26, 34, 26)
            background = GradientDrawable().apply {
                setColor(Color.rgb(245, 247, 250)); cornerRadius = 28f
            }
        }
        val title = TextView(activity).apply { text = kind; textSize = 13f; setTextColor(Color.rgb(80,80,80)) }
        val body = TextView(activity).apply { text = answer; textSize = 18f; setTextColor(Color.rgb(25,25,25)); setPadding(0,8,0,0) }
        card.addView(title); card.addView(body)
        container.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0,8,0,14) })
    }
}
