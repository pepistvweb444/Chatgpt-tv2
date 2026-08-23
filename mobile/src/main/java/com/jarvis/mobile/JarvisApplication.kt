package com.jarvis.mobile

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

class JarvisApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        JarvisSync.start(this)
        val prefs=getSharedPreferences("jarvis_mobile",MODE_PRIVATE)
        if(prefs.getBoolean("wake_word_enabled",false) && ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED){
            runCatching{ContextCompat.startForegroundService(this,Intent(this,WakeWordService::class.java))}
        }
    }
}
