from pathlib import Path
p = Path('mobile/src/main/java/com/jarvis/mobile/MainActivity.kt')
s = p.read_text()

# Library rendering helpers use Locale when formatting/filtering artifact metadata.
if 'import java.util.Locale' not in s:
    import_anchor = 'import java.util.UUID\n'
    if import_anchor in s:
        s = s.replace(import_anchor, 'import java.util.Locale\n' + import_anchor, 1)
    else:
        s = s.replace('import java.net.URL\n', 'import java.net.URL\nimport java.util.Locale\n', 1)

old = '''        findViewById<View>(R.id.files).setOnClickListener {
            closeDrawer(); runCatching { startActivity(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }) }
        }'''
new = '''        findViewById<View>(R.id.files).setOnClickListener {
            closeDrawer()
            runCatching { startActivity(Intent(this, LibraryActivity::class.java)) }
        }'''
if old in s:
    s = s.replace(old, new, 1)
old2 = '''        prefs.edit().putString("chat_$conversationId", a.toString()).apply()
        if (role == "user") updateConversationTitle(text)'''
new2 = '''        prefs.edit().putString("chat_$conversationId", a.toString()).apply()
        if (role == "assistant") LibraryStore.indexMessage(this, text, images, videos, conversationId)
        if (role == "user") updateConversationTitle(text)'''
if old2 in s and 'LibraryStore.indexMessage(this, text' not in s:
    s = s.replace(old2, new2, 1)
p.write_text(s)
print('Library wired into MainActivity')
