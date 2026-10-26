package com.jarvis.mobile

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.UUID

class PhoneBridgeService : Service() {
    @Volatile private var running = false
    private var server: ServerSocket? = null

    override fun onCreate() {
        super.onCreate()
        try {
            if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL, "Jarvis TV bridge", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(this, 0, Intent(this, ChatActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            startForeground(93, NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.sym_action_call)
                .setContentTitle("Jarvis · puente con TV")
                .setContentText("Remote, llamadas, mensajes y control de aplicaciones")
                .setOngoing(true).setContentIntent(open).build())
            getSharedPreferences("jarvis_mobile", MODE_PRIVATE).edit().putBoolean("bridge_running", true).remove("bridge_start_error").apply()
            running = true
            Thread { serve() }.start()
        } catch (e: Throwable) {
            running = false
            getSharedPreferences("jarvis_mobile", MODE_PRIVATE).edit().putBoolean("bridge_running", false).putString("bridge_start_error", "${e.javaClass.simpleName}: ${e.message.orEmpty()}").apply()
            stopSelf()
        }
    }

    private fun serve() {
        try {
            server = ServerSocket(PORT)
            while (running) {
                val socket = server?.accept() ?: break
                Thread {
                    socket.use { s ->
                        val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                        val first = reader.readLine().orEmpty()
                        val headers = mutableMapOf<String, String>()
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            val colon = line.indexOf(':')
                            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
                        }
                        val path = first.split(" ").getOrNull(1).orEmpty()
                        val response = handle(path, headers)
                        val body = response.second
                        val code = response.first
                        val out = s.getOutputStream()
                        out.write("HTTP/1.1 $code ${if (code == 200) "OK" else "ERROR"}\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body".toByteArray())
                        out.flush()
                    }
                }.start()
            }
        } catch (e: Exception) {
            getSharedPreferences("jarvis_mobile", MODE_PRIVATE).edit().putBoolean("bridge_running", false).putString("bridge_start_error", "${e.javaClass.simpleName}: ${e.message.orEmpty()}").apply()
        }
    }

    private fun authorized(headers: Map<String,String>, path: String): Boolean {
        val prefs = getSharedPreferences("jarvis_mobile", MODE_PRIVATE)
        val expected = prefs.getString("remote_token", "").orEmpty()
        val bearer = headers["authorization"].orEmpty().removePrefix("Bearer ").trim()
        val query = URLDecoder.decode(path.substringAfter("token=", "").substringBefore("&"), StandardCharsets.UTF_8.name())
        return expected.isNotBlank() && (bearer == expected || query == expected) && prefs.getBoolean("remote_control_enabled", false)
    }

    private fun handle(path: String, headers: Map<String,String> = emptyMap()): Pair<Int,String> {
        if (path.startsWith("/ping")) return 200 to JSONObject().put("ok", true).put("device", "jarvis-phone").toString()
        if (path.startsWith("/pair?")) {
            val prefs = getSharedPreferences("jarvis_mobile", MODE_PRIVATE)
            if (!prefs.getBoolean("remote_control_enabled", false) || !prefs.getBoolean("pairing_enabled", false)) return 403 to JSONObject().put("error", "pairing-disabled").toString()
            val supplied = URLDecoder.decode(path.substringAfter("pin=", "").substringBefore("&"), StandardCharsets.UTF_8.name()).trim()
            val expectedPin = prefs.getString("pairing_pin", "").orEmpty()
            if (expectedPin.length != 4 || supplied != expectedPin) return 401 to JSONObject().put("error", "pin-invalid").toString()
            var token = prefs.getString("remote_token", "").orEmpty()
            if (token.isBlank()) token = UUID.randomUUID().toString().replace("-", "")
            prefs.edit().putString("remote_token", token).putBoolean("pairing_enabled", false).apply()
            return 200 to JSONObject().put("ok", true).put("paired", true).put("token", token).toString()
        }
        if (!authorized(headers, path)) return 401 to JSONObject().put("error", "unauthorized").toString()
        if (path.startsWith("/permissions")) return 200 to permissionStatus().toString()
        if (path.startsWith("/unread")) return 200 to unreadForTv().toString()
        if (path.startsWith("/agenda")) return 200 to agendaForTv().toString()
        if (path.startsWith("/domotics")) return 200 to domoticsForTv().toString()
        // 1.0.42 fix: the action route must be matched before the generic /incoming-call route (it used to swallow /incoming-call-action)
        if (path.startsWith("/incoming-call-action") || path.startsWith("/call-action?")) {
            val action = URLDecoder.decode(path.substringAfter("action=", "ignore").substringBefore("&"), StandardCharsets.UTF_8.name()).lowercase()
            val (ok, message) = CallActionManager.perform(this, action)
            return (if (ok) 200 else 409) to JSONObject().put("ok", ok).put("message", message).put("call", CallStateStore.current(this)).toString()
        }
        if (path.startsWith("/incoming-call")) return 200 to JSONObject().put("ok", true).put("call", CallStateStore.current(this)).toString()
        if (path.startsWith("/contacts")) {
            val q = URLDecoder.decode(path.substringAfter("q=", "").substringBefore("&"), StandardCharsets.UTF_8.name()).trim()
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return 403 to JSONObject().put("error", "contacts-permission-required").toString()
            return 200 to contactsForTv(q).toString()
        }
        if (path.startsWith("/remote?")) {
            val rawTask = path.substringAfter("task=", "").substringBefore("&")
            val task = URLDecoder.decode(rawTask, StandardCharsets.UTF_8.name()).trim()
            if (task.isBlank()) return 400 to JSONObject().put("error", "task-required").toString()
            getSharedPreferences("jarvis_mobile", MODE_PRIVATE).edit().putString("pending_remote_task", task).apply()
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("remote_task", task))
            return 200 to JSONObject().put("ok", true).put("status", "accepted").put("task", task).toString()
        }
        if (path.startsWith("/messages")) {
            val source = URLDecoder.decode(path.substringAfter("source=", "all").substringBefore("&"), StandardCharsets.UTF_8.name())
            return 200 to recentMessages(source).toString()
        }
        if (path.startsWith("/call?")) {
            val raw = path.substringAfter("number=", "").substringBefore("&")
            val number = URLDecoder.decode(raw, StandardCharsets.UTF_8.name()).trim()
            if (number.isBlank()) return 400 to JSONObject().put("error", "number-required").toString()
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) return 403 to JSONObject().put("error", "call-permission-required").toString()
            return try {
                startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(number))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                if (path.contains("speaker=1")) android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ runCatching { val am = getSystemService(android.media.AudioManager::class.java); am.mode = android.media.AudioManager.MODE_IN_CALL; am.isSpeakerphoneOn = true } }, 2500)
                200 to JSONObject().put("ok", true).put("status", "calling").toString()
            } catch (e: Exception) { 500 to JSONObject().put("error", e.message ?: "call-failed").toString() }
        }
        return 404 to JSONObject().put("error", "not-found").toString()
    }

    private fun contactsForTv(q: String): JSONObject {
        val out = JSONArray()
        val norm = { x: String -> java.text.Normalizer.normalize(x.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "") }
        val nq = norm(q)
        runCatching {
            contentResolver.query(android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER, android.provider.ContactsContract.CommonDataKinds.Phone.STARRED),
                null, null, android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC")?.use { c ->
                val seen = HashSet<String>()
                while (c.moveToNext() && out.length() < 400) {
                    val name = c.getString(0).orEmpty(); val num = c.getString(1).orEmpty().replace(" ", "")
                    if (num.isBlank() || !seen.add(name + num)) continue
                    if (nq.isNotBlank() && !norm(name).contains(nq)) continue
                    out.put(JSONObject().put("name", name).put("number", num).put("starred", c.getInt(2) == 1))
                }
            }
        }
        return JSONObject().put("ok", true).put("q", q).put("contacts", out)
    }

    private fun permissionStatus(): JSONObject = JSONObject()
        .put("readSms", ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED)
        .put("receiveSms", ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED)
        .put("sendSms", ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED)
        .put("contacts", ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED)
        .put("callPhone", ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED)
        .put("answerCalls", ContextCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED)

    private fun unreadForTv(): JSONObject {
        val recent = recentMessages("all")
        val messages = recent.optJSONArray("messages") ?: JSONArray()
        val items = JSONArray()
        for (i in 0 until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            items.put(JSONObject()
                .put("source", m.optString("source"))
                .put("from", m.optString("from"))
                .put("text", m.optString("text"))
                .put("time", m.optLong("time")))
        }
        return JSONObject()
            .put("ok", true)
            .put("unreadCount", items.length())
            .put("items", items)
            .put("permissions", permissionStatus())
    }

    private fun agendaForTv(): JSONObject {
        val now = System.currentTimeMillis()
        val end = now + 7L * 24L * 60L * 60L * 1000L
        val events = JSONArray()
        val reminders = JSONArray()
        val hasCalendar = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

        if (hasCalendar) runCatching {
            val uri = android.provider.CalendarContract.Instances.CONTENT_URI.buildUpon()
            android.content.ContentUris.appendId(uri, now)
            android.content.ContentUris.appendId(uri, end)
            val projection = arrayOf(
                android.provider.CalendarContract.Instances.TITLE,
                android.provider.CalendarContract.Instances.BEGIN,
                android.provider.CalendarContract.Instances.END,
                android.provider.CalendarContract.Instances.EVENT_LOCATION,
                android.provider.CalendarContract.Instances.ALL_DAY,
                android.provider.CalendarContract.Instances.CALENDAR_DISPLAY_NAME
            )
            contentResolver.query(uri.build(), projection, null, null, android.provider.CalendarContract.Instances.BEGIN + " ASC")?.use { c ->
                val ti=c.getColumnIndex(android.provider.CalendarContract.Instances.TITLE)
                val bi=c.getColumnIndex(android.provider.CalendarContract.Instances.BEGIN)
                val ei=c.getColumnIndex(android.provider.CalendarContract.Instances.END)
                val li=c.getColumnIndex(android.provider.CalendarContract.Instances.EVENT_LOCATION)
                val ai=c.getColumnIndex(android.provider.CalendarContract.Instances.ALL_DAY)
                val ci=c.getColumnIndex(android.provider.CalendarContract.Instances.CALENDAR_DISPLAY_NAME)
                while(c.moveToNext() && events.length()<40){
                    val title=if(ti>=0)c.getString(ti).orEmpty() else "Evento"
                    if(title.isBlank()) continue
                    events.put(JSONObject()
                        .put("title",title)
                        .put("begin",if(bi>=0)c.getLong(bi) else 0L)
                        .put("end",if(ei>=0)c.getLong(ei) else 0L)
                        .put("location",if(li>=0)c.getString(li).orEmpty() else "")
                        .put("allDay",ai>=0&&c.getInt(ai)!=0)
                        .put("calendar",if(ci>=0)c.getString(ci).orEmpty() else ""))
                }
            }
        }

        val feed=runCatching{JSONArray(getSharedPreferences("jarvis_mobile",MODE_PRIVATE).getString("notification_feed","[]"))}.getOrElse{JSONArray()}
        for(i in feed.length()-1 downTo 0){
            val n=feed.optJSONObject(i)?:continue
            val pkg=n.optString("package").lowercase()
            val title=n.optString("title").trim()
            val text=n.optString("text").trim()
            val combined="$title $text".lowercase()
            val relevant=pkg.contains("calendar")||pkg.contains("tasks")||pkg.contains("todo")||pkg.contains("reminder")||combined.contains("recordatorio")||combined.contains("cita")||combined.contains("evento")
            if(!relevant) continue
            reminders.put(JSONObject().put("title",title.ifBlank{"Recordatorio"}).put("text",text).put("time",n.optLong("time")))
            if(reminders.length()>=20) break
        }

        return JSONObject()
            .put("ok",true)
            .put("calendarPermission",hasCalendar)
            .put("events",events)
            .put("reminders",reminders)
            .put("eventCount",events.length())
            .put("reminderCount",reminders.length())
    }

    private fun domoticsForTv(): JSONObject {
        val prefs=getSharedPreferences("jarvis_mobile",MODE_PRIVATE)
        val snapshot=prefs.getString("domotics_tv_snapshot_json","").orEmpty()
        if(snapshot.isNotBlank()) runCatching {
            val o=JSONObject(snapshot)
            if(o.optJSONArray("items")!=null) return o.put("bridgeAt",System.currentTimeMillis())
        }

        val items=JSONArray()
        runCatching {
            val a=JSONArray(prefs.getString("homey_devices_json","[]"))
            for(i in 0 until a.length()){
                val d=a.optJSONObject(i)?:continue
                items.put(JSONObject()
                    .put("provider","homey")
                    .put("id",d.optString("id"))
                    .put("name",d.optString("name").ifBlank{"Homey"})
                    .put("room",d.optString("zone").ifBlank{"Casa"})
                    .put("type",d.optString("class").ifBlank{d.optString("type")})
                    .put("state",d.optJSONObject("state")?:JSONObject()))
            }
        }
        return JSONObject().put("ok",true).put("items",items).put("updatedAt",System.currentTimeMillis())
    }

    private fun recentMessages(source: String): JSONObject {
        val out = JSONArray(); val normalized = source.lowercase()
        if ((normalized == "all" || normalized == "sms") && ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) runCatching {
            contentResolver.query(Uri.parse("content://sms/inbox"), arrayOf("address","body","date"), null, null, "date DESC")?.use { c ->
                val ai=c.getColumnIndex("address"); val bi=c.getColumnIndex("body"); val di=c.getColumnIndex("date")
                while(c.moveToNext() && out.length()<15) out.put(JSONObject().put("source","sms").put("from",if(ai>=0)c.getString(ai).orEmpty() else "").put("text",if(bi>=0)c.getString(bi).orEmpty() else "").put("time",if(di>=0)c.getLong(di) else 0L))
            }
        }
        if (normalized == "all" || normalized == "whatsapp" || normalized == "notifications") {
            val feed=runCatching{JSONArray(getSharedPreferences("jarvis_mobile",MODE_PRIVATE).getString("notification_feed","[]"))}.getOrElse{JSONArray()}
            for(i in feed.length()-1 downTo 0){if(out.length()>=30)break;val n=feed.optJSONObject(i)?:continue;val pkg=n.optString("package");val wa=pkg.contains("whatsapp",true);if(normalized=="whatsapp"&&!wa)continue;if(normalized=="all"&&!wa&&!pkg.contains("messag",true))continue;out.put(JSONObject().put("source",if(wa)"whatsapp" else "notification").put("from",n.optString("conversation").ifBlank{n.optString("title")}).put("text",n.optString("text")).put("time",n.optLong("time")))}
        }
        return JSONObject().put("permissions", permissionStatus()).put("messages", out)
    }

    override fun onDestroy() { running=false; getSharedPreferences("jarvis_mobile",MODE_PRIVATE).edit().putBoolean("bridge_running",false).apply(); runCatching{server?.close()}; super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object { const val PORT=8765; private const val CHANNEL="jarvis_phone_bridge" }
}
