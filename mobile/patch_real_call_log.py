from pathlib import Path

# Manifest: real call history permission and full-screen incoming-call UI permission.
p=Path('mobile/src/main/AndroidManifest.xml')
s=p.read_text()
if 'android.permission.READ_CALL_LOG' not in s:
    s=s.replace('    <uses-permission android:name="android.permission.READ_PHONE_STATE" />','    <uses-permission android:name="android.permission.READ_PHONE_STATE" />\n    <uses-permission android:name="android.permission.READ_CALL_LOG" />',1)
if 'android.permission.USE_FULL_SCREEN_INTENT' not in s:
    s=s.replace('    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />','    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />\n    <uses-permission android:name="android.permission.USE_FULL_SCREEN_INTENT" />',1)
p.write_text(s)

# Device hub asks for call-log permission together with phone permissions.
p=Path('mobile/src/main/java/com/jarvis/mobile/DeviceHubActivity.kt')
s=p.read_text()
s=s.replace('Manifest.permission.READ_PHONE_STATE, Manifest.permission.ANSWER_PHONE_CALLS, Manifest.permission.READ_SMS', 'Manifest.permission.READ_PHONE_STATE, Manifest.permission.ANSWER_PHONE_CALLS, Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_SMS')
p.write_text(s)

# Local router: missed calls must come ONLY from CallLog.Calls, never call_feed.
p=Path('mobile/src/main/java/com/jarvis/mobile/LocalActionRouter.kt')
s=p.read_text()
if 'import android.provider.CallLog' not in s:
    s=s.replace('import android.provider.ContactsContract\n','import android.provider.ContactsContract\nimport android.provider.CallLog\n',1)

when_anchor='''        when {'''
route='''        when {
            (lower.contains("llamada perdida") || lower.contains("llamadas perdidas") || lower.contains("perdí alguna llamada") || lower.contains("perdi alguna llamada")) -> return readRealMissedCalls(20)'''
if 'readRealMissedCalls(20)' not in s:
    if when_anchor not in s: raise SystemExit('when anchor not found')
    s=s.replace(when_anchor,route,1)

marker='''    private fun messageAccessStatus(): Result {'''
method=r'''    private fun readRealMissedCalls(limit: Int): Result {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_PHONE_STATE), 75)
            return Result(true, "Necesito permiso de Registro de llamadas para mostrar llamadas perdidas reales. Concédelo y vuelve a preguntar.")
        }
        val rows=mutableListOf<String>()
        val sdf=SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
        runCatching {
            activity.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.TYPE),
                "${CallLog.Calls.TYPE}=?",
                arrayOf(CallLog.Calls.MISSED_TYPE.toString()),
                "${CallLog.Calls.DATE} DESC"
            )?.use { c ->
                val ni=c.getColumnIndex(CallLog.Calls.NUMBER); val ci=c.getColumnIndex(CallLog.Calls.CACHED_NAME); val di=c.getColumnIndex(CallLog.Calls.DATE)
                while(c.moveToNext() && rows.size<limit){
                    val number=if(ni>=0)c.getString(ni).orEmpty() else ""
                    val cached=if(ci>=0)c.getString(ci).orEmpty() else ""
                    val date=if(di>=0)c.getLong(di) else 0L
                    val who=cached.ifBlank { contactNameForNumber(number) ?: number.ifBlank { "Número oculto" } }
                    rows += "$who · ${if(date>0)sdf.format(Date(date)) else ""}${if(number.isNotBlank() && number!=who) " · $number" else ""}"
                }
            }
        }
        return if(rows.isEmpty()) Result(true,"No hay llamadas perdidas en el registro real del teléfono.")
        else Result(true,"Llamadas perdidas del registro del teléfono:\n\n"+rows.joinToString("\n"))
    }

'''
if 'private fun readRealMissedCalls' not in s:
    if marker not in s: raise SystemExit('messageAccessStatus marker not found')
    s=s.replace(marker,method+marker,1)
p.write_text(s)
print('Real Android missed-call log patch applied')
