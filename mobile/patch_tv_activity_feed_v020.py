from pathlib import Path

p=Path('mobile/src/main/java/com/jarvis/mobile/PhoneBridgeService.kt')
s=p.read_text()

# 1) Agenda notifications: 'solicitado' contains the characters 'cita', which caused
# banking/biometric notifications to be misclassified as appointments. Only accept
# known agenda apps or whole agenda words.
old='''            val relevant=pkg.contains("calendar")||pkg.contains("tasks")||pkg.contains("todo")||pkg.contains("reminder")||combined.contains("recordatorio")||combined.contains("reminder")||combined.contains("cita")||combined.contains("evento")'''
new=r'''            val knownAgendaApp = pkg.contains("calendar") || pkg.contains("calendario") || pkg.contains("tasks") || pkg.contains("task") || pkg.contains("todo") || pkg.contains("reminder") || pkg.contains("keep") || pkg.contains("microsoft.todos") || pkg.contains("samsung.android.app.reminder")
            val agendaWord = Regex("""(?iu)(?:^|[^\p{L}])(recordatorios?|reminders?|citas?|eventos?|reuniones?|tareas?)(?:$|[^\p{L}])""").containsMatchIn(combined)
            val securityNoise = listOf("huella digital","fingerprint","verificación de huella","verificacion de huella","verifica la transferencia","transferencia bancaria","autoriza la compra","autorización de pago","autorizacion de pago").any { combined.contains(it) }
            val relevant=(knownAgendaApp || agendaWord) && !securityNoise'''
if old not in s:
    raise SystemExit('agenda relevance anchor not found')
s=s.replace(old,new,1)

# 2) /mobility: return both the dedicated mobility history and recently captured
# notifications from Glovo/rides/delivery apps. This makes current orders visible
# even when their notification arrived before the dedicated mobility recorder ran.
old_route='''        if (path.startsWith("/mobility")) {
            val prefs=getSharedPreferences("jarvis_mobile", MODE_PRIVATE)
            val feed=runCatching{JSONArray(prefs.getString("mobility_feed","[]"))}.getOrElse{JSONArray()}
            return 200 to JSONObject().put("items",feed).toString()
        }'''
new_route='''        if (path.startsWith("/mobility")) {
            return 200 to mobilityForTv().toString()
        }'''
if old_route not in s:
    raise SystemExit('mobility route anchor not found')
s=s.replace(old_route,new_route,1)

helper_anchor='    private fun permissionStatus(): JSONObject'
if 'private fun mobilityForTv()' not in s:
    helper=r'''    private fun mobilityForTv(): JSONObject {
        val prefs=getSharedPreferences("jarvis_mobile", MODE_PRIVATE)
        val rows=mutableListOf<JSONObject>()
        val seen=mutableSetOf<String>()
        val now=System.currentTimeMillis()
        val keepAfter=now-7L*24L*60L*60L*1000L

        fun sourceFor(pkg:String, title:String, text:String): Pair<String,String>? {
            val blob=(pkg+" "+title+" "+text).lowercase()
            return when {
                pkg.contains("glovo",true) || blob.contains("glovo") -> "Glovo" to "delivery"
                (pkg.contains("uber",true) && (pkg.contains("eats",true) || blob.contains("uber eats"))) || blob.contains("uber eats") -> "Uber Eats" to "delivery"
                pkg.contains("justeat",true) || blob.contains("just eat") -> "Just Eat" to "delivery"
                pkg.contains("deliveroo",true) || blob.contains("deliveroo") -> "Deliveroo" to "delivery"
                pkg.contains("bolt",true) || pkg.contains("mtakso",true) || blob.contains("bolt") -> "Bolt" to "ride"
                pkg.contains("cabify",true) || blob.contains("cabify") -> "Cabify" to "ride"
                pkg.contains("pidetaxi",true) || blob.contains("pidetaxi") || blob.contains("pide taxi") -> "PideTaxi" to "ride"
                pkg.contains("freenow",true) || blob.contains("free now") || blob.contains("freenow") -> "FREE NOW" to "ride"
                pkg.contains("uber",true) || blob.contains(" uber ") -> "Uber" to "ride"
                pkg.contains("villavesa",true) || blob.contains("villavesa") || blob.contains("transporte urbano comarcal") -> "Villavesa" to "transit"
                else -> null
            }
        }

        fun addRow(o:JSONObject, active:Boolean=false) {
            val time=o.optLong("time",0L)
            if(time>0L && time<keepAfter) return
            val source=o.optString("source").ifBlank {
                sourceFor(o.optString("package"),o.optString("title"),o.optString("text"))?.first.orEmpty()
            }
            if(source.isBlank()) return
            val title=o.optString("title").trim()
            val text=o.optString("text").trim()
            if(title.isBlank() && text.isBlank()) return
            val kind=o.optString("kind").ifBlank { sourceFor(o.optString("package"),title,text)?.second ?: "mobility" }
            val key=(source+"|"+title+"|"+text).lowercase()
            if(!seen.add(key)) return
            val eta=o.optString("etaMinutes").ifBlank {
                Regex("(?i)(?:llega(?:da)?|llegará|llegara|en|eta|arrival|arrives? in)\\s*(?:aprox\\.?\\s*)?(\\d{1,3})\\s*(?:min|minutos?|minutes?)").find(title+" "+text)?.groupValues?.getOrNull(1).orEmpty()
            }
            rows += JSONObject()
                .put("source",source).put("kind",kind).put("title",title).put("text",text)
                .put("package",o.optString("package")).put("etaMinutes",eta).put("time",time)
                .put("active",active)
        }

        val dedicated=runCatching{JSONArray(prefs.getString("mobility_feed","[]"))}.getOrElse{JSONArray()}
        for(i in 0 until dedicated.length()) dedicated.optJSONObject(i)?.let{addRow(it,false)}

        fun scanNotificationFeed(key:String, active:Boolean) {
            val feed=runCatching{JSONArray(prefs.getString(key,"[]"))}.getOrElse{JSONArray()}
            val start=(feed.length()-160).coerceAtLeast(0)
            for(i in start until feed.length()) {
                val n=feed.optJSONObject(i)?:continue
                val pair=sourceFor(n.optString("package"),n.optString("title"),n.optString("text"))?:continue
                val copy=JSONObject(n.toString()).put("source",pair.first).put("kind",pair.second)
                addRow(copy,active)
            }
        }
        scanNotificationFeed("notification_feed",false)
        scanNotificationFeed("active_notification_feed",true)

        val sorted=rows.sortedBy{it.optLong("time",0L)}.takeLast(100)
        val out=JSONArray(); sorted.forEach{out.put(it)}
        return JSONObject().put("ok",true).put("items",out).put("count",out.length()).put("updatedAt",now)
    }

'''
    if helper_anchor not in s:
        raise SystemExit('permissionStatus helper anchor not found')
    s=s.replace(helper_anchor,helper+helper_anchor,1)

p.write_text(s)
print('TV activity feed v0.2.20: strict agenda + Glovo/delivery recovery applied')
