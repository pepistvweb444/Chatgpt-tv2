from pathlib import Path
p=Path('mobile/src/main/java/com/jarvis/mobile/JarvisNotificationListener.kt')
s=p.read_text()
old='n.largeIcon?.loadDrawable(this)'
new='n.getLargeIcon()?.loadDrawable(this)'
if old in s:
    s=s.replace(old,new)
# Keep a safe fallback in case a generated variant used largeIcon as the legacy Bitmap field.
s=s.replace('CallStateStore.drawableToBase64(n.largeIcon.loadDrawable(this))','CallStateStore.drawableToBase64(n.getLargeIcon()?.loadDrawable(this))')
p.write_text(s)
print('VoIP call icon uses Notification.getLargeIcon() Android Icon API')
