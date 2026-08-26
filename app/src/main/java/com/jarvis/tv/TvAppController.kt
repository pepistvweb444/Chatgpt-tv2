package com.jarvis.tv

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.view.KeyEvent

class TvAppController(private val activity: Activity) {
    data class Result(val handled: Boolean, val message: String = "")

    fun handle(raw: String): Result {
        val text = raw.trim(); val lower = text.lowercase()
        when {
            lower.startsWith("abre ") || lower.startsWith("abrir ") -> {
                val target = text.substringAfter(' ').trim()
                val app = detectApp(target)
                if (app != null && openApp(app)) {
                    val query = extractContentQuery(target, app)
                    if (query.isNotBlank()) {
                        activity.window.decorView.postDelayed({ searchAndPlay(query) }, 1600)
                        return Result(true, "He abierto $app y voy a buscar $query en la televisión.")
                    }
                    return Result(true, "He abierto $app en la televisión.")
                }
                return if (openApp(target)) Result(true, "He abierto $target en la televisión.") else Result(false)
            }
            (lower.contains("pon ") || lower.contains("reproduce ") || lower.contains("ver ")) && detectApp(lower) != null -> {
                val app=detectApp(lower)!!; val q=extractContentQuery(text,app)
                if(!openApp(app)) return Result(false)
                activity.window.decorView.postDelayed({ searchAndPlay(q) },1600)
                return Result(true,"Buscando ${q.ifBlank{"el contenido"}} en $app…")
            }
            lower.contains("busca ") && detectApp(lower) != null -> {
                val app=detectApp(lower)!!; val q=text.substringAfter("busca ","").substringBefore(" en ").trim()
                if(!openApp(app)) return Result(false)
                activity.window.decorView.postDelayed({ searchOnly(q) },1600)
                return Result(true,"Buscando $q en $app…")
            }
            lower.contains("pausa") || lower == "pausar" -> return media(KeyEvent.KEYCODE_MEDIA_PAUSE, "Reproducción pausada.")
            lower.contains("reanuda") || lower.contains("continúa reproduciendo") || lower.contains("continua reproduciendo") || lower == "reproducir" -> return media(KeyEvent.KEYCODE_MEDIA_PLAY, "Continuando la reproducción.")
            lower.contains("siguiente") && (lower.contains("capítulo") || lower.contains("canción") || lower.contains("pista")) -> return media(KeyEvent.KEYCODE_MEDIA_NEXT, "He pasado al siguiente elemento.")
            lower.contains("anterior") && (lower.contains("capítulo") || lower.contains("canción") || lower.contains("pista")) -> return media(KeyEvent.KEYCODE_MEDIA_PREVIOUS, "He vuelto al elemento anterior.")
            lower.contains("para la reproducción") || lower == "detén" || lower == "detener" -> return media(KeyEvent.KEYCODE_MEDIA_STOP, "He detenido la reproducción.")
        }
        return Result(false)
    }

    private fun detectApp(s:String):String? = listOf("netflix","prime video","prime","amazon","youtube","spotify","disney+","disney plus","max","hbo max","movistar plus","movistar+").firstOrNull{s.contains(it,true)}
    private fun extractContentQuery(text:String,app:String):String {
        var q=text
        listOf("abre","abrir","pon","reproduce","reproducir","ver","busca","buscar").forEach{q=q.replace(Regex("(?i)^\\s*$it\\s+"),"")}
        q=q.replace(Regex("(?i)\\s+(en|con|desde)\\s+(la\\s+app\\s+de\\s+)?${Regex.escape(app)}.*$"),"")
        q=q.replace(Regex("(?i)^(netflix|prime video|prime|amazon|youtube|spotify|disney\\+|disney plus|max|hbo max|movistar plus|movistar\\+)\\s*"),"")
        return q.trim(' ',',','.',':',';')
    }

    fun openApp(name: String): Boolean {
        val wanted = name.lowercase().trim()
        val aliases = mapOf(
            "prime" to listOf("prime video", "amazon video"), "amazon" to listOf("prime video", "amazon video"),
            "netflix" to listOf("netflix"), "spotify" to listOf("spotify"), "youtube" to listOf("youtube"),
            "disney+" to listOf("disney+","disney plus"), "disney plus" to listOf("disney+","disney plus"),
            "max" to listOf("max","hbo max"), "hbo max" to listOf("max","hbo max"),
            "movistar plus" to listOf("movistar plus","movistar+"), "movistar+" to listOf("movistar plus","movistar+"),
            "fotos" to listOf("photos", "fotos", "gallery", "galería")
        )
        val needles = aliases.entries.firstOrNull { wanted.contains(it.key) }?.value ?: listOf(wanted)
        val apps = activity.packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
        val match = apps.firstOrNull { app -> val label=activity.packageManager.getApplicationLabel(app).toString().lowercase(); needles.any { label.contains(it) } } ?: return false
        val launch = activity.packageManager.getLaunchIntentForPackage(match.packageName) ?: return false
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); activity.startActivity(launch); return true
    }

    private fun send(action:String, extras:Intent.()->Unit={}){ activity.sendBroadcast(Intent(action).setPackage(activity.packageName).apply(extras)) }
    private fun searchOnly(query:String){
        if(query.isBlank())return
        send(JarvisAccessibilityService.ACTION_CLICK_TEXT){putExtra("text","Buscar")}
        activity.window.decorView.postDelayed({ send(JarvisAccessibilityService.ACTION_SET_TEXT){putExtra("text",query);putExtra("target","Buscar")}; activity.window.decorView.postDelayed({send(JarvisAccessibilityService.ACTION_CLICK_TEXT){putExtra("text",query)}},900) },500)
    }
    private fun searchAndPlay(query:String){
        if(query.isBlank()){ media(KeyEvent.KEYCODE_MEDIA_PLAY,""); return }
        searchOnly(query)
        activity.window.decorView.postDelayed({ send(JarvisAccessibilityService.ACTION_CLICK_TEXT){putExtra("text",query)}; activity.window.decorView.postDelayed({
            val labels=listOf("Reproducir","Ver ahora","Play","Continuar","Ver","Watch now")
            labels.forEachIndexed{i,l->activity.window.decorView.postDelayed({send(JarvisAccessibilityService.ACTION_CLICK_TEXT){putExtra("text",l)}},i*250L)}
        },1100)},1700)
    }

    private fun media(keyCode: Int, message: String): Result {
        val am = activity.getSystemService(Activity.AUDIO_SERVICE) as AudioManager
        @Suppress("DEPRECATION") am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,keyCode))
        @Suppress("DEPRECATION") am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP,keyCode))
        return Result(true,message)
    }
}
