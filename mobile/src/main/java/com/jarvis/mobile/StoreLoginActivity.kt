package com.jarvis.mobile

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

/** Lets the user sign in to their own store account. Jarvis stores the cookie or token they just created. */
class StoreLoginActivity : Activity() {
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = intent.getStringExtra("store").orEmpty().ifBlank { "glovo" }
        val page = DeliveryCoordinator.loginUrl(store)
        val token = EditText(this).apply {
            hint = "Token de tu sesión, si la web no deja la cookie de la API"
            setSingleLine(true)
        }
        val pay = CheckBox(this).apply {
            text = "Permitir que Jarvis envíe el pedido solo después de una segunda confirmación"
        }
        val web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()
            loadUrl(page)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            addView(TextView(this@StoreLoginActivity).apply {
                text = "Entra con tu cuenta de ${DeliveryCoordinator.storeName(store)}. Jarvis no trae ninguna contraseña puesta."
                textSize = 16f
            })
            addView(token)
            addView(pay)
            addView(Button(this@StoreLoginActivity).apply {
                text = "Guardar mi sesión"
                setOnClickListener { save(store, page, token.text.toString().trim(), pay.isChecked) }
            })
            addView(web, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
    }

    private fun save(store: String, page: String, token: String, allowPay: Boolean) {
        val cookie = runCatching { CookieManager.getInstance().getCookie(page) }.getOrNull().orEmpty()
        if (cookie.isBlank() && token.isBlank()) {
            Toast.makeText(this, "Todavía no hay cookie ni token. Entra en la cuenta primero.", Toast.LENGTH_LONG).show()
            return
        }
        val json = JSONObject()
            .put("store", store)
            .put("baseUrl", DeliveryCoordinator.defaultBase(store))
            .put("cookie", cookie)
            .put("authorization", token)
            .put("allowPay", allowPay)
            .put("placeOrderPath", "")
        getSharedPreferences("jarvis_mobile", MODE_PRIVATE).edit()
            .putString(DeliveryCoordinator.prefKey(store), json.toString())
            .apply()
        Toast.makeText(this, "Sesión de ${DeliveryCoordinator.storeName(store)} guardada en el teléfono.", Toast.LENGTH_LONG).show()
        finish()
    }
}
