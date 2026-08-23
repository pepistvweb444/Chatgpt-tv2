package com.jarvis.mobile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object TadoClient {
    private const val CLIENT_ID = "1bb50063-6b0c-4d11-bd99-387f4a91cc46"
    private const val API = "https://my.tado.com/api/v2"
    private const val TOKEN = "https://login.tado.com/oauth2/token"

    data class Zone(
        val homeId: Int,
        val id: Int,
        val name: String,
        val type: String,
        val power: String,
        val temperature: Double?,
        val target: Double?
    )

    fun isConnected(context: Context): Boolean =
        context.getSharedPreferences("jarvis_mobile", Context.MODE_PRIVATE)
            .getString("domotics_tado_refresh_token", null) != null

    fun zones(context: Context): List<Zone> {
        val token = validToken(context)
        val me = JSONObject(get("$API/me", token))
        val homes = me.optJSONArray("homes") ?: JSONArray()
        val out = mutableListOf<Zone>()
        for (h in 0 until homes.length()) {
            val homeId = homes.optJSONObject(h)?.optInt("id") ?: continue
            val zones = JSONArray(get("$API/homes/$homeId/zones", token))
            val states = JSONObject(get("$API/homes/$homeId/zoneStates", token)).optJSONObject("zoneStates") ?: JSONObject()
            for (i in 0 until zones.length()) {
                val z = zones.optJSONObject(i) ?: continue
                val id = z.optInt("id")
                val state = states.optJSONObject(id.toString()) ?: JSONObject()
                val setting = state.optJSONObject("setting") ?: JSONObject()
                val temp = state.optJSONObject("sensorDataPoints")?.optJSONObject("insideTemperature")?.optDouble("celsius")
                val target = setting.optJSONObject("temperature")?.optDouble("celsius")
                out += Zone(
                    homeId = homeId,
                    id = id,
                    name = z.optString("name", "Zona $id"),
                    type = z.optString("type", setting.optString("type", "HEATING")),
                    power = setting.optString("power", "UNKNOWN"),
                    temperature = temp?.takeUnless { it.isNaN() },
                    target = target?.takeUnless { it.isNaN() }
                )
            }
        }
        return out
    }

    fun setPower(context: Context, zone: Zone, on: Boolean, target: Double? = null) {
        val type = if (zone.type.contains("AIR", true)) "AIR_CONDITIONING" else "HEATING"
        val setting = JSONObject().put("type", type).put("power", if (on) "ON" else "OFF")
        if (on && target != null) setting.put("temperature", JSONObject().put("celsius", target))
        val body = JSONObject()
            .put("setting", setting)
            .put("termination", JSONObject().put("type", "MANUAL"))
        put(context, "$API/homes/${zone.homeId}/zones/${zone.id}/overlay", body.toString())
    }

    fun setTemperature(context: Context, zone: Zone, target: Double) = setPower(context, zone, true, target)

    fun resumeSchedule(context: Context, zone: Zone) {
        val token = validToken(context)
        request("DELETE", "$API/homes/${zone.homeId}/zones/${zone.id}/overlay", token, null)
    }

    private fun put(context: Context, url: String, body: String) {
        val token = validToken(context)
        request("PUT", url, token, body)
    }

    private fun validToken(context: Context): String {
        val prefs = context.getSharedPreferences("jarvis_mobile", Context.MODE_PRIVATE)
        val existing = prefs.getString("domotics_tado_access_token", null)
        val expiry = prefs.getLong("domotics_tado_access_expires_at", 0L)
        if (!existing.isNullOrBlank() && System.currentTimeMillis() < expiry - 30_000L) return existing
        val refresh = prefs.getString("domotics_tado_refresh_token", null)
            ?: throw IllegalStateException("Tado no está conectado")
        val body = "client_id=${enc(CLIENT_ID)}&grant_type=refresh_token&refresh_token=${enc(refresh)}"
        val c = URL(TOKEN).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 8000; c.readTimeout = 12000
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        c.setRequestProperty("Accept", "application/json")
        c.outputStream.use { it.write(body.toByteArray()) }
        val text = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (c.responseCode !in 200..299) throw IllegalStateException("No se pudo renovar Tado (${c.responseCode})")
        val json = JSONObject(text)
        val access = json.getString("access_token")
        val edit = prefs.edit().putString("domotics_tado_access_token", access)
            .putLong("domotics_tado_access_expires_at", System.currentTimeMillis() + json.optLong("expires_in", 600L) * 1000L)
        json.optString("refresh_token").takeIf { it.isNotBlank() }?.let { edit.putString("domotics_tado_refresh_token", it) }
        edit.apply()
        return access
    }

    private fun get(url: String, token: String): String = request("GET", url, token, null)

    private fun request(method: String, url: String, token: String, body: String?): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method; c.connectTimeout = 8000; c.readTimeout = 12000
        c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("Accept", "application/json")
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        c.disconnect()
        if (code !in 200..299) throw IllegalStateException("Tado HTTP $code ${text.take(180)}")
        return text
    }

    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")
}
