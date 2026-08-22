package com.jarvis.mobile

import android.app.Application

class JarvisApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        JarvisSync.start(this)
    }
}
