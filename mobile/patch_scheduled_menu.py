from pathlib import Path

p = Path('mobile/src/main/java/com/jarvis/mobile/MainActivity.kt')
s = p.read_text()
old = '''        findViewById<View>(R.id.wakeWord).setOnClickListener {
            closeDrawer(); runCatching { startService(Intent(this, WakeWordService::class.java)) }; status.text = "Hola Jarvis · escuchando"
        }'''
new = '''        findViewById<View>(R.id.wakeWord).setOnClickListener {
            closeDrawer()
            showScheduledTasks()
        }'''
if old not in s:
    raise SystemExit('Programadas listener pattern not found')
s = s.replace(old, new, 1)
marker = '    private fun showConnections() {'
method = '''    private fun showScheduledTasks() {
        runCatching {
            AlertDialog.Builder(this)
                .setTitle("Programadas")
                .setMessage("Aquí aparecerán tus recordatorios, tareas y automatizaciones programadas. Esta sección ya no inicia el servicio de escucha de Jarvis.")
                .setPositiveButton("Aceptar", null)
                .show()
        }.onFailure {
            status.text = "Programadas no disponible"
            Toast.makeText(this, "No se pudo abrir Programadas", Toast.LENGTH_SHORT).show()
        }
    }

'''
if marker not in s:
    raise SystemExit('Insertion marker not found')
s = s.replace(marker, method + marker, 1)
p.write_text(s)
print('Patched Programadas menu: removed WakeWordService launch and added crash-safe screen')
