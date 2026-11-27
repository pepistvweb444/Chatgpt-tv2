package com.jarvis.logic

data class DeliveryTask(val store: String, val item: String, val quantity: Int)

object DeliveryTaskParser {
    val knownStores = listOf(
        "uber eats", "just eat", "glovo", "deliveroo", "amazon", "aliexpress", "dia", "makro"
    )
    val apiStores = setOf("glovo", "uber eats", "just eat", "deliveroo")

    fun parse(text: String): DeliveryTask? {
        val normalized = normalizeItem(text)
        if (normalized.isBlank()) return null
        val store = knownStores.firstOrNull { normalized.contains(it) } ?: return null
        val shopping = listOf("compra", "comprar", "anade", "agrega", "pon", "pide", "pedir", "carrito", "cesta", "cantidad")
            .any { normalized.contains(it) }
        if (!shopping) return null
        val quantity = Regex("\\b(\\d{1,2})\\b").find(normalized)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(0, 99) ?: 1
        var item = normalized
        knownStores.forEach { item = item.replace(it, " ") }
        listOf("compra", "comprar", "anade", "agrega", "pon", "pide", "pedir", "en", "de", "del", "la", "el", "los", "las", "un", "una", "carrito", "cesta", "al", "a", "app", "por", "favor", "cantidad").forEach {
            item = Regex("\\b$it\\b").replace(item, " ")
        }
        item = item.replace(Regex("\\b$quantity\\b"), " ").replace(Regex("\\s+"), " ").trim()
        if (item.isBlank()) return null
        return DeliveryTask(store, item, quantity)
    }
}
