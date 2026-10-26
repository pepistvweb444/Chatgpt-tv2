package com.jarvis.mobile

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat

object CallActionManager {
    @Suppress("DEPRECATION")
    fun perform(context: Context, action: String): Pair<Boolean, String> {
        val call = CallStateStore.current(context)
        // TV in-call controls (outgoing calls placed from the TV too): speaker / mute / hang up
        val n0 = action.lowercase().trim()
        if (n0 == "speaker" || n0 == "speaker_on" || n0 == "speaker_off" || n0 == "mute" || n0 == "unmute" || n0 == "end" || n0 == "hangup") {
            val am = context.getSystemService(android.media.AudioManager::class.java)
            return runCatching {
                when (n0) {
                    "speaker", "speaker_on", "speaker_off" -> { am.mode = android.media.AudioManager.MODE_IN_CALL; am.isSpeakerphoneOn = if (n0 == "speaker") !am.isSpeakerphoneOn else n0 == "speaker_on"; true to (if (am.isSpeakerphoneOn) "Altavoz activado." else "Altavoz desactivado.") }
                    "mute", "unmute" -> { am.isMicrophoneMute = if (n0 == "mute") !am.isMicrophoneMute else false; true to (if (am.isMicrophoneMute) "Micrófono silenciado." else "Micrófono activado.") }
                    else -> {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) false to "Falta el permiso ANSWER_PHONE_CALLS."
                        else { val ok = context.getSystemService(TelecomManager::class.java).endCall(); CallStateStore.clear(context, "ended"); ok to (if (ok) "Llamada colgada." else "No había llamada que colgar.") }
                    }
                }
            }.getOrElse { false to (it.message ?: "call-control-failed") }
        }
        if (!call.optBoolean("active", false)) return false to "No hay una llamada entrante activa."
        val normalized = action.lowercase().trim()
        if (normalized == "ignore" || normalized == "leave" || normalized == "ring") return true to "La llamada seguirá sonando."

        return if (call.optString("source") == "cellular") {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) {
                false to "Falta el permiso ANSWER_PHONE_CALLS."
            } else {
                val telecom = context.getSystemService(TelecomManager::class.java)
                val ok = runCatching {
                    when (normalized) {
                        "answer" -> telecom.acceptRingingCall()
                        "reject", "decline" -> telecom.endCall()
                        else -> return false to "Acción de llamada desconocida."
                    }
                    true
                }.getOrDefault(false)
                if (ok) {
                    CallStateStore.update(context, call.optString("id")) {
                        it.put("state", if (normalized == "answer") "answered" else "rejected")
                        it.put("active", normalized == "answer")
                    }
                    true to if (normalized == "answer") "Llamada contestada." else "Llamada rechazada."
                } else false to "Android no permitió ejecutar la acción de llamada."
            }
        } else {
            val key = call.optString("notificationKey")
            if (key.isBlank()) return false to "La app de llamada no expuso un control remoto utilizable."
            context.sendBroadcast(Intent(JarvisNotificationListener.ACTION_CALL_CONTROL)
                .setPackage(context.packageName)
                .putExtra("notification_key", key)
                .putExtra("call_action", normalized))
            true to "Acción enviada a ${call.optString("app").ifBlank { "la app" }}."
        }
    }
}
