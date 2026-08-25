from pathlib import Path

p = Path('mobile/src/main/java/com/jarvis/mobile/MainActivity.kt')
s = p.read_text()

field = '    private lateinit var widgetHost: LinearLayout\n'
if 'private var activeWidgetGroup: LinearLayout? = null' not in s and field in s:
    s = s.replace(field, field + '    private var activeWidgetGroup: LinearLayout? = null\n', 1)

new_begin = '''    private fun beginWidgetGroup(title: String) {
        welcomePanel.visibility = View.VISIBLE
        widgetHost.removeAllViews()
        widgetHost.visibility = View.GONE
        val group = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) }
        }
        activeWidgetGroup = group
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(8))
        }
        row.addView(makeAvatar("assistant"))
        row.addView(TextView(this).apply {
            text = "Jarvis · $title"
            textSize = 14f
            setTextColor(Color.rgb(220, 232, 255))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        group.addView(row)
        conversationHost.addView(group)
    }

'''

if 'activeWidgetGroup = group' not in s:
    start = s.find('    private fun beginWidgetGroup(title: String) {')
    if start != -1:
        next_fun = s.find('\n    private fun ', start + 10)
        if next_fun == -1:
            raise SystemExit('next function after beginWidgetGroup not found')
        s = s[:start] + new_begin + s[next_fun + 1:]
    else:
        # If another patch renamed the helper, do not abort the entire build.
        print('beginWidgetGroup not found; skipping structural replacement')

# Route generated widget rows into the conversation stream whenever possible.
s = s.replace('row.addView(card); widgetHost.addView(row)', 'row.addView(card); (activeWidgetGroup ?: conversationHost).addView(row)')
s = s.replace('row.addView(card); widgetHost.addView(row);', 'row.addView(card); (activeWidgetGroup ?: conversationHost).addView(row);')
s = s.replace('val card = widgetHost.findViewWithTag<LinearLayout>("news_card_$i")', 'val card = (activeWidgetGroup ?: conversationHost).findViewWithTag<LinearLayout>("news_card_$i")')

# Keep greeting/quick cards visible while the conversation grows below them.
s = s.replace('        welcomePanel.visibility = View.GONE\n        val user = role == "user"', '        welcomePanel.visibility = View.VISIBLE\n        val user = role == "user"')
s = s.replace('        welcomePanel.visibility = if (a.length() == 0) View.VISIBLE else View.GONE', '        welcomePanel.visibility = View.VISIBLE')

p.write_text(s)
print('Mobile chat order patch completed')
