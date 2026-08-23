package com.jarvis.mobile

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

class DomoticsHubActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("jarvis_mobile", MODE_PRIVATE) }
    private lateinit var list: LinearLayout
    private val backend = "https://chatgpt-tv2.vercel.app"
    @Volatile private var tadoPolling = false

    data class Provider(val id:String,val name:String,val desc:String,val loginLabel:String)
    private val providers = listOf(
        Provider("sensibo","Sensibo","Aire acondicionado","Entrar con mi cuenta Sensibo"),
        Provider("tado","Tado","Climatización","Entrar con mi cuenta Tado"),
        Provider("smartthings","Samsung SmartThings","Casa y energía","Entrar con mi cuenta Samsung"),
        Provider("homeconnect","Bosch / Siemens Home Connect","Electrodomésticos","Entrar con mi cuenta Home Connect"),
        Provider("hue","Philips Hue","Iluminación","Entrar con mi cuenta Hue"),
        Provider("roborock","Roborock","Robot aspirador","Entrar con mi cuenta Roborock")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(28,28,28,28)}
        root.addView(TextView(this).apply{text="Domótica y cuentas";textSize=26f})
        root.addView(TextView(this).apply{text="Conecta cada cuenta y Jarvis mostrará aquí sus equipos controlables. Tado ya permite descubrir zonas y controlarlas directamente.";textSize=14f;setPadding(0,8,0,18)})
        list=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        root.addView(ScrollView(this).apply{addView(list)},LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,0,1f))
        root.addView(Button(this).apply{text="Volver";setOnClickListener{finish()}})
        setContentView(root)
        handleCallback(intent)
        refresh()
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handleCallback(intent); refresh() }
    override fun onResume(){super.onResume();refresh()}

    private fun refresh(){
        list.removeAllViews()
        providers.forEach { p ->
            val status=prefs.getString("domotics_${p.id}_status","not_connected") ?: "not_connected"
            val connected=status=="connected"
            val row=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(22,18,22,18)}
            row.addView(TextView(this).apply{text="${p.name}  ${if(connected)"✓" else "○"}";textSize=18f})
            row.addView(TextView(this).apply{text="${p.desc} · ${if(connected)"conectado" else if(status=="pending")"esperando autorización" else "sin conectar"}";textSize=13f})
            if (p.id=="tado" && connected) {
                row.addView(Button(this).apply{text="Ver y controlar mis equipos Tado";setOnClickListener{loadTadoDevices()}})
            }
            row.addView(Button(this).apply{text=if(connected)"Volver a autorizar" else p.loginLabel;setOnClickListener{startLogin(p)}})
            if(connected) row.addView(Button(this).apply{text="Desconectar";setOnClickListener{disconnect(p)}})
            list.addView(row)
        }
    }

    private fun loadTadoDevices(){
        Toast.makeText(this,"Consultando Tado…",Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val zones=TadoClient.zones(this)
                runOnUiThread {
                    if(zones.isEmpty()) { Toast.makeText(this,"Tado está conectado, pero no devolvió zonas controlables.",Toast.LENGTH_LONG).show(); return@runOnUiThread }
                    showTadoZones(zones)
                }
            } catch(e:Exception) { runOnUiThread{Toast.makeText(this,"No pude leer tus equipos Tado: ${e.message}",Toast.LENGTH_LONG).show()} }
        }.start()
    }

    private fun showTadoZones(zones:List<TadoClient.Zone>){
        val labels=zones.map { z ->
            val now=z.temperature?.let{"%.1f°".format(it)} ?: "--"
            val target=z.target?.let{" · objetivo %.1f°".format(it)} ?: ""
            "${z.name} · $now$target · ${z.power}"
        }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Equipos Tado").setItems(labels){_,which->showTadoControl(zones[which])}.setNegativeButton("Cerrar",null).show()
    }

    private fun showTadoControl(zone:TadoClient.Zone){
        val actions=arrayOf("Encender", "Apagar", "Subir 1°", "Bajar 1°", "Volver al horario automático")
        AlertDialog.Builder(this).setTitle(zone.name).setMessage("Temperatura actual: ${zone.temperature?.let{"%.1f °C".format(it)} ?: "sin dato"}\nObjetivo: ${zone.target?.let{"%.1f °C".format(it)} ?: "sin dato"}").setItems(actions){_,i->
            Thread {
                try {
                    when(i){
                        0 -> TadoClient.setPower(this,zone,true,zone.target ?: 21.0)
                        1 -> TadoClient.setPower(this,zone,false)
                        2 -> TadoClient.setTemperature(this,zone,(zone.target ?: zone.temperature ?: 21.0)+1.0)
                        3 -> TadoClient.setTemperature(this,zone,(zone.target ?: zone.temperature ?: 21.0)-1.0)
                        4 -> TadoClient.resumeSchedule(this,zone)
                    }
                    runOnUiThread{Toast.makeText(this,"${zone.name}: orden enviada ✓",Toast.LENGTH_SHORT).show();loadTadoDevices()}
                } catch(e:Exception){runOnUiThread{Toast.makeText(this,"No se pudo controlar ${zone.name}: ${e.message}",Toast.LENGTH_LONG).show()}}
            }.start()
        }.setNegativeButton("Cancelar",null).show()
    }

    private fun startLogin(p:Provider){
        if(p.id=="tado") { startTadoDeviceFlow(); return }
        val installId=prefs.getString("domotics_install_id",null) ?: UUID.randomUUID().toString().also{prefs.edit().putString("domotics_install_id",it).apply()}
        prefs.edit().putString("domotics_${p.id}_status","pending").apply()
        val returnUri="jarvis://domotics/callback"
        val url="$backend/api/domotics/connect?provider=${Uri.encode(p.id)}&installId=${Uri.encode(installId)}&returnUri=${Uri.encode(returnUri)}"
        val ok=runCatching{startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)));true}.getOrDefault(false)
        if(!ok) Toast.makeText(this,"No se pudo abrir el inicio de sesión.",Toast.LENGTH_LONG).show()
        refresh()
    }

    private fun startTadoDeviceFlow(){
        if(tadoPolling) return
        tadoPolling=true
        prefs.edit().putString("domotics_tado_status","pending").apply();refresh()
        Toast.makeText(this,"Preparando acceso seguro a Tado…",Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val clientId="1bb50063-6b0c-4d11-bd99-387f4a91cc46"
                val startUrl="https://login.tado.com/oauth2/device_authorize?client_id=${enc(clientId)}&scope=${enc("offline_access")}" 
                val data=JSONObject(postForm(startUrl,""))
                val deviceCode=data.getString("device_code")
                val verification=data.optString("verification_uri_complete",data.optString("verification_uri"))
                val expires=data.optInt("expires_in",300)
                var interval=data.optInt("interval",5).coerceAtLeast(3)
                runOnUiThread { runCatching{startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(verification)))};Toast.makeText(this,"Inicia sesión en Tado y autoriza Jarvis.",Toast.LENGTH_LONG).show() }
                val deadline=System.currentTimeMillis()+expires*1000L
                var connected=false
                while(System.currentTimeMillis()<deadline && !connected){
                    Thread.sleep(interval*1000L)
                    val tokenBody="client_id=${enc(clientId)}&device_code=${enc(deviceCode)}&grant_type=${enc("urn:ietf:params:oauth:grant-type:device_code")}" 
                    val response=postFormWithStatus("https://login.tado.com/oauth2/token",tokenBody)
                    if(response.first in 200..299){
                        val tok=JSONObject(response.second);val access=tok.optString("access_token");val refreshToken=tok.optString("refresh_token")
                        if(access.isNotBlank()){
                            val e=prefs.edit().putString("domotics_tado_status","connected").putString("domotics_tado_access_token",access).putLong("domotics_tado_access_expires_at",System.currentTimeMillis()+tok.optLong("expires_in",600)*1000L)
                            if(refreshToken.isNotBlank())e.putString("domotics_tado_refresh_token",refreshToken);e.apply();connected=true
                            runOnUiThread{Toast.makeText(this,"Tado conectado ✓. Ya puedes ver tus equipos.",Toast.LENGTH_LONG).show();refresh();loadTadoDevices()}
                        }
                    } else {
                        val err=runCatching{JSONObject(response.second).optString("error")}.getOrDefault("")
                        if(err=="slow_down") interval+=5
                        if(err!="authorization_pending" && err!="slow_down" && err.isNotBlank()) throw IllegalStateException(err)
                    }
                }
                if(!connected){prefs.edit().putString("domotics_tado_status","not_connected").apply();runOnUiThread{Toast.makeText(this,"La autorización de Tado ha caducado.",Toast.LENGTH_LONG).show();refresh()}}
            } catch(e:Exception){prefs.edit().putString("domotics_tado_status","not_connected").apply();runOnUiThread{Toast.makeText(this,"No se pudo conectar Tado: ${e.message}",Toast.LENGTH_LONG).show();refresh()}}
            finally { tadoPolling=false }
        }.start()
    }

    private fun postForm(url:String,body:String):String{val r=postFormWithStatus(url,body);if(r.first !in 200..299)throw IllegalStateException("HTTP ${r.first}: ${r.second}");return r.second}
    private fun postFormWithStatus(url:String,body:String):Pair<Int,String>{val c=(URL(url).openConnection() as HttpURLConnection).apply{requestMethod="POST";doOutput=true;connectTimeout=8000;readTimeout=12000;setRequestProperty("Content-Type","application/x-www-form-urlencoded");setRequestProperty("Accept","application/json")};c.outputStream.use{it.write(body.toByteArray())};val code=c.responseCode;val text=(if(code in 200..299)c.inputStream else c.errorStream)?.bufferedReader()?.use{it.readText()}.orEmpty();c.disconnect();return code to text}
    private fun enc(v:String)=URLEncoder.encode(v,"UTF-8")

    private fun disconnect(p:Provider){val e=prefs.edit().remove("domotics_${p.id}_status").remove("domotics_${p.id}_account");if(p.id=="tado")e.remove("domotics_tado_access_token").remove("domotics_tado_refresh_token").remove("domotics_tado_access_expires_at");e.apply();refresh();Toast.makeText(this,"${p.name} desconectado.",Toast.LENGTH_SHORT).show()}

    private fun handleCallback(i:Intent){val d=i.data ?: return;if(d.scheme!="jarvis" || d.host!="domotics") return;val provider=d.getQueryParameter("provider") ?: return;val status=d.getQueryParameter("status") ?: "error";val account=d.getQueryParameter("account");prefs.edit().putString("domotics_${provider}_status",if(status=="ok")"connected" else "not_connected").apply();if(!account.isNullOrBlank()) prefs.edit().putString("domotics_${provider}_account",account).apply();Toast.makeText(this,if(status=="ok")"Cuenta vinculada correctamente" else "No se pudo completar la vinculación",Toast.LENGTH_LONG).show()}
}
