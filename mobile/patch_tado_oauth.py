from pathlib import Path

p = Path('mobile/src/main/java/com/jarvis/mobile/MainActivity.kt')
s = p.read_text()

# Replace the old technical Tado MCP form with the official tado OAuth device-code flow.
start = s.find('    private fun showTadoSettings() {')
end = s.find('    private fun showConnections() {', start)
if start < 0 or end < 0:
    raise SystemExit('Tado settings block not found')

replacement = r'''    private fun showTadoSettings() {
        val connected = prefs.getString("tado_refresh_token", "").orEmpty().isNotBlank()
        val state = if (connected) "Conectado a tu cuenta tado°" else "No conectado"
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        box.addView(TextView(this).apply {
            text = state
            setTextColor(if (connected) Color.rgb(120, 220, 150) else Color.LTGRAY)
            textSize = 16f
        })
        box.addView(TextView(this).apply {
            text = "Tado ya no permite que Jarvis envíe directamente tu usuario y contraseña a la API. Pulsa Conectar: se abrirá la página oficial de tado°, donde puedes iniciar sesión con tu usuario/contraseña o el método asociado a tu cuenta. Jarvis guardará el token OAuth, no tu contraseña."
            setTextColor(Color.LTGRAY)
            textSize = 13f
            setPadding(0, dp(10), 0, dp(8))
        })
        AlertDialog.Builder(this)
            .setTitle("Tado · climatización")
            .setView(box)
            .setPositiveButton(if (connected) "Reconectar" else "Conectar con Tado") { _, _ -> beginTadoLogin() }
            .setNeutralButton(if (connected) "Comprobar" else "Finalizar conexión") { _, _ -> completePendingTadoLogin() }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun beginTadoLogin() {
        status.text = "Tado · preparando inicio de sesión…"
        Thread {
            try {
                val endpoint = URL("https://login.tado.com/oauth2/device_authorize")
                val c = (endpoint.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 10000
                    readTimeout = 15000
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    setRequestProperty("Accept", "application/json")
                }
                val body = "client_id=1bb50063-6b0c-4d11-bd99-387f4a91cc46&scope=offline_access"
                c.outputStream.use { it.write(body.toByteArray()) }
                val raw = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}: ${raw.take(180)}")
                val j = JSONObject(raw)
                val deviceCode = j.optString("device_code")
                val userCode = j.optString("user_code")
                val verify = j.optString("verification_uri_complete").ifBlank { j.optString("verification_uri") }
                if (deviceCode.isBlank() || verify.isBlank()) throw IllegalStateException("Tado no devolvió un código de autorización")
                prefs.edit()
                    .putString("tado_device_code", deviceCode)
                    .putString("tado_user_code", userCode)
                    .putLong("tado_device_expires", System.currentTimeMillis() + j.optLong("expires_in", 300L) * 1000L)
                    .apply()
                runOnUiThread {
                    status.text = "Tado · inicia sesión en la página oficial"
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(verify))) }
                    AlertDialog.Builder(this)
                        .setTitle("Inicia sesión en Tado")
                        .setMessage("Se ha abierto la página oficial de tado°. Inicia sesión allí con tu usuario y contraseña. Código: $userCode\n\nCuando termines, vuelve a Jarvis y pulsa Finalizar conexión.")
                        .setPositiveButton("Finalizar conexión") { _, _ -> completePendingTadoLogin() }
                        .setNegativeButton("Más tarde", null)
                        .show()
                }
            } catch (e: Throwable) {
                runOnUiThread { status.text = "Jarvis listo"; Toast.makeText(this, "No se pudo iniciar Tado: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun completePendingTadoLogin() {
        val deviceCode = prefs.getString("tado_device_code", "").orEmpty()
        if (deviceCode.isBlank()) {
            Toast.makeText(this, "Primero pulsa Conectar con Tado", Toast.LENGTH_LONG).show()
            return
        }
        status.text = "Tado · verificando autorización…"
        Thread {
            try {
                val c = (URL("https://login.tado.com/oauth2/token").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 10000
                    readTimeout = 15000
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    setRequestProperty("Accept", "application/json")
                }
                val body = "client_id=1bb50063-6b0c-4d11-bd99-387f4a91cc46&device_code=${java.net.URLEncoder.encode(deviceCode, "UTF-8")}&grant_type=${java.net.URLEncoder.encode("urn:ietf:params:oauth:grant-type:device_code", "UTF-8")}" 
                c.outputStream.use { it.write(body.toByteArray()) }
                val raw = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                val j = runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
                if (c.responseCode !in 200..299) {
                    val err = j.optString("error")
                    if (err == "authorization_pending") throw IllegalStateException("Todavía falta confirmar el acceso en la página de Tado")
                    throw IllegalStateException(j.optString("error_description").ifBlank { err.ifBlank { "HTTP ${c.responseCode}" } })
                }
                val access = j.optString("access_token")
                val refresh = j.optString("refresh_token")
                if (access.isBlank() || refresh.isBlank()) throw IllegalStateException("Tado no devolvió los tokens de acceso")
                prefs.edit()
                    .putString("tado_access_token", access)
                    .putString("tado_refresh_token", refresh)
                    .putLong("tado_access_expires", System.currentTimeMillis() + j.optLong("expires_in", 600L) * 1000L - 30000L)
                    .putBoolean("tado_enabled", true)
                    .remove("tado_device_code")
                    .apply()
                ensureTadoSelected()
                runOnUiThread {
                    status.text = "Jarvis listo"
                    Toast.makeText(this, "Tado conectado correctamente", Toast.LENGTH_LONG).show()
                    testTadoConnection()
                }
            } catch (e: Throwable) {
                runOnUiThread { status.text = "Jarvis listo"; Toast.makeText(this, "Tado: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun ensureTadoSelected() {
        val selected = runCatching { JSONArray(prefs.getString("selected_tools", "[]")) }.getOrElse { JSONArray() }
        val names = (0 until selected.length()).map { selected.optString(it) }.filter { it.isNotBlank() }.toMutableList()
        if (!names.contains("Tado")) names.add("Tado")
        prefs.edit().putString("selected_tools", JSONArray(names).toString()).apply()
        restoreSelectedTools()
    }

    private fun refreshTadoTokenIfNeeded(): String {
        val current = prefs.getString("tado_access_token", "").orEmpty()
        val expiry = prefs.getLong("tado_access_expires", 0L)
        if (current.isNotBlank() && expiry > System.currentTimeMillis()) return current
        val refresh = prefs.getString("tado_refresh_token", "").orEmpty()
        if (refresh.isBlank()) return ""
        val c = (URL("https://login.tado.com/oauth2/token").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 10000; readTimeout = 15000
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("Accept", "application/json")
        }
        val body = "client_id=1bb50063-6b0c-4d11-bd99-387f4a91cc46&grant_type=refresh_token&refresh_token=${java.net.URLEncoder.encode(refresh, "UTF-8")}" 
        c.outputStream.use { it.write(body.toByteArray()) }
        val raw = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (c.responseCode !in 200..299) return ""
        val j = JSONObject(raw)
        val access = j.optString("access_token")
        val nextRefresh = j.optString("refresh_token").ifBlank { refresh }
        if (access.isBlank()) return ""
        prefs.edit().putString("tado_access_token", access).putString("tado_refresh_token", nextRefresh)
            .putLong("tado_access_expires", System.currentTimeMillis() + j.optLong("expires_in", 600L) * 1000L - 30000L).apply()
        return access
    }

    private fun testTadoConnection() {
        Thread {
            try {
                val token = refreshTadoTokenIfNeeded()
                if (token.isBlank()) throw IllegalStateException("La sesión ha caducado; vuelve a conectar Tado")
                val c = (URL("https://my.tado.com/api/v2/me").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"; connectTimeout = 10000; readTimeout = 15000
                    setRequestProperty("Authorization", "Bearer $token")
                    setRequestProperty("Accept", "application/json")
                }
                val raw = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
                val me = JSONObject(raw)
                val name = me.optString("name").ifBlank { me.optString("username").ifBlank { "cuenta Tado" } }
                runOnUiThread { Toast.makeText(this, "Conexión Tado correcta · $name", Toast.LENGTH_LONG).show() }
            } catch (e: Throwable) {
                runOnUiThread { Toast.makeText(this, "No se pudo comprobar Tado: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

'''

s = s[:start] + replacement + s[end:]
p.write_text(s)
print('Official Tado OAuth device login patch applied')
