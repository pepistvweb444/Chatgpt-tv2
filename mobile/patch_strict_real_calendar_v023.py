from pathlib import Path

def replace_function(text, signature, replacement):
    start=text.find(signature)
    if start<0: raise SystemExit(f'{signature} not found')
    brace=text.find('{',start); depth=0; in_string=False; esc=False
    for i in range(brace,len(text)):
        ch=text[i]
        if in_string:
            if esc: esc=False
            elif ch=='\\': esc=True
            elif ch=='"': in_string=False
        else:
            if ch=='"': in_string=True
            elif ch=='{': depth+=1
            elif ch=='}':
                depth-=1
                if depth==0:return text[:start]+replacement+text[i+1:]
    raise SystemExit('function end not found')

# Manifest permission is mandatory for real CalendarContract data.
p=Path('mobile/src/main/AndroidManifest.xml')
m=p.read_text()
if 'android.permission.READ_CALENDAR' not in m:
    anchor='    <uses-permission android:name="android.permission.READ_CONTACTS" />\n'
    if anchor not in m: raise SystemExit('contacts permission anchor missing')
    m=m.replace(anchor,anchor+'    <uses-permission android:name="android.permission.READ_CALENDAR" />\n',1)
p.write_text(m)

# Phone bridge: /agenda contains only CalendarContract events, never guessed notification text.
p=Path('mobile/src/main/java/com/jarvis/mobile/PhoneBridgeService.kt')
s=p.read_text()
agenda=r'''    private fun agendaForTv(): JSONObject {
        val now=System.currentTimeMillis()
        val end=now+7L*24L*60L*60L*1000L
        val events=JSONArray()
        val hasCalendar=ContextCompat.checkSelfPermission(this,Manifest.permission.READ_CALENDAR)==PackageManager.PERMISSION_GRANTED
        var calendarCount=0
        var queryError=""
        if(hasCalendar) {
            calendarCount=runCatching {
                contentResolver.query(
                    android.provider.CalendarContract.Calendars.CONTENT_URI,
                    arrayOf(android.provider.CalendarContract.Calendars._ID),
                    null,null,null
                )?.use{it.count} ?: 0
            }.getOrDefault(0)
            runCatching {
                val builder=android.provider.CalendarContract.Instances.CONTENT_URI.buildUpon()
                android.content.ContentUris.appendId(builder,now)
                android.content.ContentUris.appendId(builder,end)
                val projection=arrayOf(
                    android.provider.CalendarContract.Instances.EVENT_ID,
                    android.provider.CalendarContract.Instances.TITLE,
                    android.provider.CalendarContract.Instances.BEGIN,
                    android.provider.CalendarContract.Instances.END,
                    android.provider.CalendarContract.Instances.EVENT_LOCATION,
                    android.provider.CalendarContract.Instances.ALL_DAY,
                    android.provider.CalendarContract.Instances.CALENDAR_DISPLAY_NAME
                )
                contentResolver.query(builder.build(),projection,null,null,android.provider.CalendarContract.Instances.BEGIN+" ASC")?.use{c->
                    val ii=c.getColumnIndex(android.provider.CalendarContract.Instances.EVENT_ID)
                    val ti=c.getColumnIndex(android.provider.CalendarContract.Instances.TITLE)
                    val bi=c.getColumnIndex(android.provider.CalendarContract.Instances.BEGIN)
                    val ei=c.getColumnIndex(android.provider.CalendarContract.Instances.END)
                    val li=c.getColumnIndex(android.provider.CalendarContract.Instances.EVENT_LOCATION)
                    val ai=c.getColumnIndex(android.provider.CalendarContract.Instances.ALL_DAY)
                    val ci=c.getColumnIndex(android.provider.CalendarContract.Instances.CALENDAR_DISPLAY_NAME)
                    while(c.moveToNext()&&events.length()<60){
                        val title=if(ti>=0)c.getString(ti).orEmpty().trim() else ""
                        if(title.isBlank())continue
                        events.put(JSONObject()
                            .put("eventId",if(ii>=0)c.getLong(ii) else -1L)
                            .put("title",title)
                            .put("begin",if(bi>=0)c.getLong(bi) else 0L)
                            .put("end",if(ei>=0)c.getLong(ei) else 0L)
                            .put("location",if(li>=0)c.getString(li).orEmpty() else "")
                            .put("allDay",ai>=0&&c.getInt(ai)!=0)
                            .put("calendar",if(ci>=0)c.getString(ci).orEmpty() else "")
                        )
                    }
                }
            }.onFailure{queryError=it.javaClass.simpleName+": "+it.message.orEmpty()}
        }
        return JSONObject()
            .put("ok",hasCalendar&&queryError.isBlank())
            .put("source","android-calendar-provider")
            .put("calendarPermission",hasCalendar)
            .put("calendarCount",calendarCount)
            .put("queryError",queryError)
            .put("events",events)
            .put("reminders",JSONArray())
            .put("eventCount",events.length())
            .put("reminderCount",0)
            .put("generatedAt",System.currentTimeMillis())
    }'''
if '    private fun agendaForTv()' in s:
    s=replace_function(s,'    private fun agendaForTv()',agenda)
else:
    raise SystemExit('agendaForTv not generated before strict patch')
p.write_text(s)

# Main screen: "Mi día" must go through LocalActionRouter (real CalendarContract), not backend AI.
p=Path('mobile/src/main/java/com/jarvis/mobile/MainActivity.kt')
x=p.read_text()
x=x.replace(
    'sendVisualPrompt("day", "Dame mi resumen del día. Devuelve una línea separada por cada cita, recordatorio, aviso o asunto importante, sin introducción ni conclusión.")',
    'input.setText("qué tengo hoy en mi calendario"); sendMessage()'
)
# Request calendar permission once from the visible mobile app so the TV bridge can later read it.
anchor='        warmLocation()\n'
if 'REQ_CALENDAR' not in x and anchor in x:
    x=x.replace(anchor,anchor+'''        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_CALENDAR), 174)
        }
''',1)
p.write_text(x)

print('Strict real-calendar bridge applied')
