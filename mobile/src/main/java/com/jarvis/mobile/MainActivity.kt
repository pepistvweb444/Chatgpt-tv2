package com.jarvis.mobile

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class MainActivity : AppCompatActivity() {
    private lateinit var transcript: TextView
    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var scroll: ScrollView
    private lateinit var recentChats: TextView
    private lateinit var sideMenu: LinearLayout
    private val prefs by lazy { getSharedPreferences("jarvis_mobile", MODE_PRIVATE) }
    private var conversationId = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        transcript=findViewById(R.id.transcript); input=findViewById(R.id.input); status=findViewById(R.id.status); scroll=findViewById(R.id.scroll); recentChats=findViewById(R.id.recentChats); sideMenu=findViewById(R.id.sideMenu)
        conversationId=prefs.getString("currentConversation",null)?:newConversation()
        loadConversation()
        refreshDrawerRecents()

        findViewById<Button>(R.id.send).setOnClickListener{sendMessage()}
        findViewById<Button>(R.id.mic).setOnClickListener{ Toast.makeText(this,"Habla con Jarvis",Toast.LENGTH_SHORT).show() }
        findViewById<Button>(R.id.newChat).setOnClickListener{startNewChat()}
        findViewById<Button>(R.id.chats).setOnClickListener{toggleDrawer()}
        findViewById<Button>(R.id.menuNewChat).setOnClickListener{startNewChat();toggleDrawer(false)}
        findViewById<Button>(R.id.menuHistory).setOnClickListener{showChats()}
        findViewById<Button>(R.id.connections).setOnClickListener{showConnections()}
        findViewById<Button>(R.id.tools).setOnClickListener{showToolPicker()}
        findViewById<Button>(R.id.menuPlugins).setOnClickListener{showToolPicker()}
        findViewById<Button>(R.id.homeAutomation).setOnClickListener{sendAutomationPrompt("Abre mi panel de domótica y muéstrame el estado de casa")}
        findViewById<Button>(R.id.dayWidget).setOnClickListener{sendAutomationPrompt("Dame mi resumen del día con agenda, recordatorios y asuntos importantes")}
        findViewById<Button>(R.id.newsWidget).setOnClickListener{sendAutomationPrompt("Muéstrame las noticias más importantes de hoy con imágenes o vídeos cuando existan")}
        findViewById<Button>(R.id.weatherWidget).setOnClickListener{sendAutomationPrompt("¿Qué tiempo hace ahora y cuál es la previsión de hoy?")}
        findViewById<Button>(R.id.phoneControl).setOnClickListener{startActivity(Intent(this,DeviceHubActivity::class.java))}
        findViewById<Button>(R.id.voiceSettings).setOnClickListener{showVoiceSettings()}
        findViewById<Button>(R.id.camera).setOnClickListener{Toast.makeText(this,"Cámara e imágenes",Toast.LENGTH_SHORT).show();toggleDrawer(false)}
        findViewById<Button>(R.id.files).setOnClickListener{toggleDrawer(false);startActivity(Intent(Intent.ACTION_OPEN_DOCUMENT).apply{addCategory(Intent.CATEGORY_OPENABLE);type="*/*"})}
        findViewById<Button>(R.id.wakeWord).setOnClickListener{startService(Intent(this,WakeWordService::class.java));status.text="Hola Jarvis · escuchando";toggleDrawer(false)}
    }

    private fun toggleDrawer(show:Boolean?=null){sideMenu.visibility=if(show ?: (sideMenu.visibility!=View.VISIBLE)) View.VISIBLE else View.GONE}
    private fun startNewChat(){conversationId=newConversation();transcript.text="";recentChats.text="¿En qué puedo ayudarte hoy?";refreshDrawerRecents()}
    private fun newConversation():String{val id=UUID.randomUUID().toString();val index=runCatching{JSONArray(prefs.getString("chatIndex","[]"))}.getOrElse{JSONArray()};index.put(JSONObject().put("id",id).put("title","Nuevo chat").put("updated",System.currentTimeMillis()));prefs.edit().putString("currentConversation",id).putString("chat_$id","[]").putString("chatIndex",index.toString()).apply();return id}
    private fun history()=runCatching{JSONArray(prefs.getString("chat_$conversationId","[]"))}.getOrElse{JSONArray()}
    private fun loadConversation(){val a=history();val b=StringBuilder();for(i in 0 until a.length()){val o=a.optJSONObject(i)?:continue;b.append(if(o.optString("role")=="user")"\nTú\n" else "\nJarvis\n").append(o.optString("content")).append("\n")};transcript.text=b.toString()}
    private fun append(role:String,text:String){val a=history();a.put(JSONObject().put("role",role).put("content",text));prefs.edit().putString("chat_$conversationId",a.toString()).apply();transcript.append(if(role=="user")"\nTú\n$text\n" else "\nJarvis\n$text\n");updateConversationTitle(text,role);scroll.post{scroll.fullScroll(ScrollView.FOCUS_DOWN)}}
    private fun updateConversationTitle(text:String,role:String){if(role!="user")return;val arr=runCatching{JSONArray(prefs.getString("chatIndex","[]"))}.getOrElse{JSONArray()};for(i in 0 until arr.length()){val o=arr.optJSONObject(i)?:continue;if(o.optString("id")==conversationId){if(o.optString("title")=="Nuevo chat")o.put("title",text.take(38));o.put("updated",System.currentTimeMillis())}};prefs.edit().putString("chatIndex",arr.toString()).apply();refreshDrawerRecents()}
    private fun chatObjects():List<JSONObject>{val a=runCatching{JSONArray(prefs.getString("chatIndex","[]"))}.getOrElse{JSONArray()};val out=mutableListOf<JSONObject>();for(i in 0 until a.length())a.optJSONObject(i)?.let{out.add(it)};return out.sortedByDescending{it.optLong("updated")}}
    private fun refreshDrawerRecents(){val list=chatObjects().take(6);findViewById<TextView>(R.id.menuRecents).text="Recientes\n\n"+(if(list.isEmpty())"Todavía no hay conversaciones" else list.joinToString("\n\n"){it.optString("title").ifBlank{"Chat"}})}
    private fun showChats(){val list=chatObjects();if(list.isEmpty())return;AlertDialog.Builder(this).setTitle("Chats").setItems(list.map{it.optString("title")}.toTypedArray()){_,i->conversationId=list[i].optString("id");prefs.edit().putString("currentConversation",conversationId).apply();loadConversation();toggleDrawer(false)}.setNegativeButton("Cerrar",null).show()}

    private fun showToolPicker(){
        val tools=arrayOf("ChatGPT","Home Assistant / Domótica","Homey","Home Connect","Google Maps","Gmail","Calendario","Notion","WhatsApp","Otros MCP")
        val checked=BooleanArray(tools.size){true}
        AlertDialog.Builder(this).setTitle("Herramientas y complementos").setMultiChoiceItems(tools,checked){_,which,isChecked->checked[which]=isChecked}
            .setPositiveButton("Usar"){_,_->val selected=tools.filterIndexed{i,_->checked[i]};findViewById<TextView>(R.id.selectedTools).text=selected.joinToString("   •   ");findViewById<android.widget.HorizontalScrollView>(R.id.selectedToolsScroll).visibility=if(selected.isEmpty())View.GONE else View.VISIBLE;toggleDrawer(false)}
            .setNeutralButton("Gestionar MCP"){_,_->showConnections()}.setNegativeButton("Cerrar",null).show()
    }
    private fun showHomeAutomation(){showToolPicker()}
    private fun sendAutomationPrompt(text:String){input.setText(text);sendMessage()}
    private fun showConnections(){AlertDialog.Builder(this).setTitle("Complementos y MCP").setMessage("Selecciona los MCP desde el botón + del compositor. Jarvis enviará el contexto de la conversación junto con las herramientas activas.").setPositiveButton("Aceptar",null).show()}
    private fun showVoiceSettings(){val voices=arrayOf("coral","alloy","ash","ballad","echo","fable","nova","onyx","sage","shimmer","verse");AlertDialog.Builder(this).setTitle("Voz de Jarvis").setItems(voices){_,i->prefs.edit().putString("voice",voices[i]).apply();speak("Hola. Esta es mi voz de Jarvis.")}.show()}

    private fun sendMessage(){val m=input.text.toString().trim();if(m.isBlank())return;input.text.clear();append("user",m);status.text="Pensando…";Thread{try{val c=(URL("$BACKEND/api/chat").openConnection() as HttpURLConnection).apply{requestMethod="POST";doOutput=true;connectTimeout=12000;readTimeout=60000;setRequestProperty("Content-Type","application/json")};val body=JSONObject().put("message",m).put("conversationId",conversationId).put("client","jarvis-mobile").put("history",history()).toString();c.outputStream.use{it.write(body.toByteArray())};val raw=(if(c.responseCode in 200..299)c.inputStream else c.errorStream).bufferedReader().use{it.readText()};val reply=runCatching{JSONObject(raw).optString("reply")}.getOrDefault(raw).ifBlank{raw};runOnUiThread{append("assistant",reply);status.text="Jarvis listo";speak(reply)}}catch(e:Exception){runOnUiThread{status.text="Error: ${e.message}"}}}.start()}
    private fun speak(text:String){val clean=text.replace(Regex("https?://\\S+")," ").replace(Regex("[*_`#>|]+")," ").replace(Regex("\\s+")," ").trim();if(clean.isBlank())return;val i=Intent(this,MobileSpeechService::class.java).putExtra("text",clean).putExtra("voice",prefs.getString("voice","coral"));androidx.core.content.ContextCompat.startForegroundService(this,i)}
    companion object{private const val BACKEND="https://chatgpt-tv2.vercel.app"}
}
