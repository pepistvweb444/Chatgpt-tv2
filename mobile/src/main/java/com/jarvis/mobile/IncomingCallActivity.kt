package com.jarvis.mobile

import android.app.Activity
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class IncomingCallActivity : Activity() {
    private lateinit var root: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private var lastFingerprint = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(36,42,36,36); setBackgroundColor(Color.rgb(8,11,16)) }
        setContentView(root); render()
    }
    override fun onResume() { super.onResume(); handler.post(refreshLoop) }
    override fun onPause() { handler.removeCallbacks(refreshLoop); super.onPause() }
    private val refreshLoop = object : Runnable { override fun run() { val call=CallStateStore.current(this@IncomingCallActivity); if(!call.optBoolean("active",false)&&call.optString("state")!="answered"){finish();return}; val f=call.toString().hashCode().toString(); if(f!=lastFingerprint)render(); handler.postDelayed(this,700L) } }

    private fun render() {
        val call=CallStateStore.current(this); lastFingerprint=call.toString().hashCode().toString(); root.removeAllViews()
        root.addView(TextView(this).apply { text="Llamada entrante · ${call.optString("app").ifBlank{if(call.optString("source")=="cellular")"Teléfono" else "App"}}"; textSize=17f; setTextColor(Color.rgb(160,180,255)); gravity=Gravity.CENTER })
        val photo=ImageView(this).apply { layoutParams=LinearLayout.LayoutParams(220,220).apply{topMargin=24;bottomMargin=22}; scaleType=ImageView.ScaleType.CENTER_CROP; setBackgroundColor(Color.rgb(28,34,48)) }
        call.optString("photoData").takeIf{it.isNotBlank()}?.let{encoded->runCatching{val bytes=Base64.decode(encoded,Base64.DEFAULT);photo.setImageBitmap(BitmapFactory.decodeByteArray(bytes,0,bytes.size))}}
        root.addView(photo)
        val known=call.optBoolean("knownContact",false); val publicLabel=call.optString("publicLabel")
        val display=if(known)call.optString("name") else publicLabel.ifBlank{call.optString("name")}.ifBlank{call.optString("number").ifBlank{"Llamada entrante"}}
        root.addView(TextView(this).apply { text=display; textSize=28f; setTextColor(Color.WHITE); gravity=Gravity.CENTER })
        val classification=call.optString("classification")
        val detail=when(classification){
            "checking"->"Buscando el número en Internet…"
            "spam_probable"->"⚠ Spam probable · ${call.optInt("spamScore",0)}%"
            "possible_spam"->"⚠ Posible spam · señal pública parcial"
            "contact"->buildString{append(if(call.optBoolean("priority",false))"★ Contacto prioritario" else "Contacto guardado");if(call.optBoolean("recent",false))append(" · contacto habitual/reciente")}
            else->if(publicLabel.isNotBlank())"Coincidencia pública en Internet · no confirmada" else "Número o usuario desconocido"
        }
        root.addView(TextView(this).apply { text=buildString{append(detail);call.optString("number").takeIf{it.isNotBlank()&&it!=display}?.let{append("\n").append(it)};call.optString("publicSource").takeIf{it.isNotBlank()}?.let{append("\nCoincidencia: ").append(it)};call.optString("spamSources").takeIf{it.isNotBlank()}?.let{append("\nFuentes spam: ").append(it)}}; textSize=16f; setTextColor(Color.rgb(205,215,230));gravity=Gravity.CENTER;setPadding(0,12,0,28) })
        val buttons=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER}
        fun add(label:String,action:String){buttons.addView(Button(this).apply{text=label;layoutParams=LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f).apply{marginEnd=8};setOnClickListener{val(ok,message)=CallActionManager.perform(this@IncomingCallActivity,action);Toast.makeText(this@IncomingCallActivity,message,Toast.LENGTH_SHORT).show();if(ok&&action!="ignore"){IncomingCallPresenter.dismiss(this@IncomingCallActivity);finish()}}})}
        add("Contestar","answer");add("Rechazar","reject");add("Dejar sonar","ignore");root.addView(buttons)
        root.addView(TextView(this).apply{text=if(call.optBoolean("video",false))"Videollamada: Jarvis nunca activará ni enviará tu cámara antes de que aceptes." else "Jarvis no contesta automáticamente: la decisión es tuya.";textSize=13f;setTextColor(Color.rgb(150,165,185));gravity=Gravity.CENTER;setPadding(0,24,0,0)})
    }
}
