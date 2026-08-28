from pathlib import Path
import re
p=Path('mobile/src/main/java/com/jarvis/mobile/MainActivity.kt')
s=p.read_text()
if 'import android.app.role.RoleManager' not in s:
    # add after package/import area
    m=re.search(r'(package com\.jarvis\.mobile\n)',s)
    if m: s=s[:m.end()]+'\nimport android.app.role.RoleManager\nimport android.os.Build\n'+s[m.end():]

if 'ensureJarvisCallScreeningRole()' not in s:
    # inject call after super.onCreate(...)
    m=re.search(r'super\.onCreate\([^\)]*\)',s)
    if m: s=s[:m.end()]+'\n        ensureJarvisCallScreeningRole()'+s[m.end():]
    else: print('onCreate super anchor not found; role call skipped')

if 'private fun ensureJarvisCallScreeningRole' not in s:
    # insert before final class brace
    idx=s.rfind('}')
    method=r'''
    private fun ensureJarvisCallScreeningRole() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        runCatching {
            val rm = getSystemService(RoleManager::class.java) ?: return
            if (rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) && !rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)) {
                startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING), 9107)
            }
        }
    }
'''
    if idx>=0: s=s[:idx]+method+s[idx:]

p.write_text(s)
print('Call screening role setup applied')
