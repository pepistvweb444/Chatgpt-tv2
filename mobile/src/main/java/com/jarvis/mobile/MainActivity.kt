package com.jarvis.mobile

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
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
    private val prefs by lazy { getSharedPreferences("jarvis_mobile", MODE_PRIVATE) }
    private var conversationId = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        transcript=findViewById(R.id.transcript); input=findViewById(R.id.input); status=findViewById(R.id.status); scroll=findViewById(R.id.scroll); recentChats=findViewById(R.id.recentChats)
        conversationId=prefs.getString("currentConversation",null)?:newConversation()
        loadConversation()
        findViewById<Button>(R.id.send).setOnClickListener{sendMessage()}
        findViewById<Button>(R.id.mic).setOnClickListener{ Toast.makeText(this,"Di 'Hola Jarvis' o usa el micrófono de voz",Toast.LENGTH_SHORT).show() }
        findViewById<Button>(R.id.newChat).setOnClickListener{conversationId=newConversation(); transcript.text=""}
        findViewById<Button>(R.id.chats).setOnClickListener{showChats()}
        findViewById<Button>(R.id.connections).setOnClickListener{showConnections()}
        findViewById<Button>(R.id.tools).setOnClickListener{showConnections()}
        findViewById<Button>(R.id.homeAutomation).setOnClickListener{showHomeAutomation()}
        findViewById<Button>(R.id.phoneControl).setOnClickListener{startActivity(Intent(this,DeviceHubActivity::class.java))}
        findViewById<Button>(R.id.voiceSettings).setOnClickListener{showVoiceSettings()}
        findViewById<Button>(R.id.camera).setOnClickListener{Toast.makeText(this,"Cámara disponible desde Jarvis",Toast.LENGTH_SHORT).show()}
        findViewById<Button>(R.id.files).setOnClickListener{startActivity(Intent(Intent.ACTION_OPEN_DOCUMENT).apply{addCategory(Intent.CATEGORY_OPENABLE);type="*/*"})}
        findViewById<Button>(R.id.wakeWord).setOnClickListener{startService(Intent(this,WakeWordService::class.java));status.text="Hola Jarvis · escuchando"}
    }

    private fun newConversation():String{val id=UUID.randomUUID().toString();prefs.edit().putString("currentConversation",id).putString("chat_$id","[]").apply();return id}
    private fun history()=runCatching{JSONArray(prefs.getString("chat_$conversationId","[]"))}.getOrElse{JSONArray()}
    private fun loadConversation(){val a=history();val b=StringBuilder();for(i in 0 until a.length()){val o=a.optJSONObject(i)?:continue;b.append(if(o.optString("role")=="user")"\nTú\n" else "\nJarvis\n").append(o.optString("content")).append("\n")};transcript.text=b.toString()}
    private fun append(role:String,text:String){val a=history();a.put(JSONObject().put("role",role).put("content",text));prefs.edit().putString("chat_$conversationId",a.toString()).apply();transcript.append(if(role=="user")"\nTú\n$text\n" else "\nJarvis\n$text\n");scroll.post{scroll.fullScroll(ScrollView.FOCUS_DOWN)}}
    private fun showChats(){Toast.makeText(this,"Historial local de Jarvis activo",Toast.LENGTH_SHORT).show()}

    private fun showHomeAutomation(){
        val items=arrayOf("Homey · dispositivos y Flows","Home Connect · electrodomésticos","MCP domótica · servidores conectados","Control por voz · pedir a Jarvis")
        AlertDialog.Builder(this).setTitle("⌂ Domótica").setItems(items){_,which->
            when(which){
                0->sendAutomationPrompt("Muéstrame y controla mis dispositivos de Homey")
                1->sendAutomationPrompt("Muéstrame mis electrodomésticos Home Connect y su estado")
                2->showConnections()
                3->Toast.makeText(this,"Puedes decir: Jarvis, enciende el aire acondicionado",Toast.LENGTH_LONG).show()
            }
        }.setNegativeButton("Cerrar",null).show()
    }
    private fun sendAutomationPrompt(text:String){input.setText(text);sendMessage()}
    private fun showConnections(){AlertDialog.Builder(this).setTitle("Conexiones").setMessage("Jarvis puede usar las conexiones y MCP configurados en el backend para web y domótica.").setPositiveButton("Cerrar",null).show()}
    private fun showVoiceSettings(){val voices=arrayOf("coral","alloy","ash","ballad","echo","fable","nova","onyx","sage","shimmer","verse");AlertDialog.Builder(this).setTitle("Voz de Jarvis").setItems(voices){_,i->prefs.edit().putString("voice",voices[i]).apply();speak("Hola. Esta es mi voz de Jarvis.")}.show()}

    private fun sendMessage(){val m=input.text.toString().trim();if(m.isBlank())return;input.text.clear();append("user",m);status.text="Pensando…";Thread{try{val c=(URL("$BACKEND/api/chat").openConnection() as HttpURLConnection).apply{requestMethod="POST";doOutput=true;connectTimeout=12000;readTimeout=60000;setRequestProperty("Content-Type","application/json")};val body=JSONObject().put("message",m).put("conversationId",conversationId).put("client","jarvis-mobile").put("history",history()).toString();c.outputStream.use{it.write(body.toByteArray())};val raw=(if(c.responseCode in 200..299)c.inputStream else c.errorStream).bufferedReader().use{it.readText()};val reply=runCatching{JSONObject(raw).optString("reply")}.getOrDefault(raw).ifBlank{raw};runOnUiThread{append("assistant",reply);status.text="Jarvis listo";speak(reply)}}catch(e:Exception){runOnUiThread{status.text="Error: ${e.message}"}}}.start()}
    private fun speak(text:String){val clean=text.replace(Regex("https?://\\S+")," ").replace(Regex("[*_`#>|]+")," ").replace(Regex("\\s+")," ").trim();if(clean.isBlank())return;val i=Intent(this,MobileSpeechService::class.java).putExtra("text",clean).putExtra("voice",prefs.getString("voice","coral"));androidx.core.content.ContextCompat.startForegroundService(this,i)}
    companion object{private const val BACKEND="https://chatgpt-tv2.vercel.app"}
}
