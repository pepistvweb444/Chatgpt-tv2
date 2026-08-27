from pathlib import Path
import re

p=Path('mobile/src/main/java/com/jarvis/mobile/PhoneAgentController.kt')
s=p.read_text()

# Keep a short execution trace per task so the planner can see what it already did.
if 'private val recentAgentActions' not in s:
    anchor='    @Volatile private var cancelled = false\n'
    if anchor not in s: raise SystemExit('cancelled field anchor not found')
    s=s.replace(anchor, anchor+'    private val recentAgentActions = mutableListOf<String>()\n    private var lastUiSignature: String = ""\n    private var unchangedUiCount: Int = 0\n',1)

# Reset trace on every new user task. Compatible with the shopping clarification patch.
if 'recentAgentActions.clear()' not in s:
    anchor='''        cancelled = false
        val resolved = resolveTask(task)'''
    repl='''        cancelled = false
        recentAgentActions.clear()
        lastUiSignature = ""
        unchangedUiCount = 0
        val resolved = resolveTask(task)'''
    if anchor not in s: raise SystemExit('run reset anchor not found')
    s=s.replace(anchor,repl,1)

# Add helpers before loop.
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

# Deterministic Makro inserts its own state machine between pkg and plan(), so do
# not rely on those lines being adjacent. Instrument the actual planner call.
plan_line='''            val action = plan(task, ui, pkg, step)'''
tracking='''            val sig = uiSignature(ui, pkg)
            if (sig == lastUiSignature) unchangedUiCount++ else unchangedUiCount = 0
            lastUiSignature = sig
            val action = plan(task, ui, pkg, step)
            if (repeatedWithoutProgress(action)) {
                val label=action.optString("text").ifBlank{action.optString("action")}
                return finish(onDone, "No voy a repetir $label otra vez porque la aplicación no está avanzando. He detenido este paso para evitar duplicar productos.", false)
            }
            rememberAction(action)'''
if 'val sig = uiSignature(ui, pkg)' not in s:
    if plan_line not in s: raise SystemExit('planner call not found')
    s=s.replace(plan_line,tracking,1)

# Send recent action history to backend planner.
old='''val body=JSONObject().put("task",task).put("ui",ui).put("packageName",pkg).put("step",step).put("preferredProvider",preferred)'''
new='''val body=JSONObject().put("task",task).put("ui",ui).put("packageName",pkg).put("step",step).put("preferredProvider",preferred).put("recentActions",JSONArray(recentAgentActions))'''
if 'put("recentActions",JSONArray(recentAgentActions))' not in s:
    if old not in s: raise SystemExit('plan request body anchor not found')
    s=s.replace(old,new,1)

p.write_text(s)
print('Stateful shopping execution trace and duplicate-add protection applied')
