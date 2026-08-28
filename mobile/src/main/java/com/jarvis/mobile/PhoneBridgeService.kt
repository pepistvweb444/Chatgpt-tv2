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

class PhoneBridgeService : Service() {
    @Volatile private var running=false
    private var server:ServerSocket?=null
    override fun onCreate(){super.onCreate();try{if(Build.VERSION.SDK_INT>=26)getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL,"Jarvis TV bridge",NotificationManager.IMPORTANCE_LOW));val open=PendingIntent.getActivity(this,0,Intent(this,ChatActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT);startForeground(93,NotificationCompat.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.sym_action_call).setContentTitle("Jarvis · puente con TV").setContentText("Llamadas, SMS y notificaciones para Jarvis TV").setOngoing(true).setContentIntent(open).build());getSharedPreferences("jarvis_mobile",MODE_PRIVATE).edit().putBoolean("bridge_running",true).remove("bridge_start_error").apply();running=true;Thread{serve()}.start()}catch(e:Throwable){running=false;getSharedPreferences("jarvis_mobile",MODE_PRIVATE).edit().putBoolean("bridge_running",false).putString("bridge_start_error","${e.javaClass.simpleName}: ${e.message.orEmpty()}").apply();stopSelf()}}
    private fun serve(){try{server=ServerSocket(PORT);while(running){val socket=server?.accept()?:break;Thread{socket.use{s->val reader=BufferedReader(InputStreamReader(s.getInputStream()));val first=reader.readLine().orEmpty();while(reader.readLine()?.isNotEmpty()==true){};val path=first.split(" ").getOrNull(1).orEmpty();val response=handle(path);val body=response.second;val code=response.first;val out=s.getOutputStream();out.write("HTTP/1.1 $code ${if(code==200)"OK" else "ERROR"}\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body".toByteArray());out.flush()}}.start()}}catch(e:Exception){getSharedPreferences("jarvis_mobile",MODE_PRIVATE).edit().putBoolean("bridge_running",false).putString("bridge_start_error","${e.javaClass.simpleName}: ${e.message.orEmpty()}").apply()}}
    private fun handle(path:String):Pair<Int,String>{
        if(path.startsWith("/ping"))return 200 to JSONObject().put("ok",true).put("device","jarvis-phone").toString()
        if(path.startsWith("/permissions"))return 200 to permissionStatus().toString()
        if(path.startsWith("/incoming-call-action")){val action=URLDecoder.decode(path.substringAfter("action=","ignore").substringBefore("&"),StandardCharsets.UTF_8.name()).lowercase();val(ok,message)=CallActionManager.perform(this,action);return(if(ok)200 else 409)to JSONObject().put("ok",ok).put("message",message).put("call",CallStateStore.current(this)).toString()}
        if(path.startsWith("/incoming-call"))return 200 to JSONObject().put("ok",true).put("call",CallStateStore.current(this)).toString()
        if(path.startsWith("/messages")){val source=URLDecoder.decode(path.substringAfter("source=","all").substringBefore("&"),StandardCharsets.UTF_8.name());return 200 to recentMessages(source).toString()}
        if(path.startsWith("/call?")){val raw=path.substringAfter("number=","").substringBefore("&");val number=URLDecoder.decode(raw,StandardCharsets.UTF_8.name()).trim();if(number.isBlank())return 400 to JSONObject().put("error","number-required").toString();if(ContextCompat.checkSelfPermission(this,Manifest.permission.CALL_PHONE)!=PackageManager.PERMISSION_GRANTED)return 403 to JSONObject().put("error","call-permission-required").toString();return try{startActivity(Intent(Intent.ACTION_CALL,Uri.parse("tel:"+Uri.encode(number))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));200 to JSONObject().put("ok",true).put("status","calling").toString()}catch(e:Exception){500 to JSONObject().put("error",e.message?:"call-failed").toString()}}
        return 404 to JSONObject().put("error","not-found").toString()
    }
    private fun permissionStatus():JSONObject=JSONObject().put("readSms",ContextCompat.checkSelfPermission(this,Manifest.permission.READ_SMS)==PackageManager.PERMISSION_GRANTED).put("receiveSms",ContextCompat.checkSelfPermission(this,Manifest.permission.RECEIVE_SMS)==PackageManager.PERMISSION_GRANTED).put("sendSms",ContextCompat.checkSelfPermission(this,Manifest.permission.SEND_SMS)==PackageManager.PERMISSION_GRANTED).put("contacts",ContextCompat.checkSelfPermission(this,Manifest.permission.READ_CONTACTS)==PackageManager.PERMISSION_GRANTED).put("callPhone",ContextCompat.checkSelfPermission(this,Manifest.permission.CALL_PHONE)==PackageManager.PERMISSION_GRANTED).put("answerCalls",ContextCompat.checkSelfPermission(this,Manifest.permission.ANSWER_PHONE_CALLS)==PackageManager.PERMISSION_GRANTED)
    private fun recentMessages(source:String):JSONObject{val out=JSONArray();val normalized=source.lowercase();if(normalized=="all"||normalized=="sms")if(ContextCompat.checkSelfPermission(this,Manifest.permission.READ_SMS)==PackageManager.PERMISSION_GRANTED)runCatching{contentResolver.query(Uri.parse("content://sms/inbox"),arrayOf("address","body","date"),null,null,"date DESC")?.use{c->val ai=c.getColumnIndex("address");val bi=c.getColumnIndex("body");val di=c.getColumnIndex("date");while(c.moveToNext()&&out.length()<15)out.put(JSONObject().put("source","sms").put("from",if(ai>=0)c.getString(ai).orEmpty()else"").put("text",if(bi>=0)c.getString(bi).orEmpty()else"").put("time",if(di>=0)c.getLong(di)else 0L))}};if(normalized=="all"||normalized=="whatsapp"||normalized=="notifications"){val prefs=getSharedPreferences("jarvis_mobile",MODE_PRIVATE);val feed=runCatching{JSONArray(prefs.getString("notification_feed","[]"))}.getOrElse{JSONArray()};for(i in feed.length()-1 downTo 0){if(out.length()>=30)break;val n=feed.optJSONObject(i)?:continue;val pkg=n.optString("package");val wa=pkg.contains("whatsapp",true);if(normalized=="whatsapp"&&!wa)continue;if(normalized=="all"&&!wa&&!pkg.contains("messag",true))continue;out.put(JSONObject().put("source",if(wa)"whatsapp" else "notification").put("from",n.optString("conversation").ifBlank{n.optString("title")}).put("text",n.optString("text")).put("time",n.optLong("time")))}};return JSONObject().put("permissions",permissionStatus()).put("messages",out)}
    override fun onDestroy(){running=false;getSharedPreferences("jarvis_mobile",MODE_PRIVATE).edit().putBoolean("bridge_running",false).apply();runCatching{server?.close()};super.onDestroy()}
    override fun onBind(intent:Intent?):IBinder?=null
    companion object{const val PORT=8765;private const val CHANNEL="jarvis_phone_bridge"}
}
