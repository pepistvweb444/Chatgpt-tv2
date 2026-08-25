from pathlib import Path
p=Path('mobile/src/main/java/com/jarvis/mobile/MainActivity.kt')
s=p.read_text()
anchor='''        findViewById<View>(R.id.menuPlugins).setOnClickListener { showToolPicker() }'''
insert=anchor+'''\n        findViewById<View>(R.id.menuDomotics).setOnClickListener {\n            closeDrawer()\n            showUnifiedDomoticsWidget()\n        }'''
if 'R.id.menuDomotics).setOnClickListener' not in s:
    if anchor not in s: raise SystemExit('menu anchor not found')
    s=s.replace(anchor,insert,1)
p.write_text(s)
print('Drawer Domótica wired to unified device/room configurator')
