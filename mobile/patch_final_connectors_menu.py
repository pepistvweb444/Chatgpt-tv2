from pathlib import Path
import re
p=Path('mobile/src/main/java/com/jarvis/mobile/MainActivity.kt')
s=p.read_text()
start=s.find('    private fun showConnections()')
if start<0:
    print('showConnections not found; skipped')
    raise SystemExit(0)
brace=s.find('{',start)
if brace<0:
    raise SystemExit(0)
depth=0; end=-1
for i in range(brace,len(s)):
    if s[i]=='{': depth+=1
    elif s[i]=='}':
        depth-=1
        if depth==0:
            end=i+1; break
if end<0: raise SystemExit(0)
new='''    private fun showConnections() {
        val items = arrayOf("Homey Cloud", "Home Connect", "Google Home", "LG ThinQ", "Otros MCP")
        AlertDialog.Builder(this)
            .setTitle("Conectores")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> runCatching { startActivity(Intent(this, HomeyActivity::class.java)) }
                    1 -> runCatching { showUnifiedDomoticsWidget() }
                    2 -> runCatching { startActivity(Intent(this, GoogleHomeActivity::class.java)) }
                    3 -> runCatching { startActivity(Intent(this, LgThinQActivity::class.java)) }
                    4 -> runCatching { showMcpSettings() }
                }
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }'''
s=s[:start]+new+s[end:]
p.write_text(s)
print('Final connectors dialog now includes LG ThinQ')
