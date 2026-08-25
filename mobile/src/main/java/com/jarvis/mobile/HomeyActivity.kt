package com.jarvis.mobile

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class HomeyActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("jarvis_mobile", MODE_PRIVATE) }
    private lateinit var host: LinearLayout
    private lateinit var status: TextView
    private val backend = "https://chatgpt-tv2.vercel.app"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(28,28,28,28); setBackgroundColor(Color.rgb(9,12,18)) }
        root.addView(TextView(this).apply { text="Homey Cloud"; textSize=28f; setTextColor(Color.WHITE) })
        root.addView(TextView(this).apply { text="Luces y dispositivos enlazados a Homey · acceso OAuth oficial"; textSize=14f; setTextColor(Color.LTGRAY); setPadding(0,6,0,14) })
        status=TextView(this).apply { textSize=14f; setTextColor(Color.WHITE); setPadding(0,0,0,12) }; root.addView(status)
        root.addView(Button(this).apply { text="CONECTAR / AUTORIZAR HOMEY CLOUD"; setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$backend/api/domotics/homey-auth"))) } })
        root.addView(Button(this).apply { text="ACTUALIZAR DISPOSITIVOS"; setOnClickListener { refresh() } })
        root.addView(Button(this).apply { text="DESCONECTAR HOMEY"; setOnClickListener { prefs.edit().remove("homey_session").remove("homey_devices_json").apply(); render(JSONArray()); status.text="Homey desconectado" } })
        host=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(host) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,0,1f))
        setContentView(root)
        handleIntent(intent)
        if (prefs.getString("homey_session","").orEmpty().isNotBlank()) refresh() else status.text="Pulsa Conectar para iniciar sesión en Homey."
    }

    override fun onNewIntent(intent: Intent?) { super.onNewIntent(intent); if(intent!=null){ setIntent(intent); handleIntent(intent) } }

    private fun handleIntent(i: Intent) {
        val data=i.data ?: return
        if(data.scheme=="jarvis" && data.host=="homey") {
            val session=data.getQueryParameter("session").orEmpty()
            if(session.isNotBlank()) { prefs.edit().putString("homey_session",session).apply(); status.text="Homey autorizado. Cargando dispositivos…"; refresh() }
        }
    }

    private fun refresh() {
        val session=prefs.getString("homey_session","").orEmpty(); if(session.isBlank()){status.text="Falta autorizar Homey";return}
        status.text="Consultando Homey Cloud…"
        Thread {
            try {
                val c=(URL("$backend/api/domotics/homey").openConnection() as HttpURLConnection).apply { requestMethod="GET";connectTimeout=9000;readTimeout=25000;setRequestProperty("X-Homey-Session",session) }
                val raw=(if(c.responseCode in 200..299)c.inputStream else c.errorStream)?.bufferedReader()?.use{it.readText()}.orEmpty()
                if(c.responseCode !in 200..299) throw IllegalStateException(raw.take(240))
                val o=JSONObject(raw); val devices=o.optJSONArray("devices")?:JSONArray(); val renewed=o.optString("session")
                val edit=prefs.edit().putString("homey_devices_json",devices.toString()); if(renewed.isNotBlank()) edit.putString("homey_session",renewed); edit.apply()
                runOnUiThread { status.text="Homey conectado · ${devices.length()} dispositivos"; render(devices) }
            } catch(e:Throwable){ runOnUiThread { status.text="Homey: ${e.message?:"error"}" } }
        }.start()
    }

    private fun render(devices: JSONArray) {
        host.removeAllViews()
        for(i in 0 until devices.length()) {
            val d=devices.optJSONObject(i)?:continue; val name=d.optString("name").ifBlank{"Dispositivo"}; val state=d.optJSONObject("state")?:JSONObject()
            val on:Boolean?=if(state.has("onoff")&&!state.isNull("onoff")) state.optBoolean("onoff") else null
            val temp=when { state.has("measure_temperature")&&!state.isNull("measure_temperature") -> state.optDouble("measure_temperature").toString()+" °C"; state.has("target_temperature")&&!state.isNull("target_temperature") -> state.optDouble("target_temperature").toString()+" °C"; else -> "" }
            val b=Button(this).apply { text="${if(on==true) "●" else if(on==false) "○" else "•"} $name${if(temp.isNotBlank()) " · $temp" else ""}"; isAllCaps=false }
            if(on!=null) b.setOnClickListener { setCapability(d.optString("id"),"onoff",!on,name) }
            host.addView(b)
        }
    }

    private fun setCapability(deviceId:String,capability:String,value:Any,name:String) {
        val session=prefs.getString("homey_session","").orEmpty(); if(session.isBlank())return
        status.text="Actualizando $name…"
        Thread {
            try {
                val c=(URL("$backend/api/domotics/homey").openConnection() as HttpURLConnection).apply { requestMethod="POST";doOutput=true;connectTimeout=9000;readTimeout=25000;setRequestProperty("Content-Type","application/json");setRequestProperty("X-Homey-Session",session) }
                val body=JSONObject().put("deviceId",deviceId).put("capabilityId",capability).put("value",value)
                c.outputStream.use{it.write(body.toString().toByteArray())}; val raw=(if(c.responseCode in 200..299)c.inputStream else c.errorStream)?.bufferedReader()?.use{it.readText()}.orEmpty(); if(c.responseCode !in 200..299)throw IllegalStateException(raw.take(180))
                val o=JSONObject(raw); val arr=o.optJSONArray("devices")?:JSONArray(); val renewed=o.optString("session"); val edit=prefs.edit().putString("homey_devices_json",arr.toString()); if(renewed.isNotBlank())edit.putString("homey_session",renewed); edit.apply()
                runOnUiThread { status.text="$name actualizado"; render(arr) }
            } catch(e:Throwable){runOnUiThread{Toast.makeText(this,"Homey: ${e.message}",Toast.LENGTH_LONG).show();status.text="No se pudo cambiar $name"}}
        }.start()
    }
}
