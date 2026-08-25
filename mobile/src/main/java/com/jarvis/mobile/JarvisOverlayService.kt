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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> hide()
            ACTION_COMMAND -> handleCommand(intent.getStringExtra(EXTRA_COMMAND).orEmpty())
            else -> show("", intent?.getStringExtra(EXTRA_TEXT).orEmpty().ifBlank { "Jarvis escuchando…" })
        }
        return START_NOT_STICKY
    }

    private fun handleCommand(command: String) {
        if (command.isBlank()) { show("", "Te escucho…"); return }
        show(command, "Pensando…")
        Thread {
            val reply = runCatching {
                val c = (URL("$backend/api/chat").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 10000
                    readTimeout = 60000
                    setRequestProperty("Content-Type", "application/json")
                }
                val prefs = getSharedPreferences("jarvis_mobile", MODE_PRIVATE)
                val body = JSONObject()
                    .put("message", command)
                    .put("client", "jarvis-overlay")
                    .put("preferredProvider", prefs.getString("ai_provider", "auto") ?: "auto")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
                val raw = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
                runCatching { JSONObject(raw).optString("reply") }.getOrDefault(raw).ifBlank { "He terminado." }
            }.getOrElse { "No he podido completar la acción: ${it.message ?: "error"}" }
            main.post {
                show(command, reply)
                runCatching {
                    startService(Intent(this, MobileSpeechService::class.java).apply {
                        putExtra("text", reply)
                        putExtra("voice", getSharedPreferences("jarvis_mobile", MODE_PRIVATE).getString("voice", "coral"))
                    })
                }
            }
        }.start()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun show(userText: String, assistantText: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            stopSelf(); return
        }
        hide()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                setColor(Color.rgb(246, 247, 251))
                cornerRadius = dp(30).toFloat()
                setStroke(dp(1), Color.rgb(208, 211, 222))
            }
        }
        outer.addView(TextView(this).apply {
            text = "Jarvis"
            textSize = 18f
            setTextColor(Color.rgb(28, 31, 40))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        if (userText.isNotBlank()) outer.addView(TextView(this).apply {
            text = "Tú · $userText"
            textSize = 14f
            setTextColor(Color.rgb(74, 78, 91))
            setPadding(0, dp(8), 0, 0)
        })
        outer.addView(TextView(this).apply {
            text = assistantText
            textSize = 17f
            setTextColor(Color.rgb(27, 30, 38))
            setPadding(0, dp(9), 0, 0)
            setLineSpacing(0f, 1.08f)
        })
        outer.addView(TextView(this).apply {
            text = "Toca para abrir Jarvis   ·   Mantén pulsado para cerrar"
            textSize = 11f
            setTextColor(Color.rgb(105, 109, 122))
            setPadding(0, dp(12), 0, 0)
        })
        outer.setOnClickListener {
            val open = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("hands_free", true)
                putExtra("wake_word_triggered", true)
                if (userText.isNotBlank()) putExtra("overlay_command", userText)
            }
            runCatching { startActivity(open) }
        }
        outer.setOnLongClickListener { hide(); true }
        root = outer

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(
            dp(360),
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(70)
        }
        runCatching { wm?.addView(outer, lp) }.onFailure { hide() }
    }

    private fun hide() {
        root?.let { runCatching { wm?.removeView(it) } }
        root = null
    }

    override fun onDestroy() {
        hide()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_SHOW = "com.jarvis.mobile.overlay.SHOW"
        const val ACTION_HIDE = "com.jarvis.mobile.overlay.HIDE"
        const val ACTION_COMMAND = "com.jarvis.mobile.overlay.COMMAND"
        const val EXTRA_TEXT = "text"
        const val EXTRA_COMMAND = "command"
    }
}
