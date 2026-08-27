from pathlib import Path

# Accessibility: expose hints/view ids and use them when targeting edit fields.
p = Path('mobile/src/main/java/com/jarvis/mobile/JarvisAccessibilityService.kt')
s = p.read_text()
old = '''    private fun safeLabel(n: AccessibilityNodeInfo): String {
        if (n.isPassword) return ""
        return listOf(n.text?.toString().orEmpty(), n.contentDescription?.toString().orEmpty())
            .firstOrNull { it.isNotBlank() }.orEmpty().trim()
    }
'''
new = '''    private fun safeLabel(n: AccessibilityNodeInfo): String {
        if (n.isPassword) return ""
        return listOf(
            n.text?.toString().orEmpty(),
            n.contentDescription?.toString().orEmpty(),
            n.hintText?.toString().orEmpty(),
            n.viewIdResourceName.orEmpty().substringAfterLast('/').replace('_', ' ')
        ).firstOrNull { it.isNotBlank() }.orEmpty().trim()
    }
'''
if old in s:
    s = s.replace(old, new, 1)

old_snapshot = '''                .put("text", label.take(240))
                .put("class", n.className?.toString().orEmpty())
                .put("clickable", n.isClickable)
                .put("editable", n.isEditable)'''
new_snapshot = '''                .put("text", label.take(240))
                .put("hint", n.hintText?.toString().orEmpty().take(160))
                .put("viewId", n.viewIdResourceName.orEmpty().take(180))
                .put("class", n.className?.toString().orEmpty())
                .put("clickable", n.isClickable)
                .put("editable", n.isEditable)'''
if old_snapshot in s:
    s = s.replace(old_snapshot, new_snapshot, 1)

old_set = '''        val node = if (target.isBlank()) nodes.firstOrNull() else nodes.firstOrNull { safeLabel(it).contains(target, true) } ?: nodes.firstOrNull()'''
new_set = '''        val wanted = target.trim()
        val node = if (wanted.isBlank()) nodes.firstOrNull() else nodes.firstOrNull {
            val hay = listOf(
                safeLabel(it),
                it.hintText?.toString().orEmpty(),
                it.viewIdResourceName.orEmpty().substringAfterLast('/').replace('_', ' ')
            ).joinToString(" ")
            hay.contains(wanted, true) || wanted.contains(hay.trim(), true)
        } ?: nodes.firstOrNull()'''
if old_set in s:
    s = s.replace(old_set, new_set, 1)
p.write_text(s)

# Controller: explicitly route Makro, allow longer shopping flows, and prefill Product search.
p = Path('mobile/src/main/java/com/jarvis/mobile/PhoneAgentController.kt')
s = p.read_text()
s = s.replace('''"booking","thefork","restaurante","hotel","vuelo")''', '''"booking","thefork","restaurante","hotel","vuelo","makro","metro markets")''')
s = s.replace('''"glovo", "amazon", "aliexpress", "dia")''', '''"glovo", "amazon", "aliexpress", "dia", "makro", "metro markets")''')
s = s.replace('''while (!cancelled && step < 25) {''', '''val maxSteps = if (looksLikeShoppingTask(task)) 55 else 25
        while (!cancelled && step < maxSteps) {''')
s = s.replace('''"aliexpress" to listOf("com.alibaba.aliexpresshd"),"booking" to listOf("com.booking"))''', '''"aliexpress" to listOf("com.alibaba.aliexpresshd"),"booking" to listOf("com.booking"),"makro" to emptyList())''')

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

needle='''            val pkg = prefs.getString("foreground_package", "").orEmpty()
            val action = plan(task, ui, pkg, step)'''
replacement=r'''            val pkg = prefs.getString("foreground_package", "").orEmpty()
            val taskLower = task.lowercase()
            if (taskLower.contains("makro") && step in 1..10) {
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

# Apply the stricter state machine that submits the search, selects the result and then Add to cart.
exec(Path('mobile/patch_makro_deterministic.py').read_text(), {'__name__':'__main__'})
print('Makro deterministic search targeting, result selection and cart flow applied')
