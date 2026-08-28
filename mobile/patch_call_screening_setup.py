from pathlib import Path
import re

p = Path('mobile/src/main/java/com/jarvis/mobile/MainActivity.kt')
s = p.read_text()

if 'import android.app.role.RoleManager' not in s:
    s = s.replace('import android.Manifest\n', 'import android.Manifest\nimport android.app.role.RoleManager\n')
if 'import android.os.Build' not in s:
    s = s.replace('import android.os.Bundle\n', 'import android.os.Build\nimport android.os.Bundle\n')

anchor = '        warmLocation()\n'
call = '        maybeRequestCallScreeningRole()\n        ensurePhoneBridgeForCalls()\n'
if 'maybeRequestCallScreeningRole()' not in s:
    if anchor not in s:
        raise SystemExit('MainActivity warmLocation anchor not found')
    s = s.replace(anchor, anchor + call, 1)

marker = '    private fun dp(v: Int)'
methods = r'''    private fun maybeRequestCallScreeningRole() {
        if (Build.VERSION.SDK_INT < 29) return
        val rm = getSystemService(RoleManager::class.java) ?: return
        if (!rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) || rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)) return
        val now = System.currentTimeMillis()
        val last = prefs.getLong("call_screening_role_last_prompt", 0L)
        if (now - last < 6L * 60L * 60L * 1000L) return
        prefs.edit().putLong("call_screening_role_last_prompt", now).apply()
        window.decorView.postDelayed({
            AlertDialog.Builder(this)
                .setTitle("Activar llamadas Jarvis")
                .setMessage("Para que Jarvis detecte una llamada entrante, muestre el contacto/spam y permita responder también desde Jarvis TV, Android debe seleccionar Jarvis como app de identificación y cribado de llamadas.")
                .setPositiveButton("ACTIVAR") { _, _ ->
                    runCatching { startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING), 5401) }
                }
                .setNegativeButton("Más tarde", null)
                .show()
        }, 650L)
    }

    private fun ensurePhoneBridgeForCalls() {
        val screening = if (Build.VERSION.SDK_INT >= 29) runCatching {
            getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
        }.getOrDefault(false) else false
        if (screening || prefs.getBoolean("remote_control_enabled", false)) {
            runCatching { ContextCompat.startForegroundService(this, Intent(this, PhoneBridgeService::class.java)) }
        }
    }

'''
if 'private fun maybeRequestCallScreeningRole()' not in s:
    if marker not in s:
        raise SystemExit('MainActivity dp marker not found')
    s = s.replace(marker, methods + marker, 1)

profile = '''        if (requestCode == REQ_PROFILE_IMAGE && resultCode == RESULT_OK) {'''
role_block = '''        if (requestCode == 5401) {
            if (Build.VERSION.SDK_INT >= 29) {
                val held = runCatching { getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_CALL_SCREENING) }.getOrDefault(false)
                if (held) {
                    prefs.edit().remove("call_screening_role_last_prompt").apply()
                    runCatching { ContextCompat.startForegroundService(this, Intent(this, PhoneBridgeService::class.java)) }
                    Toast.makeText(this, "Jarvis ya identifica llamadas y las comparte con Jarvis TV", Toast.LENGTH_LONG).show()
                } else Toast.makeText(this, "El identificador de llamadas Jarvis sigue desactivado", Toast.LENGTH_LONG).show()
            }
        }
'''
if 'requestCode == 5401' not in s:
    if profile not in s:
        raise SystemExit('MainActivity onActivityResult anchor not found')
    s = s.replace(profile, role_block + profile, 1)

start = s.find('    private fun showConnections()')
if start >= 0:
    brace = s.find('{', start)
    if brace >= 0:
        depth = 0; end = -1
        for i in range(brace, len(s)):
            if s[i] == '{': depth += 1
            elif s[i] == '}':
                depth -= 1
                if depth == 0:
                    end = i + 1; break
        if end > 0:
            final_dialog = '''    private fun showConnections() {
        val items = arrayOf("Homey Cloud", "Home Connect", "Google Home", "LG ThinQ", "Otros MCP")
        AlertDialog.Builder(this)
            .setTitle("Conectores")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> runCatching { startActivity(Intent(this, HomeyActivity::class.java)) }
                    1 -> runCatching { showUnifiedDomoticsWidget() }
                    2 -> runCatching { startActivity(Intent(this, GoogleHomeActivity::class.java)) }
                    3 -> runCatching { startActivity(Intent(this, LgThinQActivity::class.java)) }
                    4 -> runCatching { showMcpSettings() }
                }
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }'''
            s = s[:start] + final_dialog + s[end:]

p.write_text(s)

d = Path('mobile/src/main/java/com/jarvis/mobile/DeviceHubActivity.kt')
t = d.read_text()
needle = '        add("Activar Jarvis para identificar llamadas") { requestCallScreeningRole() }\n'
extra = needle + '''        add("Permitir aviso de llamada a pantalla completa") {
            if (Build.VERSION.SDK_INT >= 34) {
                runCatching { startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName"))) }
                    .onFailure { startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)) }
            } else Toast.makeText(this, "En esta versión de Android no hace falta este permiso adicional.", Toast.LENGTH_LONG).show()
        }
'''
if 'Permitir aviso de llamada a pantalla completa' not in t and needle in t:
    t = t.replace(needle, extra, 1)

# When the phone/SMS permission request completes, immediately continue with the
# Android call-screening role. Previously users could grant every permission and
# Jarvis still never received onScreenCall because the role remained unassigned.
old_perm = '''override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) { super.onRequestPermissionsResult(requestCode, permissions, grantResults); refreshStatus() }'''
new_perm = '''override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshStatus()
        if (requestCode == 50 && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            requestCallScreeningRole()
        }
    }'''
if old_perm in t:
    t = t.replace(old_perm, new_perm, 1)

old = 'if(requestCode==54){ Toast.makeText(this, if(resultCode==RESULT_OK) "Jarvis ya puede identificar llamadas entrantes" else "No se activó el identificador de llamadas", Toast.LENGTH_LONG).show(); refreshStatus() }'
new = '''if(requestCode==54){
            if(resultCode==RESULT_OK) {
                getSharedPreferences("jarvis_mobile",MODE_PRIVATE).edit().remove("call_screening_role_last_prompt").apply()
                runCatching { ContextCompat.startForegroundService(this, Intent(this, PhoneBridgeService::class.java)) }
                AlertDialog.Builder(this)
                    .setTitle("Llamadas de WhatsApp, Instagram, Zoom y otras apps")
                    .setMessage("Para que Jarvis también detecte y permita contestar/rechazar llamadas de aplicaciones, activa Jarvis en Acceso a notificaciones.")
                    .setPositiveButton("ACTIVAR") { _, _ -> openNotificationListenerSettings() }
                    .setNegativeButton("Más tarde", null).show()
            }
            Toast.makeText(this, if(resultCode==RESULT_OK) "Jarvis ya puede identificar llamadas entrantes" else "No se activó el identificador de llamadas", Toast.LENGTH_LONG).show()
            refreshStatus()
        }'''
t = t.replace(old, new)
d.write_text(t)
print('Persistent call-screening activation + immediate TV bridge + VoIP setup applied')
