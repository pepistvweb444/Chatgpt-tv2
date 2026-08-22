package com.jarvis.tv

import android.app.Application

class JarvisApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        JarvisSync.start(this)
    }
}
