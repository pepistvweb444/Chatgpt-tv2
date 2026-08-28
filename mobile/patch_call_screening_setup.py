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

p.write_text(s)
print('Call screening role prompt + persistent TV call bridge applied')
