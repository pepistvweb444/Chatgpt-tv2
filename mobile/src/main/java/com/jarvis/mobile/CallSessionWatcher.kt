package com.jarvis.mobile

import android.content.Context
import android.content.Intent
import android.util.Base64
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

object CallSessionWatcher {
    private val started = ConcurrentHashMap.newKeySet<String>()

    fun afterCallPublished(context: Context, callId: String) {
        if (callId.isBlank() || !started.add(callId)) return
        val app = context.applicationContext
        Thread {
            runCatching { CallerEnricher.enrich(app) }
            val client = InitTpvClient(app)
            val current = CallStateStore.current(app)
            if (current.optString("id") != callId || !current.optBoolean("active", false)) return@Thread
            if (client.configured() && !current.optBoolean("aiRequested", false)) {
                val accepted = client.handToAi(callId)
                CallStateStore.update(app, callId) {
                    it.put("aiRequested", true)
                    it.put("aiConnected", accepted)
                    it.put("phase", if (accepted) "ai" else "ringing")
                    it.put("aiStatus", if (accepted) "IA Premium contestando..." else "IA Premium sin línea InitTPV")
                }
            } else if (!client.configured()) {
                CallStateStore.update(app, callId) {
                    it.put("aiConnected", false)
                    it.put("aiStatus", "IA Premium sin línea InitTPV")
                    if (it.optString("phase").isBlank()) it.put("phase", "ringing")
                }
            }
            repeat(8) { tick ->
                val live = CallStateStore.current(app)
                if (live.optString("id") != callId || !live.optBoolean("active", false)) return@Thread
                if (client.configured()) mergePlatform(app, client, callId)
                if (live.optBoolean("video", false) && client.configured() && tick % 2 == 0) {
                    val frame = client.videoFrame(callId)
                    if (frame != null) {
                        CallStateStore.update(app, callId) {
                            it.put("videoFrame", Base64.encodeToString(frame, Base64.NO_WRAP))
                            it.put("videoPreview", "live")
                        }
                    } else if (live.optString("videoPreview").isBlank()) {
                        CallStateStore.update(app, callId) { it.put("videoPreview", "unavailable") }
                    }
                }
                Thread.sleep(2500)
            }
        }.start()
    }

    fun refresh(context: Context) {
        val call = CallStateStore.current(context)
        if (!call.optBoolean("active", false)) return
        val client = InitTpvClient(context)
        if (!client.configured()) return
        Thread { runCatching { mergePlatform(context.applicationContext, client, call.optString("id")) } }.start()
    }

    private fun mergePlatform(context: Context, client: InitTpvClient, callId: String) {
        val payload = client.pollActive() ?: return
        val calls = payload.optJSONArray("calls") ?: JSONArray()
        var match: JSONObject? = null
        for (i in 0 until calls.length()) {
            val item = calls.optJSONObject(i) ?: continue
            if (item.optString("id") == callId || calls.length() == 1) { match = item; break }
        }
        val found = match ?: return
        CallStateStore.update(context, callId) { call ->
            val lines = found.optJSONArray("transcript")
            if (lines != null && lines.length() > 0) call.put("transcript", lines).put("phase", "live")
            if (found.optBoolean("wantsUser", false)) call.put("wantsUser", true).put("phase", "live")
            val remotePhase = found.optString("state")
            if (remotePhase == "live" || remotePhase == "ai") call.put("phase", remotePhase).put("aiConnected", true)
            if (found.optString("aiStatus").isNotBlank()) call.put("aiStatus", found.optString("aiStatus"))
            call.put("updatedAt", System.currentTimeMillis())
        }
        val fresh = CallStateStore.current(context)
        if (fresh.optBoolean("wantsUser", false) && !fresh.optBoolean("promptSpoken", false)) {
            CallStateStore.update(context, callId) { it.put("promptSpoken", true) }
            val name = fresh.optString("name").ifBlank { "Quien llama" }
            val text = "$name quiere hablar contigo. ¿Coges la llamada?"
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, MobileSpeechService::class.java).putExtra("text", text).putExtra("voice", "coral")
                )
            }
        }
    }
}
