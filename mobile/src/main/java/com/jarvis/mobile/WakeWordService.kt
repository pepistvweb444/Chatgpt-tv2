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

    override fun onCreate(){super.onCreate();createChannel();startForegroundNotification();running=true;main.post{startRecognizer()}}
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{if(!running){running=true;main.post{startRecognizer()}};return START_STICKY}

    private fun startForegroundNotification(){
        val open=PendingIntent.getActivity(this,0,Intent(this,ChatActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        startForeground(71,NotificationCompat.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("Jarvis escuchando en segundo plano").setContentText("Hola Jarvis · Hola Ale · Hola Leo").setOngoing(true).setContentIntent(open).build())
    }

    private fun startRecognizer(){
        if(!running||triggered)return
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
            override fun onPartialResults(partialResults:Bundle?){partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty().firstOrNull{matchesWakeWord(normalize(it))}?.let{trigger()}}
            override fun onResults(results:Bundle?){if(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty().any{matchesWakeWord(normalize(it))})trigger() else restartSoon(80)}
            override fun onError(error:Int){if(running&&!triggered)restartSoon(if(error==SpeechRecognizer.ERROR_RECOGNIZER_BUSY)350 else 120)}
        })}
        val i=Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply{
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE,"es-ES")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,5)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,450L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,250L)
        }
        runCatching{recognizer?.startListening(i)}.onFailure{restartSoon(350)}
    }

    private fun restartSoon(delay:Long){main.postDelayed({if(running&&!triggered)startRecognizer()},delay)}
    private fun trigger(){if(!running||triggered)return;triggered=true;runCatching{recognizer?.cancel()};val i=Intent(this,ChatActivity::class.java).apply{addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP);putExtra("wake_word_triggered",true);putExtra("continuous_voice",true)};runCatching{startActivity(i)};main.postDelayed({triggered=false;if(running)startRecognizer()},2200)}
    private fun matchesWakeWord(text:String):Boolean{
        if(text.isBlank())return false
        val names=prefs.getString("wake_names","jarvis,ale,leo,lola").orEmpty().split(',').map{normalize(it)}.filter{it.isNotBlank()}.ifEmpty{listOf("jarvis","ale","leo","lola")}
        return names.any{name->text==name||text.contains("hola $name")||text.contains("oye $name")||text.contains("hey $name")||text.contains("eh $name")}
    }
    private fun normalize(v:String)=Normalizer.normalize(v.lowercase(Locale.getDefault()),Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"),"").replace(Regex("[^a-z0-9 ]+")," ").replace(Regex("\\s+")," ").trim()
    override fun onDestroy(){running=false;main.removeCallbacksAndMessages(null);runCatching{recognizer?.cancel()};runCatching{recognizer?.destroy()};recognizer=null;super.onDestroy()}
    override fun onBind(intent:Intent?):IBinder?=null
    private fun createChannel(){if(android.os.Build.VERSION.SDK_INT>=26)getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL,"Jarvis wake word",NotificationManager.IMPORTANCE_LOW))}
    companion object{private const val CHANNEL="jarvis_wake_word"}
}
