from pathlib import Path

# Notification listener: capture mobility/taxi status updates and surface important arrivals.
p=Path('mobile/src/main/java/com/jarvis/mobile/JarvisNotificationListener.kt')
s=p.read_text()
if 'private fun mobilitySource' not in s:
    insert=r'''
    private fun mobilitySource(pkg: String, blob: String): String? = when {
        pkg.contains("cabify", true) || blob.contains("cabify", true) -> "Cabify"
        pkg.contains("pidetaxi", true) || blob.contains("pidetaxi", true) || blob.contains("pide taxi", true) -> "PideTaxi"
        pkg.contains("villavesa", true) || blob.contains("villavesa", true) || blob.contains("tu villavesa", true) -> "Villavesa"
        else -> null
    }

    private fun recordMobility(source: String, title: String, body: String, pkg: String) {
        val prefs=getSharedPreferences("jarvis_mobile", MODE_PRIVATE)
        val now=System.currentTimeMillis()
        val item=JSONObject().put("source",source).put("title",title).put("text",body).put("package",pkg).put("time",now)
        val feed=runCatching{JSONArray(prefs.getString("mobility_feed","[]"))}.getOrElse{JSONArray()}
        feed.put(item)
        val trimmed=JSONArray(); val start=(feed.length()-100).coerceAtLeast(0)
        for(i in start until feed.length()) trimmed.put(feed.opt(i))
        prefs.edit().putString("mobility_feed",trimmed.toString()).putString("last_mobility_status",item.toString()).apply()

        val normalized=(title+" "+body).lowercase()
        val important=listOf("ha llegado","llegado","está esperando","esta esperando","en el punto de recogida","recogida","en camino","llega en","minuto","arrived","driver is here","pickup").any{normalized.contains(it)}
        if(important){
            runCatching {
                val nm=getSystemService(android.app.NotificationManager::class.java)
                if(android.os.Build.VERSION.SDK_INT>=26) nm.createNotificationChannel(android.app.NotificationChannel("jarvis_mobility","Jarvis movilidad",android.app.NotificationManager.IMPORTANCE_HIGH))
                nm.notify(7812, androidx.core.app.NotificationCompat.Builder(this,"jarvis_mobility")
                    .setSmallIcon(android.R.drawable.ic_dialog_map)
                    .setContentTitle("Jarvis · $source")
                    .setContentText(body.ifBlank{title}.take(160))
                    .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(body.ifBlank{title}))
                    .setAutoCancel(true).build())
            }
            if(prefs.getBoolean("mobility_voice_alerts",true)) runCatching {
                androidx.core.content.ContextCompat.startForegroundService(this, android.content.Intent(this, MobileSpeechService::class.java)
                    .putExtra("text", "$source. ${body.ifBlank{title}}")
                    .putExtra("voice", prefs.getString("voice","coral")))
            }
        }
    }
'''
    # insert before onNotificationRemoved
    marker='    override fun onNotificationRemoved(sbn: StatusBarNotification?) {'
    if marker in s: s=s.replace(marker,insert+'\n'+marker,1)

if 'recordMobility(mobility' not in s:
    needle='''        if (title.isBlank() && body.isBlank()) return

        val item = JSONObject()'''
    repl='''        if (title.isBlank() && body.isBlank()) return
        val mobility = mobilitySource(packageName, "$title $body $subText")
        if (mobility != null) recordMobility(mobility, title, body.ifBlank { text }, packageName)

        val item = JSONObject()'''
    if needle in s: s=s.replace(needle,repl,1)
p.write_text(s)

# Local router: answer current taxi/Villavesa status from the latest captured app updates.
p=Path('mobile/src/main/java/com/jarvis/mobile/LocalActionRouter.kt')
s=p.read_text()
if 'readMobilityStatus' not in s:
    anchor='''        when {'''
    route='''        when {
            ((lower.contains("cabify") || lower.contains("pidetaxi") || lower.contains("pide taxi") || lower.contains("taxi") || lower.contains("villavesa")) &&
             (lower.contains("estado") || lower.contains("dónde") || lower.contains("donde") || lower.contains("llega") || lower.contains("recoge") || lower.contains("cuánto") || lower.contains("cuanto") || lower.contains("avisa"))) -> return readMobilityStatus(lower)'''
    if anchor in s: s=s.replace(anchor,route,1)
    marker='''    private fun messageAccessStatus(): Result {'''
    method=r'''    private fun readMobilityStatus(query: String): Result {
        val prefs=activity.getSharedPreferences("jarvis_mobile", Activity.MODE_PRIVATE)
        val feed=runCatching{JSONArray(prefs.getString("mobility_feed","[]"))}.getOrElse{JSONArray()}
        val wanted=when {
            query.contains("cabify") -> "cabify"
            query.contains("pidetaxi") || query.contains("pide taxi") || query.contains("taxi") -> "pidetaxi"
            query.contains("villavesa") -> "villavesa"
            else -> ""
        }
        val rows=mutableListOf<String>()
        for(i in feed.length()-1 downTo 0){
            if(rows.size>=8) break
            val o=feed.optJSONObject(i)?:continue
            val source=o.optString("source")
            if(wanted.isNotBlank() && !source.lowercase().contains(wanted)) continue
            val title=o.optString("title")
            val body=o.optString("text")
            val whenText=java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(o.optLong("time",0L)))
            rows += "$source|$whenText|${title.replace("|"," ")}|${body.replace("|"," ").replace("\n"," ").take(700)}"
        }
        return if(rows.isEmpty()) Result(true,"__MOBILITY_EMPTY__|No tengo todavía una actualización reciente de esa reserva o trayecto. Mantendré vigilancia sobre sus notificaciones.")
        else Result(true,"__MOBILITY_WIDGET__\n"+rows.joinToString("\n"))
    }

'''
    if marker in s: s=s.replace(marker,method+marker,1)
p.write_text(s)

# MainActivity: render mobility status inline.
p=Path('mobile/src/main/java/com/jarvis/mobile/MainActivity.kt')
s=p.read_text()
if '__MOBILITY_WIDGET__' not in s:
    # insert before generic local handling branch where message widget checks exist
    candidates=['''            if (local.message.startsWith("__NEW_MESSAGES__|")) {''','''            if (local.message.startsWith("__MESSAGES_WIDGET__")) {''']
    needle=next((x for x in candidates if x in s),None)
    if needle:
        block=r'''            if (local.message.startsWith("__MOBILITY_WIDGET__")) {
                beginWidgetGroup("Movilidad · estado en tiempo real")
                local.message.lines().drop(1).filter { it.isNotBlank() }.take(10).forEach { line ->
                    val p=line.split("|",limit=4)
                    val source=p.getOrElse(0){"Movilidad"}; val whenText=p.getOrElse(1){""}; val title=p.getOrElse(2){""}; val body=p.getOrElse(3){""}
                    addTextWidget("day", "$source · $whenText", listOf(title,body).filter{it.isNotBlank()}.joinToString(" · "))
                }
                status.text="Jarvis listo"
            } else if (local.message.startsWith("__MOBILITY_EMPTY__|")) {
                beginWidgetGroup("Movilidad")
                addTextWidget("day","Sin actualización reciente",local.message.substringAfter("|"))
                status.text="Jarvis listo"
            } else '''+needle.strip()
        s=s.replace(needle,block,1)
p.write_text(s)

# TV bridge endpoint so Jarvis TV can display the same taxi/transit state.
p=Path('mobile/src/main/java/com/jarvis/mobile/PhoneBridgeService.kt')
s=p.read_text()
if 'path.startsWith("/mobility")' not in s:
    anchor='''        if (path.startsWith("/permissions")) return 200 to permissionStatus().toString()'''
    add=anchor+'''\n        if (path.startsWith("/mobility")) {\n            val prefs=getSharedPreferences("jarvis_mobile", MODE_PRIVATE)\n            val feed=runCatching{JSONArray(prefs.getString("mobility_feed","[]"))}.getOrElse{JSONArray()}\n            return 200 to JSONObject().put("items",feed).toString()\n        }'''
    if anchor in s:s=s.replace(anchor,add,1)
p.write_text(s)
print('Realtime taxi/Cabify/PideTaxi/Villavesa status tracking applied')
