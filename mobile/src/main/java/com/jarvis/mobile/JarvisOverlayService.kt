package com.jarvis.mobile

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class JarvisOverlayService : Service() {
    private var wm: WindowManager? = null
    private var root: View? = null
    private val main = Handler(Looper.getMainLooper())
    private val backend = "https://chatgpt-tv2.vercel.app"
    private var lastDomoticsDevice: JSONObject? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> hide()
            ACTION_COMMAND -> handleCommand(intent.getStringExtra(EXTRA_COMMAND).orEmpty())
            else -> showText("", intent?.getStringExtra(EXTRA_TEXT).orEmpty().ifBlank { "Jarvis escuchando…" })
        }
        return START_NOT_STICKY
    }

    private fun isDomotics(command: String): Boolean {
        val s = command.lowercase()
        return listOf("aire acondicionado","aire ","sensibo","termostato","temperatura","climatización","climatizacion").any { s.contains(it) }
    }

    private fun handleCommand(command: String) {
        if (command.isBlank()) { showText("", "Te escucho…"); return }
        if (isDomotics(command)) {
            showText(command, "Consultando el dispositivo…")
            Thread { handleDomotics(command) }.start()
            return
        }
        showText(command, "Pensando…")
        Thread {
            val reply = runCatching { chat(command) }.getOrElse { "No he podido completar la acción: ${it.message ?: "error"}" }
            main.post { showText(command, reply); speak(reply) }
        }.start()
    }

    private fun chat(command: String): String {
        val c = (URL("$backend/api/chat").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 8000; readTimeout = 45000
            setRequestProperty("Content-Type", "application/json")
        }
        val prefs = getSharedPreferences("jarvis_mobile", MODE_PRIVATE)
        val body = JSONObject().put("message", command).put("client", "jarvis-overlay")
            .put("preferredProvider", prefs.getString("ai_provider", "auto") ?: "auto")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val raw = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
        return runCatching { JSONObject(raw).optString("reply") }.getOrDefault(raw).ifBlank { "He terminado." }
    }

    private fun httpJson(url: String, method: String = "GET", body: JSONObject? = null): JSONObject {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method; connectTimeout = 4500; readTimeout = 10000
            setRequestProperty("Accept", "application/json")
            if (body != null) { doOutput = true; setRequestProperty("Content-Type", "application/json") }
        }
        if (body != null) c.outputStream.use { it.write(body.toString().toByteArray()) }
        val raw = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (c.responseCode !in 200..299) throw IllegalStateException(runCatching { JSONObject(raw).optString("error") }.getOrDefault("HTTP ${c.responseCode}"))
        return JSONObject(raw)
    }

    private fun firstSensibo(): JSONObject {
        val j = httpJson("$backend/api/domotics/sensibo")
        val a = j.optJSONArray("devices") ?: throw IllegalStateException("Sensibo no devolvió dispositivos")
        if (a.length() == 0) throw IllegalStateException("No hay dispositivos Sensibo conectados")
        return a.optJSONObject(0) ?: throw IllegalStateException("Dispositivo Sensibo no válido")
    }

    private fun handleDomotics(command: String) {
        val result = runCatching {
            var d = firstSensibo()
            val id = d.optString("id")
            val s = command.lowercase()
            val temp = Regex("(?:a|pon(?:lo)? a|temperatura(?: a)?)\\s*(1[6-9]|2[0-9]|3[0])(?:[,.]\\d+)?\\s*(?:grados|°)?").find(s)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
            when {
                s.contains("enciende") || s.contains("encender") -> httpJson("$backend/api/domotics/sensibo", "POST", JSONObject().put("id", id).put("action", "power").put("on", true))
                s.contains("apaga") || s.contains("apagar") -> httpJson("$backend/api/domotics/sensibo", "POST", JSONObject().put("id", id).put("action", "power").put("on", false))
            }
            if (temp != null) httpJson("$backend/api/domotics/sensibo", "POST", JSONObject().put("id", id).put("action", "temperature").put("value", temp))
            d = httpJson("$backend/api/domotics/sensibo?id=${java.net.URLEncoder.encode(id, "UTF-8")}").optJSONObject("device") ?: d
            d
        }
        main.post {
            result.onSuccess { d -> lastDomoticsDevice = d; showDomoticsCard(command, d); speak(deviceSpokenSummary(d)) }
                .onFailure { showText(command, "No he podido consultar el dispositivo: ${it.message ?: "error"}") }
        }
    }

    private fun deviceSpokenSummary(d: JSONObject): String {
        val st = d.optJSONObject("state") ?: JSONObject(); val m = d.optJSONObject("measurements") ?: JSONObject()
        val on = if (st.optBoolean("on", false)) "encendido" else "apagado"
        val room = if (m.has("temperature") && !m.isNull("temperature")) ", habitación a ${m.optDouble("temperature")} grados" else ""
        val target = if (st.has("targetTemperature") && !st.isNull("targetTemperature")) ", objetivo ${st.optDouble("targetTemperature")} grados" else ""
        return "${d.optString("name").ifBlank { "Aire acondicionado" }} $on$room$target."
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun panelBg(): GradientDrawable = GradientDrawable().apply { setColor(Color.rgb(246,247,251)); cornerRadius=dp(30).toFloat(); setStroke(dp(1),Color.rgb(208,211,222)) }

    private fun addHeader(outer: LinearLayout, userText: String) {
        outer.addView(TextView(this).apply { text="Jarvis"; textSize=18f; setTextColor(Color.rgb(28,31,40)); setTypeface(typeface, android.graphics.Typeface.BOLD) })
        if (userText.isNotBlank()) outer.addView(TextView(this).apply { text="Tú · $userText"; textSize=13f; setTextColor(Color.rgb(74,78,91)); setPadding(0,dp(6),0,0) })
    }

    private fun attachOverlay(outer: LinearLayout, userText: String) {
        outer.addView(TextView(this).apply { text="Toca para abrir Jarvis   ·   Mantén pulsado para cerrar"; textSize=11f; setTextColor(Color.rgb(105,109,122)); setPadding(0,dp(11),0,0) })
        outer.setOnClickListener {
            runCatching { startActivity(Intent(this, MainActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP); putExtra("hands_free", true); if(userText.isNotBlank()) putExtra("overlay_command", userText) }) }
        }
        outer.setOnLongClickListener { hide(); true }
        root=outer
        val type=if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val lp=WindowManager.LayoutParams(dp(370),WindowManager.LayoutParams.WRAP_CONTENT,type,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT).apply { gravity=Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y=dp(70) }
        runCatching { wm?.addView(outer,lp) }.onFailure { hide() }
    }

    private fun baseOuter(): LinearLayout {
        hide(); wm=getSystemService(WINDOW_SERVICE) as WindowManager
        return LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(16),dp(14),dp(16),dp(14)); background=panelBg() }
    }

    private fun showText(userText: String, assistantText: String) {
        if (Build.VERSION.SDK_INT>=Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) { stopSelf(); return }
        val outer=baseOuter(); addHeader(outer,userText)
        outer.addView(TextView(this).apply { text=assistantText; textSize=17f; setTextColor(Color.rgb(27,30,38)); setPadding(0,dp(9),0,0); setLineSpacing(0f,1.08f) })
        attachOverlay(outer,userText)
    }

    private fun showDomoticsCard(userText: String, d: JSONObject) {
        if (Build.VERSION.SDK_INT>=Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) return
        val outer=baseOuter(); addHeader(outer,userText)
        val st=d.optJSONObject("state") ?: JSONObject(); val m=d.optJSONObject("measurements") ?: JSONObject(); val on=st.optBoolean("on",false)
        outer.addView(TextView(this).apply { text="❄  ${d.optString("name").ifBlank { "Aire acondicionado" }}"; textSize=18f; setTextColor(Color.rgb(20,29,38)); setTypeface(typeface,android.graphics.Typeface.BOLD); setPadding(0,dp(10),0,0) })
        outer.addView(TextView(this).apply {
            val room=if(m.has("temperature")&&!m.isNull("temperature")) "Habitación ${m.optDouble("temperature")} °C" else "Temperatura ambiente —"
            val target=if(st.has("targetTemperature")&&!st.isNull("targetTemperature")) "Objetivo ${st.optDouble("targetTemperature")} °C" else "Objetivo —"
            text="${if(on) "● Encendido" else "○ Apagado"}   ·   $room\n$target   ·   ${st.optString("mode").ifBlank { "modo —" }}"; textSize=15f; setTextColor(Color.rgb(40,48,58)); setPadding(0,dp(8),0,dp(6))
        })
        val controls=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
        fun action(label:String, block:()->Unit)=controls.addView(Button(this).apply { text=label; setOnClickListener { block() } }, LinearLayout.LayoutParams(0,dp(48),1f))
        val id=d.optString("id")
        action(if(on) "Apagar" else "Encender") { Thread { runCatching { httpJson("$backend/api/domotics/sensibo","POST",JSONObject().put("id",id).put("action","power").put("on",!on)); handleDomotics("estado aire acondicionado") } }.start() }
        action("− 1°") { Thread { val t=st.optDouble("targetTemperature",22.0)-1; runCatching { httpJson("$backend/api/domotics/sensibo","POST",JSONObject().put("id",id).put("action","temperature").put("value",t)); handleDomotics("estado aire acondicionado") } }.start() }
        action("+ 1°") { Thread { val t=st.optDouble("targetTemperature",22.0)+1; runCatching { httpJson("$backend/api/domotics/sensibo","POST",JSONObject().put("id",id).put("action","temperature").put("value",t)); handleDomotics("estado aire acondicionado") } }.start() }
        outer.addView(controls); attachOverlay(outer,userText)
    }

    private fun speak(reply:String) { runCatching { startService(Intent(this,MobileSpeechService::class.java).putExtra("text",reply).putExtra("voice",getSharedPreferences("jarvis_mobile",MODE_PRIVATE).getString("voice","coral"))) } }
    private fun hide() { root?.let { runCatching { wm?.removeView(it) } }; root=null }
    override fun onDestroy(){ hide(); super.onDestroy() }
    override fun onBind(intent:Intent?):IBinder?=null

    companion object {
        const val ACTION_SHOW="com.jarvis.mobile.overlay.SHOW"; const val ACTION_HIDE="com.jarvis.mobile.overlay.HIDE"; const val ACTION_COMMAND="com.jarvis.mobile.overlay.COMMAND"
        const val EXTRA_TEXT="text"; const val EXTRA_COMMAND="command"
    }
}
