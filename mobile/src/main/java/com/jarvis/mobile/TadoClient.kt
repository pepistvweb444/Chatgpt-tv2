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
        val target: Double?,
        val overlayActive: Boolean
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
            val homeId = homes.optJSONObject(h)?.optInt("id", -1) ?: -1
            if (homeId < 0) continue
            val zoneList = JSONArray(get("$API/homes/$homeId/zones", token))
            for (i in 0 until zoneList.length()) {
                val z = zoneList.optJSONObject(i) ?: continue
                val zoneId = z.optInt("id", -1)
                if (zoneId < 0) continue
                val state = runCatching { JSONObject(get("$API/homes/$homeId/zones/$zoneId/state", token)) }.getOrElse { JSONObject() }
                val setting = state.optJSONObject("setting") ?: JSONObject()
                val sensor = state.optJSONObject("sensorDataPoints")
                val inside = sensor?.optJSONObject("insideTemperature")
                val temp = inside?.takeIf { it.has("celsius") }?.optDouble("celsius")
                val targetObj = setting.optJSONObject("temperature")
                val target = targetObj?.takeIf { it.has("celsius") }?.optDouble("celsius")
                val type = z.optString("type").ifBlank { setting.optString("type", "HEATING") }
                val power = setting.optString("power").ifBlank {
                    if (state.optJSONObject("activityDataPoints")?.optJSONObject("heatingPower")?.optDouble("percentage", 0.0) ?: 0.0 > 0) "ON" else "OFF"
                }
                out += Zone(homeId, zoneId, z.optString("name", "Zona $zoneId"), type, power,
                    temp?.takeUnless { it.isNaN() }, target?.takeUnless { it.isNaN() },
                    state.optString("overlayType").isNotBlank() && state.optString("overlayType") != "null")
            }
        }
        return out
    }

    fun setPower(context: Context, zone: Zone, on: Boolean, target: Double? = null) {
        setPowerInternal(context, zone, on, target, null)
    }

    fun setClimate(context: Context, zone: Zone, target: Double?, mode: String?) {
        setPowerInternal(context, zone, true, target, mode)
    }

    private fun setPowerInternal(context: Context, zone: Zone, on: Boolean, target: Double?, requestedMode: String?) {
        val token = validToken(context)
        val state = runCatching {
            JSONObject(get("$API/homes/${zone.homeId}/zones/${zone.id}/state", token))
        }.getOrElse { JSONObject() }
        val current = state.optJSONObject("setting") ?: JSONObject()
        val type = current.optString("type").ifBlank {
            when {
                zone.type.contains("AIR", true) -> "AIR_CONDITIONING"
                zone.type.contains("HOT_WATER", true) -> "HOT_WATER"
                else -> "HEATING"
            }
        }
        val capabilities = runCatching {
            JSONObject(get("$API/homes/${zone.homeId}/zones/${zone.id}/capabilities", token))
        }.getOrElse { JSONObject() }

        val setting = if (type == "AIR_CONDITIONING") {
            buildAcSetting(current, capabilities, on, target ?: zone.target ?: zone.temperature, requestedMode)
        } else {
            JSONObject().apply {
                put("type", type)
                put("power", if (on) "ON" else "OFF")
                if (on && target != null && type != "HOT_WATER") {
                    val tempCaps = capabilities.optJSONObject("temperatures")?.optJSONObject("celsius")
                    val min = tempCaps?.optDouble("min", 5.0) ?: 5.0
                    val max = tempCaps?.optDouble("max", 30.0) ?: 30.0
                    put("temperature", JSONObject().put("celsius", target.coerceIn(min, max)))
                }
            }
        }

        val body = JSONObject()
            .put("setting", setting)
            .put("termination", JSONObject().put("type", "MANUAL"))
        request("PUT", "$API/homes/${zone.homeId}/zones/${zone.id}/overlay", token, body.toString())
    }

    private fun buildAcSetting(current: JSONObject, capabilities: JSONObject, on: Boolean, requestedTarget: Double?, requestedMode: String?): JSONObject {
        val result = JSONObject().put("type", "AIR_CONDITIONING").put("power", if (on) "ON" else "OFF")
        val initialStates = capabilities.optJSONObject("initialStates")
        val initialMode = initialStates?.optString("mode").orEmpty()
        val currentMode = current.optString("mode")
        val supportedModes = capabilities.keys().asSequence()
            .filter { it !in setOf("type", "initialStates") && capabilities.optJSONObject(it) != null }
            .toList()
        val desired = requestedMode?.uppercase()?.takeIf { it in supportedModes }
        val mode = when {
            desired != null -> desired
            currentMode.isNotBlank() && currentMode in supportedModes -> currentMode
            initialMode.isNotBlank() && initialMode in supportedModes -> initialMode
            "COOL" in supportedModes -> "COOL"
            supportedModes.isNotEmpty() -> supportedModes.first()
            else -> "COOL"
        }
        result.put("mode", mode)

        val modeCaps = capabilities.optJSONObject(mode) ?: JSONObject()
        val initialModeState = initialStates?.optJSONObject("modes")?.optJSONObject(mode)
        val tempCaps = modeCaps.optJSONObject("temperatures")?.optJSONObject("celsius")
        if (on && tempCaps != null) {
            val min = tempCaps.optDouble("min", 16.0)
            val max = tempCaps.optDouble("max", 30.0)
            val fallback = current.optJSONObject("temperature")?.optDouble("celsius", Double.NaN)?.takeUnless { it.isNaN() }
                ?: initialModeState?.optJSONObject("temperature")?.optDouble("celsius", Double.NaN)?.takeUnless { it.isNaN() }
                ?: min
            result.put("temperature", JSONObject().put("celsius", (requestedTarget ?: fallback).coerceIn(min, max)))
        }

        copySupportedChoice("fanSpeed", current, initialModeState, modeCaps, result)
        copySupportedChoice("fanLevel", current, initialModeState, modeCaps, result)
        copySupportedChoice("swing", current, initialModeState, modeCaps, result)
        copySupportedChoice("verticalSwing", current, initialModeState, modeCaps, result)
        copySupportedChoice("horizontalSwing", current, initialModeState, modeCaps, result)
        copySupportedChoice("light", current, initialModeState, modeCaps, result)
        return result
    }

    private fun copySupportedChoice(key: String, current: JSONObject, initial: JSONObject?, modeCaps: JSONObject, target: JSONObject) {
        val allowed = modeCaps.optJSONArray(key) ?: return
        if (allowed.length() == 0) return
        val currentValue = current.optString(key)
        val initialValue = initial?.optString(key).orEmpty()
        val chosen = when {
            currentValue.isNotBlank() && jsonArrayContains(allowed, currentValue) -> currentValue
            initialValue.isNotBlank() && jsonArrayContains(allowed, initialValue) -> initialValue
            jsonArrayContains(allowed, "OFF") -> "OFF"
            jsonArrayContains(allowed, "AUTO") -> "AUTO"
            else -> allowed.optString(0)
        }
        if (chosen.isNotBlank()) target.put(key, chosen)
    }

    private fun jsonArrayContains(array: JSONArray, value: String): Boolean {
        for (i in 0 until array.length()) if (array.optString(i) == value) return true
        return false
    }

    fun setTemperature(context: Context, zone: Zone, target: Double) = setPower(context, zone, true, target)

    fun resumeSchedule(context: Context, zone: Zone) {
        val token = validToken(context)
        request("DELETE", "$API/homes/${zone.homeId}/zones/${zone.id}/overlay", token, null)
    }

    private fun validToken(context: Context): String {
        val prefs = context.getSharedPreferences("jarvis_mobile", Context.MODE_PRIVATE)
        val existing = prefs.getString("domotics_tado_access_token", null)
        val expiry = prefs.getLong("domotics_tado_access_expires_at", 0L)
        if (!existing.isNullOrBlank() && System.currentTimeMillis() < expiry - 30_000L) return existing
        val refresh = prefs.getString("domotics_tado_refresh_token", null) ?: throw IllegalStateException("Tado no está conectado")
        val body = "client_id=${enc(CLIENT_ID)}&grant_type=refresh_token&refresh_token=${enc(refresh)}"
        val c = URL(TOKEN).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 8000; c.readTimeout = 12000
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded"); c.setRequestProperty("Accept", "application/json")
        c.outputStream.use { it.write(body.toByteArray()) }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        c.disconnect()
        if (code !in 200..299) throw IllegalStateException("No se pudo renovar Tado ($code): ${text.take(120)}")
        val json = JSONObject(text); val access = json.getString("access_token")
        val edit = prefs.edit().putString("domotics_tado_access_token", access)
            .putLong("domotics_tado_access_expires_at", System.currentTimeMillis() + json.optLong("expires_in", 600L) * 1000L)
        json.optString("refresh_token").takeIf { it.isNotBlank() }?.let { edit.putString("domotics_tado_refresh_token", it) }
        edit.apply(); return access
    }

    private fun get(url: String, token: String): String = request("GET", url, token, null)

    private fun request(method: String, url: String, token: String, body: String?): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method; c.connectTimeout = 8000; c.readTimeout = 12000
        c.setRequestProperty("Authorization", "Bearer $token"); c.setRequestProperty("Accept", "application/json")
        if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", "application/json; charset=utf-8"); c.outputStream.use { it.write(body.toByteArray()) } }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        val rateLimit = c.getHeaderField("ratelimit"); c.disconnect()
        if (code !in 200..299) {
            val rateInfo = if (!rateLimit.isNullOrBlank()) " · límite $rateLimit" else ""
            throw IllegalStateException("Tado HTTP $code ${text.take(300)}$rateInfo")
        }
        return text
    }

    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")
}
