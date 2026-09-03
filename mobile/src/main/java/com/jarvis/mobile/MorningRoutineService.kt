package com.jarvis.mobile

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MorningRoutineService : Service() {
    private var ringtone: Ringtone? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL, "Rutina de mañana Jarvis", NotificationManager.IMPORTANCE_HIGH))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        startForeground(97, NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Jarvis · Rutina de mañana")
            .setContentText("Abriendo el briefing en la televisión")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(false)
            .build())

        val prefs = getSharedPreferences("jarvis_mobile", MODE_PRIVATE)
        if (prefs.getBoolean("morning_routine_alarm", true)) {
            runCatching {
                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                ringtone = RingtoneManager.getRingtone(this, uri)?.apply {
                    if (Build.VERSION.SDK_INT >= 21) audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                    play()
                }
            }
        }

        Thread {
            try {
                if (prefs.getBoolean("morning_routine_tv", true)) {
                    val host = prefs.getString("tv_remote_host", "").orEmpty().trim()
                    val token = prefs.getString("remote_token", "").orEmpty().trim()
                    if (host.isNotBlank() && token.isNotBlank()) {
                        val c = (URL("http://$host:8766/briefing").openConnection() as HttpURLConnection).apply {
                            requestMethod = "GET"; connectTimeout = 4500; readTimeout = 8000
                            setRequestProperty("Accept", "application/json")
                            setRequestProperty("Authorization", "Bearer $token")
                        }
                        val raw=(if(c.responseCode in 200..299)c.inputStream else c.errorStream)?.bufferedReader()?.use{it.readText()}.orEmpty()
                        prefs.edit().putString("morning_routine_tv_result", JSONObject().put("code",c.responseCode).put("body",raw.take(300)).put("at",System.currentTimeMillis()).toString()).apply()
                    } else {
                        prefs.edit().putString("morning_routine_tv_result", JSONObject().put("error","tv_not_registered").put("at",System.currentTimeMillis()).toString()).apply()
                    }
                }
            } catch (e: Throwable) {
                prefs.edit().putString("morning_routine_tv_result", JSONObject().put("error",e.message ?: e.javaClass.simpleName).put("at",System.currentTimeMillis()).toString()).apply()
            } finally {
                Thread.sleep(60000L)
                runCatching { ringtone?.stop() }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }.start()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { ringtone?.stop() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object { private const val CHANNEL = "jarvis_morning_routine" }
}
