from pathlib import Path
p=Path('mobile/src/main/java/com/jarvis/mobile/PhoneAgentController.kt')
s=p.read_text()

# Route short clarification answers back to the pending shopping task.
s=s.replace('''        val pending = prefs.getString("phone_agent_pending_task", "").orEmpty()
        val correction = pending.isNotBlank() && (''','''        val pending = prefs.getString("phone_agent_pending_task", "").orEmpty()
        if (pending.isNotBlank() && prefs.getBoolean("phone_agent_waiting_clarification", false)) return true
        val correction = pending.isNotBlank() && (''',1)

marker='''    private fun hasCartEvidence(ui: JSONArray): Boolean {'''
helper=r'''    private fun shoppingClarification(task: String): String? {
        if (!looksLikeShoppingTask(task)) return null
        val first = task.substringBefore("\nAclaración del usuario:").substringBefore("\nCorrección del usuario:").trim()
        val lower = canonical(first)
        val clarification = task.substringAfter("\nAclaración del usuario:", "").trim()
        val clarified = canonical(clarification)
        val generic = setOf("lata","latas","botella","botellas","pack","packs","caja","cajas","bebida","bebidas","refresco","refrescos","producto","productos")
        fun isGeneric(v:String):Boolean { val w=v.split(' ').filter{it.isNotBlank()}; return w.isEmpty() || w.all{it in generic || it.toIntOrNull()!=null || it=="de" || it=="del"} }
        val m=Regex("\\b(lata|latas|botella|botellas|pack|packs|caja|cajas)\\b").find(lower)
        if(m!=null){
            val after=lower.substring(m.range.last+1).trim().replace(Regex("^(de|del)\\s+"),"")
            val detail=if(clarified.isNotBlank()) clarified else after
            if(isGeneric(detail)) return when {
                m.value.startsWith("lata") -> "¿De qué producto quieres las latas? Dime el producto o marca y, si es relevante, si 24 son unidades sueltas o un pack/caja."
                m.value.startsWith("botella") -> "¿De qué producto quieres las botellas? Dime producto o marca y tamaño si corresponde."
                else -> "¿Qué producto y formato quieres exactamente?"
            }
        }
        if(Regex("\\b(compra|comprar|anade|añade|agrega|pide|pedir)\\b\\s+(?:\\d+\\s+)?(producto|productos|bebida|bebidas|refresco|refrescos)\\b").containsMatchIn(lower) && isGeneric(clarified))
            return "¿Qué producto concreto quieres comprar? Necesito el tipo o nombre antes de abrir la tienda."
        return null
    }

'''
if 'private fun shoppingClarification' not in s:
    if marker not in s: raise SystemExit('cart marker not found')
    s=s.replace(marker,helper+marker,1)

old='''    fun run(task: String, onUpdate: (String) -> Unit, onDone: (String) -> Unit) {
        cancelled = false
        val resolved = resolveTask(task)
        prefs.edit().putString("phone_agent_pending_task", resolved).apply()
        Thread { loop(resolved, 0, onUpdate, onDone) }.start()
    }'''
new='''    fun run(task: String, onUpdate: (String) -> Unit, onDone: (String) -> Unit) {
        cancelled = false
        val resolved = resolveTask(task)
        val question = shoppingClarification(resolved)
        if (question != null) {
            prefs.edit().putString("phone_agent_pending_task", resolved.substringBefore("\\nAclaración del usuario:").trim()).putBoolean("phone_agent_waiting_clarification", true).apply()
            activity.runOnUiThread { onDone(question) }
            return
        }
        prefs.edit().putBoolean("phone_agent_waiting_clarification", false).putString("phone_agent_pending_task", resolved).apply()
        Thread { loop(resolved, 0, onUpdate, onDone) }.start()
    }'''
if old not in s: raise SystemExit('run block not found')
s=s.replace(old,new,1)

oldresolve='''        val correction = pending.isNotBlank() && (lower.startsWith("es ") || lower.startsWith("era ") || lower.startsWith("quiero decir ") || lower.startsWith("queria decir ") || lower.startsWith("quería decir ") || lower.startsWith("me refiero a ") || lower.startsWith("la app es ") || lower == "glovo" || lower == "globo")
        val merged = if (correction) "$pending\\nCorrección del usuario: $current" else current'''
newresolve='''        val clarification = pending.isNotBlank() && prefs.getBoolean("phone_agent_waiting_clarification", false)
        val correction = !clarification && pending.isNotBlank() && (lower.startsWith("es ") || lower.startsWith("era ") || lower.startsWith("quiero decir ") || lower.startsWith("queria decir ") || lower.startsWith("quería decir ") || lower.startsWith("me refiero a ") || lower.startsWith("la app es ") || lower == "glovo" || lower == "globo")
        val merged = when { clarification -> "$pending\\nAclaración del usuario: $current"; correction -> "$pending\\nCorrección del usuario: $current"; else -> current }'''
if oldresolve not in s: raise SystemExit('resolve block not found')
s=s.replace(oldresolve,newresolve,1)

# Support planner clarify too.
s=s.replace('''                "confirm" -> { requestConfirmation(task, step, action.optString("message"), onUpdate, onDone); return }''','''                "clarify" -> {
                    prefs.edit().putString("phone_agent_pending_task", task.substringBefore("\\nAclaración del usuario:").trim()).putBoolean("phone_agent_waiting_clarification", true).apply()
                    return finish(onDone, action.optString("message").ifBlank { "Necesito que concretes el producto." }, false)
                }
                "confirm" -> { requestConfirmation(task, step, action.optString("message"), onUpdate, onDone); return }''',1)

p.write_text(s)
print('Shopping clarification guard applied before any app action')
