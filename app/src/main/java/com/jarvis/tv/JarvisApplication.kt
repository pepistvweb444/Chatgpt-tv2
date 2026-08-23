package com.jarvis.tv

import android.app.Application

class JarvisApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Keep TV sync active from application start.
        JarvisSync.start(this)
    }
}
