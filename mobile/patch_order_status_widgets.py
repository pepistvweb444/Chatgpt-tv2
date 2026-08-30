from pathlib import Path

# 1) Capture order-related notifications from Glovo / Uber / Uber Eats.
p = Path('mobile/src/main/java/com/jarvis/mobile/JarvisNotificationListener.kt')
s = p.read_text()
anchor = '''        val prefs = getSharedPreferences("jarvis_mobile", MODE_PRIVATE)\n'''
insert = '''        val prefs = getSharedPreferences("jarvis_mobile", MODE_PRIVATE)\n\n        val pkgLower = packageName.lowercase()\n        val appOrder = when {\n            pkgLower.contains("glovo") -> "Glovo"\n            pkgLower.contains("ubereats") || pkgLower.contains("uber") -> "Uber Eats"\n            else -> ""\n        }\n        if (appOrder.isNotBlank()) {\n            val blob = listOf(title, body, subText, conversation).joinToString(" ").lowercase()\n            val looksLikeOrder = listOf(\n                "pedido", "order", "repart", "delivery", "courier", "prepar", "recog",\n                "camino", "llega", "arriv", "entreg", "delivered", "restaurant", "restaurante"\n            ).any { blob.contains(it) }\n            if (looksLikeOrder) {\n                val status = JSONObject()\n                    .put("app", appOrder)\n                    .put("package", packageName)\n                    .put("title", title)\n                    .put("text", body)\n                    .put("subText", subText)\n                    .put("conversation", conversation)\n                    .put("time", System.currentTimeMillis())\n                prefs.edit().putString("last_order_status_json", status.toString()).apply()\n            }\n        }\n'''
if 'last_order_status_json' not in s and anchor in s:
    s = s.replace(anchor, insert, 1)
p.write_text(s)

# 2) Route explicit order-status questions locally.
p = Path('mobile/src/main/java/com/jarvis/mobile/LocalActionRouter.kt')
s = p.read_text()
when_anchor = '        when {\n'
route = '''        when {\n            isOrderStatusQuery(lower) -> return readLatestOrderStatus()\n'''
if 'isOrderStatusQuery(lower)' not in s and when_anchor in s:
    s = s.replace(when_anchor, route, 1)

marker = '    private fun messageAccessStatus(): Result {'
methods = r'''    private fun isOrderStatusQuery(lower: String): Boolean {
        val order = lower.contains("pedido") || lower.contains("reparto") || lower.contains("entrega") || lower.contains("order")
        val app = lower.contains("glovo") || lower.contains("uber") || lower.contains("ubereats") || lower.contains("uber eats")
        val asks = lower.contains("estado") || lower.contains("dónde") || lower.contains("donde") || lower.contains("cómo va") || lower.contains("como va") || lower.contains("cuándo llega") || lower.contains("cuando llega") || lower.contains("muéstr") || lower.contains("muestr")
        return order && (app || asks)
    }

    private fun readLatestOrderStatus(): Result {
        val prefs = activity.getSharedPreferences("jarvis_mobile", Activity.MODE_PRIVATE)
        val raw = prefs.getString("last_order_status_json", "").orEmpty()
        if (raw.isBlank()) {
            val connected = prefs.getBoolean("notification_listener_connected", false)
            return Result(true, "__ORDER_EMPTY__|" + if (connected)
                "No tengo todavía un estado de pedido reciente de Glovo o Uber Eats. Cuando llegue una notificación del pedido, Jarvis la mostrará aquí."
            else "Necesito Acceso a notificaciones para leer el estado de pedidos de Glovo y Uber Eats.")
        }
        return Result(true, "__ORDER_WIDGET__|$raw")
    }

'''
if 'private fun readLatestOrderStatus' not in s and marker in s:
    s = s.replace(marker, methods + marker, 1)
p.write_text(s)

# 3) Render the status inline when a known local-result branch is present.
# The MainActivity generator evolves frequently; this feature must never abort
# the whole APK build just because a renderer anchor has moved.
p = Path('mobile/src/main/java/com/jarvis/mobile/MainActivity.kt')
s = p.read_text()
needle_candidates = [
    '            if (local.message.startsWith("__NEW_MESSAGES__|")) {',
    '            if (local.message.startsWith("__MESSAGES_WIDGET__")) {',
    '            if (local.message.startsWith("__MESSAGES__|")) {',
    '            if (local.message.startsWith("__AGENDA_WIDGET__")) {'
]
needle = next((x for x in needle_candidates if x in s), None)
block_prefix = r'''            if (local.message.startsWith("__ORDER_WIDGET__|")) {
                val j = runCatching { JSONObject(local.message.substringAfter("__ORDER_WIDGET__|")) }.getOrElse { JSONObject() }
                val app = j.optString("app").ifBlank { "Pedido" }
                val title = j.optString("title").ifBlank { "Estado del pedido" }
                val body = listOf(j.optString("text"), j.optString("subText"), j.optString("conversation"))
                    .filter { it.isNotBlank() }.distinct().joinToString(" · ")
                beginWidgetGroup("$app · pedido")
                addTextWidget("day", title, body.ifBlank { "Estado actualizado desde la app" })
                status.text = "Jarvis listo"
            } else if (local.message.startsWith("__ORDER_EMPTY__|")) {
                beginWidgetGroup("Pedidos")
                addTextWidget("day", "Sin estado disponible", local.message.substringAfter("|"))
                status.text = "Jarvis listo"
            } else '''

if '__ORDER_WIDGET__|' not in s and needle is not None:
    s = s.replace(needle, block_prefix + needle.strip(), 1)
elif '__ORDER_WIDGET__|' not in s:
    print('Order widget renderer anchor moved; capture/router retained and build continues')

p.write_text(s)
print('Glovo/Uber order status capture/router applied; renderer patch is non-fatal')
