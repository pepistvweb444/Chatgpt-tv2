package com.jarvis.mobile

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

class JarvisOverlayService : Service() {
    private var wm: WindowManager? = null
    private var root: View? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> hide()
            else -> show(intent?.getStringExtra(EXTRA_TEXT).orEmpty().ifBlank { "Jarvis escuchando…" })
        }
        return START_NOT_STICKY
    }

    private fun show(message: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            stopSelf(); return
        }
        hide()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        val bg = GradientDrawable().apply {
            setColor(Color.rgb(19, 24, 38))
            cornerRadius = 42f
            setStroke(2, Color.rgb(77, 66, 170))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(26, 20, 26, 20)
            background = bg
        }
        box.addView(TextView(this).apply {
            text = "Jarvis"
            setTextColor(Color.WHITE)
            textSize = 18f
        })
        box.addView(TextView(this).apply {
            text = message
            setTextColor(Color.LTGRAY)
            textSize = 14f
            setPadding(0, 6, 0, 0)
        })
        box.setOnClickListener {
            val open = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("hands_free", true)
                putExtra("wake_word_triggered", true)
            }
            runCatching { startActivity(open) }
        }
        box.setOnLongClickListener { hide(); true }
        root = box

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 24
            y = 140
        }
        runCatching { wm?.addView(box, lp) }.onFailure { hide() }
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
        const val EXTRA_TEXT = "text"
    }
}
