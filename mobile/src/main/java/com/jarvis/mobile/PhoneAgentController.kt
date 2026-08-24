package com.jarvis.mobile

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import androidx.appcompat.app.AlertDialog
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class PhoneAgentController(private val activity: Activity) {
    private val prefs = activity.getSharedPreferences("jarvis_mobile", Activity.MODE_PRIVATE)
    private val backend = "https://chatgpt-tv2.vercel.app"
    @Volatile private var cancelled = false

    fun looksLikePhoneTask(text: String): Boolean {
        val s = text.lowercase()
        return (s.contains("abre ") || s.contains("ve a ") || s.contains("entra en ")) &&
            (s.contains(" y ") || s.contains("pedido") || s.contains("compra") || s.contains("busca") || s.contains("correo") || s.contains("navega") || s.contains("rellena"))
    }

    fun run(task: String, onUpdate: (String) -> Unit, onDone: (String) -> Unit) {
        cancelled = false
        Thread { loop(task, 0, onUpdate, onDone) }.start()
    }

    fun cancel() { cancelled = true }

    private fun loop(task: String, startStep: Int, onUpdate: (String) -> Unit, onDone: (String) -> Unit) {
        var step = startStep
        while (!cancelled && step < 25) {
            refreshSnapshot(); Thread.sleep(if (step == 0) 250 else 850)
            val ui = runCatching { JSONArray(prefs.getString("accessibility_ui", "[]")) }.getOrElse { JSONArray() }
            val pkg = prefs.getString("foreground_package", "").orEmpty()
            val action = plan(task, ui, pkg, step)
            when (action.optString("action")) {
                "open_app" -> { onUpdate("Abriendo ${action.optString("app")}…"); if (!openApp(action.optString("app"))) return finish(onDone, "No encuentro esa aplicación o Android no permite abrirla.") }
                "click" -> { onUpdate("Pulsando ${action.optString("text")}…"); send(JarvisAccessibilityService.ACTION_CLICK_TEXT) { putExtra("text", action.optString("text")) } }
                "type" -> { onUpdate("Escribiendo…"); send(JarvisAccessibilityService.ACTION_SET_TEXT) { putExtra("text", action.optString("text")); putExtra("target", action.optString("target")) } }
                "scroll" -> { onUpdate("Desplazando…"); send(if (action.optString("direction") == "backward") JarvisAccessibilityService.ACTION_SCROLL_BACKWARD else JarvisAccessibilityService.ACTION_SCROLL_FORWARD) }
                "back" -> send(JarvisAccessibilityService.ACTION_BACK)
                "home" -> send(JarvisAccessibilityService.ACTION_HOME)
                "wait" -> Thread.sleep(action.optLong("ms", 900).coerceIn(250, 4000))
                "confirm" -> { requestConfirmation(task, step, action.optString("message"), onUpdate, onDone); return }
                "done" -> return finish(onDone, action.optString("message").ifBlank { "Tarea completada." })
                "fail" -> return finish(onDone, action.optString("message").ifBlank { "No he podido completar la tarea." })
                else -> return finish(onDone, "El agente del teléfono recibió una acción no válida.")
            }
            step++
        }
        finish(onDone, if (cancelled) "Control del teléfono detenido." else "He detenido la tarea para evitar un bucle de acciones.")
    }

    private fun requestConfirmation(task: String, step: Int, message: String, onUpdate: (String) -> Unit, onDone: (String) -> Unit) {
        activity.runOnUiThread {
            AlertDialog.Builder(activity)
                .setTitle("Confirmar acción")
                .setMessage(message.ifBlank { "Jarvis está a punto de realizar una acción externa irreversible. ¿Quieres continuar?" })
                .setPositiveButton("Confirmar") { _, _ -> Thread { loop(task + "\nEl usuario acaba de confirmar explícitamente el paso irreversible solicitado.", step + 1, onUpdate, onDone) }.start() }
                .setNegativeButton("Cancelar") { _, _ -> cancelled = true; onDone("Acción cancelada antes del paso final.") }
                .show()
        }
    }

    private fun refreshSnapshot() = send(JarvisAccessibilityService.ACTION_REFRESH_UI)

    private fun send(action: String, extras: Intent.() -> Unit = {}) {
        activity.sendBroadcast(Intent(action).setPackage(activity.packageName).apply(extras))
    }

    private fun openApp(name: String): Boolean {
        val wanted = name.trim().lowercase()
        val apps = activity.packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
        val match = apps.firstOrNull { activity.packageManager.getApplicationLabel(it).toString().lowercase().contains(wanted) } ?: return false
        val launch = activity.packageManager.getLaunchIntentForPackage(match.packageName) ?: return false
        activity.runOnUiThread { activity.startActivity(launch) }
        return true
    }

    private fun plan(task: String, ui: JSONArray, pkg: String, step: Int): JSONObject {
        val c = (URL("$backend/api/phone-agent").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 9000; readTimeout = 35000; setRequestProperty("Content-Type", "application/json")
        }
        val body = JSONObject().put("task", task).put("ui", ui).put("packageName", pkg).put("step", step)
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val raw = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (c.responseCode !in 200..299) return JSONObject().put("action", "fail").put("message", "Error del agente: ${raw.take(180)}")
        return runCatching { JSONObject(raw) }.getOrElse { JSONObject().put("action", "fail").put("message", "Respuesta inválida del agente") }
    }

    private fun finish(onDone: (String) -> Unit, message: String) { activity.runOnUiThread { onDone(message) } }
}
