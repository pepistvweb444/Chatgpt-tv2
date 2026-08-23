package com.jarvis.mobile

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LocalActionRouter(private val activity: Activity) {
    data class Result(val handled: Boolean, val message: String = "")

    fun handle(raw: String): Result {
        val text = raw.trim(); val lower = norm(text)
        when {
            isAlarmCommand(lower) -> return createAlarm(text)
            Regex("^(puedes |podrias |quiero que |por favor )?(llama|llamar|telefonea|haz una llamada)(me)?( a| al)? ").containsMatchIn(lower) -> {
                val target = lower.replace(Regex("^(puedes |podrias |quiero que |por favor )?(llama|llamar|telefonea|haz una llamada)(me)?( a| al)? "), "").trim()
                return if (target.matches(Regex("[+0-9][0-9 .-]{5,}"))) callNumber(target) else callContact(target)
            }
            lower.contains("puedes realizar llamadas") || lower.contains("puedes hacer llamadas") || lower.contains("puedes llamar") -> return phoneAccessStatus()
            lower.contains("whatsapp") && listOf("lee","leeme","mensajes","escrito","que dice","tengo","nuevos").any { lower.contains(it) } -> return readNotificationMessages("whatsapp", 12)
            lower.contains("rcs") && listOf("lee","leeme","mensajes","tengo","nuevos").any { lower.contains(it) } -> return readNotificationMessages("messages", 12)
            lower.contains("acceso a mis mensajes") || lower.contains("puedes leer mis mensajes") || lower.contains("leer los mensajes") || lower.contains("leer mensajes") && lower.contains("puedes") -> return messageAccessStatus()
            lower.contains("ultimos mensajes") || lower.contains("ultimos sms") || lower.contains("que mensajes tengo") || lower.contains("mensajes nuevos") || lower == "lee mis mensajes" || lower == "leeme mis mensajes" || lower == "lee los mensajes" || lower == "mis mensajes" || lower == "mensajes" -> return readRecentSms(10)
            lower.startsWith("que me ha escrito ") || lower.startsWith("que dice ") || lower.startsWith("leeme lo de ") -> return readSmsFrom(text.substringAfterLast(" ").trim())
            lower.startsWith("contesta a ") && lower.contains(" diciendo ") -> return smsContact(text.substringAfter("contesta a ").substringBefore(" diciendo ").trim(), text.substringAfter(" diciendo ").trim())
            lower.startsWith("envia un sms a ") || lower.startsWith("manda un sms a ") -> { val after=text.substringAfter(" a "); return smsContact(after.substringBefore(":").trim(),after.substringAfter(":","").trim()) }
            lower.startsWith("abre ") -> return openApp(text.substringAfter(" ").trim())
            lower.contains("abre ajustes") -> { activity.startActivity(Intent(Settings.ACTION_SETTINGS)); return Result(true,"He abierto Ajustes.") }
            lower == "inicio" || lower == "ve a inicio" -> { activity.sendBroadcast(Intent(JarvisAccessibilityService.ACTION_HOME).setPackage(activity.packageName)); return Result(true,"He vuelto a Inicio.") }
            lower == "atras" || lower == "volver" -> { activity.sendBroadcast(Intent(JarvisAccessibilityService.ACTION_BACK).setPackage(activity.packageName)); return Result(true,"He pulsado Atrás.") }
            lower.contains("recientes") -> { activity.sendBroadcast(Intent(JarvisAccessibilityService.ACTION_RECENTS).setPackage(activity.packageName)); return Result(true,"He abierto aplicaciones recientes.") }
        }
        return Result(false)
    }

    private fun isAlarmCommand(s:String):Boolean = s.contains("alarma") || s.startsWith("despiertame ") || s.startsWith("despierta me ") || s.startsWith("avisame a las ") || s.startsWith("avisame a la ")

    private fun createAlarm(raw:String):Result {
        var s=norm(raw)
        val words=mapOf("cero" to 0,"una" to 1,"uno" to 1,"dos" to 2,"tres" to 3,"cuatro" to 4,"cinco" to 5,"seis" to 6,"siete" to 7,"ocho" to 8,"nueve" to 9,"diez" to 10,"once" to 11,"doce" to 12,"trece" to 13,"catorce" to 14,"quince" to 15,"dieciseis" to 16,"diecisiete" to 17,"dieciocho" to 18,"diecinueve" to 19,"veinte" to 20,"veintiuna" to 21,"veintiuno" to 21,"veintidos" to 22,"veintitres" to 23)
        for((w,n) in words) s=s.replace(Regex("\\b$w\\b"),n.toString())
        val m=Regex("(?:a las|a la|para las|para la|alarma(?: a| para)?|despiertame(?: a las| a la)?|avisame(?: a las| a la)?)\\s*(\\d{1,2})(?:[:.]([0-5]\\d))?").find(s)
            ?: Regex("\\b(\\d{1,2})[:.]([0-5]\\d)\\b").find(s)
            ?: return Result(true,"No he entendido la hora. Prueba diciendo: pon alarma a las siete y media.")
        var hour=m.groupValues.getOrNull(1)?.toIntOrNull()?:return Result(true,"No he entendido la hora.")
        var minute=m.groupValues.getOrNull(2)?.toIntOrNull()?:0
        if(s.contains("y media")) minute=30
        if(s.contains("y cuarto")) minute=15
        if(s.contains("menos cuarto")){hour=(hour+23)%24;minute=45}
        if((s.contains("tarde")||s.contains("noche"))&&hour in 1..11)hour+=12
        if(s.contains("mediodia")) hour=12
        if(s.contains("medianoche")) hour=0
        if(hour !in 0..23 || minute !in 0..59) return Result(true,"La hora de la alarma no es válida.")
        val intent=Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR,hour); putExtra(AlarmClock.EXTRA_MINUTES,minute); putExtra(AlarmClock.EXTRA_MESSAGE,"Jarvis"); putExtra(AlarmClock.EXTRA_SKIP_UI,true); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            val resolved=intent.resolveActivity(activity.packageManager)
            if(resolved!=null){ activity.startActivity(intent); Result(true,"Alarma puesta a las %02d:%02d.".format(hour,minute)) }
            else {
                val show=Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                activity.startActivity(show); Result(true,"He abierto Alarmas porque el reloj del teléfono no acepta creación directa.")
            }
        } catch(_:Exception) {
            runCatching { activity.startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            Result(true,"He abierto la sección de alarmas del teléfono.")
        }
    }

    private fun phoneAccessStatus():Result{val contacts=ContextCompat.checkSelfPermission(activity,Manifest.permission.READ_CONTACTS)==PackageManager.PERMISSION_GRANTED;val calls=ContextCompat.checkSelfPermission(activity,Manifest.permission.CALL_PHONE)==PackageManager.PERMISSION_GRANTED;if(!contacts||!calls)ActivityCompat.requestPermissions(activity,buildList{if(!contacts)add(Manifest.permission.READ_CONTACTS);if(!calls)add(Manifest.permission.CALL_PHONE)}.toTypedArray(),70);return Result(true,"Llamadas: ${if(calls)"habilitadas" else "falta permiso"}. Contactos: ${if(contacts)"habilitados" else "falta permiso"}.")}
    private fun messageAccessStatus():Result{val read=ContextCompat.checkSelfPermission(activity,Manifest.permission.READ_SMS)==PackageManager.PERMISSION_GRANTED;val listener=activity.getSharedPreferences("jarvis_mobile",Activity.MODE_PRIVATE).getBoolean("notification_listener_connected",false);if(!read)ActivityCompat.requestPermissions(activity,arrayOf(Manifest.permission.READ_SMS,Manifest.permission.RECEIVE_SMS,Manifest.permission.SEND_SMS),72);if(!listener)runCatching{activity.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))};return Result(true,"SMS: ${if(read)"lectura habilitada" else "concede el permiso"}. WhatsApp y RCS: ${if(listener)"acceso a notificaciones habilitado" else "activa Jarvis en Acceso a notificaciones"}.")}
    private fun notificationFeed():JSONArray=runCatching{JSONArray(activity.getSharedPreferences("jarvis_mobile",Activity.MODE_PRIVATE).getString("notification_feed","[]"))}.getOrElse{JSONArray()}
    private fun readNotificationMessages(filter:String,limit:Int):Result{val feed=notificationFeed();val out=mutableListOf<String>();val q=filter.lowercase();for(i in feed.length()-1 downTo 0){if(out.size>=limit)break;val n=feed.optJSONObject(i)?:continue;val pkg=n.optString("package");val who=n.optString("conversation").ifBlank{n.optString("title")};val body=n.optString("text");val match=if(q=="whatsapp")pkg.contains("whatsapp",true) else if(q=="messages")pkg.contains("messag",true)||pkg.contains("sms",true) else "$pkg $who $body".contains(q,true);if(match&&body.isNotBlank())out+="${who.ifBlank{pkg}}: ${body.take(700)}"};val connected=activity.getSharedPreferences("jarvis_mobile",Activity.MODE_PRIVATE).getBoolean("notification_listener_connected",false);if(out.isEmpty()&&!connected)runCatching{activity.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))};return Result(true,if(out.isEmpty())"No tengo mensajes recientes capturados de $filter. ${if(!connected)"Te he abierto Acceso a notificaciones para activar Jarvis." else "El acceso está activo; puedo leer los mensajes que lleguen como notificación."}" else "Mensajes recientes:\n\n"+out.joinToString("\n\n"))}
    private fun ensureSmsPermission():Boolean{if(ContextCompat.checkSelfPermission(activity,Manifest.permission.READ_SMS)==PackageManager.PERMISSION_GRANTED)return true;ActivityCompat.requestPermissions(activity,arrayOf(Manifest.permission.READ_SMS,Manifest.permission.RECEIVE_SMS,Manifest.permission.SEND_SMS),72);return false}
    private fun readRecentSms(limit:Int):Result{if(!ensureSmsPermission())return Result(true,"Concede el permiso de SMS y vuelve a decir lee mis mensajes.");val out=mutableListOf<String>();val sdf=SimpleDateFormat("dd/MM HH:mm",Locale.getDefault());runCatching{activity.contentResolver.query(Uri.parse("content://sms/inbox"),arrayOf("address","body","date"),null,null,"date DESC")?.use{c->val ai=c.getColumnIndex("address");val bi=c.getColumnIndex("body");val di=c.getColumnIndex("date");while(c.moveToNext()&&out.size<limit){val a=if(ai>=0)c.getString(ai).orEmpty() else "";val b=if(bi>=0)c.getString(bi).orEmpty() else "";val d=if(di>=0)c.getLong(di) else 0L;out+="${contactNameForNumber(a)?:a} · ${if(d>0)sdf.format(Date(d)) else ""}\n${b.take(500)}"}}};return if(out.isNotEmpty())Result(true,"Últimos SMS:\n\n"+out.joinToString("\n\n")) else readNotificationMessages("messages",limit)}
    private fun readSmsFrom(name:String):Result=readNotificationMessages(name,8)
    private fun contactNameForNumber(number:String):String?{if(number.isBlank()||ContextCompat.checkSelfPermission(activity,Manifest.permission.READ_CONTACTS)!=PackageManager.PERMISSION_GRANTED)return null;val uri=Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI,Uri.encode(number));activity.contentResolver.query(uri,arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),null,null,null)?.use{c->if(c.moveToFirst())return c.getString(0)};return null}
    private fun callNumber(number:String):Result{if(ContextCompat.checkSelfPermission(activity,Manifest.permission.CALL_PHONE)!=PackageManager.PERMISSION_GRANTED){ActivityCompat.requestPermissions(activity,arrayOf(Manifest.permission.CALL_PHONE),70);return Result(true,"Concede permiso de Teléfono y repite la orden.")};val clean=number.filter{it.isDigit()||it=='+'};return try{activity.startActivity(Intent(Intent.ACTION_CALL,Uri.parse("tel:${Uri.encode(clean)}")));Result(true,"Llamando a $clean.")}catch(_:Exception){activity.startActivity(Intent(Intent.ACTION_DIAL,Uri.parse("tel:${Uri.encode(clean)}")));Result(true,"He abierto el marcador con $clean.")}}
    private fun callContact(name:String):Result{if(name.isBlank())return Result(true,"Dime a quién quieres llamar.");val nc=ContextCompat.checkSelfPermission(activity,Manifest.permission.READ_CONTACTS)!=PackageManager.PERMISSION_GRANTED;val np=ContextCompat.checkSelfPermission(activity,Manifest.permission.CALL_PHONE)!=PackageManager.PERMISSION_GRANTED;if(nc||np){ActivityCompat.requestPermissions(activity,buildList{if(nc)add(Manifest.permission.READ_CONTACTS);if(np)add(Manifest.permission.CALL_PHONE)}.toTypedArray(),70);return Result(true,"Concede Contactos y Teléfono y repite la orden.")};val match=findPhone(name)?:return Result(true,"No encuentro un contacto llamado $name.");AlertDialog.Builder(activity).setTitle("Llamar a ${match.first}").setMessage(match.second).setPositiveButton("LLAMAR"){_,_->callNumber(match.second)}.setNegativeButton("Cancelar",null).show();return Result(true,"He encontrado a ${match.first}. Confirma la llamada en pantalla.")}
    private fun smsContact(name:String,body:String):Result{if(ContextCompat.checkSelfPermission(activity,Manifest.permission.READ_CONTACTS)!=PackageManager.PERMISSION_GRANTED){ActivityCompat.requestPermissions(activity,arrayOf(Manifest.permission.READ_CONTACTS),71);return Result(true,"Concede Contactos y repite la orden.")};val match=findPhone(name)?:return Result(true,"No encuentro un contacto llamado $name.");activity.startActivity(Intent(Intent.ACTION_SENDTO,Uri.parse("smsto:${Uri.encode(match.second)}")).apply{putExtra("sms_body",body)});return Result(true,"He preparado el SMS para ${match.first}.")}
    private fun openApp(name:String):Result{val wanted=norm(name);val apps=activity.packageManager.getInstalledApplications(PackageManager.GET_META_DATA);val match=apps.firstOrNull{norm(activity.packageManager.getApplicationLabel(it).toString()).contains(wanted)}?:return Result(true,"No encuentro una app llamada $name.");val launch=activity.packageManager.getLaunchIntentForPackage(match.packageName)?:return Result(true,"No puedo abrir $name.");activity.startActivity(launch);return Result(true,"He abierto ${activity.packageManager.getApplicationLabel(match)}.")}
    private fun findPhone(query:String):Pair<String,String>?{val q=norm(query);val projection=arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,ContactsContract.CommonDataKinds.Phone.NUMBER);var best:Pair<String,String>?=null;activity.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,projection,null,null,null)?.use{c->while(c.moveToNext()){val n=c.getString(0).orEmpty();val p=c.getString(1).orEmpty();val nn=norm(n);if(nn==q)return n to p;if(best==null&&(nn.contains(q)||q.contains(nn)))best=n to p}};return best}
    private fun norm(s:String):String=Normalizer.normalize(s.lowercase(Locale.getDefault()),Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"),"").replace(Regex("[^a-z0-9+:. ]+")," ").replace(Regex("\\s+")," ").trim()
}
