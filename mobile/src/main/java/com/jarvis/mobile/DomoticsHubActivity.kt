package com.jarvis.mobile

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.util.UUID

class DomoticsHubActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("jarvis_mobile", MODE_PRIVATE) }
    private lateinit var list: LinearLayout
    private val backend = "https://chatgpt-tv2.vercel.app"

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
        root.addView(TextView(this).apply{text="No necesitas buscar API keys. Pulsa en cada marca y Jarvis abrirá el inicio de sesión oficial o el método de vinculación disponible.";textSize=14f;setPadding(0,8,0,18)})
        list=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        root.addView(ScrollView(this).apply{addView(list)},LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,0,1f))
        root.addView(Button(this).apply{text="Volver";setOnClickListener{finish()}})
        setContentView(root);handleCallback(intent);refresh()
    }

    override fun onNewIntent(intent: Intent?) { super.onNewIntent(intent); if(intent!=null){setIntent(intent);handleCallback(intent);refresh()} }
    override fun onResume(){super.onResume();refresh()}

    private fun refresh(){
        list.removeAllViews()
        providers.forEach { p ->
            val status=prefs.getString("domotics_${p.id}_status","not_connected") ?: "not_connected"
            val connected=status=="connected"
            val row=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(22,18,22,18)}
            row.addView(TextView(this).apply{text="${p.name}  ${if(connected)"✓" else "○"}";textSize=18f})
            row.addView(TextView(this).apply{text="${p.desc} · ${if(connected)"conectado" else if(status=="pending")"pendiente de completar acceso" else "sin conectar"}";textSize=13f})
            row.addView(Button(this).apply{text=if(connected)"Volver a autorizar" else p.loginLabel;setOnClickListener{startLogin(p)}})
            if(connected) row.addView(Button(this).apply{text="Desconectar";setOnClickListener{disconnect(p)}})
            list.addView(row)
        }
    }

    private fun startLogin(p:Provider){
        val installId=prefs.getString("domotics_install_id",null) ?: UUID.randomUUID().toString().also{prefs.edit().putString("domotics_install_id",it).apply()}
        prefs.edit().putString("domotics_${p.id}_status","pending").apply()
        val url="$backend/api/domotics/connect?provider=${Uri.encode(p.id)}&installId=${Uri.encode(installId)}&returnUri=${Uri.encode("jarvis://domotics/callback")}" 
        val ok=runCatching{startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)));true}.getOrDefault(false)
        if(!ok) Toast.makeText(this,"No se pudo abrir el inicio de sesión.",Toast.LENGTH_LONG).show()
    }

    private fun disconnect(p:Provider){
        prefs.edit().remove("domotics_${p.id}_status").remove("domotics_${p.id}_account").apply();refresh()
        Toast.makeText(this,"${p.name} desconectado de este dispositivo.",Toast.LENGTH_SHORT).show()
    }

    private fun handleCallback(i:Intent){
        val d=i.data ?: return
        if(d.scheme!="jarvis" || d.host!="domotics") return
        val provider=d.getQueryParameter("provider") ?: return
        val status=d.getQueryParameter("status") ?: "error"
        val account=d.getQueryParameter("account")
        prefs.edit().putString("domotics_${provider}_status",if(status=="ok")"connected" else "not_connected").apply()
        if(!account.isNullOrBlank()) prefs.edit().putString("domotics_${provider}_account",account).apply()
        Toast.makeText(this,if(status=="ok")"Cuenta vinculada correctamente" else "No se pudo completar la vinculación",Toast.LENGTH_LONG).show()
    }
}
