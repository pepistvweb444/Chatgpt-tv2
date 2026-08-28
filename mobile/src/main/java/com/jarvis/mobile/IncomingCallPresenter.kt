package com.jarvis.mobile

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Base64
import androidx.core.app.NotificationCompat

object IncomingCallPresenter {
    const val CHANNEL = "jarvis_incoming_calls"
    const val NOTIFICATION_ID = 5107

    fun show(context: Context, call: org.json.JSONObject) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Llamadas entrantes Jarvis", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Identificación y control de llamadas entrantes"
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            })
        }
        val activityIntent = Intent(context, IncomingCallActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        )
        val open = PendingIntent.getActivity(
            context, 5107, activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        fun action(label: String, value: String, request: Int): NotificationCompat.Action {
            val pi = PendingIntent.getBroadcast(context, request,
                Intent(context, CallActionReceiver::class.java).putExtra("call_action", value),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            return NotificationCompat.Action(0, label, pi)
        }
        val known = call.optBoolean("knownContact", false)
        val classification = call.optString("classification", if (known) "contact" else "unknown")
        val title = call.optString("name").ifBlank { call.optString("number").ifBlank { call.optString("app").ifBlank { "Llamada entrante" } } }
        val detail = when (classification) {
            "spam_probable" -> "⚠ Spam probable · ${call.optInt("spamScore", 0)}%"
            "possible_spam" -> "Posible spam · comprobación parcial"
            "contact" -> if (call.optBoolean("priority", false)) "★ Contacto prioritario" else "Contacto"
            else -> "Número o usuario desconocido"
        }
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(buildString {
                append(detail)
                call.optString("app").takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
                call.optString("spamSources").takeIf { it.isNotBlank() }?.let { append("\nFuentes: ").append(it) }
            }))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .addAction(action("Contestar", "answer", 5108))
            .addAction(action("Rechazar", "reject", 5109))
            .addAction(action("Dejar sonar", "ignore", 5110))
        val photo = call.optString("photoData")
        if (photo.isNotBlank()) runCatching {
            val bytes = Base64.decode(photo, Base64.DEFAULT)
            builder.setLargeIcon(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        }
        nm.notify(NOTIFICATION_ID, builder.build())

        // Samsung/Android recientes pueden no desplegar el full-screen intent aunque la
        // notificación exista. El rol de call screening recibe la llamada en tiempo real,
        // así que intentamos abrir también la tarjeta directamente. Si Android bloquea
        // el arranque en segundo plano, la notificación full-screen sigue siendo respaldo.
        runCatching { context.startActivity(activityIntent) }
    }

    fun dismiss(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }
}

class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.getStringExtra("call_action").orEmpty()
        val (ok, _) = CallActionManager.perform(context, action)
        if (ok && action != "ignore") IncomingCallPresenter.dismiss(context)
    }
}
