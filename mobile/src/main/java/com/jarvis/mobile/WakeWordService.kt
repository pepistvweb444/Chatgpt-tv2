package com.jarvis.mobile

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.text.Normalizer
import java.util.Locale

class WakeWordService : Service() {
    @Volatile private var running=false
    @Volatile private var triggered=false
    private var recognizer:SpeechRecognizer?=null
    private val main=Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("jarvis_mobile",MODE_PRIVATE) }
    private var wakeLock:PowerManager.WakeLock?=null

    override fun onCreate(){super.onCreate();createChannel();startForegroundNotification();acquireWakeLock();running=true;main.post{startRecognizer()}}
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{running=true;acquireWakeLock();main.post{startRecognizer()};return START_STICKY}

    private fun acquireWakeLock(){if(wakeLock?.isHeld==true)return;wakeLock=(getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Jarvis:WakeWord").apply{setReferenceCounted(false);runCatching{acquire(12*60*60*1000L)}}}

    private fun startForegroundNotification(){
        val open=PendingIntent.getActivity(this,0,Intent(this,ChatActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        startForeground(71,NotificationCompat.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("Jarvis escuchando").setContentText("Hola Jarvis · Hola Ale · Hola Leo").setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open).build())
    }

    private fun conversationActive():Boolean=System.currentTimeMillis()<prefs.getLong("voice_session_until",0L)

    private fun startRecognizer(){
        if(!running||triggered)return
        // During a live conversation ChatActivity owns the microphone. The hotword recognizer
        // must stay out of the way or Android reports BUSY and the UI says "Escuchando" while
        // no audio is actually captured.
        if(conversationActive()){
            runCatching{recognizer?.cancel()};runCatching{recognizer?.destroy()};recognizer=null
            restartSoon(1200L)
            return
        }
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){stopSelf();return}
        if(!SpeechRecognizer.isRecognitionAvailable(this)){restartSoon(900);return}
        runCatching{recognizer?.cancel()};runCatching{recognizer?.destroy()}
        recognizer=SpeechRecognizer.createSpeechRecognizer(this).apply{setRecognitionListener(object:RecognitionListener{
            override fun onReadyForSpeech(params:Bundle?){}
            override fun onBeginningOfSpeech(){}
            override fun onRmsChanged(rmsdB:Float){}
            override fun onBufferReceived(buffer:ByteArray?){}
            override fun onEndOfSpeech(){}
            override fun onEvent(eventType:Int,params:Bundle?){}
            override fun onPartialResults(partialResults:Bundle?){partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty().firstOrNull{matchesWakeWord(normalize(it))}?.let{trigger(it)}}
            override fun onResults(results:Bundle?){val hit=results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty().firstOrNull{matchesWakeWord(normalize(it))};if(hit!=null)trigger(hit) else restartSoon(80)}
            override fun onError(error:Int){if(!running||triggered)return;val delay=when(error){SpeechRecognizer.ERROR_RECOGNIZER_BUSY->600L;SpeechRecognizer.ERROR_NETWORK,SpeechRecognizer.ERROR_NETWORK_TIMEOUT->800L;SpeechRecognizer.ERROR_NO_MATCH,SpeechRecognizer.ERROR_SPEECH_TIMEOUT->100L;else->220L};restartSoon(delay)}
        })}
        val i=Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply{putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);putExtra(RecognizerIntent.EXTRA_LANGUAGE,"es-ES");putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE,"es-ES");putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,true);putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,8);putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,650L);putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,350L);putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE,false)}
        runCatching{recognizer?.startListening(i)}.onFailure{restartSoon(420)}
    }

    private fun restartSoon(delay:Long){main.postDelayed({if(running&&!triggered)startRecognizer()},delay)}

    private fun trigger(raw:String){
        if(!running||triggered)return
        triggered=true
        // Reserve the microphone for the actual conversation immediately, before opening ChatActivity.
        prefs.edit().putLong("voice_session_until",System.currentTimeMillis()+120_000L).apply()
        runCatching{recognizer?.cancel()};runCatching{recognizer?.destroy()};recognizer=null
        val command=extractCommand(raw)
        val i=Intent(this,ChatActivity::class.java).apply{addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP);putExtra("wake_word_triggered",true);putExtra("continuous_voice",true);if(command.isNotBlank())putExtra("wake_command",command)}
        runCatching{startActivity(i)}
        main.postDelayed({triggered=false;if(running)startRecognizer()},1200L)
    }

    private fun extractCommand(raw:String):String{
        val normalized=normalize(raw)
        for(alias in wakeAliases())for(v in listOf("hola $alias","oye $alias","hey $alias","eh $alias")){val idx=normalized.indexOf(v);if(idx>=0){val rest=normalized.substring(idx+v.length).trim();if(rest.length>=2)return rest}}
        return ""
    }

    private fun wakeAliases():List<String>{
        val configured=prefs.getString("wake_names","jarvis,ale,leo,lola").orEmpty().split(',').map{normalize(it)}.filter{it.isNotBlank()}
        return (configured+listOf("jarvis","jarbis","jervis","yarvis","charvis","ale","hale","leo","lola")).map{normalize(it)}.distinct()
    }

    private fun matchesWakeWord(text:String):Boolean{
        if(text.isBlank())return false
        return wakeAliases().any{name->text==name||text.startsWith("hola $name")||text.contains(" hola $name")||text.startsWith("oye $name")||text.contains(" oye $name")||text.startsWith("hey $name")||text.contains(" hey $name")||text.startsWith("eh $name")||text.contains(" eh $name")}
    }

    private fun normalize(v:String)=Normalizer.normalize(v.lowercase(Locale.getDefault()),Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"),"").replace(Regex("[^a-z0-9 ]+")," ").replace(Regex("\\s+")," ").trim()

    override fun onTaskRemoved(rootIntent:Intent?){if(prefs.getBoolean("wake_word_enabled",false)){running=true;triggered=false;main.postDelayed({startRecognizer()},500)};super.onTaskRemoved(rootIntent)}
    override fun onDestroy(){running=false;main.removeCallbacksAndMessages(null);runCatching{recognizer?.cancel()};runCatching{recognizer?.destroy()};recognizer=null;runCatching{if(wakeLock?.isHeld==true)wakeLock?.release()};wakeLock=null;super.onDestroy()}
    override fun onBind(intent:Intent?):IBinder?=null
    private fun createChannel(){if(android.os.Build.VERSION.SDK_INT>=26)getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL,"Jarvis wake word",NotificationManager.IMPORTANCE_LOW))}
    companion object{private const val CHANNEL="jarvis_wake_word"}
}
