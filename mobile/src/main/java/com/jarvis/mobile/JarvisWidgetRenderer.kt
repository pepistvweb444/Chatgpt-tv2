package com.jarvis.mobile

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object JarvisWidgetRenderer {
    private const val BACKEND="https://chatgpt-tv2.vercel.app"

    fun render(activity: Activity, container: LinearLayout, query: String, answer: String) {
        val q=query.lowercase()
        if(q.contains("noticia")||q.contains("titular")||q.contains("actualidad")){
            fetchAndRenderNews(activity,container,query)
            return
        }
        if(q.contains("aire acondicionado")||q.contains("tado")||q.contains("clima")||q.contains("termostato")){
            renderTadoStatus(activity,container,true)
            return
        }
        val kind=when{
            q.contains("alarma")||q.contains("despiert")||q.contains("avisa")->"⏰  ALARMA"
            q.contains("whatsapp")||q.contains("sms")||q.contains("mensaje")->"💬  MENSAJES"
            q.contains("tiempo")||q.contains("temperatura")||q.contains("lluv")->"🌤️  TIEMPO"
            q.contains("smartthings")||q.contains("hue")||q.contains("home connect")||q.contains("roborock")||q.contains("domot")->"🏠  DOMÓTICA"
            q.contains("llama")||q.contains("llamar")||q.contains("telefono")->"📞  TELÉFONO"
            else->return
        }
        container.removeAllViews();container.visibility=LinearLayout.VISIBLE
        val card=baseCard(activity)
        card.addView(TextView(activity).apply{text=kind;textSize=13f;setTextColor(Color.rgb(80,80,80))})
        card.addView(TextView(activity).apply{text=answer;textSize=18f;setTextColor(Color.rgb(25,25,25));setPadding(0,8,0,0)})
        container.addView(card,params())
    }

    fun renderTadoStatus(activity:Activity,container:LinearLayout,replace:Boolean=false){
        val connected=TadoClient.isConnected(activity) || activity.getSharedPreferences("jarvis_mobile",Activity.MODE_PRIVATE).getString("domotics_tado_status","")=="connected"
        if(!connected)return
        if(replace) container.removeAllViews()
        container.visibility=LinearLayout.VISIBLE
        val loading=TextView(activity).apply{text="❄️  CLIMATIZACIÓN · actualizando…";textSize=14f;setTextColor(Color.DKGRAY);setPadding(4,6,4,10)}
        container.addView(loading)
        Thread{
            try{
                val zones=TadoClient.zones(activity)
                activity.runOnUiThread{
                    container.removeView(loading)
                    if(zones.isEmpty())return@runOnUiThread
                    zones.forEach{zone->container.addView(tadoCard(activity,container,zone),params())}
                }
            }catch(e:Exception){activity.runOnUiThread{container.removeView(loading);Toast.makeText(activity,"No pude actualizar Tado: ${e.message}",Toast.LENGTH_LONG).show()}}
        }.start()
    }

    private fun tadoCard(activity:Activity,container:LinearLayout,zone:TadoClient.Zone):LinearLayout{
        val card=baseCard(activity)
        card.addView(TextView(activity).apply{text="❄️  ${zone.name.uppercase()}";textSize=13f;setTextColor(Color.rgb(70,70,70))})
        card.addView(TextView(activity).apply{text=zone.temperature?.let{"%.1f °C".format(it)}?:"-- °C";textSize=32f;setTextColor(Color.rgb(20,20,20));setPadding(0,6,0,0)})
        card.addView(TextView(activity).apply{text="Objetivo ${zone.target?.let{"%.1f °C".format(it)}?:"--"} · ${if(zone.power.equals("ON",true))"Encendido" else "Apagado"}";textSize=15f;setTextColor(Color.DKGRAY);setPadding(0,2,0,12)})
        val row=LinearLayout(activity).apply{orientation=LinearLayout.HORIZONTAL}
        row.addView(actionButton(activity,"− 1°"){changeTado(activity,container,zone,"down")},LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
        row.addView(actionButton(activity,"+ 1°"){changeTado(activity,container,zone,"up")},LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
        row.addView(actionButton(activity,if(zone.power.equals("ON",true))"APAGAR" else "ENCENDER"){changeTado(activity,container,zone,if(zone.power.equals("ON",true))"off" else "on")},LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
        card.addView(row)
        card.addView(actionButton(activity,"VOLVER AL HORARIO AUTOMÁTICO"){changeTado(activity,container,zone,"schedule")})
        return card
    }

    private fun actionButton(activity:Activity,label:String,onClick:()->Unit)=Button(activity).apply{text=label;textSize=12f;setOnClickListener{onClick()}}

    private fun changeTado(activity:Activity,container:LinearLayout,zone:TadoClient.Zone,action:String){
        Thread{
            try{
                when(action){
                    "off"->TadoClient.setPower(activity,zone,false)
                    "on"->TadoClient.setPower(activity,zone,true,zone.target?:zone.temperature?:21.0)
                    "up"->TadoClient.setTemperature(activity,zone,((zone.target?:zone.temperature?:21.0)+1.0).coerceAtMost(30.0))
                    "down"->TadoClient.setTemperature(activity,zone,((zone.target?:zone.temperature?:21.0)-1.0).coerceAtLeast(5.0))
                    "schedule"->TadoClient.resumeSchedule(activity,zone)
                }
                Thread.sleep(450)
                activity.runOnUiThread{renderTadoStatus(activity,container,true);Toast.makeText(activity,"${zone.name}: actualizado",Toast.LENGTH_SHORT).show()}
            }catch(e:Exception){activity.runOnUiThread{Toast.makeText(activity,"No se pudo controlar ${zone.name}: ${e.message}",Toast.LENGTH_LONG).show()}}
        }.start()
    }

    private fun fetchAndRenderNews(activity:Activity,container:LinearLayout,query:String){
        container.removeAllViews();container.visibility=LinearLayout.VISIBLE
        container.addView(TextView(activity).apply{text="📰  Cargando últimas noticias…";textSize=15f;setTextColor(Color.DKGRAY);setPadding(4,8,4,12)})
        Thread{
            try{
                val topic=extractTopic(query)
                val endpoint=if(topic.isBlank())"$BACKEND/api/news" else "$BACKEND/api/news?q=${URLEncoder.encode(topic,"UTF-8")}" 
                val c=(URL(endpoint).openConnection() as HttpURLConnection).apply{connectTimeout=5000;readTimeout=12000}
                val body=(if(c.responseCode in 200..299)c.inputStream else c.errorStream)?.bufferedReader()?.use{it.readText()}.orEmpty()
                if(c.responseCode !in 200..299)throw IllegalStateException("HTTP ${c.responseCode}")
                val items=JSONObject(body).optJSONArray("items")?:JSONArray()
                activity.runOnUiThread{renderNews(activity,container,items)}
            }catch(_:Exception){activity.runOnUiThread{container.removeAllViews();container.addView(TextView(activity).apply{text="No se pudieron cargar las noticias ahora.";textSize=15f;setTextColor(Color.DKGRAY)})}}
        }.start()
    }

    private fun extractTopic(query:String):String{
        var q=query.lowercase().replace("últimas noticias","").replace("ultimas noticias","").replace("noticias","").replace("titulares","").replace("actualidad","")
        q=q.replace(Regex("(?i)\\b(de|sobre|acerca de|hoy|del día|del dia|últimas|ultimas)\\b")," ")
        return q.replace(Regex("[?!.]+")," ").replace(Regex("\\s+")," ").trim()
    }

    fun renderNews(activity:Activity,container:LinearLayout,items:JSONArray){
        container.removeAllViews();container.visibility=LinearLayout.VISIBLE
        container.addView(TextView(activity).apply{text="📰  ÚLTIMAS NOTICIAS";textSize=14f;setTextColor(Color.DKGRAY);setPadding(4,4,4,10)})
        for(i in 0 until minOf(items.length(),6)){
            val o=items.optJSONObject(i)?:continue
            val title=o.optString("title").trim();if(title.isBlank())continue
            val source=o.optString("source").trim();val article=o.optString("url").trim();val image=o.optString("image").trim();val video=o.optString("video").trim()
            val card=baseCard(activity)
            if(image.isNotBlank()){
                val iv=ImageView(activity).apply{layoutParams=LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,420);scaleType=ImageView.ScaleType.CENTER_CROP;contentDescription=title}
                card.addView(iv)
                Thread{runCatching{URL(image).openStream().use{BitmapFactory.decodeStream(it)}}.getOrNull()?.let{bmp->activity.runOnUiThread{iv.setImageBitmap(bmp)}}}.start()
            }
            card.addView(TextView(activity).apply{text=title;textSize=18f;setTextColor(Color.rgb(20,20,20));setPadding(0,if(image.isBlank())0 else 14,0,6)})
            if(source.isNotBlank())card.addView(TextView(activity).apply{text=source;textSize=13f;setTextColor(Color.GRAY)})
            if(video.isNotBlank())card.addView(TextView(activity).apply{text="▶ Ver vídeo";textSize=14f;setTextColor(Color.rgb(35,92,190));setPadding(0,10,0,0);setOnClickListener{runCatching{activity.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(video)))}}})
            else if(article.isNotBlank())card.addView(TextView(activity).apply{text="Abrir noticia";textSize=14f;setTextColor(Color.rgb(35,92,190));setPadding(0,10,0,0);setOnClickListener{runCatching{activity.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(article)))}}})
            container.addView(card,params())
        }
    }

    private fun baseCard(activity:Activity)=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL;setPadding(26,22,26,22);background=GradientDrawable().apply{setColor(Color.rgb(245,247,250));cornerRadius=28f}}
    private fun params()=LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{setMargins(0,8,0,14)}
}
