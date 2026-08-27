from pathlib import Path
import re

p=Path('mobile/src/main/java/com/jarvis/mobile/PhoneAgentController.kt')
s=p.read_text()

# Keep a short execution trace per task so the planner can see what it already did.
if 'private val recentAgentActions' not in s:
    anchor='    @Volatile private var cancelled = false\n'
    if anchor not in s: raise SystemExit('cancelled field anchor not found')
    s=s.replace(anchor, anchor+'    private val recentAgentActions = mutableListOf<String>()\n    private var lastUiSignature: String = ""\n    private var unchangedUiCount: Int = 0\n',1)

# Reset trace on every new user task.
if 'recentAgentActions.clear()' not in s:
    m=re.search(r'(\s*cancelled\s*=\s*false\s*\n)(\s*val\s+resolved\s*=\s*resolveTask\(task\))', s)
    if m:
        repl=m.group(1)+'        recentAgentActions.clear()\n        lastUiSignature = ""\n        unchangedUiCount = 0\n'+m.group(2)
        s=s[:m.start()]+repl+s[m.end():]
    else:
        print('warning: run reset anchor not found; continuing without explicit reset injection')

marker='    private fun loop(task: String, startStep: Int, onUpdate: (String) -> Unit, onDone: (String) -> Unit) {'
if 'private fun uiSignature(' not in s:
    helpers=r'''    private fun uiSignature(ui: JSONArray, pkg: String): String {
        val b=StringBuilder(pkg)
        for(i in 0 until minOf(ui.length(),80)) {
            val o=ui.optJSONObject(i)?:continue
            b.append('|').append(o.optString("text")).append('|').append(o.optString("hint")).append('|').append(o.optString("viewId"))
        }
        return b.toString().hashCode().toString()
    }

    private fun actionFingerprint(a: JSONObject): String = listOf(
        a.optString("action"), a.optString("app"), a.optString("text"), a.optString("target"), a.optString("direction")
    ).joinToString("|").lowercase()

    private fun rememberAction(a: JSONObject) {
        val fp=actionFingerprint(a)
        if(fp.isNotBlank()) {
            recentAgentActions += fp
            while(recentAgentActions.size>10) recentAgentActions.removeAt(0)
        }
    }

    private fun repeatedWithoutProgress(a: JSONObject): Boolean {
        val fp=actionFingerprint(a)
        if(fp.isBlank()) return false
        val repeats=recentAgentActions.takeLast(4).count{it==fp}
        return unchangedUiCount>=2 && repeats>=2
    }

'''
    if marker not in s: raise SystemExit('loop marker not found')
    s=s.replace(marker,helpers+marker,1)

# Instrument whichever planner-call spelling exists after the Makro/clarification patches.
if 'val sig = uiSignature(ui, pkg)' not in s:
    planner=re.search(r'(?m)^(\s*)(?:val|var)\s+action\s*=\s*plan\(task\s*,\s*ui\s*,\s*pkg\s*,\s*step\s*\)\s*$', s)
    if planner:
        indent=planner.group(1)
        original=planner.group(0)
        tracking=(
            f'{indent}val sig = uiSignature(ui, pkg)\n'
            f'{indent}if (sig == lastUiSignature) unchangedUiCount++ else unchangedUiCount = 0\n'
            f'{indent}lastUiSignature = sig\n'
            f'{original}\n'
            f'{indent}if (repeatedWithoutProgress(action)) {{\n'
            f'{indent}    val label=action.optString("text").ifBlank{{action.optString("action")}}\n'
            f'{indent}    return finish(onDone, "No voy a repetir $label otra vez porque la aplicación no está avanzando. He detenido este paso para evitar duplicar productos.", false)\n'
            f'{indent}}}\n'
            f'{indent}rememberAction(action)'
        )
        s=s[:planner.start()]+tracking+s[planner.end():]
    else:
        # Current deterministic shopping flow may decide some actions locally before calling plan().
        # Do not fail the whole APK build merely because the exact planner assignment moved.
        print('warning: planner assignment moved; state trace injection skipped for this build')

# Send recent action history to backend planner when the request body exists.
if 'put("recentActions",JSONArray(recentAgentActions))' not in s:
    body=re.search(r'val\s+body\s*=\s*JSONObject\(\)([^\n]+)', s)
    if body and '.put("preferredProvider",preferred)' in body.group(0):
        old=body.group(0)
        new=old.replace('.put("preferredProvider",preferred)', '.put("preferredProvider",preferred).put("recentActions",JSONArray(recentAgentActions))')
        s=s[:body.start()]+new+s[body.end():]
    else:
        print('warning: plan request body anchor moved; recentActions backend field skipped')

p.write_text(s)
print('Stateful shopping patch applied compatibly')
