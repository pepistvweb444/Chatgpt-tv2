package com.jarvis.mobile

import android.app.Activity
import com.jarvis.logic.ApprovalCard
import com.jarvis.logic.Basket
import com.jarvis.logic.CartResult
import com.jarvis.logic.DeliverySession
import com.jarvis.logic.DeliveryTask
import com.jarvis.logic.HttpBasketGateway
import com.jarvis.logic.HttpRequest
import com.jarvis.logic.HttpResponse
import com.jarvis.logic.HttpTransport
import com.jarvis.logic.IdempotentCart
import com.jarvis.logic.PayResult
import com.jarvis.logic.payRequest
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class DeliveryCoordinator(private val activity: Activity) {
    sealed class Prep {
        data class Ready(val card: ApprovalCard) : Prep()
        data class Stopped(val message: String) : Prep()
        data class Fallback(val reason: String) : Prep()
    }

    fun session(store: String): DeliverySession? {
        val raw = activity.getSharedPreferences("jarvis_mobile", Activity.MODE_PRIVATE)
            .getString(prefKey(store), "").orEmpty()
        if (raw.isBlank()) return null
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val cookie = json.optString("cookie")
        val authorization = json.optString("authorization")
        if (cookie.isBlank() && authorization.isBlank()) return null
        return DeliverySession(
            store = store,
            baseUrl = json.optString("baseUrl").ifBlank { defaultBase(store) },
            cookie = cookie,
            authorization = authorization,
            placeOrderPath = json.optString("placeOrderPath"),
            allowPay = json.optBoolean("allowPay", false)
        )
    }

    fun prepare(task: DeliveryTask): Prep {
        val session = session(task.store) ?: return Prep.Fallback("No hay una sesión de ${storeName(task.store)} en este teléfono.")
        return try {
            val cart = IdempotentCart(HttpBasketGateway(session, AndroidHttpTransport()))
            when (val result = cart.ensureProduct(task.item, task.quantity)) {
                is CartResult.Ready -> Prep.Ready(
                    ApprovalCard(
                        task.store,
                        result.basket,
                        "Cesta de ${storeName(task.store)} lista. Cantidad fijada en ${task.quantity}."
                    )
                )
                is CartResult.Stopped -> Prep.Stopped(result.message)
            }
        } catch (error: Exception) {
            Prep.Fallback("La API de ${storeName(task.store)} no ha respondido (${error.message ?: "error"}).")
        }
    }

    fun pay(store: String, basket: Basket, explicitConfirmation: Boolean): PayResult {
        val session = session(store) ?: return PayResult.Blocked("No hay sesión. No envío el pedido.")
        val decision = payRequest(session, basket, explicitConfirmation)
        if (decision !is PayResult.Submitted) return decision
        val path = session.placeOrderPath
        if (!path.startsWith("/")) return PayResult.Blocked("La ruta de pago no es válida. No envío el pedido.")
        val response = AndroidHttpTransport().exchange(
            HttpRequest(
                method = "POST",
                url = session.baseUrl.trimEnd('/') + path,
                headers = buildMap {
                    put("Accept", "application/json")
                    put("Content-Type", "application/json")
                    if (session.authorization.isNotBlank()) put("Authorization", session.authorization)
                    if (session.cookie.isNotBlank()) put("Cookie", session.cookie)
                },
                body = JSONObject().put("basketId", basket.basketId).put("confirm", true).toString()
            )
        )
        return if (response.code in 200..299) decision
        else PayResult.Blocked("El pedido no se ha enviado (HTTP ${response.code}). La cesta no se ha vuelto a tocar.")
    }

    companion object {
        fun prefKey(store: String) = "delivery_session_" + store.replace(' ', '_')

        fun storeName(store: String) = when (store) {
            "glovo" -> "Glovo"
            "uber eats" -> "Uber Eats"
            "just eat" -> "Just Eat"
            "deliveroo" -> "Deliveroo"
            else -> store.replaceFirstChar { it.uppercase() }
        }

        fun defaultBase(store: String) = when (store) {
            "uber eats" -> "https://www.ubereats.com"
            "just eat" -> "https://www.just-eat.es"
            "deliveroo" -> "https://api.deliveroo.com"
            else -> "https://api.glovoapp.com"
        }

        fun loginUrl(store: String) = when (store) {
            "uber eats" -> "https://www.ubereats.com/"
            "just eat" -> "https://www.just-eat.es/"
            "deliveroo" -> "https://deliveroo.es/"
            else -> "https://glovoapp.com/"
        }
    }
}

private class AndroidHttpTransport : HttpTransport {
    override fun exchange(request: HttpRequest): HttpResponse {
        val connection = (URL(request.url).openConnection() as HttpURLConnection).apply {
            requestMethod = request.method
            connectTimeout = 8000
            readTimeout = 12000
            instanceFollowRedirects = false
            request.headers.forEach { (key, value) -> setRequestProperty(key, value) }
            if (request.body != null) {
                doOutput = true
                outputStream.use { it.write(request.body!!.toByteArray()) }
            }
        }
        val code = connection.responseCode
        val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        return HttpResponse(code, text)
    }
}
