from pathlib import Path

# All dynamic response widgets belong to the conversation flow, directly after
# the user's message. The old dedicated widgetHost lives above conversationHost
# in the layout, which made results appear at the top of the chat.
src = Path('mobile/src/main/java/com/jarvis/mobile/MainActivity.kt')
s = src.read_text()
s=s.replace('widgetHost.addView(', 'conversationHost.addView(')
s=s.replace('widgetHost.removeAllViews(); widgetHost.visibility = View.VISIBLE; welcomePanel.visibility = View.GONE',
            'widgetHost.visibility = View.GONE; welcomePanel.visibility = View.GONE')
s=s.replace('widgetHost.removeAllViews(); widgetHost.visibility=View.VISIBLE; welcomePanel.visibility=View.GONE',
            'widgetHost.visibility=View.GONE; welcomePanel.visibility=View.GONE')

# Any click listener that was attached to the obsolete host should not hijack
# the whole conversation. Keep provider cards independently clickable instead.
s=s.replace('if (a.length() > 0) widgetHost.setOnClickListener { startActivity(Intent(this, GoogleHomeActivity::class.java)) }',
            'if (a.length() > 0) { /* cards already render inline */ }')

# Re-wire the drawer as the LAST patch so later generation scripts cannot erase it.
listener='''        findViewById<View>(R.id.menuDomotics).setOnClickListener {
            closeDrawer()
            showUnifiedDomoticsWidget()
            scroll.postDelayed({ scroll.fullScroll(ScrollView.FOCUS_DOWN) }, 120L)
        }
'''
import re
s=re.sub(r'\s*findViewById<View>\(R\.id\.menuDomotics\)\.setOnClickListener\s*\{[\s\S]*?\n\s*\}', '', s, count=1)
anchor='        findViewById<View>(R.id.menuPlugins).setOnClickListener { showToolPicker() }\n'
if anchor not in s:
    raise SystemExit('menuPlugins anchor not found')
s=s.replace(anchor, anchor+listener, 1)

# Ensure every widget group scrolls to the newly appended response.
needle='''        conversationHost.addView(row)
    }

    private fun addTextWidget'''
if needle in s:
    s=s.replace(needle, '''        conversationHost.addView(row)
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun addTextWidget''', 1)

# Expose Homey Cloud directly in the Connectors dialog. Previously HomeyActivity
# existed but the connectors UI only showed a generic explanatory message.
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
if old in s:
    s=s.replace(old,new,1)
elif 'private fun showConnections()' in s and 'Homey Cloud' not in s[s.find('private fun showConnections()'):s.find('private fun showConnections()')+1200]:
    raise SystemExit('showConnections exists but has an unexpected format')

src.write_text(s)
print('Inline conversation widgets + final Domótica drawer listener + Homey connector applied')
