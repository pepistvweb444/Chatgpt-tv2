package com.jarvis.mobile

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.media.MediaPlayer
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Button
import android.widget.EditText
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

object FastVoice {
    private const val BACKEND="https://chatgpt-tv2.vercel.app"
    private val pool=Executors.newFixedThreadPool(5)
    private val generation=AtomicInteger(0)
    @Volatile private var player:MediaPlayer?=null
    @Volatile private var bargeRecognizer:SpeechRecognizer?=null
    @Volatile private var interrupted=false

    fun stop(){generation.incrementAndGet();interrupted=true;runCatching{player?.stop()};runCatching{player?.release()};player=null;stopBargeRecognizer()}

    fun speak(activity:Activity,prefs:SharedPreferences,raw:String,onStart:(()->Unit)?=null){
        stop();interrupted=false
        val gen=generation.incrementAndGet();val text=speechText(raw)
        if(text.isBlank()){activity.runOnUiThread{onStart?.invoke();relisten(activity)};return}
        prefs.edit().putString("voice","mi_voz").apply()
        val chunks=chunk(text);val files=ConcurrentHashMap<Int,File>();val started=AtomicBoolean(false);val failed=AtomicBoolean(false)
        fun fail(){if(generation.get()!=gen||!failed.compareAndSet(false,true))return;activity.runOnUiThread{onStart?.invoke();relisten(activity)}}
        chunks.forEachIndexed{index,part->pool.execute{
            if(failed.get()||generation.get()!=gen)return@execute
            try{
                files[index]=downloadClone(activity,part,index)
                if(index==0&&started.compareAndSet(false,true)&&generation.get()==gen)activity.runOnUiThread{onStart?.invoke();startBargeIn(activity,text,gen);play(activity,files,chunks.size,0,gen)}
            }catch(_:Exception){if(index==0)fail()}
        }}
    }

    private fun speechText(raw:String):String{
        var s=raw
        s=s.replace(Regex("!?\\[([^]]+)]\\((?:https?://|www\\.)[^)]+\\)",RegexOption.IGNORE_CASE),"$1")
        s=s.replace(Regex("(?i)(?:https?://|www\\.)\\S+"),"")
        s=s.replace(Regex("(?i)\\b[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}\\b"),"")
        s=s.replace(Regex("[^]*"),"")
        s=s.replace(Regex("(?im)^\\s*(fuente|fuentes|source|sources|url|enlace|link)\\s*:\\s*.*$"),"")
        s=s.replace(Regex("\\([^)]{0,220}\\)")," ").replace(Regex("\\[[^]]{0,220}]")," ")
        s=s.replace(Regex("(?m)^\\s*(?:[-*•]+|\\d+[.)])\\s*"),"")
        s=s.replace(Regex("[*_`#>|~^=+\\\\/{}<>]+")," ")
        s=s.replace("&"," y ").replace("%"," por ciento ").replace("€"," euros ")
        s=s.replace(Regex("[^\\p{L}\\p{N} áéíóúüñÁÉÍÓÚÜÑ,.;:!?¿¡'\"-]+")," ")
        s=s.replace(Regex("\\s*[:;]+\\s*"),", ").replace(Regex("\\s*[-–—]{2,}\\s*"),", ")
        s=s.replace(Regex("[!?]{2,}"),".").replace(Regex("\\.{2,}"),".").replace(Regex("[\\r\\n]+"),". ")
        s=s.replace(Regex("\\s+")," ").replace(Regex("\\s+([,.;!?])"),"$1").replace(Regex("([,.;!?])(?=[\\p{L}\\p{N}])"),"$1 ")
        return s.trim(' ','.',',')
    }

    private fun chunk(text:String):List<String>{
        val out=mutableListOf<String>();var rest=text;var first=true
        while(rest.isNotBlank()){
            val max=if(first)52 else 105
            if(rest.length<=max){out+=rest;break}
            val w=rest.take(max+1);var cut=listOf(w.lastIndexOf(". "),w.lastIndexOf("? "),w.lastIndexOf("! "),w.lastIndexOf(", "),w.lastIndexOf(' ')).maxOrNull()?:max
            if(cut<if(first)20 else 36)cut=max
            val take=(cut+if(w.getOrNull(cut) in listOf('.','?','!',','))1 else 0).coerceAtMost(rest.length)
            out+=rest.take(take).trim();rest=rest.drop(take).trim();first=false
        }
        return out.filter{it.isNotBlank()}
    }

    private fun downloadClone(activity:Activity,text:String,index:Int):File{
        val c=(URL("$BACKEND/api/speech").openConnection() as HttpURLConnection).apply{requestMethod="POST";doOutput=true;connectTimeout=1800;readTimeout=8500;setRequestProperty("Content-Type","application/json; charset=utf-8");setRequestProperty("Accept","audio/*");setRequestProperty("Connection","keep-alive")}
        c.outputStream.use{it.write(JSONObject().put("text",text).put("voice","mi_voz").put("provider","race-clone").put("speed",1.12).toString().toByteArray())}
        if(c.responseCode !in 200..299)throw IllegalStateException("TTS ${c.responseCode}")
        return File(activity.cacheDir,"jarvis-clone-${System.currentTimeMillis()}-$index.mp3").also{f->c.inputStream.use{input->f.outputStream().use{input.copyTo(it)}}}
    }

    private fun play(activity:Activity,files:ConcurrentHashMap<Int,File>,total:Int,index:Int,gen:Int){
        if(generation.get()!=gen||interrupted)return
        if(index>=total){stopBargeRecognizer();activity.window.decorView.postDelayed({if(generation.get()==gen&&!interrupted)relisten(activity)},90L);return}
        val file=files[index];if(file==null){activity.window.decorView.postDelayed({play(activity,files,total,index,gen)},25L);return}
        runCatching{player?.release()}
        player=MediaPlayer().apply{setDataSource(file.absolutePath);setOnPreparedListener{if(generation.get()==gen&&!interrupted)it.start()};setOnCompletionListener{p->p.release();file.delete();if(player===p)player=null;play(activity,files,total,index+1,gen)};setOnErrorListener{p,_,_->p.release();file.delete();if(player===p)player=null;play(activity,files,total,index+1,gen);true};prepareAsync()}
    }

    private fun startBargeIn(activity:Activity,assistantText:String,gen:Int){
        if(!SpeechRecognizer.isRecognitionAvailable(activity))return
        stopBargeRecognizer();val assistantTokens=normalize(assistantText).split(' ').filter{it.length>2}.toSet()
        activity.window.decorView.postDelayed({
            if(generation.get()!=gen||interrupted)return@postDelayed
            val r=SpeechRecognizer.createSpeechRecognizer(activity);bargeRecognizer=r
            r.setRecognitionListener(object:RecognitionListener{
                override fun onReadyForSpeech(params:Bundle?){}
                override fun onBeginningOfSpeech(){}
                override fun onRmsChanged(rmsdB:Float){}
                override fun onBufferReceived(buffer:ByteArray?){}
                override fun onEndOfSpeech(){}
                override fun onError(error:Int){if(generation.get()==gen&&!interrupted)restartBarge(activity,assistantText,gen,160L)}
                override fun onPartialResults(partialResults:Bundle?){val heard=partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty();maybeInterrupt(activity,heard,assistantTokens,gen,false)}
                override fun onResults(results:Bundle?){val heard=results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty();if(!maybeInterrupt(activity,heard,assistantTokens,gen,true))restartBarge(activity,assistantText,gen,70L)}
                override fun onEvent(eventType:Int,params:Bundle?){}
            })
            val i=Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply{putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);putExtra(RecognizerIntent.EXTRA_LANGUAGE,"es-ES");putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,true);putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,5);putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,300L);putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,180L);putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE,false)}
            runCatching{r.startListening(i)}
        },80L)
    }

    private fun restartBarge(activity:Activity,assistantText:String,gen:Int,delay:Long){activity.window.decorView.postDelayed({if(generation.get()==gen&&!interrupted)startBargeIn(activity,assistantText,gen)},delay)}

    private fun maybeInterrupt(activity:Activity,heardRaw:String,assistantTokens:Set<String>,gen:Int,final:Boolean):Boolean{
        if(generation.get()!=gen||interrupted)return false
        val heard=heardRaw.trim();val h=normalize(heard);if(h.length<3)return false
        val tokens=h.split(' ').filter{it.length>1};if(tokens.isEmpty())return false
        val overlap=tokens.count{it in assistantTokens}.toDouble()/tokens.size
        val novel=tokens.count{it !in assistantTokens}
        val looksLikeEcho=overlap>=0.86&&tokens.size>=3
        val realSpeech=!looksLikeEcho && if(final){tokens.size>=1&&(novel>=1||tokens.size>=3)}else{h.length>=5&&(novel>=1||tokens.size>=2&&overlap<0.7)}
        if(!realSpeech)return false
        interrupted=true;runCatching{player?.stop()};runCatching{player?.release()};player=null;stopBargeRecognizer()
        activity.runOnUiThread{val input=activity.findViewById<EditText>(R.id.input);input.setText(heard);input.setSelection(heard.length);activity.findViewById<Button>(R.id.send).performClick()}
        return true
    }

    private fun relisten(activity:Activity){if(activity.isFinishing||activity.isDestroyed)return;activity.runOnUiThread{runCatching{activity.findViewById<Button>(R.id.mic).performClick()}}}
    private fun stopBargeRecognizer(){runCatching{bargeRecognizer?.cancel()};runCatching{bargeRecognizer?.destroy()};bargeRecognizer=null}
    private fun normalize(value:String):String=Normalizer.normalize(value.lowercase(Locale.getDefault()),Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"),"").replace(Regex("[^a-z0-9 ]+")," ").replace(Regex("\\s+")," ").trim()
}
