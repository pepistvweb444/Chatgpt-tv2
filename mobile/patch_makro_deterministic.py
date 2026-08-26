from pathlib import Path
p=Path('mobile/src/main/java/com/jarvis/mobile/PhoneAgentController.kt')
s=p.read_text()

# Ensure Makro is always considered an app/shopping target in the generated controller.
s=s.replace('''"booking","thefork","restaurante","hotel","vuelo")''','''"booking","thefork","restaurante","hotel","vuelo","makro","metro markets")''')
s=s.replace('''"glovo", "amazon", "aliexpress", "dia")''','''"glovo", "amazon", "aliexpress", "dia", "makro", "metro markets")''')
s=s.replace('''while (!cancelled && step < 25) {''','''val maxSteps = if (looksLikeShoppingTask(task)) 55 else 25
        while (!cancelled && step < maxSteps) {''')

# Add helpers before loop.
marker='''    private fun loop(task: String, startStep: Int, onUpdate: (String) -> Unit, onDone: (String) -> Unit) {'''
helpers=r'''    private fun makroProductFromTask(task: String): String {
        val clean = task.replace(Regex("(?i)\\b(en|desde|por)\\s+(la\\s+app\\s+de\\s+)?makro\\b.*$"), "")
        val candidates = listOf("compra", "comprar", "añade", "anade", "agrega", "busca", "buscar")
        var out = clean
        candidates.forEach { verb -> out = out.replace(Regex("(?i)^.*?\\b$verb\\b\\s*"), "") }
        return out.trim().trim(',', '.', ':', ';').take(180)
    }

    private fun uiHasEditableSearch(ui: JSONArray): Boolean {
        for (i in 0 until ui.length()) {
            val o=ui.optJSONObject(i)?:continue
            if(!o.optBoolean("editable",false)) continue
            val hay=(o.optString("text")+" "+o.optString("hint")+" "+o.optString("viewId")).lowercase()
            if(hay.contains("producto")||hay.contains("buscar")||hay.contains("search")||hay.isBlank()) return true
        }
        return false
    }

    private fun uiContainsProduct(ui: JSONArray, product: String): Boolean {
        val p=canonical(product)
        if(p.isBlank()) return false
        val raw=ui.toString().lowercase()
        return raw.contains(p) || p.split(' ').filter{it.length>2}.count{raw.contains(it)} >= 2
    }

'''
if 'private fun makroProductFromTask' not in s:
    if marker not in s: raise SystemExit('loop marker not found')
    s=s.replace(marker,helpers+marker,1)

# Before asking the LLM, directly fill Makro's visible search box once.
needle='''            val pkg = prefs.getString("foreground_package", "").orEmpty()
            val action = plan(task, ui, pkg, step)'''
replacement=r'''            val pkg = prefs.getString("foreground_package", "").orEmpty()
            val taskLower = task.lowercase()
            if (taskLower.contains("makro") && step in 1..8) {
                val product = makroProductFromTask(task)
                if (product.isNotBlank() && uiHasEditableSearch(ui) && !uiContainsProduct(ui, product)) {
                    onUpdate("Makro · buscando $product…")
                    send(JarvisAccessibilityService.ACTION_SET_TEXT) {
                        putExtra("text", product)
                        putExtra("target", "Producto")
                    }
                    Thread.sleep(1100)
                    send(JarvisAccessibilityService.ACTION_CLICK_TEXT) { putExtra("text", "Buscar") }
                    Thread.sleep(1000)
                    step++
                    continue
                }
            }
            val action = plan(task, ui, pkg, step)'''
if 'Makro · buscando $product' not in s:
    if needle not in s: raise SystemExit('plan call anchor not found')
    s=s.replace(needle,replacement,1)

p.write_text(s)
print('Deterministic Makro search pre-route applied')
