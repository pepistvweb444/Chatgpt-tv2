from pathlib import Path
p=Path('mobile/src/main/java/com/jarvis/mobile/PhoneAgentController.kt')
s=p.read_text()
old='''                "done" -> return finish(onDone, action.optString("message").ifBlank { "Tarea completada." }, true)'''
new='''                "done" -> {
                    val msg = action.optString("message").ifBlank { "Tarea completada." }
                    if (looksLikeShoppingTask(task) && !hasCartEvidence(ui)) {
                        onUpdate("Todavía no puedo confirmar que el producto esté en el carrito; verificando…")
                        Thread.sleep(900)
                    } else return finish(onDone, msg, true)
                }'''
if old not in s:
    raise SystemExit('done action anchor not found')
s=s.replace(old,new,1)
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
print('Phone agent cart verification guard applied')
