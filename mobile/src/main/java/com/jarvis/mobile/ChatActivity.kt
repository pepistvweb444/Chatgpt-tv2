package com.jarvis.mobile

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

class ChatActivity : AppCompatActivity() {
    private lateinit var transcript: TextView
    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var scroll: ScrollView
    private lateinit var widgetContainer: LinearLayout
    private val prefs by lazy { getSharedPreferences("jarvis_mobile", MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())
    private val actionRouter by lazy { LocalActionRouter(this) }
    private var conversationId = ""
    private var recorder: MediaRecorder? = null
    private var voiceFile: File? = null
    private var recognizer: SpeechRecognizer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)
        transcript=findViewById(R.id.transcript);input=findViewById(R.id.input);status=findViewById(R.id.status);scroll=findViewById(R.id.scroll);widgetContainer=findViewById(R.id.widgetContainer)
        conversationId=prefs.getString("currentConversation",null)?:newChat(false);loadConversation()
        findViewById<Button>(R.id.send).setOnClickListener{sendMessage()}
        findViewById<Button>(R.id.mic).setOnClickListener{startVoiceCapture()}
        findViewById<Button>(R.id.menu).setOnClickListener{showChats()}
        findViewById<Button>(R.id.newChat).setOnClickListener{newChat(true)}
        findViewById<Button>(R.id.plugins).setOnClickListener{showPlugins()}
        findViewById<Button>(R.id.device).setOnClickListener{startActivity(Intent(this,DeviceHubActivity::class.java))}
        findViewById<Button>(R.id.home).setOnClickListener{startActivity(Intent(this,DomoticsHubActivity::class.java))}
        findViewById<Button>(R.id.wake).setOnClickListener{toggleWakeWord()}
        findViewById<Button>(R.id.voice).setOnClickListener{showVoiceSettings()}
        findViewById<Button>(R.id.camera).setOnClickListener{runCatching{startActivity(Intent(MediaStore.ACTION_IMAGE_CAPTURE))}}
        findViewById<Button>(R.id.files).setOnClickListener{startActivity(Intent(Intent.ACTION_OPEN_DOCUMENT).apply{addCategory(Intent.CATEGORY_OPENABLE);type="*/*"})}
        findViewById<TextView>(R.id.phoneCard).setOnClickListener{startActivity(Intent(this,DeviceHubActivity::class.java))}
        findViewById<TextView>(R.id.visionCard).setOnClickListener{runCatching{startActivity(Intent(MediaStore.ACTION_IMAGE_CAPTURE))}}
        findViewById<TextView>(R.id.nowBrief).setOnClickListener{refreshNowBrief()}
        findViewById<View>(R.id.weatherCard).setOnClickListener{refreshWeather(null,false)}
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED && ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED){
            ActivityCompat.requestPermissions(this,arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION),93)
        }
        refreshWeather(null,false);handleWakeIntent(intent)
    }

    override fun onNewIntent(intent:Intent){super.onNewIntent(intent);setIntent(intent);handleWakeIntent(intent)}
    private fun handleWakeIntent(intent:Intent?){
        if(intent?.getBooleanExtra("wake_word_triggered",false)!=true)return
        keepVoiceSessionAlive()
        status.text="Hola Jarvis/Ale/Leo detectado · te escucho"
        val command=intent.getStringExtra("wake_command").orEmpty().trim()
        handler.postDelayed({
            if(command.isNotBlank()){input.setText(command);sendMessage()} else startVoiceCapture()
        },180)
    }

    private fun keepVoiceSessionAlive(){prefs.edit().putLong("voice_session_until",System.currentTimeMillis()+120_000L).apply()}

    private fun newChat(showToast:Boolean):String{val id=UUID.randomUUID().toString();conversationId=id;prefs.edit().putString("currentConversation",id).putString("chat_$id","[]").remove("response_$id").apply();val index=chatIndex();index.put(JSONObject().put("id",id).put("title","Nuevo chat").put("updated",System.currentTimeMillis()));prefs.edit().putString("chatIndex",index.toString()).apply();if(::transcript.isInitialized)transcript.text="";if(showToast)Toast.makeText(this,"Nuevo chat",Toast.LENGTH_SHORT).show();return id}
    private fun chatIndex():JSONArray=runCatching{JSONArray(prefs.getString("chatIndex","[]"))}.getOrElse{JSONArray()}
    private fun history():JSONArray=runCatching{JSONArray(prefs.getString("chat_$conversationId","[]"))}.getOrElse{JSONArray()}
    private fun localMcps():JSONArray=runCatching{JSONArray(prefs.getString("mcps","[]"))}.getOrElse{JSONArray()}

    private fun showChats(){val arr=chatIndex();val items=mutableListOf<JSONObject>();for(i in 0 until arr.length())arr.optJSONObject(i)?.let{items.add(it)};items.sortByDescending{it.optLong("updated")};if(items.isEmpty())return;AlertDialog.Builder(this).setTitle("Chats").setItems(items.map{it.optString("title","Chat")}.toTypedArray()){_,which->conversationId=items[which].optString("id");prefs.edit().putString("currentConversation",conversationId).apply();loadConversation()}.setNegativeButton("Cerrar",null).show()}
    private fun append(role:String,text:String){val arr=history();arr.put(JSONObject().put("role",role).put("content",text));prefs.edit().putString("chat_$conversationId",arr.toString()).apply();transcript.append(if(role=="user")"\nTú\n$text\n" else "\nJarvis\n$text\n");val idx=chatIndex();for(i in 0 until idx.length()){val o=idx.optJSONObject(i)?:continue;if(o.optString("id")==conversationId){if(role=="user"&&o.optString("title")=="Nuevo chat")o.put("title",text.take(40));o.put("updated",System.currentTimeMillis());break}};prefs.edit().putString("chatIndex",idx.toString()).apply();scroll.post{scroll.fullScroll(ScrollView.FOCUS_DOWN)}}
    private fun loadConversation(){val arr=history();val sb=StringBuilder();for(i in 0 until arr.length()){val o=arr.optJSONObject(i)?:continue;sb.append(if(o.optString("role")=="user")"\nTú\n" else "\nJarvis\n").append(o.optString("content")).append("\n")};transcript.text=sb.toString()}

    private fun isWeatherQuery(message:String):Boolean{
        val q=message.lowercase()
        return q.contains("tiempo")||q.contains("temperatura")||q.contains("va a llover")||q.contains("lloverá")||q.contains("previsión")||q.contains("prevision")||q.contains("meteorolog")
    }

    private fun extractWeatherPlace(message:String):String?{
        val clean=message.trim().replace(Regex("[?!.]+$"),"")
        val patterns=listOf(Regex("(?i)\\ben\\s+(.+)$"),Regex("(?i)\\bde\\s+(.+)$"))
        for(p in patterns){val m=p.find(clean);val value=m?.groupValues?.getOrNull(1)?.trim();if(!value.isNullOrBlank()&&value.length<80)return value}
        return null
    }

    private fun sendMessage(){
        val message=input.text.toString().trim();if(message.isBlank())return
        keepVoiceSessionAlive()
        input.text.clear();append("user",message)
        if(isWeatherQuery(message)){
            status.text="Actualizando widget del tiempo…"
            refreshWeather(extractWeatherPlace(message),true)
            return
        }
        val local=actionRouter.handle(message)
        if(local.handled){append("assistant",local.message);JarvisWidgetRenderer.render(this,widgetContainer,message,local.message);status.text="Acción del teléfono";speak(local.message);return}
        status.text="Pensando…";val h=history();val previous=prefs.getString("response_$conversationId",null)
        Thread{try{val result=postChat(message,h,previous);if(!result.second.isNullOrBlank())prefs.edit().putString("response_$conversationId",result.second).apply();runOnUiThread{append("assistant",result.first);JarvisWidgetRenderer.render(this,widgetContainer,message,result.first);status.text="● Listo · memoria activa";speak(result.first)}}catch(e:Exception){runOnUiThread{status.text="Error";Toast.makeText(this,e.message,Toast.LENGTH_LONG).show();handler.postDelayed({startVoiceCapture()},400)}}}.start()
    }

    private fun lastLocationPayload():JSONObject?{
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED && ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED)return null
        return runCatching{
            val lm=getSystemService(LOCATION_SERVICE) as LocationManager
            val providers=listOf(LocationManager.GPS_PROVIDER,LocationManager.NETWORK_PROVIDER,LocationManager.PASSIVE_PROVIDER)
            var best:Location?=null
            for(p in providers){val l=runCatching{lm.getLastKnownLocation(p)}.getOrNull()?:continue;if(best==null||l.time>(best?.time?:0L))best=l}
            best?.let{JSONObject().put("latitude",it.latitude).put("longitude",it.longitude).put("accuracyMeters",it.accuracy.toDouble()).put("timestamp",it.time).put("source","android-phone")}
        }.getOrNull()
    }

    private fun postChat(message:String,history:JSONArray,previous:String?):Pair<String,String?>{
        val c=(URL("$BACKEND/api/chat").openConnection() as HttpURLConnection).apply{requestMethod="POST";doOutput=true;connectTimeout=8000;readTimeout=45000;setRequestProperty("Content-Type","application/json")}
        val payload=JSONObject().put("message",message).put("conversationId",conversationId).put("client","jarvis-mobile").put("history",history).put("clientMcps",localMcps()).apply{
            if(!previous.isNullOrBlank())put("previousResponseId",previous)
            lastLocationPayload()?.let{put("location",it)}
        }
        c.outputStream.use{it.write(payload.toString().toByteArray())};val body=(if(c.responseCode in 200..299)c.inputStream else c.errorStream)?.bufferedReader()?.use{it.readText()}.orEmpty();if(c.responseCode !in 200..299)throw IllegalStateException("HTTP ${c.responseCode} $body");val json=JSONObject(body);return json.optString("reply") to json.optString("responseId").ifBlank{null}
    }

    private fun refreshWeather(place:String?=null,speakResult:Boolean=false){
        findViewById<View>(R.id.weatherCard).visibility=View.VISIBLE
        findViewById<TextView>(R.id.weatherForecast).visibility=View.VISIBLE
        findViewById<TextView>(R.id.weatherDetail).text="Actualizando…"
        Thread{
            try{
                val endpoint=if(place.isNullOrBlank()) "$BACKEND/api/weather" else "$BACKEND/api/weather?place=${URLEncoder.encode(place,"UTF-8")}" 
                val c=(URL(endpoint).openConnection() as HttpURLConnection).apply{connectTimeout=6000;readTimeout=8000}
                val body=(if(c.responseCode in 200..299)c.inputStream else c.errorStream)?.bufferedReader()?.use{it.readText()}.orEmpty()
                if(c.responseCode !in 200..299)throw IllegalStateException(body)
                val json=JSONObject(body);val code=json.optInt("code",3);val icon=weatherIcon(code);val temp=json.optDouble("temperature");val feels=json.optDouble("feelsLike");val wind=json.optDouble("wind");val resolvedPlace=json.optString("place","Ubicación actual");val days=json.optJSONArray("days")?:JSONArray();val forecast=mutableListOf<String>();for(i in 0 until minOf(days.length(),4)){val d=days.optJSONObject(i)?:continue;forecast+="${d.optString("date").takeLast(5)} ${weatherIcon(d.optInt("code"))} ${d.optDouble("min").toInt()}°/${d.optDouble("max").toInt()}° · lluvia ${d.optInt("rain")}%"}
                val spoken="En $resolvedPlace hay ${temp.toInt()} grados. Sensación de ${feels.toInt()} grados y viento de ${wind.toInt()} kilómetros por hora."
                runOnUiThread{findViewById<TextView>(R.id.weatherIcon).text=icon;findViewById<TextView>(R.id.weatherPlace).text=resolvedPlace;findViewById<TextView>(R.id.weatherTemp).text="${temp.toInt()}°";findViewById<TextView>(R.id.weatherDetail).text="Sensación ${feels.toInt()}° · viento ${wind.toInt()} km/h";findViewById<TextView>(R.id.weatherForecast).text=forecast.joinToString("   ");status.text="● Tiempo actualizado";if(speakResult)speak(spoken);scroll.post{scroll.smoothScrollTo(0,0)}}
            }catch(_:Exception){runOnUiThread{findViewById<TextView>(R.id.weatherDetail).text="Tiempo no disponible · pulsa para reintentar";status.text="No se pudo actualizar el tiempo"}}
        }.start()
    }
    private fun weatherIcon(code:Int):String=when(code){0->"☀️";1,2->"🌤️";3->"☁️";45,48->"🌫️";in 51..67->"🌧️";in 71..77->"🌨️";in 80..82->"🌦️";in 95..99->"⛈️";else->"🌤️"}
    private fun refreshNowBrief(){val card=findViewById<TextView>(R.id.nowBrief);card.text="NOW BRIEF\nActualizando…";val notif=runCatching{JSONArray(prefs.getString("notification_feed","[]"))}.getOrElse{JSONArray()};val recent=mutableListOf<String>();for(i in (notif.length()-1) downTo 0){val o=notif.optJSONObject(i)?:continue;val t=listOf(o.optString("title"),o.optString("text")).filter{it.isNotBlank()}.joinToString(": ");if(t.isNotBlank())recent.add(t.take(120));if(recent.size>=4)break};val prompt="Crea un Now Brief breve para hoy sin repetir el tiempo, que ya se muestra en un widget. Resume estas notificaciones recientes: ${recent.joinToString(" | ")}. Máximo 6 líneas, en español.";Thread{try{val result=postChat(prompt,JSONArray(),null).first;runOnUiThread{card.text="NOW BRIEF\n$result"}}catch(_:Exception){runOnUiThread{card.text="NOW BRIEF\nNo se pudo actualizar"}}}.start()}
    private fun showPlugins(){AlertDialog.Builder(this).setTitle("Complementos").setMessage("MCP remotos, cuentas y domótica de Jarvis.").setPositiveButton("DOMÓTICA"){_,_->startActivity(Intent(this,DomoticsHubActivity::class.java))}.setNeutralButton("MCP"){_,_->startActivity(Intent(this,MainActivity::class.java))}.setNegativeButton("Cerrar",null).show()}
    private fun showVoiceSettings(){val voices=arrayOf("mi_voz","openvoice","coral","alloy","ash","ballad","echo","fable","nova","onyx","sage","shimmer","verse");val cur=prefs.getString("voice","mi_voz")?:"mi_voz";AlertDialog.Builder(this).setTitle("Voz de Jarvis").setSingleChoiceItems(voices,voices.indexOf(cur).coerceAtLeast(0)){d,w->prefs.edit().putString("voice",voices[w]).apply();d.dismiss();speak("Hola. Esta es mi voz.")}.show()}

    private fun toggleWakeWord(){val enabled=prefs.getBoolean("wake_word_enabled",false);if(enabled){stopService(Intent(this,WakeWordService::class.java));prefs.edit().putBoolean("wake_word_enabled",false).apply();status.text="Activación por voz desactivada"}else{if(ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){ActivityCompat.requestPermissions(this,arrayOf(Manifest.permission.RECORD_AUDIO),91);return};ContextCompat.startForegroundService(this,Intent(this,WakeWordService::class.java));prefs.edit().putBoolean("wake_word_enabled",true).apply();status.text="Hola Jarvis / Ale / Leo · escuchando en segundo plano"}}
    private fun setupRecognizer(){if(!SpeechRecognizer.isRecognitionAvailable(this))return;recognizer?.destroy();recognizer=SpeechRecognizer.createSpeechRecognizer(this).apply{setRecognitionListener(object:RecognitionListener{override fun onReadyForSpeech(params:Bundle?){status.text="Escuchando…"};override fun onBeginningOfSpeech(){status.text="Te escucho…"};override fun onRmsChanged(rmsdB:Float){};override fun onBufferReceived(buffer:ByteArray?){};override fun onEndOfSpeech(){status.text="Procesando…"};override fun onError(error:Int){if(error==SpeechRecognizer.ERROR_RECOGNIZER_BUSY){handler.postDelayed({setupRecognizer();startVoiceCapture()},500)}else startRecorderFallback()};override fun onResults(results:Bundle?){val text=results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty();if(text.isNotBlank()){input.setText(text);sendMessage()}else handler.postDelayed({startVoiceCapture()},180)};override fun onPartialResults(partialResults:Bundle?){val p=partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull();if(!p.isNullOrBlank()){input.setText(p);status.text="Escuchando… $p"}};override fun onEvent(eventType:Int,params:Bundle?){}})}}
    private fun startVoiceCapture(){
        keepVoiceSessionAlive()
        if(prefs.getBoolean("wake_word_enabled",false))runCatching{startService(Intent(this,WakeWordService::class.java).setAction(WakeWordService.ACTION_PAUSE_FOR_CONVERSATION))}
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){ActivityCompat.requestPermissions(this,arrayOf(Manifest.permission.RECORD_AUDIO),90);return}
        if(recognizer==null)setupRecognizer();val r=recognizer;if(r!=null){val i=Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply{putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);putExtra(RecognizerIntent.EXTRA_LANGUAGE,"es-ES");putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,true);putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,5);putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,800L);putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,500L);putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE,false)};try{r.cancel();handler.postDelayed({runCatching{r.startListening(i)}.onFailure{startRecorderFallback()}},120L);return}catch(_:Exception){}};startRecorderFallback()
    }
    private fun startRecorderFallback(){if(recorder!=null)return;val file=File(cacheDir,"voice-${System.currentTimeMillis()}.m4a");voiceFile=file;recorder=(if(Build.VERSION.SDK_INT>=31)MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()).apply{setAudioSource(MediaRecorder.AudioSource.MIC);setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);setAudioEncoder(MediaRecorder.AudioEncoder.AAC);setAudioSamplingRate(16000);setAudioEncodingBitRate(64000);setOutputFile(file.absolutePath);prepare();start()};status.text="Escuchando…";handler.postDelayed({stopVoiceCaptureFallback()},4000)}
    private fun stopVoiceCaptureFallback(){val r=recorder?:return;runCatching{r.stop()};runCatching{r.release()};recorder=null;val file=voiceFile?:return;voiceFile=null;Thread{try{val c=(URL("$BACKEND/api/transcribe").openConnection() as HttpURLConnection).apply{requestMethod="POST";doOutput=true;connectTimeout=8000;readTimeout=20000;setRequestProperty("Content-Type","audio/mp4")};c.outputStream.use{out->file.inputStream().use{it.copyTo(out)}};val body=c.inputStream.bufferedReader().use{it.readText()};val text=JSONObject(body).optString("text");runOnUiThread{if(text.isNotBlank()){input.setText(text);sendMessage()}else startVoiceCapture()}}catch(_:Exception){runOnUiThread{status.text="Error de voz";handler.postDelayed({startVoiceCapture()},500)}}finally{file.delete()}}.start()}

    private fun speak(text:String){keepVoiceSessionAlive();FastVoice.speak(this,prefs,text){status.text="● Hablando · voz clonada"}}
    override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray){super.onRequestPermissionsResult(requestCode,permissions,grantResults);if(requestCode==90&&grantResults.firstOrNull()==PackageManager.PERMISSION_GRANTED)startVoiceCapture();if(requestCode==91&&grantResults.firstOrNull()==PackageManager.PERMISSION_GRANTED)toggleWakeWord()}
    override fun onDestroy(){handler.removeCallbacksAndMessages(null);recognizer?.destroy();recorder?.let{runCatching{it.release()}};FastVoice.stop();super.onDestroy()}
    companion object{private const val BACKEND="https://chatgpt-tv2.vercel.app"}
}
