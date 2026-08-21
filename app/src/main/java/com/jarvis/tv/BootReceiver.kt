package com.jarvis.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val canOverlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)
        if (!canOverlay) return
        try {
            ContextCompat.startForegroundService(context, Intent(context, OverlayService::class.java))
        } catch (_: Exception) {}
    }
}
