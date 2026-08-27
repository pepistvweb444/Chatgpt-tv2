from pathlib import Path

p = Path('mobile/src/main/java/com/jarvis/mobile/MainActivity.kt')
s = p.read_text()

# Strengthen room persistence: keep both the provider/id key and a provider/name alias.
old = '''    private fun domoticsRoomKey(deviceKey: String) = "domotics_room_" + deviceKey

    private fun roomForDevice(deviceKey: String): String =
        prefs.getString(domoticsRoomKey(deviceKey), "Sin asignar").orEmpty().ifBlank { "Sin asignar" }
'''
new = r'''    private fun domoticsRoomKey(deviceKey: String) = "domotics_room_" + deviceKey

    private fun domoticsRoomAliasKey(deviceKey: String, deviceName: String): String {
        val provider = deviceKey.substringBefore(':').lowercase()
        val normalized = java.text.Normalizer.normalize(deviceName.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
        return "domotics_room_alias_${provider}_${normalized}"
    }

    private fun saveDomoticsRoom(deviceKey: String, deviceName: String, room: String) {
        prefs.edit()
            .putString(domoticsRoomKey(deviceKey), room)
            .putString(domoticsRoomAliasKey(deviceKey, deviceName), room)
            .putLong("domotics_room_saved_at", System.currentTimeMillis())
            .commit()
    }

    private fun roomForDevice(deviceKey: String, deviceName: String = ""): String {
        val direct = prefs.getString(domoticsRoomKey(deviceKey), "").orEmpty()
        if (direct.isNotBlank()) return direct
        if (deviceName.isNotBlank()) {
            val alias = prefs.getString(domoticsRoomAliasKey(deviceKey, deviceName), "").orEmpty()
            if (alias.isNotBlank()) {
                prefs.edit().putString(domoticsRoomKey(deviceKey), alias).apply()
                return alias
            }
        }
        return "Sin asignar"
    }
'''
if old in s:
    s = s.replace(old, new, 1)

# Save synchronously and avoid immediately re-querying providers after a room change.
s = s.replace(
    'prefs.edit().putStringSet("domotics_custom_rooms", next).putString(domoticsRoomKey(deviceKey), name).apply()',
    'prefs.edit().putStringSet("domotics_custom_rooms", next).commit(); saveDomoticsRoom(deviceKey, deviceName, name)'
)
s = s.replace(
    'prefs.edit().putString(domoticsRoomKey(deviceKey), selected).apply()',
    'saveDomoticsRoom(deviceKey, deviceName, selected)'
)
s = s.replace(
    '''                                showUnifiedDomoticsWidget()
                                refreshDomoticsQuickCard()''',
    '''                                refreshDomoticsQuickCard()
                                status.text = "Ubicación guardada · $deviceName · $name"'''
)
s = s.replace(
    '''                    showUnifiedDomoticsWidget()
                    refreshDomoticsQuickCard()''',
    '''                    refreshDomoticsQuickCard()
                    status.text = "Ubicación guardada · $deviceName · $selected"'''
)

# Resolve rooms with a stable-name fallback for all providers.
s = s.replace('roomForDevice("tado:${it.id}")', 'roomForDevice("tado:${it.id}", it.name)')
s = s.replace('roomForDevice("sensibo:${it.id}")', 'roomForDevice("sensibo:${it.id}", it.name)')
s = s.replace('roomForDevice("homeconnect:${it.haId}")', 'roomForDevice("homeconnect:${it.haId}", it.name)')

# Cache Home Connect devices for 60s to avoid repeated GET/status/settings/program requests and HTTP 429.
old_fetch = '''    private fun fetchHomeConnectDevices(token: String): List<HcDeviceCard> {
        val (code, raw) = hcApi("GET", "/api/homeappliances", token)
        if (code !in 200..299) throw IllegalStateException("Home Connect HTTP $code")'''
new_fetch = '''    private fun fetchHomeConnectDevices(token: String): List<HcDeviceCard> {
        val now = System.currentTimeMillis()
        val cachedAt = prefs.getLong("homeconnect_devices_cache_at", 0L)
        val cachedRaw = prefs.getString("homeconnect_devices_cache_raw", "").orEmpty()
        val (code, raw) = if (cachedRaw.isNotBlank() && now - cachedAt < 60000L) {
            200 to cachedRaw
        } else {
            val response = hcApi("GET", "/api/homeappliances", token)
            if (response.first in 200..299) {
                prefs.edit().putString("homeconnect_devices_cache_raw", response.second).putLong("homeconnect_devices_cache_at", now).apply()
            }
            response
        }
        if (code == 429 && cachedRaw.isNotBlank()) {
            return fetchHomeConnectDevicesFromListRaw(token, cachedRaw, allowDetailRefresh = false)
        }
        if (code !in 200..299) throw IllegalStateException("Home Connect HTTP $code")
        return fetchHomeConnectDevicesFromListRaw(token, raw, allowDetailRefresh = now - prefs.getLong("homeconnect_details_cache_at", 0L) > 60000L)
    }

    private fun fetchHomeConnectDevicesFromListRaw(token: String, raw: String, allowDetailRefresh: Boolean): List<HcDeviceCard> {'''
if old_fetch in s:
    s = s.replace(old_fetch, new_fetch, 1)
    # Replace the first function tail before showHomeConnectDevicesWidget so helper can close cleanly.
    marker = '\n    private fun showHomeConnectDevicesWidget() {'
    start = s.find('    private fun fetchHomeConnectDevicesFromListRaw(')
    end = s.find(marker, start)
    if start >= 0 and end > start:
        block = s[start:end]
        # Existing body contains per-device calls. Gate them when cache must be used.
        block = block.replace('''            val (_, statusRaw) = hcApi("GET", "/api/homeappliances/${java.net.URLEncoder.encode(id, "UTF-8")}/status", token)
            val (_, settingsRaw) = hcApi("GET", "/api/homeappliances/${java.net.URLEncoder.encode(id, "UTF-8")}/settings", token)
            val (activeCode, activeRaw) = hcApi("GET", "/api/homeappliances/${java.net.URLEncoder.encode(id, "UTF-8")}/programs/active", token)
            val active = if (activeCode in 200..299) runCatching { JSONObject(activeRaw).optJSONObject("data")?.optString("key").orEmpty() }.getOrDefault("") else ""
            val (progCode, progRaw) = hcApi("GET", "/api/homeappliances/${java.net.URLEncoder.encode(id, "UTF-8")}/programs/available", token)''', '''            val detailPrefix = "homeconnect_detail_${id}_"
            var statusRaw = prefs.getString(detailPrefix + "status", "").orEmpty()
            var settingsRaw = prefs.getString(detailPrefix + "settings", "").orEmpty()
            var activeRaw = prefs.getString(detailPrefix + "active", "").orEmpty()
            var progRaw = prefs.getString(detailPrefix + "programs", "").orEmpty()
            var activeCode = if (activeRaw.isNotBlank()) 200 else 404
            var progCode = if (progRaw.isNotBlank()) 200 else 404
            if (allowDetailRefresh) {
                val statusResp = hcApi("GET", "/api/homeappliances/${java.net.URLEncoder.encode(id, "UTF-8")}/status", token)
                val settingsResp = hcApi("GET", "/api/homeappliances/${java.net.URLEncoder.encode(id, "UTF-8")}/settings", token)
                val activeResp = hcApi("GET", "/api/homeappliances/${java.net.URLEncoder.encode(id, "UTF-8")}/programs/active", token)
                val progResp = hcApi("GET", "/api/homeappliances/${java.net.URLEncoder.encode(id, "UTF-8")}/programs/available", token)
                if (statusResp.first in 200..299) { statusRaw = statusResp.second; prefs.edit().putString(detailPrefix + "status", statusRaw).apply() }
                if (settingsResp.first in 200..299) { settingsRaw = settingsResp.second; prefs.edit().putString(detailPrefix + "settings", settingsRaw).apply() }
                if (activeResp.first in 200..299) { activeCode = activeResp.first; activeRaw = activeResp.second; prefs.edit().putString(detailPrefix + "active", activeRaw).apply() }
                if (progResp.first in 200..299) { progCode = progResp.first; progRaw = progResp.second; prefs.edit().putString(detailPrefix + "programs", progRaw).apply() }
            }
            val active = if (activeCode in 200..299) runCatching { JSONObject(activeRaw).optJSONObject("data")?.optString("key").orEmpty() }.getOrDefault("") else ""''')
        # mark detail cache only after helper has processed list
        block = block.replace('''        return out
    }
''', '''        if (allowDetailRefresh) prefs.edit().putLong("homeconnect_details_cache_at", System.currentTimeMillis()).apply()
        return out
    }
''', 1)
        s = s[:start] + block + s[end:]

p.write_text(s)
print('Persistent room mapping + Home Connect 429 throttling applied')
