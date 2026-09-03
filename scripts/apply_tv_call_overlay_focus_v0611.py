from pathlib import Path
p=Path('app/src/main/java/com/jarvis/tv/JarvisAccessibilityService.kt')
s=p.read_text()
old='WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT).apply{gravity=Gravity.CENTER}'
new='WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,PixelFormat.TRANSLUCENT).apply{gravity=Gravity.CENTER}'
if old not in s:
    raise SystemExit('call overlay params anchor not found')
s=s.replace(old,new,1)
p.write_text(s)
print('Fire TV call filter overlay made focusable for D-pad remote')
