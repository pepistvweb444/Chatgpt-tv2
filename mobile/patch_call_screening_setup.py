from pathlib import Path
import re

p = Path('mobile/src/main/java/com/jarvis/mobile/MainActivity.kt')
s = p.read_text()

# Required imports for the official Android call-screening role.
if 'import android.app.role.RoleManager' not in s:
    s = s.replace('import android.Manifest\n', 'import android.Manifest\nimport android.app.role.RoleManager\n')
if 'import android.os.Build' not in s:
    s = s.replace('import android.os.Bundle\n', 'import android.os.Build\nimport android.os.Bundle\n')

# Add a one-time official role request after the activity is fully initialized.
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
        val prompted = prefs.getBoolean("call_screening_role_prompted", false)
        if (prompted) return
        prefs.edit().putBoolean("call_screening_role_prompted", true).apply()
        window.decorView.postDelayed({
            runCatching { startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING), 5401) }
        }, 700L)
    }

    private fun ensurePhoneBridgeForCalls() {
        // Once the user paired/activated remote control, keep the phone bridge alive
        // so Jarvis TV can receive incoming-call cards even when this activity is not open.
        if (prefs.getBoolean("remote_control_enabled", false)) {
            runCatching { ContextCompat.startForegroundService(this, Intent(this, PhoneBridgeService::class.java)) }
        }
    }

'''
if 'private fun maybeRequestCallScreeningRole()' not in s:
    if marker not in s:
        raise SystemExit('MainActivity dp marker not found')
    s = s.replace(marker, methods + marker, 1)

# Final connector dialog. This runs at the end of the current patch chain so
# LG ThinQ cannot disappear because an earlier MCP/settings patch rewrote it.
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
print('Call screening role + TV bridge + final LG ThinQ connectors dialog applied')
