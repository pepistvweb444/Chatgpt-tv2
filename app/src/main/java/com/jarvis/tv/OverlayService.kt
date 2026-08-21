package com.jarvis.tv

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat

class OverlayService : Service() {
    private var windowManager: WindowManager? = null
    private var bubble: TextView? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.app_icon)
            .setContentTitle("Jarvis TV activo")
            .setContentText("Burbuja del asistente disponible")
            .setOngoing(true)
            .setContentIntent(openIntent)
            .build()
        startForeground(NOTIFICATION_ID, notification)
        showBubble()
    }

    private fun showBubble() {
        if (!Settings.canDrawOverlays(this)) return
        if (bubble != null) return

        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm
        val view = TextView(this).apply {
            text = "AI"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xCC151922.toInt())
            setPadding(28, 20, 28, 20)
            isFocusable = true
            isClickable = true
            contentDescription = "Abrir Jarvis TV"
            setOnClickListener {
                startActivity(
                    Intent(this@OverlayService, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
            }
        }
        bubble = view

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = 42
            y = 42
        }

        try {
            wm.addView(view, params)
        } catch (_: Exception) {
            bubble = null
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (bubble == null) showBubble()
        return START_STICKY
    }

    override fun onDestroy() {
        bubble?.let { view ->
            try { windowManager?.removeView(view) } catch (_: Exception) {}
        }
        bubble = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Jarvis TV assistant",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "jarvis_overlay"
        private const val NOTIFICATION_ID = 303
    }
}
