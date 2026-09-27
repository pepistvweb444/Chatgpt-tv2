package com.jarvis.tv

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val canOverlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)
        if (canOverlay) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, OverlayService::class.java))
            }
        }
        // Some Android TV builds restrict microphone foreground services directly
        // from BOOT_COMPLETED. We try here and MainActivity retries whenever Jarvis opens.
        runCatching {
            ContextCompat.startForegroundService(context, Intent(context, WakeWordService::class.java))
        }

        val prefs = context.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
        val sofaEnabled = prefs.getBoolean("sofaVisionEnabled", false)
        val cameraGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (sofaEnabled && cameraGranted) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, SofaVisionService::class.java))
            }
        }
    }
}
