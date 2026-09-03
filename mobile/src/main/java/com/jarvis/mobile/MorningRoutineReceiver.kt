package com.jarvis.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class MorningRoutineReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        RoutineScheduler.scheduleMorning(context)
        runCatching {
            ContextCompat.startForegroundService(context, Intent(context, MorningRoutineService::class.java))
        }
    }
}
