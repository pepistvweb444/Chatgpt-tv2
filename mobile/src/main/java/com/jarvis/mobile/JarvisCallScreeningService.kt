package com.jarvis.mobile

import android.content.Intent
import android.telecom.Call
import android.telecom.CallScreeningService
import org.json.JSONArray
import org.json.JSONObject

class JarvisCallScreeningService : CallScreeningService() {
    override fun onScreenCall(callDetails: Call.Details) {
        val incoming = if (android.os.Build.VERSION.SDK_INT >= 29) callDetails.callDirection == Call.Details.DIRECTION_INCOMING else true
        val handle = callDetails.handle?.schemeSpecificPart.orEmpty()
        val prefs = getSharedPreferences("jarvis_mobile", MODE_PRIVATE)
        val arr = runCatching { JSONArray(prefs.getString("call_feed", "[]")) }.getOrElse { JSONArray() }
        arr.put(JSONObject().put("number", handle).put("time", System.currentTimeMillis()).put("direction", if (incoming) "incoming" else "outgoing"))
        prefs.edit().putString("call_feed", arr.toString()).putString("last_incoming_call", handle).putLong("last_incoming_call_at", System.currentTimeMillis()).apply()

        if (incoming) {
            respondToCall(callDetails, CallResponse.Builder().setDisallowCall(false).setRejectCall(false).setSilenceCall(false).build())
            runCatching {
                startActivity(Intent(this, IncomingCallActivity::class.java)
                    .putExtra("number", handle)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
        }
    }
}
