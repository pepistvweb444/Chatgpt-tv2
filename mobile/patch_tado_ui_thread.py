from pathlib import Path

p = Path('mobile/src/main/java/com/jarvis/mobile/MainActivity.kt')
s = p.read_text()

# Tado OAuth completion runs on a worker thread. Updating the selected-tools UI
# from there causes Android's "Only the original thread..." exception.
s = s.replace(
    'prefs.edit().putString("selected_tools", JSONArray(names).toString()).apply()\n        restoreSelectedTools()',
    'prefs.edit().putString("selected_tools", JSONArray(names).toString()).apply()\n        runOnUiThread { restoreSelectedTools() }'
)

# A successful connection check should immediately show the actual thermostat/
# climate zones rather than only displaying a Toast.
s = s.replace(
    'runOnUiThread { Toast.makeText(this, "Conexión Tado correcta · $name", Toast.LENGTH_LONG).show() }',
    'runOnUiThread {\n                    Toast.makeText(this, "Conexión Tado correcta · $name", Toast.LENGTH_LONG).show()\n                    handleTadoRequest("Dime el estado del aire acondicionado de Tado")\n                }'
)

p.write_text(s)
print('Tado UI thread fixed and thermostat widget wired after connection check')
