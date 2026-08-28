package com.jarvis.mobile

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.TelecomManager
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import org.json.JSONObject

object IncomingCallUi {
    const val CHANNEL = "jarvis_incoming_calls"
    const val NOTIFICATION_ID = 9401
    const val EXTRA_CALL = "jarvis_call_json"

    fun publish(context: Context, data: JSONObject) {
        context.getSharedPreferences("jarvis_mobile", Context.MODE_PRIVATE).edit()
            .putString("last_incoming_call_json", data.toString())
            .apply()

        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Llamadas entrantes Jarvis", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Cribado de llamadas y llamadas de aplicaciones"
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            })
        }

        val open = Intent(context, IncomingCallActivity::class.java).putExtra(EXTRA_CALL, data.toString())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val full = PendingIntent.getActivity(context, 9401, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun actionPi(action: String, code: Int): PendingIntent = PendingIntent.getBroadcast(
            context, code,
            Intent(context, CallActionReceiver::class.java)
                .putExtra("call_action", action)
                .putExtra("call_source", data.optString("source", "phone"))
                .putExtra("call_key", data.optString("notificationKey")),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val name = data.optString("name").ifBlank { data.optString("publicLabel") }.ifBlank { data.optString("number") }.ifBlank { "Llamada entrante" }
        val spam = data.optString("classification")
        val subtitle = when (spam) {
            "spam_probable" -> "Spam probable · ${data.optInt("spamScore", 0)}%"
            "possible_spam", "suspicious" -> "Posible spam"
            else -> data.optString("app").ifBlank { if (data.optBoolean("contactKnown")) "Contacto" else "Número desconocido" }
        }
        val notif = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle(name)
            .setContentText(subtitle)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(full)
            .setFullScreenIntent(full, true)
            .addAction(android.R.drawable.sym_action_call, "Contestar", actionPi("answer", 9402))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Rechazar", actionPi("reject", 9403))
            .addAction(android.R.drawable.ic_menu_view, "Dejar sonar", actionPi("ignore", 9404))
            .build()
        nm.notify(NOTIFICATION_ID, notif)
        runCatching { context.startActivity(open) }
    }

    fun dismiss(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }
}

class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        perform(context, intent.getStringExtra("call_action").orEmpty(), intent.getStringExtra("call_source").orEmpty(), intent.getStringExtra("call_key").orEmpty())
    }

    companion object {
        fun perform(context: Context, action: String, source: String, key: String): Boolean {
            if (action == "ignore") return true
            val ok = if (source == "phone") {
                val telecom = context.getSystemService(TelecomManager::class.java)
                runCatching {
                    when (action) {
                        "answer" -> { if (Build.VERSION.SDK_INT >= 26) telecom.acceptRingingCall(); true }
                        "reject" -> { if (Build.VERSION.SDK_INT >= 28) telecom.endCall() else false }
                        else -> false
                    }
                }.getOrDefault(false)
            } else {
                JarvisNotificationListener.performCallAction(key, action)
            }
            if (ok && action != "ignore") IncomingCallUi.dismiss(context)
            return ok
        }
    }
}

class IncomingCallActivity : Activity() {
    private var data = JSONObject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        window.statusBarColor = Color.BLACK
        data = runCatching { JSONObject(intent.getStringExtra(IncomingCallUi.EXTRA_CALL).orEmpty()) }.getOrElse {
            runCatching { JSONObject(getSharedPreferences("jarvis_mobile", MODE_PRIVATE).getString("last_incoming_call_json", "{}")) }.getOrElse { JSONObject() }
        }
        render()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        data = runCatching { JSONObject(intent?.getStringExtra(IncomingCallUi.EXTRA_CALL).orEmpty()) }.getOrElse { data }
        render()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun bg(color: Int) = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(color) }

    private fun render() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(40), dp(24), dp(30))
            setBackgroundColor(Color.rgb(7, 10, 16))
        }
        val photo = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(112), dp(112)).apply { bottomMargin = dp(18) }
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.rgb(53, 58, 76)) }
            clipToOutline = true
        }
        val photoUri = data.optString("photoUri")
        val photoPath = data.optString("photoPath")
        when {
            photoPath.isNotBlank() -> runCatching { photo.setImageBitmap(BitmapFactory.decodeFile(photoPath)) }
            photoUri.isNotBlank() -> runCatching { photo.setImageURI(Uri.parse(photoUri)) }
            else -> photo.setImageResource(android.R.drawable.sym_def_app_icon)
        }
        root.addView(photo)

        val name = data.optString("name").ifBlank { data.optString("publicLabel") }.ifBlank { "Número desconocido" }
        root.addView(TextView(this).apply { text = name; textSize = 27f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setTypeface(typeface, android.graphics.Typeface.BOLD) })
        val number = data.optString("number")
        if (number.isNotBlank()) root.addView(TextView(this).apply { text = number; textSize = 17f; setTextColor(Color.rgb(190,198,214)); gravity = Gravity.CENTER; setPadding(0,dp(6),0,0) })

        val classification = data.optString("classification")
        val info = when {
            data.optBoolean("contactKnown") -> buildString {
                append(if (data.optBoolean("favorite")) "★ Contacto favorito" else "Contacto guardado")
                if (data.optBoolean("recent")) append(" · utilizado recientemente")
            }
            classification == "spam_probable" -> "⚠ Spam probable · ${data.optInt("spamScore",0)}% · ${data.optJSONArray("spamSources")?.length() ?: 0} fuentes"
            classification == "possible_spam" || classification == "suspicious" -> "⚠ Posible spam · revisar antes de contestar"
            data.optString("publicLabel").isNotBlank() -> "Coincidencia pública en Internet · no confirmada"
            else -> "Número desconocido · sin coincidencias suficientes"
        }
        root.addView(TextView(this).apply { text = info; textSize = 16f; setTextColor(if(classification=="spam_probable") Color.rgb(255,170,120) else Color.rgb(145,184,255)); gravity = Gravity.CENTER; setPadding(0,dp(16),0,dp(24)) })
        val app = data.optString("app")
        if (app.isNotBlank()) root.addView(TextView(this).apply { text = "Llamada por $app${if(data.optBoolean("video")) " · vídeo" else ""}"; textSize = 15f; setTextColor(Color.LTGRAY); gravity = Gravity.CENTER; setPadding(0,0,0,dp(18)) })

        fun button(label: String, color: Int, action: String): Button = Button(this).apply {
            text = label; textSize = 17f; setTextColor(Color.WHITE); background = bg(color)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)).apply { bottomMargin = dp(12) }
            setOnClickListener {
                val ok = CallActionReceiver.perform(this@IncomingCallActivity, action, data.optString("source","phone"), data.optString("notificationKey"))
                if (action == "ignore" || ok) finish()
            }
        }
        root.addView(button("Contestar", Color.rgb(24, 122, 73), "answer"))
        root.addView(button("Rechazar", Color.rgb(151, 52, 52), "reject"))
        root.addView(button("Dejar sonar", Color.rgb(48, 56, 76), "ignore"))
        setContentView(root)
    }
}
