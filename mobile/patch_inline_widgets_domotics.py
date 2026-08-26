from pathlib import Path
p=Path('mobile/src/main/java/com/jarvis/mobile/MainActivity.kt')
s=p.read_text()

# All dynamic response widgets belong to the conversation flow, directly after
# the user's message. The old dedicated widgetHost lives above conversationHost
# in the layout, which made results appear at the top of the chat.
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

p.write_text(s)
print('Inline conversation widgets + final Domótica drawer listener applied')
