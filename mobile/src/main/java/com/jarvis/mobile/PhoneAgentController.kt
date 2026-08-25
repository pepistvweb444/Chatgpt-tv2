package com.jarvis.mobile

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
                s.startsWith("me refiero a ") || s.startsWith("la app es ") || s == "glovo" || s == "globo"
        )
        if (correction) return true
        val verbs = listOf("abre ","ve a ","entra en ","compra ","comprar ","reserva ","reservar ","busca ","buscar ","navega ","rellena ","añade ","agrega ","pide ","pedir ")
        val targets = listOf("app","aplicación","aplicacion","amazon","glovo","chrome","navegador","web","internet","dia","día","aliexpress","booking","thefork","restaurante","hotel","vuelo")
        return verbs.any { s.contains(it) } && (targets.any { s.contains(it) } || s.contains(" en ") || s.contains(" por internet"))
    }

    private fun looksLikeShoppingTask(task: String): Boolean {
        val s = canonical(task)
        return listOf("compra", "comprar", "anade", "agrega", "carrito", "cesta", "pedido", "glovo", "amazon", "aliexpress", "dia").any { s.contains(it) }
    }

    private fun hasCartEvidence(ui: JSONArray): Boolean {
        val evidence = listOf("carrito", "cesta", "ver cesta", "ver carrito", "subtotal", "articulo", "artículos", "items", "producto añadido", "añadido", "checkout")
        for (i in 0 until ui.length()) {
            val item = ui.optJSONObject(i) ?: continue
            val text = buildString {
                append(item.optString("text")); append(' ')
                append(item.optString("contentDescription")); append(' ')
                append(item.optString("hint")); append(' ')
                append(item.optString("viewId"))
            }.lowercase()
            if (evidence.any { text.contains(it) }) return true
            val numeric = Regex("\\b[1-9]\\d?\\b").find(text)?.value?.toIntOrNull()
            if (numeric != null && (text.contains("carrito") || text.contains("cesta") || text.contains("artículo") || text.contains("item"))) return true
        }
        return false
    }

    fun run(task: String, onUpdate: (String) -> Unit, onDone: (String) -> Unit) {
        cancelled = false
        val resolved = resolveTask(task)
        prefs.edit().putString("phone_agent_pending_task", resolved).apply()
        Thread { loop(resolved, 0, onUpdate, onDone) }.start()
    }

    fun cancel() { cancelled = true; prefs.edit().remove("phone_agent_pending_task").apply() }

    private fun resolveTask(task: String): String {
        val current = task.trim(); val pending = prefs.getString("phone_agent_pending_task", "").orEmpty(); val lower = current.lowercase()
        val correction = pending.isNotBlank() && (lower.startsWith("es ") || lower.startsWith("era ") || lower.startsWith("quiero decir ") || lower.startsWith("queria decir ") || lower.startsWith("quería decir ") || lower.startsWith("me refiero a ") || lower.startsWith("la app es ") || lower == "glovo" || lower == "globo")
        val merged = if (correction) "$pending\nCorrección del usuario: $current" else current
        return merged.replace(Regex("(?i)\\bglobo\\b"), "Glovo").replace(Regex("(?i)\\bglovo\\b"), "Glovo")
    }

    private fun loop(task: String, startStep: Int, onUpdate: (String) -> Unit, onDone: (String) -> Unit) {
        var step = startStep
        while (!cancelled && step < 25) {
            refreshSnapshot(); Thread.sleep(if (step == 0) 350 else 850)
            val ui = runCatching { JSONArray(prefs.getString("accessibility_ui", "[]")) }.getOrElse { JSONArray() }
            val pkg = prefs.getString("foreground_package", "").orEmpty()
            val action = plan(task, ui, pkg, step)
            when (action.optString("action")) {
                "open_app" -> {
                    val app = action.optString("app"); onUpdate("Abriendo $app…")
                    if (!openApp(app)) {
                        onUpdate("No encuentro $app; continúo en el navegador…")
                        openWebSearch("$app ${task.take(180)}")
                    }
                    Thread.sleep(1300)
                }
                "open_url" -> { onUpdate("Abriendo web…"); openUrl(action.optString("url")); Thread.sleep(1200) }
                "web_search" -> { onUpdate("Buscando en Internet…"); openWebSearch(action.optString("query").ifBlank { task }); Thread.sleep(1400) }
                "click" -> { onUpdate("Pulsando ${action.optString("text")}…"); send(JarvisAccessibilityService.ACTION_CLICK_TEXT) { putExtra("text", action.optString("text")) } }
                "type" -> { onUpdate("Escribiendo…"); send(JarvisAccessibilityService.ACTION_SET_TEXT) { putExtra("text", action.optString("text")); putExtra("target", action.optString("target")) } }
                "scroll" -> { onUpdate("Desplazando…"); send(if (action.optString("direction") == "backward") JarvisAccessibilityService.ACTION_SCROLL_BACKWARD else JarvisAccessibilityService.ACTION_SCROLL_FORWARD) }
                "back" -> send(JarvisAccessibilityService.ACTION_BACK)
                "home" -> send(JarvisAccessibilityService.ACTION_HOME)
                "wait" -> Thread.sleep(action.optLong("ms", 900).coerceIn(250, 4000))
                "confirm" -> { requestConfirmation(task, step, action.optString("message"), onUpdate, onDone); return }
                "done" -> {
                    if (looksLikeShoppingTask(task) && !hasCartEvidence(ui)) {
                        onUpdate("Todavía no puedo confirmar que el producto esté en el carrito; verificando…")
                        step++
                        continue
                    }
                    return finish(onDone, action.optString("message").ifBlank { "Tarea completada." }, true)
                }
                "fail" -> return finish(onDone, action.optString("message").ifBlank { "No he podido completar la tarea." }, false)
                else -> return finish(onDone, "El agente del teléfono recibió una acción no válida.", false)
            }
            step++
        }
        finish(onDone, if (cancelled) "Control del teléfono detenido." else "He detenido la tarea para evitar un bucle de acciones.", false)
    }

    private fun requestConfirmation(task: String, step: Int, message: String, onUpdate: (String) -> Unit, onDone: (String) -> Unit) {
        activity.runOnUiThread {
            AlertDialog.Builder(activity).setTitle("Confirmar acción")
                .setMessage(message.ifBlank { "Jarvis ha preparado la acción y está a punto de ejecutarla. ¿Quieres continuar?" })
                .setPositiveButton("Confirmar") { _, _ -> Thread { loop(task + "\nEl usuario acaba de confirmar explícitamente el paso irreversible solicitado.", step + 1, onUpdate, onDone) }.start() }
                .setNegativeButton("Cancelar") { _, _ -> cancelled = true; prefs.edit().remove("phone_agent_pending_task").apply(); onDone("Acción cancelada antes del paso final.") }.show()
        }
    }

    private fun refreshSnapshot() = send(JarvisAccessibilityService.ACTION_REFRESH_UI)
    private fun send(action: String, extras: Intent.() -> Unit = {}) { activity.sendBroadcast(Intent(action).setPackage(activity.packageName).apply(extras)) }

    private fun canonical(value: String): String {
        var s = Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        s = s.replace(Regex("[^a-z0-9]+"), " ").trim().replace(Regex("^(el|la|los|las|app|aplicacion|aplicación)\\s+"), "")
        val aliases = mapOf("globo" to "glovo","glovo app" to "glovo","amazon shopping" to "amazon","amazon compras" to "amazon","amazon app" to "amazon","google chrome" to "chrome","internet" to "chrome","navegador" to "chrome")
        return aliases[s] ?: s
    }
    private fun distance(a: String, b: String): Int { if(a==b)return 0;if(a.isEmpty())return b.length;if(b.isEmpty())return a.length;var prev=IntArray(b.length+1){it};for(i in a.indices){val cur=IntArray(b.length+1);cur[0]=i+1;for(j in b.indices)cur[j+1]=min(min(cur[j]+1,prev[j+1]+1),prev[j]+if(a[i]==b[j])0 else 1);prev=cur};return prev[b.length] }

    private fun launchPackage(packageName: String): Boolean {
        val launch = activity.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { activity.runOnUiThread { activity.startActivity(launch) }; true }.getOrDefault(false)
    }
    private fun openApp(name: String): Boolean {
        val wanted=canonical(name); if(wanted.isBlank())return false
        val known=mapOf("glovo" to listOf("com.glovo"),"amazon" to listOf("com.amazon.mShop.android.shopping"),"chrome" to listOf("com.android.chrome"),"aliexpress" to listOf("com.alibaba.aliexpresshd"),"booking" to listOf("com.booking"))
        known[wanted]?.forEach { if(launchPackage(it)) return true }
        val pm=activity.packageManager; val launcher=Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER); val activities=pm.queryIntentActivities(launcher,PackageManager.MATCH_ALL)
        val scored=activities.map{ri->val label=canonical(ri.loadLabel(pm)?.toString().orEmpty());val pkg=ri.activityInfo.packageName;val tail=canonical(pkg.substringAfterLast('.'));val score=when{label==wanted->0;label.contains(wanted)||wanted.contains(label)->1;tail==wanted||tail.contains(wanted)->2;else->3+distance(label.take(32),wanted.take(32))};Triple(score,ri,label)}.sortedBy{it.first}
        val best=scored.firstOrNull()?:return false; if(!(best.first<=2||distance(best.third,wanted)<=2))return false
        val pkg=best.second.activityInfo.packageName; if(launchPackage(pkg))return true
        val explicit=Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClassName(pkg,best.second.activityInfo.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { activity.runOnUiThread { activity.startActivity(explicit) }; true }.getOrDefault(false)
    }
    private fun openUrl(raw: String): Boolean {
        val url=raw.trim(); if(!url.startsWith("http://")&&!url.startsWith("https://"))return false
        return runCatching { activity.runOnUiThread { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }; true }.getOrDefault(false)
    }
    private fun openWebSearch(query: String): Boolean {
        val uri=Uri.parse("https://www.google.com/search?q="+Uri.encode(query.take(500)))
        return runCatching { activity.runOnUiThread { activity.startActivity(Intent(Intent.ACTION_VIEW,uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }; true }.getOrDefault(false)
    }

    private fun plan(task: String, ui: JSONArray, pkg: String, step: Int): JSONObject {
        val c=(URL("$backend/api/phone-agent").openConnection() as HttpURLConnection).apply { requestMethod="POST";doOutput=true;connectTimeout=9000;readTimeout=35000;setRequestProperty("Content-Type","application/json") }
        val preferred=prefs.getString("ai_provider","auto").orEmpty().ifBlank { "auto" }
        val body=JSONObject().put("task",task).put("ui",ui).put("packageName",pkg).put("step",step).put("preferredProvider",preferred)
        c.outputStream.use{it.write(body.toString().toByteArray())}; val code=c.responseCode; val raw=(if(code in 200..299)c.inputStream else c.errorStream)?.bufferedReader()?.use{it.readText()}.orEmpty()
        if(code !in 200..299)return JSONObject().put("action","fail").put("message","Error del agente: ${raw.take(220)}")
        return runCatching{JSONObject(raw)}.getOrElse{JSONObject().put("action","fail").put("message","Respuesta inválida del agente")}
    }
    private fun finish(onDone:(String)->Unit,message:String,clearPending:Boolean){if(clearPending)prefs.edit().remove("phone_agent_pending_task").apply();activity.runOnUiThread{onDone(message)}}
}
