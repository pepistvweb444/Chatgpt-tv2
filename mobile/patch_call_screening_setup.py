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
        // Re-prompt once for this release even if an older build was dismissed. The
        // Android role consent is mandatory; permissions alone do not deliver calls.
        val key = "call_screening_role_prompted_0216"
        if (prefs.getBoolean(key, false)) return
        prefs.edit().putBoolean(key, true).apply()
        window.decorView.postDelayed({
            AlertDialog.Builder(this)
                .setTitle("Activar llamadas Jarvis")
                .setMessage("Para identificar una llamada entrante, mostrar el contacto/spam y permitir responder también desde Jarvis TV, Android debe autorizar a Jarvis como app de identificación y cribado de llamadas.")
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

# Keep connector dialog final and deterministic.
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

# DeviceHub: explicit recovery buttons for both mandatory role and Android 14+
# full-screen call notification permission.
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
# When the role is granted, keep the TV bridge alive immediately.
old = 'if(requestCode==54){ Toast.makeText(this, if(resultCode==RESULT_OK) "Jarvis ya puede identificar llamadas entrantes" else "No se activó el identificador de llamadas", Toast.LENGTH_LONG).show(); refreshStatus() }'
new = 'if(requestCode==54){ if(resultCode==RESULT_OK) runCatching { ContextCompat.startForegroundService(this, Intent(this, PhoneBridgeService::class.java)) }; Toast.makeText(this, if(resultCode==RESULT_OK) "Jarvis ya puede identificar llamadas entrantes" else "No se activó el identificador de llamadas", Toast.LENGTH_LONG).show(); refreshStatus() }'
t = t.replace(old, new)
d.write_text(t)
print('Reliable call-screening activation + full-screen call alerts + TV bridge applied')
