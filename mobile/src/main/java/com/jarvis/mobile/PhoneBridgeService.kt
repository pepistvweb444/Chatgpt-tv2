package com.jarvis.mobile

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class PhoneBridgeService : Service() {
    @Volatile private var running = false
    private var server: ServerSocket? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Jarvis TV bridge", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, ChatActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        startForeground(93, NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle("Jarvis · puente con TV")
            .setContentText("Permite a Jarvis TV solicitar llamadas desde este teléfono")
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
                        while (reader.readLine()?.isNotEmpty() == true) {}
                        val path = first.split(" ").getOrNull(1).orEmpty()
                        val response = handle(path)
                        val body = response.second
                        val code = response.first
                        val out = s.getOutputStream()
                        out.write("HTTP/1.1 $code ${if (code==200) "OK" else "ERROR"}\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body".toByteArray())
                        out.flush()
                    }
                }.start()
            }
        } catch (_: Exception) { }
    }

    private fun handle(path: String): Pair<Int,String> {
        if (path.startsWith("/ping")) return 200 to "jarvis-phone-ok"
        if (!path.startsWith("/call?")) return 404 to "not-found"
        val raw = path.substringAfter("number=", "").substringBefore("&")
        val number = URLDecoder.decode(raw, StandardCharsets.UTF_8.name()).trim()
        if (number.isBlank()) return 400 to "number-required"
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) return 403 to "call-permission-required"
        return try {
            startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(number))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            200 to "calling"
        } catch (e: Exception) { 500 to (e.message ?: "call-failed") }
    }

    override fun onDestroy() {
        running = false
        runCatching { server?.close() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val PORT = 8765
        private const val CHANNEL = "jarvis_phone_bridge"
    }
}
