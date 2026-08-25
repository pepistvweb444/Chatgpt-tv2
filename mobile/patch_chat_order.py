from pathlib import Path

p = Path('mobile/src/main/java/com/jarvis/mobile/MainActivity.kt')
s = p.read_text()

field = '    private lateinit var widgetHost: LinearLayout\n'
if 'private var activeWidgetGroup: LinearLayout? = null' not in s:
    if field not in s:
        raise SystemExit('widgetHost field marker not found')
    s = s.replace(field, field + '    private var activeWidgetGroup: LinearLayout? = null\n', 1)

old_begin = '''    private fun beginWidgetGroup(title: String) {
        widgetHost.removeAllViews(); widgetHost.visibility = View.VISIBLE; welcomePanel.visibility = View.GONE
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(2), 0, dp(8)) }
        row.addView(makeAvatar("assistant"))
        row.addView(TextView(this).apply { text = "Jarvis · $title"; textSize = 14f; setTextColor(Color.rgb(220, 232, 255)); setTypeface(typeface, android.graphics.Typeface.BOLD) })
        widgetHost.addView(row)
    }
'''
new_begin = '''    private fun beginWidgetGroup(title: String) {
        welcomePanel.visibility = View.VISIBLE
        widgetHost.removeAllViews(); widgetHost.visibility = View.GONE
        val group = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) }
        }
        activeWidgetGroup = group
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(2), 0, dp(8)) }
        row.addView(makeAvatar("assistant"))
        row.addView(TextView(this).apply { text = "Jarvis · $title"; textSize = 14f; setTextColor(Color.rgb(220, 232, 255)); setTypeface(typeface, android.graphics.Typeface.BOLD) })
        group.addView(row)
        conversationHost.addView(group)
    }
'''
if old_begin in s:
    s = s.replace(old_begin, new_begin, 1)
elif 'activeWidgetGroup = group' not in s:
    raise SystemExit('beginWidgetGroup block not found')

s = s.replace('row.addView(card); widgetHost.addView(row)', 'row.addView(card); (activeWidgetGroup ?: conversationHost).addView(row)')
s = s.replace('val card = widgetHost.findViewWithTag<LinearLayout>("news_card_$i")', 'val card = (activeWidgetGroup ?: conversationHost).findViewWithTag<LinearLayout>("news_card_$i")')

# Keep the quick cards (welcomePanel) visible at the top even while chatting.
s = s.replace('        welcomePanel.visibility = View.GONE\n        val user = role == "user"', '        welcomePanel.visibility = View.VISIBLE\n        val user = role == "user"')
s = s.replace('        welcomePanel.visibility = if (a.length() == 0) View.VISIBLE else View.GONE', '        welcomePanel.visibility = View.VISIBLE')

p.write_text(s)
print('Mobile chat order fixed: user first, assistant widgets below')
