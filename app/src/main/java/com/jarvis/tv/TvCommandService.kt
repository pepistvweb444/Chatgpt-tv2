package com.jarvis.tv

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket

class TvCommandService : Service() {
    @Volatile private var running = false
    private var server: ServerSocket? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL, "Jarvis TV local control", NotificationManager.IMPORTANCE_MIN))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        startForeground(96, NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Jarvis TV listo para rutinas")
            .setContentText("Permite abrir el briefing desde Jarvis Mobile")
            .setOngoing(true).setContentIntent(open).build())
        running = true
        Thread { serve() }.start()
    }

    private fun serve() {
        try {
            server = ServerSocket(PORT)
            while (running) {
                val socket = server?.accept() ?: break
                Thread {
                    socket.use { s ->
                        val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                        val first = reader.readLine().orEmpty()
                        val headers = mutableMapOf<String, String>()
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            val colon = line.indexOf(':')
                            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
                        }
                        val path = first.split(" ").getOrNull(1).orEmpty()
                        val (code, body) = handle(path, headers)
                        val bytes = body.toByteArray(Charsets.UTF_8)
                        s.getOutputStream().apply {
                            write("HTTP/1.1 $code ${if (code == 200) "OK" else "ERROR"}\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(bytes); flush()
                        }
                    }
                }.start()
            }
        } catch (_: Throwable) {
        }
    }

    private fun authorized(headers: Map<String,String>): Boolean {
        val expected = getSharedPreferences("jarvis", MODE_PRIVATE).getString("mobile_remote_token", "").orEmpty().trim()
        val bearer = headers["authorization"].orEmpty().removePrefix("Bearer ").trim()
        return expected.isNotBlank() && bearer == expected
    }

    private fun handle(path: String, headers: Map<String,String>): Pair<Int,String> {
        if (path.startsWith("/ping")) return 200 to JSONObject().put("ok", true).put("device", "jarvis-tv").toString()
        if (!authorized(headers)) return 401 to JSONObject().put("error", "unauthorized").toString()
        if (path.startsWith("/briefing")) {
            wakeAndOpenBriefing()
            return 200 to JSONObject().put("ok", true).put("status", "opening-briefing").toString()
        }
        return 404 to JSONObject().put("error", "not-found").toString()
    }

    private fun wakeAndOpenBriefing() {
        runCatching {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val lock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "JarvisTV:MorningBriefing")
            lock.acquire(12000L)
        }
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("show_morning_briefing", true)
        startActivity(i)
    }

    override fun onDestroy() {
        running = false
        runCatching { server?.close() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val PORT = 8766
        private const val CHANNEL = "jarvis_tv_command"
    }
}
