package com.jarvis.logic

import org.json.JSONArray
import org.json.JSONObject

data class BasketLine(val key: String, val name: String, val quantity: Int, val unitPriceCents: Int?)

data class Basket(
    val store: String,
    val basketId: String,
    val lines: List<BasketLine>,
    val totalCents: Int?,
    val currency: String = "EUR"
) {
    fun quantityOf(key: String): Int = lines.firstOrNull { it.key == key }?.quantity ?: 0

    fun match(query: String): BasketLine? {
        val wanted = normalizeItem(query)
        if (wanted.isBlank()) return null
        return lines.firstOrNull { normalizeItem(it.name) == wanted }
            ?: lines.firstOrNull { normalizeItem(it.name).contains(wanted) || wanted.contains(normalizeItem(it.name)) }
    }
}

fun normalizeItem(value: String): String {
    val folded = java.text.Normalizer.normalize(value.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
    return folded.replace(Regex("[^a-z0-9]+"), " ").trim()
}

data class DeliverySession(
    val store: String,
    val baseUrl: String,
    val cookie: String = "",
    val authorization: String = "",
    val placeOrderPath: String = "",
    val allowPay: Boolean = false
)

data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null
)

data class HttpResponse(val code: Int, val body: String)

interface HttpTransport {
    fun exchange(request: HttpRequest): HttpResponse
}

interface BasketGateway {
    fun read(): Basket
    /** Sets the absolute quantity of an existing line. Must not add the delta on top. */
    fun setQuantity(lineKey: String, quantity: Int)
    /** Inserts a missing product once, with the full target quantity in that single request. */
    fun addOnce(itemQuery: String, quantity: Int)
}

sealed class CartResult {
    data class Ready(val basket: Basket, val changed: Boolean) : CartResult()
    data class Stopped(val basket: Basket?, val message: String) : CartResult()
}

class IdempotentCart(private val gateway: BasketGateway) {
    fun ensureProduct(query: String, target: Int): CartResult {
        require(target >= 0) { "La cantidad no puede ser negativa." }
        val first = try {
            gateway.read()
        } catch (error: Exception) {
            return CartResult.Stopped(null, "No he podido leer la cesta. Paro para no duplicar productos.")
        }
        val existing = first.match(query)
        if (existing == null && target == 0) return CartResult.Ready(first, changed = false)
        var writes = 0
        try {
            if (existing == null) {
                gateway.addOnce(query, target)
                writes++
            } else if (existing.quantity != target) {
                gateway.setQuantity(existing.key, target)
                writes++
            } else {
                return CartResult.Ready(first, changed = false)
            }
        } catch (error: Exception) {
            return CartResult.Stopped(first, "No he podido fijar la cantidad. Paro sin volver a añadir.")
        }
        val second = try {
            gateway.read()
        } catch (error: Exception) {
            return CartResult.Stopped(first, "He enviado la cantidad, pero no he podido releer la cesta. Paro para no repetir el alta.")
        }
        val after = second.match(query)
        if (after?.quantity == target) return CartResult.Ready(second, changed = writes > 0)
        if (after != null) {
            try {
                gateway.setQuantity(after.key, target)
            } catch (error: Exception) {
                return CartResult.Stopped(second, "La cesta no quedó en $target. He parado para no duplicar ni triplicar.")
            }
            val third = try {
                gateway.read()
            } catch (error: Exception) {
                return CartResult.Stopped(second, "No he podido comprobar la cesta tras corregir la cantidad. Paro aquí.")
            }
            val done = third.match(query)
            if (done?.quantity == target) return CartResult.Ready(third, changed = true)
            return CartResult.Stopped(third, "La cesta sigue sin la cantidad exacta ($target). He parado para no duplicar el producto.")
        }
        return CartResult.Stopped(second, "No veo «$query» en la cesta después de un único alta. No lo vuelvo a añadir.")
    }
}

data class ApprovalCard(val store: String, val basket: Basket, val message: String) {
    val lines: List<BasketLine> get() = basket.lines
    val totalCents: Int? get() = basket.totalCents

    fun summary(): String {
        val items = if (lines.isEmpty()) "La cesta está vacía." else lines.joinToString("\n") { line ->
            val price = line.unitPriceCents?.let { " · ${formatMoney(it * line.quantity, basket.currency)}" }.orEmpty()
            "• ${line.quantity} × ${line.name}$price"
        }
        val total = basket.totalCents?.let { "\n\nTotal ${formatMoney(it, basket.currency)}" }.orEmpty()
        return "$message\n\n$items$total\n\nJarvis no paga ni envía el pedido con esta tarjeta."
    }
}

fun formatMoney(cents: Int, currency: String): String {
    val sign = if (cents < 0) "-" else ""
    val abs = kotlin.math.abs(cents)
    val major = abs / 100
    val minor = (abs % 100).toString().padStart(2, '0')
    val symbol = if (currency.equals("EUR", true)) "€" else currency
    return "$sign$major,$minor $symbol"
}

sealed class PayResult {
    data class Blocked(val message: String) : PayResult()
    data class Submitted(val message: String) : PayResult()
}

/**
 * Payment is a separate call. The cart builder never invokes it.
 * It still refuses unless the user opted in on their own session and confirmed.
 */
fun payRequest(session: DeliverySession, basket: Basket, explicitConfirmation: Boolean): PayResult {
    if (!explicitConfirmation) return PayResult.Blocked("Falta la confirmación explícita. No envío el pedido.")
    if (!session.allowPay || session.placeOrderPath.isBlank()) {
        return PayResult.Blocked("El pago está desactivado en tu sesión. La cesta queda preparada y Jarvis no la envía.")
    }
    return PayResult.Submitted("Pedido enviado a ${session.store} por la ruta que configuraste. Revisa el cargo en la app.")
}

object BasketJson {
    fun parse(store: String, raw: String): Basket? {
        val text = raw.trim()
        if (text.isBlank() || !(text.startsWith("{") || text.startsWith("["))) return null
        val obj = when {
            text.startsWith("[") -> {
                val arr = JSONArray(text)
                if (arr.length() == 0) return Basket(store, "", emptyList(), 0)
                arr.optJSONObject(0) ?: return null
            }
            else -> JSONObject(text)
        }
        val nested = obj.optJSONObject("basket") ?: obj.optJSONObject("cart") ?: obj.optJSONObject("data") ?: obj
        val basketId = firstString(nested, listOf("basketId", "cartId", "id", "uuid"))
        val array = nested.optJSONArray("products")
            ?: nested.optJSONArray("items")
            ?: nested.optJSONArray("lines")
            ?: nested.optJSONArray("cartItems")
            ?: JSONArray()
        val lines = mutableListOf<BasketLine>()
        for (i in 0 until array.length()) {
            val line = array.optJSONObject(i) ?: continue
            val key = firstString(line, listOf("id", "productId", "uuid", "itemId", "sku", "lineId"))
            val name = firstString(line, listOf("name", "title", "productName", "label")).ifBlank { key }
            if (key.isBlank() && name.isBlank()) continue
            val qty = line.optInt("quantity", line.optInt("qty", line.optInt("count", 1))).coerceAtLeast(0)
            val unit = moneyCents(line, listOf("unitPrice", "price", "unit_price"))
            lines += BasketLine(key.ifBlank { name }, name.ifBlank { key }, qty, unit)
        }
        val total = moneyCents(nested, listOf("total", "totalPrice", "subtotal", "basketTotal"))
        return Basket(store, basketId, lines, total)
    }

    fun setQuantitySpec(session: DeliverySession, basket: Basket, lineKey: String, quantity: Int): HttpRequest {
        val root = session.baseUrl.trimEnd('/')
        val path = when (session.store) {
            "ubereats" -> "/_p/api/updateCartItemV1"
            "justeat" -> "/cart/items/$lineKey"
            "deliveroo" -> "/orderapp/v1/baskets/${basket.basketId}/items/$lineKey"
            else -> "/v3/baskets/${basket.basketId}/products/$lineKey"
        }
        val body = when (session.store) {
            "ubereats" -> JSONObject().put("cartId", basket.basketId).put("itemId", lineKey).put("quantity", quantity).toString()
            else -> JSONObject().put("quantity", quantity).toString()
        }
        val method = if (session.store == "ubereats") "POST" else "PUT"
        return HttpRequest(method, root + path, headers(session), body)
    }

    fun addOnceSpec(session: DeliverySession, basket: Basket, query: String, quantity: Int): HttpRequest {
        val root = session.baseUrl.trimEnd('/')
        val path = when (session.store) {
            "ubereats" -> "/_p/api/addCartItemV1"
            "justeat" -> "/cart/items"
            "deliveroo" -> "/orderapp/v1/baskets/${basket.basketId}/items"
            else -> "/v3/baskets/${basket.basketId}/products"
        }
        val body = JSONObject().put("name", query).put("quantity", quantity).put("basketId", basket.basketId).toString()
        return HttpRequest("POST", root + path, headers(session), body)
    }

    fun readSpec(session: DeliverySession): HttpRequest {
        val root = session.baseUrl.trimEnd('/')
        val path = when (session.store) {
            "ubereats" -> "/_p/api/getCartsV1"
            "justeat" -> "/cart"
            "deliveroo" -> "/orderapp/v1/baskets/current"
            else -> "/v3/baskets"
        }
        val method = if (session.store == "ubereats") "POST" else "GET"
        val body = if (method == "POST") "{}" else null
        return HttpRequest(method, root + path, headers(session), body)
    }

    private fun headers(session: DeliverySession): Map<String, String> {
        val out = linkedMapOf("Accept" to "application/json", "Content-Type" to "application/json")
        if (session.authorization.isNotBlank()) out["Authorization"] = session.authorization
        if (session.cookie.isNotBlank()) out["Cookie"] = session.cookie
        return out
    }

    private fun firstString(obj: JSONObject, keys: List<String>): String {
        for (key in keys) {
            val value = obj.optString(key)
            if (value.isNotBlank() && value != "null") return value
        }
        return ""
    }

    private fun moneyCents(obj: JSONObject, keys: List<String>): Int? {
        for (key in keys) {
            if (!obj.has(key) || obj.isNull(key)) continue
            val asInt = obj.opt(key)
            val number = when (asInt) {
                is Int -> asInt.toDouble()
                is Long -> asInt.toDouble()
                is Double -> asInt
                is String -> asInt.replace(",", ".").toDoubleOrNull()
                else -> obj.optDouble(key, Double.NaN)
            } ?: continue
            if (number.isNaN()) continue
            return kotlin.math.round(number * 100.0).toInt()
        }
        return null
    }
}

class HttpBasketGateway(private val session: DeliverySession, private val transport: HttpTransport) : BasketGateway {
    override fun read(): Basket {
        val response = transport.exchange(BasketJson.readSpec(session))
        if (response.code !in 200..299) throw IllegalStateException("HTTP ${response.code}")
        return BasketJson.parse(session.store, response.body) ?: throw IllegalStateException("cesta ilegible")
    }

    override fun setQuantity(lineKey: String, quantity: Int) {
        val basket = read()
        val response = transport.exchange(BasketJson.setQuantitySpec(session, basket, lineKey, quantity))
        if (response.code !in 200..299) throw IllegalStateException("HTTP ${response.code}")
    }

    override fun addOnce(itemQuery: String, quantity: Int) {
        val basket = read()
        val response = transport.exchange(BasketJson.addOnceSpec(session, basket, itemQuery, quantity))
        if (response.code !in 200..299) throw IllegalStateException("HTTP ${response.code}")
    }
}

sealed class LoopDecision {
    data object Proceed : LoopDecision()
    data class Stop(val message: String, val success: Boolean) : LoopDecision()
}

class CartLoopGuard(private val maxIdentical: Int = 2) {
    private val history = mutableListOf<String>()

    fun reset() { history.clear() }

    fun decide(action: String, observedQty: Int?, targetQty: Int?): LoopDecision {
        val key = normalizeItem(action).ifBlank { action.trim().lowercase() }
        if (targetQty != null && observedQty != null) {
            if (observedQty == targetQty) {
                return LoopDecision.Stop("La cantidad ya es $targetQty. No vuelvo a añadir.", success = true)
            }
            if (observedQty > targetQty) {
                return LoopDecision.Stop("La cantidad visible ($observedQty) ya supera $targetQty. He parado para no triplicar el pedido.", success = false)
            }
        }
        val same = history.count { it == key }
        if (key.isNotBlank() && same >= maxIdentical) {
            return LoopDecision.Stop("He repetido la misma acción sin poder verificar la cantidad. Paro para no duplicar productos.", success = false)
        }
        if (key.isNotBlank()) history += key
        return LoopDecision.Proceed
    }
}

object CartUi {
    fun observeQuantity(texts: List<String>, item: String?): Int? {
        val blob = texts.joinToString(" \n ").lowercase()
        val itemNorm = item?.let { normalizeItem(it) }.orEmpty()
        val patterns = listOf(
            Regex("cantidad\\s*[:=]?\\s*(\\d{1,2})"),
            Regex("(\\d{1,2})\\s*[x×]\\s+"),
            Regex("[x×]\\s*(\\d{1,2})\\b"),
            Regex("qty\\s*[:=]?\\s*(\\d{1,2})")
        )
        val window = if (itemNorm.isBlank()) blob else {
            val idx = normalizeItem(blob).indexOf(itemNorm)
            if (idx < 0) blob else blob.substring(maxOf(0, idx - 40), minOf(blob.length, idx + itemNorm.length + 48))
        }
        for (pattern in patterns) {
            val found = pattern.find(window) ?: continue
            val qty = found.groupValues[1].toIntOrNull() ?: continue
            if (qty in 0..99) return qty
        }
        return null
    }
}
