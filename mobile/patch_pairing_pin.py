from pathlib import Path

# Add a short-lived/easy 4-digit pairing PIN on top of the existing long Remote token.
p = Path('mobile/src/main/java/com/jarvis/mobile/PhoneBridgeService.kt')
s = p.read_text()

marker = '        if (path.startsWith("/ping")) return 200 to JSONObject().put("ok", true).put("device", "jarvis-phone").toString()\n'
pair_block = r'''        if (path.startsWith("/pair?")) {
            val prefs = getSharedPreferences("jarvis_mobile", MODE_PRIVATE)
            if (!prefs.getBoolean("remote_control_enabled", false)) return 403 to JSONObject().put("error", "remote-disabled").toString()
            if (!prefs.getBoolean("pairing_enabled", false)) return 403 to JSONObject().put("error", "pairing-disabled").toString()
            val suppliedPin = URLDecoder.decode(path.substringAfter("pin=", "").substringBefore("&"), StandardCharsets.UTF_8.name()).trim()
            val expectedPin = prefs.getString("pairing_pin", "").orEmpty()
            if (expectedPin.length != 4 || suppliedPin != expectedPin) return 401 to JSONObject().put("error", "pin-invalid").toString()
            var token = prefs.getString("remote_token", "").orEmpty()
            if (token.isBlank()) {
                token = java.util.UUID.randomUUID().toString().replace("-", "")
                prefs.edit().putString("remote_token", token).apply()
            }
            prefs.edit().putBoolean("pairing_enabled", false).apply()
            return 200 to JSONObject().put("ok", true).put("paired", true).put("token", token).toString()
        }
'''
if '/pair?' not in s:
    if marker not in s:
        raise SystemExit('ping marker not found in PhoneBridgeService')
    s = s.replace(marker, marker + pair_block, 1)
p.write_text(s)

# Replace the visible long-token workflow with a four-digit PIN workflow.
p = Path('mobile/src/main/java/com/jarvis/mobile/DeviceHubActivity.kt')
s = p.read_text()

old_activate = r'''        add("Activar control Remote de Jarvis") {
            val prefs = getSharedPreferences("jarvis_mobile", MODE_PRIVATE)
            var token = prefs.getString("remote_token", "").orEmpty()
            if (token.isBlank()) token = UUID.randomUUID().toString().replace("-", "")
            prefs.edit().putString("remote_token", token).putBoolean("remote_control_enabled", true).apply()
            runCatching { ContextCompat.startForegroundService(this, Intent(this, PhoneBridgeService::class.java)) }
            Toast.makeText(this, "Remote activado. Usa una VPN privada para acceder desde fuera de tu Wi‑Fi.\nIP: ${localIp()}:${PhoneBridgeService.PORT}\nToken: $token", Toast.LENGTH_LONG).show()
        }
        add("Desactivar control Remote") {
            getSharedPreferences("jarvis_mobile", MODE_PRIVATE).edit().putBoolean("remote_control_enabled", false).apply()
            Toast.makeText(this, "Remote desactivado", Toast.LENGTH_SHORT).show()
        }
        add("Regenerar token Remote") {
            val token = UUID.randomUUID().toString().replace("-", "")
            getSharedPreferences("jarvis_mobile", MODE_PRIVATE).edit().putString("remote_token", token).apply()
            Toast.makeText(this, "Nuevo token Remote: $token", Toast.LENGTH_LONG).show()
        }
'''
new_activate = r'''        add("Activar control Remote de Jarvis") {
            val prefs = getSharedPreferences("jarvis_mobile", MODE_PRIVATE)
            var token = prefs.getString("remote_token", "").orEmpty()
            if (token.isBlank()) token = UUID.randomUUID().toString().replace("-", "")
            var pin = prefs.getString("pairing_pin", "").orEmpty()
            if (pin.length != 4) pin = (1000..9999).random().toString()
            prefs.edit()
                .putString("remote_token", token)
                .putString("pairing_pin", pin)
                .putBoolean("pairing_enabled", true)
                .putBoolean("remote_control_enabled", true)
                .apply()
            runCatching { ContextCompat.startForegroundService(this, Intent(this, PhoneBridgeService::class.java)) }
            Toast.makeText(this, "Remote activado.\nIP del móvil: ${localIp()}\nPIN para la TV: $pin\n\nEn la misma Wi‑Fi no necesitas VPN.", Toast.LENGTH_LONG).show()
        }
        add("Mostrar / permitir PIN de emparejamiento") {
            val prefs = getSharedPreferences("jarvis_mobile", MODE_PRIVATE)
            var pin = prefs.getString("pairing_pin", "").orEmpty()
            if (pin.length != 4) pin = (1000..9999).random().toString()
            prefs.edit().putString("pairing_pin", pin).putBoolean("pairing_enabled", true).apply()
            Toast.makeText(this, "IP del móvil: ${localIp()}\nPIN de emparejamiento: $pin", Toast.LENGTH_LONG).show()
        }
        add("Cambiar PIN de emparejamiento") {
            val pin = (1000..9999).random().toString()
            getSharedPreferences("jarvis_mobile", MODE_PRIVATE).edit()
                .putString("pairing_pin", pin)
                .putBoolean("pairing_enabled", true)
                .apply()
            Toast.makeText(this, "Nuevo PIN: $pin", Toast.LENGTH_LONG).show()
        }
        add("Desactivar control Remote") {
            getSharedPreferences("jarvis_mobile", MODE_PRIVATE).edit()
                .putBoolean("remote_control_enabled", false)
                .putBoolean("pairing_enabled", false)
                .apply()
            Toast.makeText(this, "Remote desactivado", Toast.LENGTH_SHORT).show()
        }
'''
if old_activate in s:
    s = s.replace(old_activate, new_activate, 1)
elif 'Mostrar / permitir PIN de emparejamiento' not in s:
    raise SystemExit('Remote button block not found in DeviceHubActivity')

# Add the current PIN to the status panel without exposing the long token.
needle = 'val listenerConnected = prefs.getBoolean("notification_listener_connected", false)\n'
if 'val pairingPin =' not in s and needle in s:
    s = s.replace(needle, needle + '        val pairingPin = prefs.getString("pairing_pin", "----").orEmpty().ifBlank { "----" }\n', 1)
old_tail = 'Remote ${if (prefs.getBoolean("remote_control_enabled", false)) "ACTIVO" else "DESACTIVADO"}"'
new_tail = 'Remote ${if (prefs.getBoolean("remote_control_enabled", false)) "ACTIVO" else "DESACTIVADO"} · PIN $pairingPin"'
if old_tail in s:
    s = s.replace(old_tail, new_tail, 1)

p.write_text(s)
print('4-digit TV pairing PIN patch applied')
