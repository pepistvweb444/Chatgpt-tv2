package com.jarvis.mobile

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import java.net.URL

object JarvisWidgetRenderer {
    fun render(activity: Activity, container: LinearLayout, query: String, answer: String) {
        val q=query.lowercase()
        val kind=when{
            q.contains("noticia")||q.contains("titular")||q.contains("actualidad")->"📰  NOTICIAS"
            q.contains("alarma")||q.contains("despiert")||q.contains("avisa")->"⏰  ALARMA"
            q.contains("whatsapp")||q.contains("sms")||q.contains("mensaje")->"💬  MENSAJES"
            q.contains("tiempo")||q.contains("temperatura")||q.contains("lluv")->"🌤️  TIEMPO"
            q.contains("aire acondicionado")||q.contains("sensibo")||q.contains("tado")||q.contains("clima")->"❄️  CLIMATIZACIÓN"
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
