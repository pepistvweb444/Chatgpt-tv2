from pathlib import Path

p=Path('app/src/main/java/com/jarvis/tv/MainActivity.kt')
s=p.read_text()
anchor='    private fun updateChatMeta(text: String, role: String) {'
if 'private fun scrollToView(view: View)' not in s:
    helper=r'''    private fun scrollToView(view: View) {
        val scroll=findViewById<android.widget.ScrollView>(R.id.mainScroll)
        scroll.post {
            val target=(view.top - pDp(18)).coerceAtLeast(0)
            scroll.smoothScrollTo(0,target)
            view.requestFocus()
        }
    }

'''
    if anchor not in s:
        raise SystemExit('updateChatMeta anchor not found')
    s=s.replace(anchor,helper+anchor,1)
p.write_text(s)
print('TV dashboard scroll helper applied')
