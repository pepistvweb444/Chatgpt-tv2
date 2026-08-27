from pathlib import Path
p=Path('mobile/src/main/java/com/jarvis/mobile/MainActivity.kt')
s=p.read_text()
old='''    private fun showConnections() { AlertDialog.Builder(this).setTitle("Complementos y MCP").setMessage("Usa el botón + junto al campo de texto para elegir las aplicaciones y MCP con las que quieres hablar.").setPositiveButton("Aceptar", null).show() }'''
new='''    private fun showConnections() {
        val items = arrayOf("Homey Cloud", "Home Connect", "Google Home", "Otros MCP")
        AlertDialog.Builder(this)
            .setTitle("Conectores")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(this, HomeyActivity::class.java))
                    1 -> showHomeConnectSettings()
                    2 -> runCatching { startActivity(Intent(this, GoogleHomeActivity::class.java)) }
                    else -> showToolPicker()
                }
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }'''
if old not in s:
    raise SystemExit('showConnections anchor not found')
s=s.replace(old,new,1)
p.write_text(s)
print('Homey Cloud exposed in Connectors')
