from pathlib import Path

# Accessibility: add an IME-submit action for search fields. Android does not
# expose ACTION_IME_ENTER as an AccessibilityNodeInfo constant on every SDK,
# so use the platform action id directly (android.R.id.accessibilityActionImeEnter).
p=Path('mobile/src/main/java/com/jarvis/mobile/JarvisAccessibilityService.kt')
s=p.read_text()
if 'ACTION_IME_ENTER = "com.jarvis.mobile.action.IME_ENTER"' not in s:
    s=s.replace('''                ACTION_REFRESH_UI -> persistUiSnapshot()''','''                ACTION_REFRESH_UI -> persistUiSnapshot()
                ACTION_IME_ENTER -> imeEnter()''',1)
    s=s.replace('''            addAction(ACTION_SCROLL_BACKWARD); addAction(ACTION_REFRESH_UI)''','''            addAction(ACTION_SCROLL_BACKWARD); addAction(ACTION_REFRESH_UI); addAction(ACTION_IME_ENTER)''',1)
    marker='''    private fun scroll(forward: Boolean) {'''
    method='''    private fun imeEnter() {
        val node = allNodes().firstOrNull { it.isEditable && it.isEnabled && it.isFocused }
            ?: allNodes().firstOrNull { it.isEditable && it.isEnabled }
        // accessibilityActionImeEnter = 16908372. Using the integer keeps this
        // compatible with SDK stubs where ACTION_IME_ENTER is absent.
        node?.performAction(16908372)
        persistUiSnapshot()
    }

'''
    if marker not in s: raise SystemExit('scroll marker not found')
    s=s.replace(marker,method+marker,1)
    s=s.replace('''        const val ACTION_REFRESH_UI = "com.jarvis.mobile.action.REFRESH_UI"''','''        const val ACTION_REFRESH_UI = "com.jarvis.mobile.action.REFRESH_UI"
        const val ACTION_IME_ENTER = "com.jarvis.mobile.action.IME_ENTER"''',1)
# Repair an older generated form if present.
s=s.replace('node?.performAction(AccessibilityNodeInfo.ACTION_IME_ENTER)', 'node?.performAction(16908372)')
p.write_text(s)

p=Path('mobile/src/main/java/com/jarvis/mobile/PhoneAgentController.kt')
s=p.read_text()
s=s.replace('''"booking","thefork","restaurante","hotel","vuelo")''','''"booking","thefork","restaurante","hotel","vuelo","makro","metro markets")''')
s=s.replace('''"glovo", "amazon", "aliexpress", "dia")''','''"glovo", "amazon", "aliexpress", "dia", "makro", "metro markets")''')
s=s.replace('''while (!cancelled && step < 25) {''','''val maxSteps = if (looksLikeShoppingTask(task)) 60 else 25
        while (!cancelled && step < maxSteps) {''')

marker='''    private fun loop(task: String, startStep: Int, onUpdate: (String) -> Unit, onDone: (String) -> Unit) {'''
product_helper=r'''    private fun makroProductFromTask(task: String): String {
        var out = task.replace(Regex("(?i)\\b(makro|metro markets?)\\b"), " ")
        out = out.replace(Regex("(?i)\\b(abre|entra|ve|busca|buscar|compra|comprar|añade|anade|agrega|agregar|pon|mete|producto|productos)\\b"), " ")
        out = out.replace(Regex("(?i)\\b(en|desde|por|a|al|la|el|los|las|carrito|cesta|app|aplicación|aplicacion)\\b"), " ")
        return out.replace(Regex("\\s+"), " ").trim().trim(',', '.', ':', ';').take(160)
    }

'''
action_helper=r'''    private fun makroDeterministicAction(task: String, ui: JSONArray, pkg: String): JSONObject? {
        val taskLow = task.lowercase()
        val all = (0 until ui.length()).mapNotNull { ui.optJSONObject(it) }
        fun label(o: JSONObject): String = listOf(o.optString("text"), o.optString("hint"), o.optString("viewId")).joinToString(" ").trim()
        val screen = all.joinToString(" ") { label(it) }.lowercase()
        val isMakro = taskLow.contains("makro") || taskLow.contains("metro market") || pkg.lowercase().contains("makro") || screen.contains("makro")
        if (!isMakro) return null
        val product = makroProductFromTask(task)
        if (product.isBlank()) return null

        if (hasCartEvidence(ui)) return JSONObject().put("action","done").put("message","Producto confirmado en el carrito de Makro.")

        val add = all.firstOrNull { o ->
            val t=label(o).lowercase()
            o.optBoolean("clickable") && (t.contains("añadir al carrito") || t.contains("anadir al carrito") || t.contains("agregar al carrito") || t.contains("add to cart") || t=="añadir" || t=="anadir" || t=="agregar")
        }
        if (add != null) return JSONObject().put("action","click").put("text",label(add)).put("message","Makro · añadiendo al carrito")

        val tokens=canonical(product).split(' ').filter { it.length >= 3 }.take(5)
        val productResult = all.firstOrNull { o ->
            if (!o.optBoolean("clickable") || o.optBoolean("editable")) false else {
                val t=canonical(label(o))
                val hits=tokens.count { t.contains(it) }
                hits >= maxOf(1, if(tokens.size<=2) tokens.size else tokens.size-1) &&
                    !t.contains("buscar") && !t.contains("carrito") && !t.contains("cesta") && !t.contains("filtro")
            }
        }
        if (productResult != null) return JSONObject().put("action","click").put("text",label(productResult)).put("message","Makro · abriendo ${label(productResult).take(80)}")

        val editable = all.firstOrNull { it.optBoolean("editable") && it.optBoolean("enabled",true) }
        if (editable != null) {
            val current=editable.optString("text").trim()
            val currentNorm=canonical(current)
            val productNorm=canonical(product)
            val looksEmpty=current.isBlank() || current.equals("Producto",true) || current.equals("Buscar",true)
            if (looksEmpty || !currentNorm.contains(productNorm.take(10))) {
                return JSONObject().put("action","type").put("text",product).put("target",label(editable).ifBlank{"Producto"}).put("message","Makro · buscando $product")
            }
            val suggestion=all.firstOrNull { o ->
                val t=canonical(label(o)); o.optBoolean("clickable") && !o.optBoolean("editable") && tokens.isNotEmpty() && tokens.count { t.contains(it) } >= maxOf(1,tokens.size-1)
            }
            if (suggestion != null) return JSONObject().put("action","click").put("text",label(suggestion)).put("message","Makro · seleccionando sugerencia")
            val search=all.firstOrNull { o ->
                val t=canonical(label(o)); o.optBoolean("clickable") && (t=="buscar" || t.contains("buscar") || t.contains("search") || t.contains("lupa"))
            }
            if (search != null) return JSONObject().put("action","click").put("text",label(search)).put("message","Makro · ejecutando búsqueda")
            return JSONObject().put("action","ime_enter").put("message","Makro · enviando búsqueda")
        }
        return null
    }

'''
if 'private fun makroProductFromTask' not in s:
    if marker not in s: raise SystemExit('loop marker not found for product helper')
    s=s.replace(marker,product_helper+marker,1)
if 'private fun makroDeterministicAction' not in s:
    if marker not in s: raise SystemExit('loop marker not found for Makro action')
    s=s.replace(marker,action_helper+marker,1)

needle='''            val action = plan(task, ui, pkg, step)'''
if needle in s:
    s=s.replace(needle,'''            val action = makroDeterministicAction(task, ui, pkg) ?: plan(task, ui, pkg, step)''',1)

if '"ime_enter" ->' not in s:
    anchor='''                "scroll" -> { onUpdate("Desplazando…"); send(if (action.optString("direction") == "backward") JarvisAccessibilityService.ACTION_SCROLL_BACKWARD else JarvisAccessibilityService.ACTION_SCROLL_FORWARD) }'''
    if anchor not in s: raise SystemExit('scroll action anchor not found')
    s=s.replace(anchor,anchor+'''\n                "ime_enter" -> { onUpdate("Makro · enviando búsqueda…"); send(JarvisAccessibilityService.ACTION_IME_ENTER); Thread.sleep(1000) }''',1)

p.write_text(s)
print('Makro deterministic flow applied without duplicate helpers')
