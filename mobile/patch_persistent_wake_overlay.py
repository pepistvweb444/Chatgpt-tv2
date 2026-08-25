from pathlib import Path

p = Path('mobile/src/main/java/com/jarvis/mobile/MainActivity.kt')
s = p.read_text()

if 'import android.provider.Settings' not in s:
    s = s.replace('import android.os.Looper\n', 'import android.os.Looper\nimport android.provider.Settings\n')

s = s.replace(
    'findViewById<View>(R.id.mic).setOnClickListener { toggleOpenAiVoiceCapture() }',
    'findViewById<View>(R.id.mic).setOnClickListener { enablePersistentHandsFree() }'
)

marker = '    private fun startOpenAiVoiceCapture() {'
method = r'''    private fun enablePersistentHandsFree() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO)
            return
        }
        prefs.edit().putBoolean("wake_word_enabled", true).apply()
        handsFreeMode = true
        runCatching { androidx.core.content.ContextCompat.startForegroundService(this, Intent(this, WakeWordService::class.java)) }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            status.text = "Activa Mostrar sobre otras apps para la burbuja de Jarvis"
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
        } else {
            status.text = "Hola Jarvis / Hola Ale · escucha permanente"
        }
        if (!voiceRecording) startOpenAiVoiceCapture()
    }

'''
if 'private fun enablePersistentHandsFree()' not in s and marker in s:
    s = s.replace(marker, method + marker, 1)

# After RECORD_AUDIO permission is granted, enable the persistent mode, not one-shot capture.
s = s.replace(
    'if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) startOpenAiVoiceCapture() else status.text = "Permiso de micrófono denegado"',
    'if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) enablePersistentHandsFree() else status.text = "Permiso de micrófono denegado"'
)

p.write_text(s)

# Add explicit controls in the phone-control screen for overlay and persistent wake mode.
p = Path('mobile/src/main/java/com/jarvis/mobile/DeviceHubActivity.kt')
s = p.read_text()
anchor = '        add("Acceso a notificaciones") { openNotificationListenerSettings() }\n'
controls = r'''        add("Permitir burbuja de Jarvis sobre otras apps") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } else Toast.makeText(this, "La burbuja de Jarvis ya tiene permiso", Toast.LENGTH_SHORT).show()
        }
        add("Activar Hola Jarvis / Hola Ale siempre") {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 53)
            } else {
                getSharedPreferences("jarvis_mobile", MODE_PRIVATE).edit().putBoolean("wake_word_enabled", true).apply()
                ContextCompat.startForegroundService(this, Intent(this, WakeWordService::class.java))
                Toast.makeText(this, "Jarvis queda escuchando. Di Hola Jarvis o Hola Ale.", Toast.LENGTH_LONG).show()
            }
        }
        add("Desactivar Hola Jarvis / Hola Ale") {
            getSharedPreferences("jarvis_mobile", MODE_PRIVATE).edit().putBoolean("wake_word_enabled", false).apply()
            stopService(Intent(this, WakeWordService::class.java))
            stopService(Intent(this, JarvisOverlayService::class.java))
            Toast.makeText(this, "Escucha permanente desactivada", Toast.LENGTH_SHORT).show()
        }
'''
if 'Permitir burbuja de Jarvis sobre otras apps' not in s and anchor in s:
    s = s.replace(anchor, controls + anchor, 1)

# Include overlay/wake state in the diagnostics label.
old = 'WhatsApp/RCS permiso ${if (notificationAccessGranted()) "✓" else "✗"} · lector ${if (listenerConnected) "CONECTADO" else "NO CONECTADO"}"'
new = 'WhatsApp/RCS permiso ${if (notificationAccessGranted()) "✓" else "✗"} · lector ${if (listenerConnected) "CONECTADO" else "NO CONECTADO"}\\nBurbuja ${if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) "✓" else "✗"} · Hola Jarvis ${if (prefs.getBoolean("wake_word_enabled", false)) "ACTIVO" else "INACTIVO"}"'
s = s.replace(old, new)
p.write_text(s)
print('Persistent wake-word and overlay controls applied')
