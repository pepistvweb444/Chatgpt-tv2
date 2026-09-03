package com.jarvis.mobile

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Calendar

object RoutineScheduler {
    private const val REQ = 8821

    fun scheduleMorning(context: Context) {
        val prefs = context.getSharedPreferences("jarvis_mobile", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("morning_routine_enabled", false)) {
            cancelMorning(context)
            return
        }
        val hour = prefs.getInt("morning_routine_hour", 8).coerceIn(0, 23)
        val minute = prefs.getInt("morning_routine_minute", 0).coerceIn(0, 59)
        val now = Calendar.getInstance()
        val at = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (!after(now)) add(Calendar.DAY_OF_YEAR, 1)
        }
        val pi = PendingIntent.getBroadcast(
            context, REQ, Intent(context, MorningRoutineReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val am = context.getSystemService(AlarmManager::class.java)
        val show = PendingIntent.getActivity(
            context, REQ + 1, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.setAlarmClock(AlarmManager.AlarmClockInfo(at.timeInMillis, show), pi)
        prefs.edit().putLong("morning_routine_next", at.timeInMillis).apply()
    }

    fun cancelMorning(context: Context) {
        val pi = PendingIntent.getBroadcast(
            context, REQ, Intent(context, MorningRoutineReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pi != null) context.getSystemService(AlarmManager::class.java).cancel(pi)
        context.getSharedPreferences("jarvis_mobile", Context.MODE_PRIVATE).edit().remove("morning_routine_next").apply()
    }
}
