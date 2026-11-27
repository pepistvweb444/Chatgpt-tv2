package com.jarvis.mobile

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat

object CallActionManager {
    @Suppress("DEPRECATION")
    fun perform(context: Context, action: String): Pair<Boolean, String> {
        val call = CallStateStore.current(context)
        if (!call.optBoolean("active", false)) return false to "No hay una llamada activa."
        val normalized = action.lowercase().trim()
        val id = call.optString("id")
        val platform = InitTpvClient(context)
        return when (normalized) {
            "ignore", "leave", "ring", "leave_ai", "dejar" -> leaveToAi(context, platform, id)
            "mute", "silenciar" -> mute(context, platform, id, call.optBoolean("muted", false).not())
            "hangup", "colgar", "reject", "decline" -> finishLocal(context, call, platform, id, answer = false)
            "answer", "accept", "contestar", "coger", "answer_voice" -> finishLocal(context, call, platform, id, answer = true, video = false)
            "answer_video" -> finishLocal(context, call, platform, id, answer = true, video = true)
            else -> false to "Acción de llamada desconocida."
        }
    }

    private fun leaveToAi(context: Context, platform: InitTpvClient, id: String): Pair<Boolean, String> {
        if (!platform.configured()) {
            CallStateStore.update(context, id) {
                it.put("aiConnected", false).put("aiStatus", "IA Premium sin línea InitTPV")
            }
            return false to "Conecta la línea InitTPV para dejar la llamada a la IA Premium."
        }
        val ok = platform.handToAi(id)
        CallStateStore.update(context, id) {
            it.put("aiRequested", true).put("aiConnected", ok).put("phase", if (ok) "ai" else it.optString("phase"))
            it.put("aiStatus", if (ok) "IA Premium contestando..." else "IA Premium sin línea InitTPV")
        }
        silenceRinger(context)
        return if (ok) true to "La IA Premium sigue con la llamada." else false to "La línea InitTPV no ha podido atender."
    }

    private fun mute(context: Context, platform: InitTpvClient, id: String, muted: Boolean): Pair<Boolean, String> {
        val audio = context.getSystemService(AudioManager::class.java)
        runCatching { audio.isMicrophoneMute = muted }
        if (platform.configured()) platform.mute(id, muted)
        CallStateStore.update(context, id) { it.put("muted", muted) }
        return true to if (muted) "Micrófono silenciado. La IA puede seguir." else "Micrófono activo."
    }

    private fun finishLocal(context: Context, call: org.json.JSONObject, platform: InitTpvClient, id: String, answer: Boolean, video: Boolean = false): Pair<Boolean, String> {
        if (platform.configured()) {
            if (answer) platform.takeover(id, video) else platform.hangup(id)
        }
        val local = if (call.optString("source") == "cellular") cellular(context, answer) else voip(context, call, if (answer) "answer" else "reject")
        if (!local.first && call.optString("source") == "inittpv") {
            CallStateStore.update(context, id) {
                it.put("state", if (answer) "answered" else "rejected")
                it.put("phase", if (answer) "live" else "ended")
                it.put("active", answer)
            }
            return true to if (answer) {
                if (video) "Coges la videollamada por InitTPV. La cámara del teléfono sigue apagada hasta que la línea la abra." else "Coges la llamada por InitTPV."
            } else "Llamada colgada."
        }
        if (local.first) {
            CallStateStore.update(context, id) {
                it.put("state", if (answer) "answered" else "rejected")
                it.put("phase", if (answer) "live" else "ended")
                it.put("active", answer)
                if (video) it.put("answeredWithVideo", true)
            }
        }
        val videoNote = if (answer && video && call.optBoolean("video", false)) " WhatsApp puede abrir su propia cámara al aceptar; Jarvis no enciende la cámara antes de ese botón." else ""
        return local.first to (local.second + videoNote)
    }

    @Suppress("DEPRECATION")
    private fun cellular(context: Context, answer: Boolean): Pair<Boolean, String> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) {
            return false to "Falta el permiso para contestar llamadas."
        }
        val telecom = context.getSystemService(TelecomManager::class.java)
        val ok = runCatching {
            if (answer) telecom.acceptRingingCall() else telecom.endCall()
            true
        }.getOrDefault(false)
        return if (ok) true to if (answer) "Llamada contestada." else "Llamada colgada."
        else false to "Android no ha dejado actuar sobre la llamada."
    }

    private fun voip(context: Context, call: org.json.JSONObject, action: String): Pair<Boolean, String> {
        val key = call.optString("notificationKey")
        if (key.isBlank()) return false to "La app de la llamada no ofrece un botón usable."
        if (CallActionRegistry.execute(key, action)) return true to "He pulsado el botón de ${call.optString("app").ifBlank { "la app" }}."
        context.sendBroadcast(Intent(JarvisNotificationListener.ACTION_CALL_CONTROL).setPackage(context.packageName).putExtra("notification_key", key).putExtra("call_action", action))
        return true to "Acción enviada a ${call.optString("app").ifBlank { "la app" }}."
    }

    private fun silenceRinger(context: Context) {
        val telecom = context.getSystemService(TelecomManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 29 && ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
            runCatching { telecom.silenceRinger() }
        }
    }
}
