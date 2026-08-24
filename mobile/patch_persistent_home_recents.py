from pathlib import Path

# Keep the quick-access header/cards visible while conversations/widgets render below.
p = Path('mobile/src/main/java/com/jarvis/mobile/MainActivity.kt')
s = p.read_text()

# The welcome panel (greeting + quick cards) is now persistent.
s = s.replace('        welcomePanel.visibility = View.GONE\n', '')
s = s.replace('        welcomePanel.visibility = if (a.length() == 0) View.VISIBLE else View.GONE\n', '        welcomePanel.visibility = View.VISIBLE\n')
s = s.replace('conversationId = newConversation(); conversationHost.removeAllViews(); widgetHost.removeAllViews(); widgetHost.visibility = View.GONE; welcomePanel.visibility = View.VISIBLE;', 'conversationId = newConversation(); conversationHost.removeAllViews(); widgetHost.removeAllViews(); widgetHost.visibility = View.GONE; welcomePanel.visibility = View.VISIBLE;')

# menuRecents is now a LinearLayout populated with real clickable conversation rows.
s = s.replace('    private fun refreshDrawerRecents() {\n        val list = chatObjects().take(8); findViewById<TextView>(R.id.menuRecents).text = if (list.isEmpty()) "Todavía no hay conversaciones" else list.joinToString("\\n\\n") { it.optString("title").ifBlank { "Chat" } }\n    }', r'''    private fun refreshDrawerRecents() {
        val host = findViewById<LinearLayout>(R.id.menuRecents)
        host.removeAllViews()
        val list = chatObjects().take(12)
        if (list.isEmpty()) {
            host.addView(TextView(this).apply {
                text = "Todavía no hay conversaciones"
                setTextColor(Color.rgb(195, 203, 216))
                textSize = 14f
                setPadding(dp(4), dp(10), dp(4), dp(10))
            })
            return
        }
        list.forEach { chat ->
            val id = chat.optString("id")
            val title = chat.optString("title").ifBlank { "Chat" }
            host.addView(TextView(this).apply {
                text = title
                setTextColor(Color.rgb(225, 230, 239))
                textSize = 14f
                setPadding(dp(8), dp(12), dp(8), dp(12))
                background = if (id == conversationId) GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat(); setColor(Color.rgb(29, 33, 47))
                } else null
                setOnClickListener {
                    conversationId = id
                    prefs.edit().putString("currentConversation", conversationId).apply()
                    loadConversation()
                    refreshDrawerRecents()
                    closeDrawer()
                    scroll.post { scroll.scrollTo(0, 0) }
                }
            })
        }
    }''')

p.write_text(s)

# Replace the drawer recents TextView with a vertical container so each item has its own click target.
p = Path('mobile/src/main/res/layout/activity_main.xml')
s = p.read_text()
old = '<ScrollView android:layout_width="match_parent" android:layout_height="0dp" android:layout_weight="1"><TextView android:id="@+id/menuRecents" android:layout_width="match_parent" android:layout_height="wrap_content" android:text="Todavía no hay conversaciones" android:textColor="#C3CBD8" android:textSize="14sp" android:lineSpacingExtra="7dp" /></ScrollView>'
new = '<ScrollView android:layout_width="match_parent" android:layout_height="0dp" android:layout_weight="1"><LinearLayout android:id="@+id/menuRecents" android:layout_width="match_parent" android:layout_height="wrap_content" android:orientation="vertical" /></ScrollView>'
if old not in s:
    raise SystemExit('menuRecents layout marker not found')
s = s.replace(old, new)
p.write_text(s)

print('Persistent quick cards and clickable recent chats applied')
