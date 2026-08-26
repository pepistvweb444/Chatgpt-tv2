package com.jarvis.mobile

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.ContactsContract
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

class FavoriteContactsActivity : Activity() {
    private lateinit var summary: TextView
    private val prefs by lazy { getSharedPreferences("jarvis_mobile", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(32,32,32,32); setBackgroundColor(0xFF0B0B0B.toInt()) }
        root.addView(TextView(this).apply { text="Contactos prioritarios"; textSize=26f; setTextColor(0xFFFFFFFF.toInt()) })
        root.addView(TextView(this).apply { text="Jarvis dará prioridad a sus llamadas, mensajes, correos y presupuestos. También se usarán para el briefing diario."; textSize=14f; setTextColor(0xFFB8B8B8.toInt()); setPadding(0,8,0,18) })
        summary=TextView(this).apply { textSize=15f; setTextColor(0xFFE8E8E8.toInt()); setPadding(0,0,0,16) }; root.addView(summary)
        fun add(label:String, action:()->Unit)=root.addView(Button(this).apply { text=label; setOnClickListener { action() } })
        add("Elegir contactos prioritarios") { chooseContacts() }
        add("Añadir favoritos del teléfono") { importStarred() }
        add("Vaciar lista") { prefs.edit().remove("priority_contacts_json").apply(); refresh(); Toast.makeText(this,"Lista vaciada",Toast.LENGTH_SHORT).show() }
        add("Volver") { finish() }
        setContentView(root); refresh()
    }

    private fun ensureContacts():Boolean {
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.READ_CONTACTS)==PackageManager.PERMISSION_GRANTED) return true
        ActivityCompat.requestPermissions(this,arrayOf(Manifest.permission.READ_CONTACTS),91); return false
    }

    private data class C(val id:String,val name:String,val phone:String,val photo:String,val starred:Boolean)
    private fun contacts():List<C> {
        if(!ensureContacts()) return emptyList()
        val out=mutableListOf<C>(); val seen=mutableSetOf<String>()
        contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.CONTACT_ID,ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,ContactsContract.CommonDataKinds.Phone.NUMBER,ContactsContract.CommonDataKinds.Phone.PHOTO_URI,ContactsContract.CommonDataKinds.Phone.STARRED),
            null,null,ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME+" ASC")?.use { c ->
            val ii=c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID); val ni=c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME); val pi=c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER); val ph=c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.PHOTO_URI); val si=c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.STARRED)
            while(c.moveToNext()) {
                val id=if(ii>=0)c.getString(ii).orEmpty() else ""; val name=if(ni>=0)c.getString(ni).orEmpty() else ""; val phone=if(pi>=0)c.getString(pi).orEmpty() else ""; val photo=if(ph>=0)c.getString(ph).orEmpty() else ""; val starred=si>=0&&c.getInt(si)==1
                val key=id+"|"+phone.filter{it.isDigit()}.takeLast(9); if(name.isNotBlank()&&seen.add(key)) out+=C(id,name,phone,photo,starred)
            }
        }
        return out
    }

    private fun stored():JSONArray=runCatching{JSONArray(prefs.getString("priority_contacts_json","[]"))}.getOrElse{JSONArray()}
    private fun selectedKeys():MutableSet<String> { val a=stored(); return (0 until a.length()).mapNotNull{a.optJSONObject(it)?.optString("key")?.takeIf(String::isNotBlank)}.toMutableSet() }
    private fun key(c:C)=c.id+"|"+c.phone.filter{it.isDigit()}.takeLast(9)

    private fun chooseContacts(){
        val list=contacts(); if(list.isEmpty()) return
        val selected=selectedKeys(); val checked=BooleanArray(list.size){selected.contains(key(list[it]))}
        AlertDialog.Builder(this).setTitle("Contactos prioritarios").setMultiChoiceItems(list.map{"${it.name} · ${it.phone}"}.toTypedArray(),checked){_,i,v->checked[i]=v}
            .setPositiveButton("Guardar"){_,_->
                val a=JSONArray(); list.forEachIndexed{i,c->if(checked[i]) a.put(JSONObject().put("key",key(c)).put("id",c.id).put("name",c.name).put("phone",c.phone).put("photo",c.photo).put("source","manual"))}
                prefs.edit().putString("priority_contacts_json",a.toString()).apply(); refresh()
            }.setNegativeButton("Cancelar",null).show()
    }

    private fun importStarred(){
        val all=contacts(); if(all.isEmpty()) return
        val existing=stored(); val keys=selectedKeys(); val a=JSONArray(); for(i in 0 until existing.length()) existing.optJSONObject(i)?.let{a.put(it)}
        all.filter{it.starred && keys.add(key(it))}.forEach{c->a.put(JSONObject().put("key",key(c)).put("id",c.id).put("name",c.name).put("phone",c.phone).put("photo",c.photo).put("source","phone-starred"))}
        prefs.edit().putString("priority_contacts_json",a.toString()).apply(); refresh()
    }

    private fun refresh(){ val a=stored(); val lines=(0 until a.length()).mapNotNull{a.optJSONObject(it)}.map{"★ ${it.optString("name")} · ${it.optString("phone")}"}; summary.text=if(lines.isEmpty())"No hay contactos prioritarios todavía." else lines.joinToString("\n") }
    override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray){super.onRequestPermissionsResult(requestCode,permissions,grantResults);refresh()}
}
