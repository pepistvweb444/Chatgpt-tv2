from pathlib import Path
p=Path('mobile/src/main/java/com/jarvis/mobile/DeviceHubActivity.kt')
s=p.read_text()
anchor='''        add("Homey Cloud · luces y dispositivos") { startActivity(Intent(this, HomeyActivity::class.java)) }'''
line=anchor+'''\n        add("LG ThinQ · lavadora y TV") { startActivity(Intent(this, LgThinQActivity::class.java)) }'''
if 'LgThinQActivity::class.java' not in s:
    if anchor not in s: raise SystemExit('Homey button anchor not found')
    s=s.replace(anchor,line,1)
p.write_text(s)
print('LG ThinQ entry added to Device Hub')
