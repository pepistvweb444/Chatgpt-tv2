package com.jarvis.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telecom.TelecomManager
import android.widget.Toast

class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.getStringExtra("call_action").orEmpty()
        val telecom = context.getSystemService(TelecomManager::class.java)
        val ok = runCatching {
            when (action) {
                "answer" -> {
                    @Suppress("DEPRECATION")
                    telecom.acceptRingingCall()
                    true
                }
                "reject" -> {
                    @Suppress("DEPRECATION")
                    telecom.endCall()
                }
                else -> false
            }
        }.getOrDefault(false)
        if (!ok && action.isNotBlank()) Toast.makeText(context, "Android no permitió ${if (action=="answer") "contestar" else "rechazar"} la llamada", Toast.LENGTH_LONG).show()
        if (ok) context.getSharedPreferences("jarvis_mobile", Context.MODE_PRIVATE).edit()
            .putString("incoming_call_state", if (action=="answer") "answered" else "rejected")
            .putLong("incoming_call_action_at", System.currentTimeMillis()).apply()
    }
}
