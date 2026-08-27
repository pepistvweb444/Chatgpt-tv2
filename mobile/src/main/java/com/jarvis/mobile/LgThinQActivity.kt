package com.jarvis.mobile

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class LgThinQActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("jarvis_mobile", MODE_PRIVATE) }
    private lateinit var list: LinearLayout
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 28, 28, 28)
            setBackgroundColor(0xFF0B0E14.toInt())
        }
        root.addView(TextView(this).apply { text = "LG ThinQ"; textSize = 26f; setTextColor(Color.WHITE) })
        root.addView(TextView(this).apply {
            text = "Conector oficial ThinQ Connect API · muestra únicamente dispositivos reales devueltos por LG"
            textSize = 14f; setTextColor(0xFFB9C1CE.toInt()); setPadding(0,8,0,14)
        })
        status = TextView(this).apply { textSize = 13f; setTextColor(0xFF8FB6FF.toInt()); setPadding(0,0,0,12) }
        root.addView(status)
        fun add(label:String, action:()->Unit)=root.addView(Button(this).apply{ text=label; setOnClickListener{action()} })
        add("Configurar token LG ThinQ") { configureToken() }
        add("Abrir portal oficial de LG ThinQ API") { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://smartsolution.developer.lge.com/"))) }
        add("Actualizar dispositivos") { refreshDevices() }
        add("Borrar conexión LG ThinQ") { prefs.edit().remove("lg_thinq_pat").remove("lg_thinq_country").apply(); list.removeAllViews(); updateStatus() }
        val scroll = ScrollView(this)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0,16,0,20) }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f))
        setContentView(root)
        updateStatus()
        if (prefs.getString("lg_thinq_pat","").orEmpty().isNotBlank()) refreshDevices()
    }

    private fun configureToken() {
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(30,10,30,0)}
        val token=EditText(this).apply{hint="Personal Access Token (PAT)";inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD;setText(prefs.getString("lg_thinq_pat",""))}
        val country=EditText(this).apply{hint="País, por ejemplo ES";setText(prefs.getString("lg_thinq_country","ES"))}
        box.addView(token);box.addView(country)
        AlertDialog.Builder(this).setTitle("LG ThinQ Connect").setView(box)
            .setMessage("Crea un PAT en el portal oficial de LG con permisos de ver dispositivos, estados y control. Jarvis no inventará equipos si LG devuelve una lista vacía.")
            .setPositiveButton("Guardar") { _,_ ->
                prefs.edit().putString("lg_thinq_pat",token.text.toString().trim()).putString("lg_thinq_country",country.text.toString().trim().uppercase().ifBlank{"ES"}).apply()
                updateStatus(); refreshDevices()
            }.setNegativeButton("Cancelar",null).show()
    }

    private fun updateStatus(){
        val connected=prefs.getString("lg_thinq_pat","").orEmpty().isNotBlank()
        status.text=if(connected) "LG ThinQ · token configurado · país ${prefs.getString("lg_thinq_country","ES")}" else "LG ThinQ · sin configurar"
    }

    private fun api(path:String): JSONObject {
        val token=prefs.getString("lg_thinq_pat","").orEmpty(); if(token.isBlank()) throw IllegalStateException("Configura primero el token LG ThinQ")
        val country=prefs.getString("lg_thinq_country","ES").orEmpty().ifBlank{"ES"}
        val c=(URL("https://api-aic.lgthinq.com$path").openConnection() as HttpURLConnection).apply{
            requestMethod="GET";connectTimeout=7000;readTimeout=12000
            setRequestProperty("Authorization","Bearer $token")
            setRequestProperty("x-message-id",UUID.randomUUID().toString())
            setRequestProperty("country",country)
            setRequestProperty("Accept","application/json")
        }
        val raw=(if(c.responseCode in 200..299)c.inputStream else c.errorStream)?.bufferedReader()?.use{it.readText()}.orEmpty()
        if(c.responseCode !in 200..299) throw IllegalStateException("LG ThinQ HTTP ${c.responseCode}: ${raw.take(180)}")
        return JSONObject(raw)
    }

    private fun refreshDevices(){
        status.text="LG ThinQ · leyendo dispositivos reales…"
        Thread{
            try{
                val root=api("/devices")
                val arr=root.optJSONArray("response") ?: root.optJSONObject("result")?.optJSONArray("devices") ?: JSONArray()
                val devices=mutableListOf<JSONObject>()
                for(i in 0 until arr.length()) arr.optJSONObject(i)?.let{devices+=it}
                runOnUiThread{
                    list.removeAllViews()
                    if(devices.isEmpty()) addCard("LG ThinQ","LG devolvió 0 dispositivos. No se mostrarán dispositivos simulados.")
                    else devices.forEach{addDevice(it)}
                    status.text="LG ThinQ · ${devices.size} dispositivo${if(devices.size==1)"" else "s"} real${if(devices.size==1)"" else "es"}"
                }
            }catch(e:Throwable){runOnUiThread{list.removeAllViews();addCard("Error LG ThinQ",e.message?:"No disponible");status.text="LG ThinQ · error"}}
        }.start()
    }

    private fun addDevice(d:JSONObject){
        val info=d.optJSONObject("deviceInfo") ?: d
        val id=d.optString("deviceId").ifBlank{info.optString("deviceId")}
        val name=info.optString("alias").ifBlank{info.optString("modelName").ifBlank{info.optString("deviceType").ifBlank{"Dispositivo LG"}}}
        val type=info.optString("deviceType")
        val base=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(18,16,18,16);setBackgroundColor(0xFF17202B.toInt())}
        base.addView(TextView(this).apply{text=name;textSize=17f;setTextColor(Color.WHITE)})
        base.addView(TextView(this).apply{text="LG ThinQ API · $type\nID: $id";textSize=12f;setTextColor(0xFFB5C4D8.toInt());setPadding(0,5,0,8)})
        val state=TextView(this).apply{text="Estado: pulsa Actualizar";textSize=13f;setTextColor(0xFFE6EEF8.toInt())}
        base.addView(state)
        base.addView(Button(this).apply{text="Leer estado real";setOnClickListener{
            if(id.isBlank()){Toast.makeText(this@LgThinQActivity,"LG no devolvió deviceId",Toast.LENGTH_SHORT).show();return@setOnClickListener}
            Thread{try{val j=api("/devices/${Uri.encode(id)}/state");runOnUiThread{state.text="Estado real: "+j.toString(2).take(1200)}}catch(e:Throwable){runOnUiThread{state.text="Estado no disponible: ${e.message}"}}}.start()
        }})
        list.addView(base,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{bottomMargin=14})
    }

    private fun addCard(title:String,body:String){
        list.addView(TextView(this).apply{text="$title\n\n$body";textSize=15f;setTextColor(Color.WHITE);gravity=Gravity.START;setPadding(18,16,18,16);setBackgroundColor(0xFF17202B.toInt())})
    }
}
