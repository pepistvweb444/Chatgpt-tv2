from pathlib import Path

p=Path("mobile/src/main/java/com/jarvis/mobile/PhoneBridgeService.kt")
s=p.read_text()

# Expose a recent activity hub for TV. Unlike /unread, this deliberately includes
# recent history so the morning brief can show what happened overnight even after
# a notification was dismissed/read.
auth_anchor='        if (!authorized(headers, path)) return 401 to JSONObject().put("error", "unauthorized").toString()\n'
if 'path.startsWith("/activity-hub")' not in s:
    route='''        if (path.startsWith("/activity-hub")) {
            return 200 to activityHubForTv().toString()
        }
'''
    if auth_anchor not in s:
        raise SystemExit("PhoneBridge auth anchor missing")
    s=s.replace(auth_anchor,auth_anchor+route,1)

helper_anchor="    private fun permissionStatus(): JSONObject"
if "private fun activityHubForTv()" not in s:
    helper=r'''    private fun activityHubForTv(): JSONObject {
        val prefs=getSharedPreferences("jarvis_mobile",MODE_PRIVATE)
        val feed=runCatching{JSONArray(prefs.getString("notification_feed","[]"))}.getOrElse{JSONArray()}
        val active=runCatching{JSONArray(prefs.getString("active_notification_feed","[]"))}.getOrElse{JSONArray()}
        val activeKeys=mutableSetOf<String>()
        for(i in 0 until active.length()) active.optJSONObject(i)?.optString("key")?.takeIf{it.isNotBlank()}?.let(activeKeys::add)

        fun sourceFor(pkg:String):Pair<String,String> = when {
            pkg.contains("whatsapp",true) -> "WhatsApp" to "messages"
            pkg.contains("instagram",true) -> "Instagram" to "social"
            pkg.contains("facebook.orca",true) || pkg.contains("messenger",true) -> "Messenger" to "messages"
            pkg.contains("facebook.katana",true) -> "Facebook" to "social"
            pkg.contains("telegram",true) -> "Telegram" to "messages"
            pkg.contains("tiktok",true) || pkg.contains("musically",true) -> "TikTok" to "social"
            pkg.contains("google.android.gm",true) -> "Gmail" to "mail"
            pkg.contains("outlook",true) -> "Outlook" to "mail"
            pkg.contains("email",true) || pkg.contains("mail",true) -> "Correo" to "mail"
            pkg.contains("messag",true) || pkg.contains("sms",true) -> "Mensajes / RCS" to "messages"
            else -> pkg.substringAfterLast('.').ifBlank{"App"} to "other"
        }

        val items=JSONArray()
        val cutoff=System.currentTimeMillis()-48L*60L*60L*1000L
        val seen=mutableSetOf<String>()
        for(i in feed.length()-1 downTo 0) {
            if(items.length()>=80) break
            val n=feed.optJSONObject(i)?:continue
            val time=n.optLong("time",0L)
            if(time in 1 until cutoff) continue
            val title=n.optString("title").trim()
            val text=n.optString("text").trim()
            if(title.isBlank() && text.isBlank()) continue
            val pkg=n.optString("package")
            val (source,category)=sourceFor(pkg)
            if(category=="other") continue
            val key=(source+"|"+title+"|"+text).lowercase()
            if(!seen.add(key)) continue
            items.put(JSONObject()
                .put("source",source)
                .put("category",category)
                .put("package",pkg)
                .put("title",title)
                .put("text",text)
                .put("subText",n.optString("subText"))
                .put("conversation",n.optString("conversation"))
                .put("time",time)
                .put("active",activeKeys.contains(n.optString("key"))))
        }
        return JSONObject()
            .put("ok",true)
            .put("notificationAccess",prefs.getBoolean("notification_listener_connected",false))
            .put("items",items)
            .put("updatedAt",System.currentTimeMillis())
    }

'''
    if helper_anchor not in s:
        raise SystemExit("PhoneBridge helper anchor missing")
    s=s.replace(helper_anchor,helper+helper_anchor,1)

p.write_text(s)
print("Jarvis Mobile TV activity hub applied")
