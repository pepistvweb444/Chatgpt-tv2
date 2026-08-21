package com.jarvis.mobile

import android.telecom.Call
import android.telecom.CallScreeningService
import org.json.JSONArray
import org.json.JSONObject

class JarvisCallScreeningService : CallScreeningService() {
    override fun onScreenCall(callDetails: Call.Details) {
        val handle = callDetails.handle?.schemeSpecificPart.orEmpty()
        val prefs = getSharedPreferences("jarvis_mobile", MODE_PRIVATE)
        val arr = runCatching { JSONArray(prefs.getString("call_feed", "[]")) }.getOrElse { JSONArray() }
        arr.put(JSONObject().put("number", handle).put("time", System.currentTimeMillis()).put("direction", "incoming"))
        prefs.edit().putString("call_feed", arr.toString()).putString("last_incoming_call", handle).apply()
        respondToCall(callDetails, CallResponse.Builder().setDisallowCall(false).setRejectCall(false).setSilenceCall(false).build())
    }
}
