from pathlib import Path

# PhoneBridgeService: authenticated remote task endpoint + one-time 4-digit pairing PIN.
p = Path('mobile/src/main/java/com/jarvis/mobile/PhoneBridgeService.kt')
s = p.read_text()

# Parse HTTP headers so Authorization: Bearer can be used.
old = '''                        val first = reader.readLine().orEmpty()
                        while (reader.readLine()?.isNotEmpty() == true) {}
                        val path = first.split(" ").getOrNull(1).orEmpty()
                        val response = handle(path)'''
new = '''                        val first = reader.readLine().orEmpty()
                        val headers = mutableMapOf<String, String>()
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            val colon = line.indexOf(':')
                            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
                        }
                        val path = first.split(" ").getOrNull(1).orEmpty()
                        val response = handle(path, headers)'''
if old in s:
    s = s.replace(old, new)

s = s.replace('private fun handle(path: String): Pair<Int,String> {', 'private fun handle(path: String, headers: Map<String,String> = emptyMap()): Pair<Int,String> {')

marker = '        if (path.startsWith("/ping")) return 200 to JSONObject().put("ok", true).put("device", "jarvis-phone").toString()\n'
pairing = '''        if (path.startsWith("/pair?")) {
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
            // The short PIN is only for pairing. The TV receives the strong token once and stores it internally.
            prefs.edit().putBoolean("pairing_enabled", false).apply()
            return 200 to JSONObject().put("ok", true).put("paired", true).put("token", token).toString()
        }
'''
if '/pair?' not in s and marker in s:
    s = s.replace(marker, marker + pairing, 1)

remote = '''        if (path.startsWith("/remote?")) {
            val prefs = getSharedPreferences("jarvis_mobile", MODE_PRIVATE)
            val expected = prefs.getString("remote_token", "").orEmpty()
            val bearer = headers["authorization"].orEmpty().removePrefix("Bearer ").trim()
            val queryToken = URLDecoder.decode(path.substringAfter("token=", "").substringBefore("&"), StandardCharsets.UTF_8.name())
            val supplied = if (bearer.isNotBlank()) bearer else queryToken
            if (expected.isBlank() || supplied != expected) return 401 to JSONObject().put("error", "unauthorized").toString()
            if (!prefs.getBoolean("remote_control_enabled", false)) return 403 to JSONObject().put("error", "remote-disabled").toString()
            val rawTask = path.substringAfter("task=", "").substringBefore("&")
            val task = URLDecoder.decode(rawTask, StandardCharsets.UTF_8.name()).trim()
            if (task.isBlank()) return 400 to JSONObject().put("error", "task-required").toString()
            prefs.edit().putString("pending_remote_task", task).apply()
            val i = Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("remote_task", task)
            startActivity(i)
            return 200 to JSONObject().put("ok", true).put("status", "accepted").put("task", task).toString()
        }
'''
if '/remote?' not in s and marker in s:
    # Keep /pair immediately after /ping and then /remote.
    pair_end = pairing if '/pair?' not in s else ''
    if pair_end:
        pass
    # Find the end of the pair block if it was already inserted above; otherwise use marker.
    insert_after = '            return 200 to JSONObject().put("ok", true).put("paired", true).put("token", token).toString()\n        }\n'
    if insert_after in s:
        s = s.replace(insert_after, insert_after + remote, 1)
    else:
        s = s.replace(marker, marker + remote, 1)

# Make bridge notification describe remote mode too.
s = s.replace('"Llamadas, SMS y notificaciones para Jarvis TV"', '"Remote, llamadas, mensajes y control de aplicaciones"')
p.write_text(s)

# MainActivity: run a remote task when launched by the bridge.
p = Path('mobile/src/main/java/com/jarvis/mobile/MainActivity.kt')
s = p.read_text()
field = '    private val prefs by lazy { getSharedPreferences("jarvis_mobile", MODE_PRIVATE) }\n'
if 'private val remotePhoneAgent by lazy' not in s:
    s = s.replace(field, field + '    private val remotePhoneAgent by lazy { PhoneAgentController(this) }\n')

setup_anchor = '        warmLocation()\n'
setup = '''        warmLocation()
        intent?.getStringExtra("remote_task")?.takeIf { it.isNotBlank() }?.let { task ->
            status.text = "Remote · ejecutando…"
            remotePhoneAgent.run(task,
                onUpdate = { msg -> runOnUiThread { status.text = "Remote · $msg" } },
                onDone = { msg -> runOnUiThread { status.text = "Jarvis listo"; renderMessageCard("assistant", "Remote · $msg") } }
            )
            intent?.removeExtra("remote_task")
        }
'''
if 'Remote · ejecutando' not in s and setup_anchor in s:
    s = s.replace(setup_anchor, setup, 1)

# Also accept a remote task while MainActivity is already open.
if 'override fun onNewIntent(intent: Intent)' not in s:
    companion = '    companion object {'
    method = '''    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra("remote_task")?.takeIf { it.isNotBlank() }?.let { task ->
            status.text = "Remote · ejecutando…"
            remotePhoneAgent.run(task,
                onUpdate = { msg -> runOnUiThread { status.text = "Remote · $msg" } },
                onDone = { msg -> runOnUiThread { status.text = "Jarvis listo"; renderMessageCard("assistant", "Remote · $msg") } }
            )
            intent.removeExtra("remote_task")
        }
    }

'''
    if companion in s:
        s = s.replace(companion, method + companion, 1)
p.write_text(s)

# DeviceHubActivity: the user sees ONLY local IP + 4-digit pairing PIN.
# The long strong Remote token remains internal and is transferred automatically to the TV after pairing.
p = Path('mobile/src/main/java/com/jarvis/mobile/DeviceHubActivity.kt')
s = p.read_text()
if 'import java.util.UUID' not in s:
    s = s.replace('import java.net.NetworkInterface\n', 'import java.net.NetworkInterface\nimport java.util.UUID\n')

anchor = '        add("Activar puente con Jarvis TV") {\n'
buttons = '''        add("Activar control Remote de Jarvis") {
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
            Toast.makeText(this, "Nuevo PIN de emparejamiento: $pin", Toast.LENGTH_LONG).show()
        }
        add("Desactivar control Remote") {
            getSharedPreferences("jarvis_mobile", MODE_PRIVATE).edit()
                .putBoolean("remote_control_enabled", false)
                .putBoolean("pairing_enabled", false)
                .apply()
            Toast.makeText(this, "Remote desactivado", Toast.LENGTH_SHORT).show()
        }

'''

# If an old token-based Remote block is already in source, replace the whole section.
start = s.find('        add("Activar control Remote de Jarvis") {')
end = s.find(anchor, start if start >= 0 else 0)
if start >= 0 and end > start:
    s = s[:start] + buttons + s[end:]
elif 'Mostrar / permitir PIN de emparejamiento' not in s and anchor in s:
    s = s.replace(anchor, buttons + anchor, 1)

# Status includes only Remote state + PIN, never the long token.
needle = 'val listenerConnected = prefs.getBoolean("notification_listener_connected", false)\n'
if 'val pairingPin =' not in s and needle in s:
    s = s.replace(needle, needle + '        val pairingPin = prefs.getString("pairing_pin", "----").orEmpty().ifBlank { "----" }\n', 1)
old_status = 'permissionStatus.text = "Contactos ${if (granted(Manifest.permission.READ_CONTACTS)) "✓" else "✗"}   Teléfono ${if (granted(Manifest.permission.CALL_PHONE)) "✓" else "✗"}\\nSMS lectura ${if (granted(Manifest.permission.READ_SMS)) "✓" else "✗"}   SMS envío ${if (granted(Manifest.permission.SEND_SMS)) "✓" else "✗"}\\nWhatsApp/RCS permiso ${if (notificationAccessGranted()) "✓" else "✗"} · lector ${if (listenerConnected) "CONECTADO" else "NO CONECTADO"}"'
new_status = 'permissionStatus.text = "Contactos ${if (granted(Manifest.permission.READ_CONTACTS)) "✓" else "✗"}   Teléfono ${if (granted(Manifest.permission.CALL_PHONE)) "✓" else "✗"}\\nSMS lectura ${if (granted(Manifest.permission.READ_SMS)) "✓" else "✗"}   SMS envío ${if (granted(Manifest.permission.SEND_SMS)) "✓" else "✗"}\\nWhatsApp/RCS permiso ${if (notificationAccessGranted()) "✓" else "✗"} · lector ${if (listenerConnected) "CONECTADO" else "NO CONECTADO"}\\nRemote ${if (prefs.getBoolean("remote_control_enabled", false)) "ACTIVO" else "DESACTIVADO"} · PIN $pairingPin"'
if old_status in s:
    s = s.replace(old_status, new_status)
else:
    # Upgrade a previously generated Remote status that did not include the PIN.
    s = s.replace('Remote ${if (prefs.getBoolean("remote_control_enabled", false)) "ACTIVO" else "DESACTIVADO"}"', 'Remote ${if (prefs.getBoolean("remote_control_enabled", false)) "ACTIVO" else "DESACTIVADO"} · PIN $pairingPin"')

p.write_text(s)

print('Authenticated Jarvis Remote + native 4-digit pairing PIN applied')
