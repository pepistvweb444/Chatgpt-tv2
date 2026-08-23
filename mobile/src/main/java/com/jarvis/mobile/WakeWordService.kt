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
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
        running=true;acquireWakeLock()
        if(intent?.action==ACTION_PAUSE_FOR_CONVERSATION){
            prefs.edit().putLong("voice_session_until",System.currentTimeMillis()+120_000L).apply()
            runCatching{recognizer?.cancel()};runCatching{recognizer?.destroy()};recognizer=null
            // Do not fight ChatActivity for the microphone. startRecognizer() itself waits
            // while voice_session_until is active and resumes afterwards.
            main.postDelayed({startRecognizer()},1600L)
            return START_STICKY
        }
        main.post{startRecognizer()}
        return START_STICKY
    }

    private fun acquireWakeLock(){
        if(wakeLock?.isHeld==true)return
        wakeLock=(getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Jarvis:WakeWord")
            .apply{setReferenceCounted(false);runCatching{acquire()}}
    }

    private fun startForegroundNotification(){
        val open=PendingIntent.getActivity(this,0,Intent(this,ChatActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        startForeground(71,NotificationCompat.Builder(this,CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Jarvis escuchando")
            .setContentText("Di: Hola Jarvis, Hola Ale o Hola Leo")
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open).build())
    }

    private fun conversationActive():Boolean=System.currentTimeMillis()<prefs.getLong("voice_session_until",0L)

    private fun startRecognizer(){
        if(!running||triggered)return
        if(conversationActive()){
            runCatching{recognizer?.cancel()};runCatching{recognizer?.destroy()};recognizer=null
            restartSoon(1500L)
            return
        }
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){stopSelf();return}
        if(!SpeechRecognizer.isRecognitionAvailable(this)){restartSoon(1200);return}
        runCatching{recognizer?.cancel()};runCatching{recognizer?.destroy()}
        recognizer=SpeechRecognizer.createSpeechRecognizer(this).apply{setRecognitionListener(object:RecognitionListener{
            override fun onReadyForSpeech(params:Bundle?){}
            override fun onBeginningOfSpeech(){}
            override fun onRmsChanged(rmsdB:Float){}
            override fun onBufferReceived(buffer:ByteArray?){}
            override fun onEndOfSpeech(){}
            override fun onEvent(eventType:Int,params:Bundle?){}
            override fun onPartialResults(partialResults:Bundle?){
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    .orEmpty().firstOrNull{matchesWakeWord(normalize(it))}?.let{trigger(it)}
            }
            override fun onResults(results:Bundle?){
                val hit=results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    .orEmpty().firstOrNull{matchesWakeWord(normalize(it))}
                if(hit!=null)trigger(hit) else restartSoon(120)
            }
            override fun onError(error:Int){
                if(!running||triggered)return
                val delay=when(error){
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY->850L
                    SpeechRecognizer.ERROR_NETWORK,SpeechRecognizer.ERROR_NETWORK_TIMEOUT->1200L
                    SpeechRecognizer.ERROR_NO_MATCH,SpeechRecognizer.ERROR_SPEECH_TIMEOUT->180L
                    else->350L
                }
                restartSoon(delay)
            }
        })}
        val i=Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply{
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE,"es-ES")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE,"es-ES")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,8)
            // Give the user enough time to say "Hola Jarvis, ..." followed by a full command.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,900L)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE,false)
        }
        runCatching{recognizer?.startListening(i)}.onFailure{restartSoon(600)}
    }

    private fun restartSoon(delay:Long){main.postDelayed({if(running&&!triggered)startRecognizer()},delay)}

    private fun trigger(raw:String){
        if(!running||triggered)return
        triggered=true
        prefs.edit().putLong("voice_session_until",System.currentTimeMillis()+120_000L).apply()
        runCatching{recognizer?.cancel()};runCatching{recognizer?.destroy()};recognizer=null
        val command=extractCommand(raw)
        val i=Intent(this,ChatActivity::class.java).apply{
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("wake_word_triggered",true)
            putExtra("continuous_voice",true)
            if(command.isNotBlank())putExtra("wake_command",command)
        }
        runCatching{startActivity(i)}
        main.postDelayed({triggered=false;if(running)startRecognizer()},1800L)
    }

    private fun extractCommand(raw:String):String{
        val normalized=normalize(raw)
        for(alias in wakeAliases())for(v in listOf("hola $alias","oye $alias","hey $alias","eh $alias")){
            val idx=normalized.indexOf(v)
            if(idx>=0){
                val rest=normalized.substring(idx+v.length).trim()
                if(rest.length>=2)return rest
            }
        }
        return ""
    }

    private fun wakeAliases():List<String>{
        val configured=prefs.getString("wake_names","jarvis,ale,leo,lola").orEmpty().split(',').map{normalize(it)}.filter{it.isNotBlank()}
        return (configured+listOf("jarvis","jarbis","jervis","yarvis","charvis","ale","hale","leo","lola")).map{normalize(it)}.distinct()
    }

    private fun matchesWakeWord(text:String):Boolean{
        if(text.isBlank())return false
        return wakeAliases().any{name->
            text==name||text.startsWith("hola $name")||text.contains(" hola $name")||
            text.startsWith("oye $name")||text.contains(" oye $name")||
            text.startsWith("hey $name")||text.contains(" hey $name")||
            text.startsWith("eh $name")||text.contains(" eh $name")
        }
    }

    private fun normalize(v:String)=Normalizer.normalize(v.lowercase(Locale.getDefault()),Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"),"").replace(Regex("[^a-z0-9 ]+")," ").replace(Regex("\\s+")," ").trim()

    override fun onTaskRemoved(rootIntent:Intent?){
        // Keep the foreground listener alive when Jarvis is swiped away from Recents.
        if(prefs.getBoolean("wake_word_enabled",false)){
            running=true;triggered=false
            main.postDelayed({
                runCatching{ContextCompat.startForegroundService(this,Intent(this,WakeWordService::class.java))}
                startRecognizer()
            },700L)
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy(){
        running=false;main.removeCallbacksAndMessages(null)
        runCatching{recognizer?.cancel()};runCatching{recognizer?.destroy()};recognizer=null
        runCatching{if(wakeLock?.isHeld==true)wakeLock?.release()};wakeLock=null
        super.onDestroy()
    }
    override fun onBind(intent:Intent?):IBinder?=null
    private fun createChannel(){if(android.os.Build.VERSION.SDK_INT>=26)getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL,"Jarvis wake word",NotificationManager.IMPORTANCE_LOW))}
    companion object{
        const val ACTION_PAUSE_FOR_CONVERSATION="com.jarvis.mobile.PAUSE_FOR_CONVERSATION"
        private const val CHANNEL="jarvis_wake_word"
    }
}
