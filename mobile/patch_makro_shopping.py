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

# Controller: explicitly route Makro and allow longer shopping flows.
p = Path('mobile/src/main/java/com/jarvis/mobile/PhoneAgentController.kt')
s = p.read_text()
s = s.replace('''"booking","thefork","restaurante","hotel","vuelo")''', '''"booking","thefork","restaurante","hotel","vuelo","makro","metro markets")''')
s = s.replace('''while (!cancelled && step < 25) {''', '''val maxSteps = if (looksLikeShoppingTask(task)) 45 else 25
        while (!cancelled && step < maxSteps) {''')
s = s.replace('''"aliexpress" to listOf("com.alibaba.aliexpresshd"),"booking" to listOf("com.booking"))''', '''"aliexpress" to listOf("com.alibaba.aliexpresshd"),"booking" to listOf("com.booking"),"makro" to emptyList())''')
s = s.replace('''"glovo", "amazon", "aliexpress", "dia", "día")''', '''"glovo", "amazon", "aliexpress", "dia", "día", "makro", "metro markets")''')
p.write_text(s)

print('Makro search targeting, accessibility hints and shopping step budget applied')
