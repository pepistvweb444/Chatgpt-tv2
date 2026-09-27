from pathlib import Path

def replace_function(text, signature, replacement):
    start=text.find(signature)
    if start<0: raise SystemExit(f'{signature} not found')
    brace=text.find('{',start)
    depth=0; ins=False; esc=False
    for i in range(brace,len(text)):
        ch=text[i]
        if ins:
            if esc: esc=False
            elif ch=='\\': esc=True
            elif ch=='"': ins=False
        else:
            if ch=='"': ins=True
            elif ch=='{': depth+=1
            elif ch=='}':
                depth-=1
                if depth==0:return text[:start]+replacement+text[i+1:]
    raise SystemExit(f'end not found for {signature}')

# Mobile main: Mi día bypasses backend AI completely.
p=Path('mobile/src/main/java/com/jarvis/mobile/MainActivity.kt')
s=p.read_text()
s=s.replace('input.setText("qué tengo hoy en mi calendario"); sendMessage()','showRealDayFromPhone()')
if 'private fun showRealDayFromPhone()' not in s:
    anchor='    private fun dp(v: Int)'
    helper=r'''    private fun showRealDayFromPhone() {
        val result=LocalActionRouter(this).handle("qué tengo hoy en mi Google Calendar")
        when {
            result.message.startsWith("__AGENDA_WIDGET__") -> renderAgendaWidgets(result.message)
            result.message.startsWith("__AGENDA_EMPTY__|") -> {
                val msg=result.message.substringAfter("|")
                renderMessageCard("assistant",msg)
                status.text="Google Calendar"
            }
            result.handled -> {
                renderMessageCard("assistant",result.message)
                status.text="Google Calendar"
            }
            else -> {
                renderMessageCard("assistant","No he podido leer Google Calendar desde el teléfono.")
                status.text="Google Calendar no disponible"
            }
        }
    }

'''
    if anchor not in s: raise SystemExit('MainActivity helper anchor missing')
    s=s.replace(anchor,helper+anchor,1)
p.write_text(s)

# LocalActionRouter: only real Google-synced calendars.
p=Path('mobile/src/main/java/com/jarvis/mobile/LocalActionRouter.kt')
s=p.read_text()
agenda=r'''    private fun readAgenda(days: Int): Result {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.READ_CALENDAR), 174)
            return Result(true,"__AGENDA_EMPTY__|Necesito permiso de Calendario para leer Google Calendar del teléfono.")
        }
        val googleIds=mutableListOf<Long>()
        runCatching {
            activity.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars._ID,CalendarContract.Calendars.ACCOUNT_NAME,CalendarContract.Calendars.ACCOUNT_TYPE,CalendarContract.Calendars.VISIBLE),
                null,null,null
            )?.use { c ->
                val ii=c.getColumnIndex(CalendarContract.Calendars._ID)
                val ni=c.getColumnIndex(CalendarContract.Calendars.ACCOUNT_NAME)
                val ti=c.getColumnIndex(CalendarContract.Calendars.ACCOUNT_TYPE)
                val vi=c.getColumnIndex(CalendarContract.Calendars.VISIBLE)
                while(c.moveToNext()) {
                    val id=if(ii>=0)c.getLong(ii) else -1L
                    val account=if(ni>=0)c.getString(ni).orEmpty().lowercase() else ""
                    val type=if(ti>=0)c.getString(ti).orEmpty().lowercase() else ""
                    val visible=vi<0 || c.getInt(vi)!=0
                    if(id>=0 && visible && (type.contains("google") || account.endsWith("@gmail.com") || account.endsWith("@googlemail.com"))) googleIds+=id
                }
            }
        }
        if(googleIds.isEmpty()) return Result(true,"__AGENDA_EMPTY__|Android no expone ningún Google Calendar sincronizado. Revisa Ajustes > Cuentas > Google > Sincronización de Calendar y el permiso de Calendario de Jarvis Mobile.")

        val now=System.currentTimeMillis()
        val end=now+days*24L*60L*60L*1000L
        val out=mutableListOf<String>()
        val sdf=SimpleDateFormat("EEE dd/MM HH:mm",Locale.getDefault())
        val selection=CalendarContract.Instances.CALENDAR_ID+" IN ("+googleIds.joinToString(",")+")"
        runCatching {
            val uri=CalendarContract.Instances.CONTENT_URI.buildUpon()
            android.content.ContentUris.appendId(uri,now)
            android.content.ContentUris.appendId(uri,end)
            activity.contentResolver.query(
                uri.build(),
                arrayOf(CalendarContract.Instances.TITLE,CalendarContract.Instances.BEGIN,CalendarContract.Instances.EVENT_LOCATION,CalendarContract.Instances.ALL_DAY,CalendarContract.Instances.CALENDAR_DISPLAY_NAME),
                selection,null,CalendarContract.Instances.BEGIN+" ASC"
            )?.use { c ->
                val ti=c.getColumnIndex(CalendarContract.Instances.TITLE)
                val bi=c.getColumnIndex(CalendarContract.Instances.BEGIN)
                val li=c.getColumnIndex(CalendarContract.Instances.EVENT_LOCATION)
                val ai=c.getColumnIndex(CalendarContract.Instances.ALL_DAY)
                val ci=c.getColumnIndex(CalendarContract.Instances.CALENDAR_DISPLAY_NAME)
                while(c.moveToNext() && out.size<30) {
                    val title=if(ti>=0)c.getString(ti).orEmpty().trim() else ""
                    if(title.isBlank())continue
                    val begin=if(bi>=0)c.getLong(bi) else 0L
                    val allDay=ai>=0 && c.getInt(ai)!=0
                    val whenText=if(allDay) SimpleDateFormat("EEE dd/MM · todo el día",Locale.getDefault()).format(Date(begin)) else sdf.format(Date(begin))
                    val place=if(li>=0)c.getString(li).orEmpty() else ""
                    val calendar=if(ci>=0)c.getString(ci).orEmpty() else "Google Calendar"
                    out += "Google Calendar|$whenText|${title.replace("|"," ")}|${listOf(calendar,place).filter{it.isNotBlank()}.joinToString(" · ").replace("|"," ")}"
                }
            }
        }
        return if(out.isNotEmpty()) Result(true,"__AGENDA_WIDGET__\n"+out.distinct().joinToString("\n"))
        else Result(true,"__AGENDA_EMPTY__|Google Calendar está sincronizado y no devuelve eventos en los próximos $days días.")
    }'''
s=replace_function(s,'    private fun readAgenda(days: Int)',agenda)
p.write_text(s)

# TV phone bridge: same Google-only structured agenda.
p=Path('mobile/src/main/java/com/jarvis/mobile/PhoneBridgeService.kt')
s=p.read_text()
agenda_tv=r'''    private fun agendaForTv(): JSONObject {
        val hasCalendar=ContextCompat.checkSelfPermission(this,Manifest.permission.READ_CALENDAR)==PackageManager.PERMISSION_GRANTED
        val events=JSONArray()
        val googleIds=mutableListOf<Long>()
        var queryError=""
        if(hasCalendar) runCatching {
            contentResolver.query(
                android.provider.CalendarContract.Calendars.CONTENT_URI,
                arrayOf(android.provider.CalendarContract.Calendars._ID,android.provider.CalendarContract.Calendars.ACCOUNT_NAME,android.provider.CalendarContract.Calendars.ACCOUNT_TYPE,android.provider.CalendarContract.Calendars.VISIBLE),
                null,null,null
            )?.use { c ->
                val ii=c.getColumnIndex(android.provider.CalendarContract.Calendars._ID)
                val ni=c.getColumnIndex(android.provider.CalendarContract.Calendars.ACCOUNT_NAME)
                val ti=c.getColumnIndex(android.provider.CalendarContract.Calendars.ACCOUNT_TYPE)
                val vi=c.getColumnIndex(android.provider.CalendarContract.Calendars.VISIBLE)
                while(c.moveToNext()) {
                    val id=if(ii>=0)c.getLong(ii) else -1L
                    val account=if(ni>=0)c.getString(ni).orEmpty().lowercase() else ""
                    val type=if(ti>=0)c.getString(ti).orEmpty().lowercase() else ""
                    val visible=vi<0 || c.getInt(vi)!=0
                    if(id>=0 && visible && (type.contains("google") || account.endsWith("@gmail.com") || account.endsWith("@googlemail.com"))) googleIds+=id
                }
            }
        }.onFailure{queryError=it.javaClass.simpleName+": "+it.message.orEmpty()}
        if(hasCalendar && googleIds.isNotEmpty() && queryError.isBlank()) runCatching {
            val now=System.currentTimeMillis(); val end=now+7L*24L*60L*60L*1000L
            val builder=android.provider.CalendarContract.Instances.CONTENT_URI.buildUpon()
            android.content.ContentUris.appendId(builder,now);android.content.ContentUris.appendId(builder,end)
            val selection=android.provider.CalendarContract.Instances.CALENDAR_ID+" IN ("+googleIds.joinToString(",")+")"
            val projection=arrayOf(android.provider.CalendarContract.Instances.TITLE,android.provider.CalendarContract.Instances.BEGIN,android.provider.CalendarContract.Instances.END,android.provider.CalendarContract.Instances.EVENT_LOCATION,android.provider.CalendarContract.Instances.ALL_DAY,android.provider.CalendarContract.Instances.CALENDAR_DISPLAY_NAME)
            contentResolver.query(builder.build(),projection,selection,null,android.provider.CalendarContract.Instances.BEGIN+" ASC")?.use { c ->
                val ti=c.getColumnIndex(android.provider.CalendarContract.Instances.TITLE);val bi=c.getColumnIndex(android.provider.CalendarContract.Instances.BEGIN);val ei=c.getColumnIndex(android.provider.CalendarContract.Instances.END);val li=c.getColumnIndex(android.provider.CalendarContract.Instances.EVENT_LOCATION);val ai=c.getColumnIndex(android.provider.CalendarContract.Instances.ALL_DAY);val ci=c.getColumnIndex(android.provider.CalendarContract.Instances.CALENDAR_DISPLAY_NAME)
                while(c.moveToNext()&&events.length()<60) {
                    val title=if(ti>=0)c.getString(ti).orEmpty().trim() else "";if(title.isBlank())continue
                    events.put(JSONObject().put("title",title).put("begin",if(bi>=0)c.getLong(bi) else 0L).put("end",if(ei>=0)c.getLong(ei) else 0L).put("location",if(li>=0)c.getString(li).orEmpty() else "").put("allDay",ai>=0&&c.getInt(ai)!=0).put("calendar",if(ci>=0)c.getString(ci).orEmpty() else "Google Calendar"))
                }
            }
        }.onFailure{queryError=it.javaClass.simpleName+": "+it.message.orEmpty()}
        return JSONObject().put("ok",hasCalendar&&googleIds.isNotEmpty()&&queryError.isBlank()).put("source","google-calendar-on-phone").put("calendarPermission",hasCalendar).put("googleCalendarCount",googleIds.size).put("queryError",queryError).put("events",events).put("reminders",JSONArray()).put("eventCount",events.length()).put("reminderCount",0).put("generatedAt",System.currentTimeMillis())
    }'''
s=replace_function(s,'    private fun agendaForTv()',agenda_tv)
p.write_text(s)

print('Jarvis Mobile real Google Calendar day applied')