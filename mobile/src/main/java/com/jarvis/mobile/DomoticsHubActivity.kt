package com.jarvis.mobile

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

class DomoticsHubActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("jarvis_mobile", MODE_PRIVATE) }
    private lateinit var list: LinearLayout
    private val providers = listOf(
        "Sensibo" to "Aire acondicionado",
        "Tado" to "Climatización",
        "Samsung SmartThings" to "Casa y energía",
        "Bosch / Siemens Home Connect" to "Electrodomésticos",
        "Philips Hue" to "Iluminación",
        "Roborock" to "Robot aspirador"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28,28,28,28) }
        root.addView(TextView(this).apply { text = "Domótica y cuentas"; textSize = 26f })
        root.addView(TextView(this).apply { text = "Conecta cada plataforma mediante su MCP/API. Las conexiones guardadas se envían con las órdenes de Jarvis y pueden sincronizarse con TV."; textSize = 14f; setPadding(0,8,0,18) })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(list) }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,0,1f))
        root.addView(Button(this).apply { text = "Volver"; setOnClickListener { finish() } })
        setContentView(root); refresh()
    }

    private fun refresh() {
        list.removeAllViews()
        providers.forEach { (name, desc) ->
            val configured = findMcp(name) != null
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(22,18,22,18) }
            row.addView(TextView(this).apply { text = "$name  ${if (configured) "✓" else "○"}"; textSize = 18f })
            row.addView(TextView(this).apply { text = "$desc · ${if(configured) "conectado" else "sin configurar"}"; textSize = 13f })
            row.addView(Button(this).apply { text = if(configured) "Editar conexión" else "Conectar / dar acceso"; setOnClickListener { edit(name) } })
            list.addView(row)
        }
    }

    private fun mcps(): JSONArray = runCatching { JSONArray(prefs.getString("mcps","[]")) }.getOrElse { JSONArray() }
    private fun findMcp(name:String): JSONObject? { val a=mcps(); for(i in 0 until a.length()){val o=a.optJSONObject(i)?:continue;if(o.optString("server_label").equals(name,true))return o};return null }

    private fun edit(name:String) {
        val old=findMcp(name)
        val box=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(30,8,30,4) }
        val url=EditText(this).apply { hint="URL HTTPS del MCP/API"; setText(old?.optString("server_url").orEmpty()) }
        val token=EditText(this).apply { hint="Token / API key / Bearer"; setText(old?.optString("authorization").orEmpty().removePrefix("Bearer ")) }
        box.addView(TextView(this).apply { text="Jarvis no puede inventar credenciales. Introduce aquí el endpoint MCP/API y el token que te dé el proveedor o tu servidor puente."; textSize=13f })
        box.addView(url);box.addView(token)
        AlertDialog.Builder(this).setTitle(name).setView(box).setPositiveButton("GUARDAR"){_,_->
            val u=url.text.toString().trim(); if(!u.startsWith("https://")){Toast.makeText(this,"La URL debe empezar por https://",Toast.LENGTH_LONG).show();return@setPositiveButton}
            val a=mcps();val out=JSONArray();for(i in 0 until a.length()){val o=a.optJSONObject(i)?:continue;if(!o.optString("server_label").equals(name,true))out.put(o)}
            val obj=JSONObject().put("server_label",name).put("server_url",u).put("require_approval","always")
            token.text.toString().trim().takeIf{it.isNotBlank()}?.let{obj.put("authorization",if(it.startsWith("Bearer "))it else "Bearer $it")}
            out.put(obj);prefs.edit().putString("mcps",out.toString()).apply();refresh();Toast.makeText(this,"$name guardado",Toast.LENGTH_SHORT).show()
        }.setNeutralButton("BORRAR"){_,_->remove(name)}.setNegativeButton("Cancelar",null).show()
    }
    private fun remove(name:String){val a=mcps();val out=JSONArray();for(i in 0 until a.length()){val o=a.optJSONObject(i)?:continue;if(!o.optString("server_label").equals(name,true))out.put(o)};prefs.edit().putString("mcps",out.toString()).apply();refresh()}
}
