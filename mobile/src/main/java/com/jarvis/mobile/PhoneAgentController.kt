package com.jarvis.mobile

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import androidx.appcompat.app.AlertDialog
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import kotlin.math.min

class PhoneAgentController(private val activity: Activity) {
    private val prefs = activity.getSharedPreferences("jarvis_mobile", Activity.MODE_PRIVATE)
    private val backend = "https://chatgpt-tv2.vercel.app"
    @Volatile private var cancelled = false

    fun looksLikePhoneTask(text: String): Boolean {
        val s = text.lowercase().trim()
        val pending = prefs.getString("phone_agent_pending_task", "").orEmpty()
        val correction = pending.isNotBlank() && (
            s.startsWith("es ") || s.startsWith("era ") || s.startsWith("quiero decir ") ||
                s.startsWith("queria decir ") || s.startsWith("quería decir ") ||
                s.startsWith("me refiero a ") || s.startsWith("la app es ") ||
                s == "glovo" || s == "globo"
            )
        return correction || ((s.contains("abre ") || s.contains("ve a ") || s.contains("entra en ")) &&
            (s.contains(" y ") || s.contains("pedido") || s.contains("compra") || s.contains("busca") ||
                s.contains("correo") || s.contains("navega") || s.contains("rellena")))
    }

    fun run(task: String, onUpdate: (String) -> Unit, onDone: (String) -> Unit) {
        cancelled = false
        val resolved = resolveTask(task)
        prefs.edit().putString("phone_agent_pending_task", resolved).apply()
        Thread { loop(resolved, 0, onUpdate, onDone) }.start()
    }

    fun cancel() {
        cancelled = true
        prefs.edit().remove("phone_agent_pending_task").apply()
    }

    private fun resolveTask(task: String): String {
        val current = task.trim()
        val pending = prefs.getString("phone_agent_pending_task", "").orEmpty()
        val lower = current.lowercase()
        val correction = pending.isNotBlank() && (
            lower.startsWith("es ") || lower.startsWith("era ") || lower.startsWith("quiero decir ") ||
                lower.startsWith("queria decir ") || lower.startsWith("quería decir ") ||
                lower.startsWith("me refiero a ") || lower.startsWith("la app es ") ||
                lower == "glovo" || lower == "globo"
            )
        val merged = if (correction) "$pending\nCorrección del usuario: $current" else current
        return merged
            .replace(Regex("(?i)\\bglobo\\b"), "Glovo")
            .replace(Regex("(?i)\\bglovo\\b"), "Glovo")
    }

    private fun loop(task: String, startStep: Int, onUpdate: (String) -> Unit, onDone: (String) -> Unit) {
        var step = startStep
        while (!cancelled && step < 25) {
            refreshSnapshot(); Thread.sleep(if (step == 0) 300 else 900)
            val ui = runCatching { JSONArray(prefs.getString("accessibility_ui", "[]")) }.getOrElse { JSONArray() }
            val pkg = prefs.getString("foreground_package", "").orEmpty()
            val action = plan(task, ui, pkg, step)
            when (action.optString("action")) {
                "open_app" -> {
                    val app = action.optString("app")
                    onUpdate("Abriendo $app…")
                    if (!openApp(app)) return finish(onDone, "No encuentro la aplicación «$app». Puedes corregirme solo con el nombre, por ejemplo: «es Glovo».", false)
                    Thread.sleep(1200)
                }
                "click" -> {
                    onUpdate("Pulsando ${action.optString("text")}…")
                    send(JarvisAccessibilityService.ACTION_CLICK_TEXT) { putExtra("text", action.optString("text")) }
                }
                "type" -> {
                    onUpdate("Escribiendo…")
                    send(JarvisAccessibilityService.ACTION_SET_TEXT) {
                        putExtra("text", action.optString("text")); putExtra("target", action.optString("target"))
                    }
                }
                "scroll" -> {
                    onUpdate("Desplazando…")
                    send(if (action.optString("direction") == "backward") JarvisAccessibilityService.ACTION_SCROLL_BACKWARD else JarvisAccessibilityService.ACTION_SCROLL_FORWARD)
                }
                "back" -> send(JarvisAccessibilityService.ACTION_BACK)
                "home" -> send(JarvisAccessibilityService.ACTION_HOME)
                "wait" -> Thread.sleep(action.optLong("ms", 900).coerceIn(250, 4000))
                "confirm" -> {
                    requestConfirmation(task, step, action.optString("message"), onUpdate, onDone)
                    return
                }
                "done" -> return finish(onDone, action.optString("message").ifBlank { "Tarea completada." }, true)
                "fail" -> return finish(onDone, action.optString("message").ifBlank { "No he podido completar la tarea." }, false)
                else -> return finish(onDone, "El agente del teléfono recibió una acción no válida.", false)
            }
            step++
        }
        finish(onDone, if (cancelled) "Control del teléfono detenido." else "He detenido la tarea para evitar un bucle de acciones.", false)
    }

    private fun requestConfirmation(task: String, step: Int, message: String, onUpdate: (String) -> Unit, onDone: (String) -> Unit) {
        activity.runOnUiThread {
            AlertDialog.Builder(activity)
                .setTitle("Confirmar compra o acción")
                .setMessage(message.ifBlank { "Jarvis ha preparado la acción y está a punto de ejecutarla. ¿Quieres continuar?" })
                .setPositiveButton("Confirmar") { _, _ ->
                    Thread {
                        loop(task + "\nEl usuario acaba de confirmar explícitamente el paso irreversible solicitado.", step + 1, onUpdate, onDone)
                    }.start()
                }
                .setNegativeButton("Cancelar") { _, _ ->
                    cancelled = true
                    prefs.edit().remove("phone_agent_pending_task").apply()
                    onDone("Acción cancelada antes del paso final.")
                }
                .show()
        }
    }

    private fun refreshSnapshot() = send(JarvisAccessibilityService.ACTION_REFRESH_UI)

    private fun send(action: String, extras: Intent.() -> Unit = {}) {
        activity.sendBroadcast(Intent(action).setPackage(activity.packageName).apply(extras))
    }

    private fun canonical(value: String): String {
        var s = Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        s = s.replace(Regex("[^a-z0-9]+"), " ").trim()
        s = s.replace(Regex("^(el|la|los|las|app|aplicacion|aplicación)\\s+"), "")
        val aliases = mapOf(
            "globo" to "glovo",
            "glovo app" to "glovo",
            "amazon shopping" to "amazon",
            "amazon compras" to "amazon",
            "amazon app" to "amazon"
        )
        return aliases[s] ?: s
    }

    private fun distance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            val cur = IntArray(b.length + 1)
            cur[0] = i + 1
            for (j in b.indices) {
                cur[j + 1] = min(min(cur[j] + 1, prev[j + 1] + 1), prev[j] + if (a[i] == b[j]) 0 else 1)
            }
            prev = cur
        }
        return prev[b.length]
    }

    private fun launchPackage(packageName: String): Boolean {
        val pm = activity.packageManager
        val launch = pm.getLaunchIntentForPackage(packageName) ?: return false
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            activity.runOnUiThread { activity.startActivity(launch) }
            true
        }.getOrDefault(false)
    }

    private fun openApp(name: String): Boolean {
        val wanted = canonical(name)
        if (wanted.isBlank()) return false

        val knownPackages = mapOf(
            "glovo" to listOf("com.glovo"),
            "amazon" to listOf("com.amazon.mShop.android.shopping")
        )
        knownPackages[wanted]?.forEach { pkg ->
            if (launchPackage(pkg)) return true
        }

        val pm = activity.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = pm.queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)
        val scored = activities.map { ri ->
            val label = canonical(ri.loadLabel(pm)?.toString().orEmpty())
            val pkgName = ri.activityInfo.packageName
            val pkgTail = canonical(pkgName.substringAfterLast('.'))
            val score = when {
                label == wanted -> 0
                label.contains(wanted) || wanted.contains(label) -> 1
                pkgTail == wanted || pkgTail.contains(wanted) -> 2
                else -> 3 + distance(label.take(32), wanted.take(32))
            }
            Triple(score, ri, label)
        }.sortedBy { it.first }

        val best = scored.firstOrNull() ?: return false
        val acceptable = best.first <= 2 || distance(best.third, wanted) <= 2
        if (!acceptable) return false

        val pkgName = best.second.activityInfo.packageName
        if (launchPackage(pkgName)) return true

        val explicit = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(pkgName, best.second.activityInfo.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            activity.runOnUiThread { activity.startActivity(explicit) }
            true
        }.getOrDefault(false)
    }

    private fun plan(task: String, ui: JSONArray, pkg: String, step: Int): JSONObject {
        val c = (URL("$backend/api/phone-agent").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 9000; readTimeout = 35000
            setRequestProperty("Content-Type", "application/json")
        }
        val body = JSONObject().put("task", task).put("ui", ui).put("packageName", pkg).put("step", step)
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val raw = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (c.responseCode !in 200..299) return JSONObject().put("action", "fail").put("message", "Error del agente: ${raw.take(180)}")
        return runCatching { JSONObject(raw) }.getOrElse { JSONObject().put("action", "fail").put("message", "Respuesta inválida del agente") }
    }

    private fun finish(onDone: (String) -> Unit, message: String, clearPending: Boolean) {
        if (clearPending) prefs.edit().remove("phone_agent_pending_task").apply()
        activity.runOnUiThread { onDone(message) }
    }
}
