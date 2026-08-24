package com.jarvis.tv

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.view.KeyEvent

class TvAppController(private val activity: Activity) {
    data class Result(val handled: Boolean, val message: String = "")

    fun handle(raw: String): Result {
        val text = raw.trim()
        val lower = text.lowercase()
        when {
            lower.startsWith("abre ") || lower.startsWith("abrir ") -> {
                val name = text.substringAfter(' ').trim()
                return if (openApp(name)) Result(true, "He abierto $name en la televisión.") else Result(false)
            }
            lower.contains("pausa") || lower == "pausar" -> return media(KeyEvent.KEYCODE_MEDIA_PAUSE, "Reproducción pausada.")
            lower.contains("reanuda") || lower.contains("continúa reproduciendo") || lower.contains("continua reproduciendo") || lower == "reproducir" -> return media(KeyEvent.KEYCODE_MEDIA_PLAY, "Continuando la reproducción.")
            lower.contains("siguiente") && (lower.contains("capítulo") || lower.contains("canción") || lower.contains("pista")) -> return media(KeyEvent.KEYCODE_MEDIA_NEXT, "He pasado al siguiente elemento.")
            lower.contains("anterior") && (lower.contains("capítulo") || lower.contains("canción") || lower.contains("pista")) -> return media(KeyEvent.KEYCODE_MEDIA_PREVIOUS, "He vuelto al elemento anterior.")
            lower.contains("para la reproducción") || lower == "detén" || lower == "detener" -> return media(KeyEvent.KEYCODE_MEDIA_STOP, "He detenido la reproducción.")
        }
        return Result(false)
    }

    fun openApp(name: String): Boolean {
        val wanted = name.lowercase().trim()
        val aliases = mapOf(
            "prime" to listOf("prime video", "amazon video"),
            "amazon" to listOf("prime video", "amazon video"),
            "netflix" to listOf("netflix"),
            "spotify" to listOf("spotify"),
            "youtube" to listOf("youtube"),
            "fotos" to listOf("photos", "fotos", "gallery", "galería")
        )
        val needles = aliases.entries.firstOrNull { wanted.contains(it.key) }?.value ?: listOf(wanted)
        val apps = activity.packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
        val match = apps.firstOrNull { app ->
            val label = activity.packageManager.getApplicationLabel(app).toString().lowercase()
            needles.any { label.contains(it) }
        } ?: return false
        val launch = activity.packageManager.getLaunchIntentForPackage(match.packageName) ?: return false
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        activity.startActivity(launch)
        return true
    }

    private fun media(keyCode: Int, message: String): Result {
        val am = activity.getSystemService(Activity.AUDIO_SERVICE) as AudioManager
        val down = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
        val up = KeyEvent(KeyEvent.ACTION_UP, keyCode)
        @Suppress("DEPRECATION")
        am.dispatchMediaKeyEvent(down)
        @Suppress("DEPRECATION")
        am.dispatchMediaKeyEvent(up)
        return Result(true, message)
    }
}
