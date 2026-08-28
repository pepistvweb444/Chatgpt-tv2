package com.jarvis.tv

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import kotlin.math.min

class TvAppAgentController(private val activity: Activity) {
    private val prefs = activity.getSharedPreferences("jarvis_tv", Activity.MODE_PRIVATE)
    private val backend = "https://chatgpt-tv2.vercel.app"
    @Volatile private var cancelled = false

    fun looksLikeTvTask(text: String): Boolean {
        val s = canonical(text)
        val verbs = listOf("abre", "entra", "reproduce", "pon", "busca", "continua", "seguir viendo", "ver ")
        val apps = listOf("netflix", "prime video", "amazon prime", "apple tv", "max", "hbo", "disney", "youtube", "spotify", "twitch", "pluto", "movistar")
        return verbs.any { s.contains(it) } && apps.any { s.contains(it) }
    }

    fun run(task: String, onUpdate: (String) -> Unit, onDone: (String) -> Unit) {
        cancelled = false
        Thread { loop(task, onUpdate, onDone) }.start()
    }

    fun cancel() { cancelled = true }

    private fun loop(task: String, onUpdate: (String) -> Unit, onDone: (String) -> Unit) {
        var step = 0
        val recent = ArrayDeque<String>()
        while (!cancelled && step < 35) {
            refreshSnapshot()
            Thread.sleep(if (step == 0) 300 else 700)
            val ui = runCatching { JSONArray(prefs.getString("accessibility_ui", "[]")) }.getOrElse { JSONArray() }
            val pkg = prefs.getString("foreground_package", "").orEmpty()
            val action = plan(task, ui, pkg, step, recent.toList())
            val sig = action.optString("action") + ":" + action.optString("text") + ":" + action.optString("app")
            recent.addLast(sig); while (recent.size > 8) recent.removeFirst()
            when (action.optString("action")) {
                "open_app" -> {
                    val app = action.optString("app")
                    onUpdate("Abriendo $app…")
                    if (!openApp(app)) return finish(onDone, "No encuentro $app instalado en la televisión.")
                    Thread.sleep(1200)
                }
                "click" -> {
                    val text = action.optString("text")
                    onUpdate("Seleccionando $text…")
                    send(JarvisAccessibilityService.ACTION_CLICK_TEXT) { putExtra("text", text) }
                }
                "type" -> {
                    onUpdate("Escribiendo búsqueda…")
                    send(JarvisAccessibilityService.ACTION_SET_TEXT) {
                        putExtra("text", action.optString("text")); putExtra("target", action.optString("target"))
                    }
                }
                "scroll" -> send(if (action.optString("direction") == "backward") JarvisAccessibilityService.ACTION_SCROLL_BACKWARD else JarvisAccessibilityService.ACTION_SCROLL_FORWARD)
                "back" -> send(JarvisAccessibilityService.ACTION_BACK)
                "home" -> send(JarvisAccessibilityService.ACTION_HOME)
                "wait" -> Thread.sleep(action.optLong("ms", 800L).coerceIn(200L, 3000L))
                "open_url" -> openUrl(action.optString("url"))
                "web_search" -> openWebSearch(action.optString("query").ifBlank { task })
                "confirm" -> return finish(onDone, action.optString("message").ifBlank { "La plataforma requiere confirmación antes de continuar." })
                "done" -> return finish(onDone, action.optString("message").ifBlank { "Acción completada en la televisión." })
                "clarify" -> return finish(onDone, action.optString("message").ifBlank { "Necesito un dato más para continuar." })
                "fail" -> return finish(onDone, action.optString("message").ifBlank { "No he podido completar la acción en la televisión." })
                else -> return finish(onDone, "La aplicación de TV devolvió una acción no válida.")
            }
            step++
        }
        finish(onDone, if (cancelled) "Control de TV detenido." else "He detenido la navegación para evitar un bucle.")
    }

    private fun refreshSnapshot() = send(JarvisAccessibilityService.ACTION_REFRESH_UI)
    private fun send(action: String, extras: Intent.() -> Unit = {}) {
        activity.sendBroadcast(Intent(action).setPackage(activity.packageName).apply(extras))
    }

    private fun openApp(name: String): Boolean {
        val wanted = canonical(name)
        val known = mapOf(
            "netflix" to listOf("com.netflix.ninja", "com.netflix.mediaclient"),
            "prime video" to listOf("com.amazon.avod.thirdpartyclient", "com.amazon.avod"),
            "amazon prime" to listOf("com.amazon.avod.thirdpartyclient", "com.amazon.avod"),
            "apple tv" to listOf("com.apple.atve.androidtv.appletv", "com.apple.atve.sony.appletv"),
            "max" to listOf("com.wbd.stream", "com.hbo.hbonow"),
            "hbo" to listOf("com.wbd.stream", "com.hbo.hbonow"),
            "disney" to listOf("com.disney.disneyplus"),
            "youtube" to listOf("com.google.android.youtube.tv"),
            "spotify" to listOf("com.spotify.tv.android"),
            "twitch" to listOf("tv.twitch.android.app"),
            "pluto" to listOf("tv.pluto.android"),
            "movistar" to listOf("es.plus.yomvi")
        )
        known.entries.firstOrNull { wanted.contains(it.key) || it.key.contains(wanted) }?.value?.forEach { if (launchPackage(it)) return true }

        val pm = activity.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        val apps = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        val scored = apps.map { ri ->
            val label = canonical(ri.loadLabel(pm)?.toString().orEmpty())
            val score = when {
                label == wanted -> 0
                label.contains(wanted) || wanted.contains(label) -> 1
                else -> 3 + distance(label.take(36), wanted.take(36))
            }
            Triple(score, ri, label)
        }.sortedBy { it.first }
        val best = scored.firstOrNull() ?: return false
        if (best.first > 4 && distance(best.third, wanted) > 3) return false
        return launchPackage(best.second.activityInfo.packageName)
    }

    private fun launchPackage(pkg: String): Boolean {
        val pm = activity.packageManager
        val launch = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg) ?: return false
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { activity.runOnUiThread { activity.startActivity(launch) }; true }.getOrDefault(false)
    }

    private fun openUrl(raw: String): Boolean {
        val url = raw.trim(); if (!url.startsWith("http://") && !url.startsWith("https://")) return false
        return runCatching { activity.runOnUiThread { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }; true }.getOrDefault(false)
    }

    private fun openWebSearch(query: String): Boolean {
        val uri = Uri.parse("https://www.google.com/search?q=" + Uri.encode(query.take(400)))
        return runCatching { activity.runOnUiThread { activity.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }; true }.getOrDefault(false)
    }

    private fun plan(task: String, ui: JSONArray, pkg: String, step: Int, recent: List<String>): JSONObject {
        val c = (URL("$backend/api/phone-agent").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 8000; readTimeout = 30000
            setRequestProperty("Content-Type", "application/json")
        }
        val tvTask = """Estás controlando una televisión Android TV/Fire TV mediante mando y accesibilidad, NO un teléfono. 
Objetivo del usuario: $task
Para streaming puedes abrir la app, buscar el título y pulsar Reproducir/Continuar cuando la interfaz lo confirme. No alquiles, compres ni suscribas contenido sin confirmación explícita. Si la app no expone un control accesible, devuelve fail sin inventar éxito."""
        val body = JSONObject()
            .put("task", tvTask)
            .put("ui", ui)
            .put("packageName", pkg)
            .put("step", step)
            .put("preferredProvider", prefs.getString("ai_provider", "auto").orEmpty().ifBlank { "auto" })
            .put("recentActions", JSONArray(recent))
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        val raw = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) return JSONObject().put("action", "fail").put("message", "Error del agente TV: ${raw.take(180)}")
        return runCatching { JSONObject(raw) }.getOrElse { JSONObject().put("action", "fail").put("message", "Respuesta inválida del agente TV") }
    }

    private fun canonical(value: String): String {
        var s = Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        s = s.replace(Regex("[^a-z0-9+]+"), " ").trim()
        return s.replace("amazon prime video", "prime video").replace("hbo max", "max").replace("disney plus", "disney")
    }

    private fun distance(a: String, b: String): Int {
        if (a == b) return 0; if (a.isEmpty()) return b.length; if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            val cur = IntArray(b.length + 1); cur[0] = i + 1
            for (j in b.indices) cur[j + 1] = min(min(cur[j] + 1, prev[j + 1] + 1), prev[j] + if (a[i] == b[j]) 0 else 1)
            prev = cur
        }
        return prev[b.length]
    }

    private fun finish(onDone: (String) -> Unit, message: String) = activity.runOnUiThread { onDone(message) }
}
