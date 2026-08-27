from pathlib import Path
import re

src = Path('mobile/src/main/java/com/jarvis/mobile/MainActivity.kt')
s = src.read_text()

# Inline dynamic widgets into the conversation flow.
s=s.replace('widgetHost.addView(', 'conversationHost.addView(')
s=s.replace('widgetHost.removeAllViews(); widgetHost.visibility = View.VISIBLE; welcomePanel.visibility = View.GONE',
            'widgetHost.visibility = View.GONE; welcomePanel.visibility = View.GONE')
s=s.replace('widgetHost.removeAllViews(); widgetHost.visibility=View.VISIBLE; welcomePanel.visibility=View.GONE',
            'widgetHost.visibility=View.GONE; welcomePanel.visibility=View.GONE')
s=s.replace('if (a.length() > 0) widgetHost.setOnClickListener { startActivity(Intent(this, GoogleHomeActivity::class.java)) }',
            'if (a.length() > 0) { /* cards already render inline */ }')

# Re-wire Domótica as the last patch.
listener='''        findViewById<View>(R.id.menuDomotics).setOnClickListener {
            closeDrawer()
            showUnifiedDomoticsWidget()
            scroll.postDelayed({ scroll.fullScroll(ScrollView.FOCUS_DOWN) }, 120L)
        }
'''
s=re.sub(r'\s*findViewById<View>\(R\.id\.menuDomotics\)\.setOnClickListener\s*\{[\s\S]*?\n\s*\}', '', s, count=1)
anchor='        findViewById<View>(R.id.menuPlugins).setOnClickListener { showToolPicker() }\n'
if anchor in s:
    s=s.replace(anchor, anchor+listener, 1)
elif 'R.id.menuDomotics).setOnClickListener' not in s:
    # Fallback: inject before end of onCreate using a stable nearby listener.
    fallback='        findViewById<View>(R.id.voiceSettings).setOnClickListener { showVoiceSettings() }\n'
    if fallback in s:
        s=s.replace(fallback, fallback+listener, 1)

# Ensure appended widget groups scroll into view.
needle='''        conversationHost.addView(row)
    }

    private fun addTextWidget'''
if needle in s:
    s=s.replace(needle, '''        conversationHost.addView(row)
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun addTextWidget''', 1)

# Expose connectors without assuming the exact previous formatting of showConnections().
new_fun='''    private fun showConnections() {
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
    }
'''
start=s.find('    private fun showConnections()')
if start >= 0:
    # replace function by brace counting, tolerant of single-line or multiline variants
    brace=s.find('{', start)
    if brace >= 0:
        depth=0; end=None
        for i in range(brace, len(s)):
            if s[i]=='{': depth+=1
            elif s[i]=='}':
                depth-=1
                if depth==0:
                    end=i+1; break
        if end:
            s=s[:start]+new_fun+s[end:]

src.write_text(s)
print('Inline widgets + Domótica + connectors patch applied tolerantly')
