from pathlib import Path

p = Path('mobile/src/main/java/com/jarvis/mobile/PhoneBridgeService.kt')
s = p.read_text()

# Agenda: reminders must come from the persisted notification history. Using only
# active_notification_feed made the TV show whichever reminder happened to remain
# in Android's notification shade.
s = s.replace(
    'val active=runCatching{JSONArray(prefs.getString("active_notification_feed","[]"))}.getOrElse{JSONArray()}',
    'val active=runCatching{JSONArray(prefs.getString("notification_feed","[]"))}.getOrElse{JSONArray()}',
    1,
)

route_anchor = '        if (path.startsWith("/agenda")) {\n            return 200 to agendaForTv().toString()\n        }\n'
if 'path.startsWith("/calls")' not in s:
    extra = '''        if (path.startsWith("/calls")) {\n            return 200 to callsForTv().toString()\n        }\n        if (path.startsWith("/domotics")) {\n            return 200 to domoticsForTv().toString()\n        }\n'''
    if route_anchor in s:
        s = s.replace(route_anchor, route_anchor + extra, 1)
    else:
        auth_anchor = '        if (!authorized(headers, path)) return 401 to JSONObject().put("error", "unauthorized").toString()\n'
        if auth_anchor not in s:
            raise SystemExit('PhoneBridgeService auth anchor not found')
        s = s.replace(auth_anchor, auth_anchor + extra, 1)

helper_anchor = '    private fun permissionStatus(): JSONObject'
if 'private fun callsForTv()' not in s:
    helpers = r'''    private fun callsForTv(): JSONObject {
        val out = JSONArray()
        val hasLog = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED
        if (hasLog) {
            runCatching {
                contentResolver.query(
                    android.provider.CallLog.Calls.CONTENT_URI,
                    arrayOf(
                        android.provider.CallLog.Calls.NUMBER,
                        android.provider.CallLog.Calls.CACHED_NAME,
                        android.provider.CallLog.Calls.DATE,
                        android.provider.CallLog.Calls.DURATION,
                        android.provider.CallLog.Calls.TYPE,
                        android.provider.CallLog.Calls.IS_READ
                    ), null, null, android.provider.CallLog.Calls.DATE + " DESC"
                )?.use { c ->
                    val ni=c.getColumnIndex(android.provider.CallLog.Calls.NUMBER)
                    val ci=c.getColumnIndex(android.provider.CallLog.Calls.CACHED_NAME)
                    val di=c.getColumnIndex(android.provider.CallLog.Calls.DATE)
                    val dui=c.getColumnIndex(android.provider.CallLog.Calls.DURATION)
                    val ti=c.getColumnIndex(android.provider.CallLog.Calls.TYPE)
                    val ri=c.getColumnIndex(android.provider.CallLog.Calls.IS_READ)
                    while(c.moveToNext() && out.length()<30) {
                        val type=if(ti>=0)c.getInt(ti) else 0
                        val kind=when(type) {
                            android.provider.CallLog.Calls.MISSED_TYPE -> "missed"
                            android.provider.CallLog.Calls.INCOMING_TYPE -> "incoming"
                            android.provider.CallLog.Calls.OUTGOING_TYPE -> "outgoing"
                            android.provider.CallLog.Calls.REJECTED_TYPE -> "rejected"
                            android.provider.CallLog.Calls.BLOCKED_TYPE -> "blocked"
                            android.provider.CallLog.Calls.VOICEMAIL_TYPE -> "voicemail"
                            else -> "other"
                        }
                        val number=if(ni>=0)c.getString(ni).orEmpty() else ""
                        val cached=if(ci>=0)c.getString(ci).orEmpty() else ""
                        val unread=kind=="missed" && ri>=0 && c.getInt(ri)==0
                        out.put(JSONObject()
                            .put("kind",kind)
                            .put("pending",unread)
                            .put("name",cached.ifBlank{number.ifBlank{"Número oculto"}})
                            .put("number",number)
                            .put("time",if(di>=0)c.getLong(di) else 0L)
                            .put("duration",if(dui>=0)c.getLong(dui) else 0L))
                    }
                }
            }
        }
        val current = CallStateStore.current(this)
        val screening = if (Build.VERSION.SDK_INT >= 29) runCatching {
            val rm=getSystemService(android.app.role.RoleManager::class.java)
            rm?.isRoleHeld(android.app.role.RoleManager.ROLE_CALL_SCREENING) == true
        }.getOrDefault(false) else false

        // Keep recent VoIP call notifications too. Normal Android CallLog does not
        // always contain WhatsApp/Telegram/Meet calls.
        val appCalls=JSONArray()
        val feed=runCatching{JSONArray(getSharedPreferences("jarvis_mobile",MODE_PRIVATE).getString("notification_feed","[]"))}.getOrElse{JSONArray()}
        val callWords=listOf("llamada entrante","llamada perdida","videollamada","missed call","incoming call","is calling","te está llamando","te esta llamando")
        for(i in feed.length()-1 downTo 0) {
            if(appCalls.length()>=12) break
            val n=feed.optJSONObject(i)?:continue
            val pkg=n.optString("package")
            val known=listOf("whatsapp","telegram","instagram","facebook.orca","messenger","meet","teams","zoom").any{pkg.contains(it,true)}
            if(!known) continue
            val blob=(n.optString("title")+" "+n.optString("text")).lowercase()
            if(callWords.none{blob.contains(it)}) continue
            appCalls.put(JSONObject().put("source","app").put("app",pkg.substringAfterLast('.')).put("name",n.optString("title").ifBlank{"Llamada de aplicación"}).put("detail",n.optString("text")).put("time",n.optLong("time")))
        }
        return JSONObject().put("ok",true).put("callLogPermission",hasLog).put("screeningEnabled",screening).put("current",current).put("items",out).put("appCalls",appCalls)
    }

    private fun domoticsForTv(): JSONObject {
        val prefs=getSharedPreferences("jarvis_mobile",MODE_PRIVATE)
        fun arrayPref(key:String): JSONArray = runCatching{JSONArray(prefs.getString(key,"[]"))}.getOrElse{JSONArray()}
        val homey=arrayPref("homey_devices_json")
        val google=arrayPref("google_home_lights_json")
        val homeConnect=JSONArray()
        val hcRaw=prefs.getString("homeconnect_devices_cache_raw","").orEmpty()
        if(hcRaw.isNotBlank()) runCatching {
            val root=JSONObject(hcRaw)
            val arr=root.optJSONObject("data")?.optJSONArray("homeappliances")
                ?: root.optJSONArray("homeappliances") ?: root.optJSONArray("items") ?: JSONArray()
            for(i in 0 until arr.length()) {
                val d=arr.optJSONObject(i)?:continue
                val id=d.optString("haId").ifBlank{d.optString("id")}
                val name=d.optString("name").ifBlank{d.optString("brand")+" "+d.optString("type")}.trim().ifBlank{"Home Connect"}
                homeConnect.put(JSONObject().put("id",id).put("name",name).put("type",d.optString("type")).put("connected",d.optBoolean("connected",true)))
            }
        }
        return JSONObject()
            .put("ok",true)
            .put("homey",homey)
            .put("googleHome",google)
            .put("homeConnect",homeConnect)
            .put("hasTado",prefs.getString("tado_refresh_token","").orEmpty().isNotBlank())
            .put("hasHomeConnect",prefs.getString("homeconnect_refresh_token","").orEmpty().isNotBlank() || homeConnect.length()>0)
            .put("homeyConnected",prefs.getString("homey_session","").orEmpty().isNotBlank())
            .put("updatedAt",System.currentTimeMillis())
    }

'''
    if helper_anchor not in s:
        raise SystemExit('PhoneBridgeService helper anchor not found')
    s = s.replace(helper_anchor, helpers + helper_anchor, 1)

p.write_text(s)
print('TV dashboard bridge: agenda history, calls and domotics applied')
