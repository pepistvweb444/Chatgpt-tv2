from pathlib import Path
import re

p=Path('mobile/src/main/java/com/jarvis/mobile/PhoneAgentController.kt')
s=p.read_text()

replacement='''                "done" -> {
                    val msg = action.optString("message").ifBlank { "Tarea completada." }
                    if (looksLikeShoppingTask(task) && !hasCartEvidence(ui)) {
                        onUpdate("Todavía no puedo confirmar que el producto esté en el carrito; verificando…")
                        Thread.sleep(900)
                    } else return finish(onDone, msg, true)
                }'''

if 'looksLikeShoppingTask(task) && !hasCartEvidence(ui)' not in s:
    patterns = [
        r'(?m)^\s*"done"\s*->\s*return\s+finish\(onDone,\s*action\.optString\("message"\)\.ifBlank\s*\{\s*"Tarea completada\."\s*\},\s*true\)\s*$',
        r'(?m)^\s*"done"\s*->\s*\{[^\n]*finish\(onDone,[^\n]*true\)[^\n]*\}\s*$'
    ]
    changed=False
    for pat in patterns:
        s2,n=re.subn(pat,replacement,s,count=1)
        if n:
            s=s2; changed=True; break
    if not changed:
        # Fallback: inject a guard immediately before the next action case after "done".
        m=re.search(r'(?m)^\s*"done"\s*->.*$',s)
        if not m:
            raise SystemExit('done action case not found')
        line=m.group(0)
        indent=re.match(r'\s*',line).group(0)
        s=s[:m.start()]+replacement+s[m.end():]

anchor='''    private fun plan(task: String, ui: JSONArray, pkg: String, step: Int): JSONObject {'''
helpers=r'''    private fun looksLikeShoppingTask(task: String): Boolean {
        val t = task.lowercase()
        return listOf("compra", "comprar", "añade", "agrega", "carrito", "cesta", "glovo", "amazon", "aliexpress", "dia", "día").any { t.contains(it) }
    }

    private fun hasCartEvidence(ui: JSONArray): Boolean {
        val raw = ui.toString().lowercase()
        val markers = listOf(
            "carrito", "cesta", "ver cesta", "ver carrito", "mi cesta", "tu cesta",
            "subtotal", "artículo", "articulos", "artículos", "items", "productos en la cesta",
            "ir al carrito", "checkout", "pedido"
        )
        return markers.any { raw.contains(it) }
    }

'''
if 'private fun hasCartEvidence' not in s:
    if anchor not in s: raise SystemExit('plan anchor not found')
    s=s.replace(anchor,helpers+anchor,1)

p.write_text(s)
print('Phone agent cart verification guard applied safely')
