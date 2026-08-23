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
        prefs.edit().putLong("voice_session_until",System.currentTimeMillis()+120_000L).apply()
        val gen=generation.incrementAndGet();val text=speechText(raw)
        if(text.isBlank()){activity.runOnUiThread{onStart?.invoke();relisten(activity,700L)};return}
        prefs.edit().putString("voice","mi_voz").apply()
        val chunks=chunk(text);val files=ConcurrentHashMap<Int,File>();val started=AtomicBoolean(false);val failed=AtomicBoolean(false)
        fun fail(){if(generation.get()!=gen||!failed.compareAndSet(false,true))return;activity.runOnUiThread{onStart?.invoke();relisten(activity,700L)}}
        chunks.forEachIndexed{index,part->pool.execute{
            if(failed.get()||generation.get()!=gen)return@execute
            repeat(3){attempt->
                if(files[index]!=null||generation.get()!=gen)return@repeat
                try{files[index]=downloadClone(activity,part,index);return@repeat}catch(_:Exception){if(attempt<2)Thread.sleep(180L*(attempt+1))}
            }
            if(files[index]==null){if(index==0)fail() else runCatching{files[index]=downloadClone(activity,part,index)}}
            if(index==0&&files[index]!=null&&started.compareAndSet(false,true)&&generation.get()==gen)activity.runOnUiThread{onStart?.invoke();startBargeIn(activity,text,gen);play(activity,files,chunks,0,gen)}
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
        s=s.replace(Regex("(?i)(\\d+(?:[.,]\\d+)?)\\s*[°º]?\\s*c(?:elsius)?\\b"),"$1 grados")
        s=s.replace(Regex("(?i)(\\d+(?:[.,]\\d+)?)\\s*grados\\s*c(?:elsius)?\\b"),"$1 grados")
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
            val max=if(first)64 else 120
            if(rest.length<=max){out+=rest;break}
            val w=rest.take(max+1);var cut=listOf(w.lastIndexOf(". "),w.lastIndexOf("? "),w.lastIndexOf("! "),w.lastIndexOf(", "),w.lastIndexOf(' ')).maxOrNull()?:max
            if(cut<if(first)24 else 42)cut=max
            val take=(cut+if(w.getOrNull(cut) in listOf('.','?','!',','))1 else 0).coerceAtMost(rest.length)
            out+=rest.take(take).trim();rest=rest.drop(take).trim();first=false
        }
        return out.filter{it.isNotBlank()}
    }

    private fun downloadClone(activity:Activity,text:String,index:Int):File{
        val c=(URL("$BACKEND/api/speech").openConnection() as HttpURLConnection).apply{requestMethod="POST";doOutput=true;connectTimeout=2200;readTimeout=10000;setRequestProperty("Content-Type","application/json; charset=utf-8");setRequestProperty("Accept","audio/*");setRequestProperty("Connection","keep-alive")}
        c.outputStream.use{it.write(JSONObject().put("text",text).put("voice","mi_voz").put("provider","race-clone").put("speed",1.12).toString().toByteArray())}
        if(c.responseCode !in 200..299)throw IllegalStateException("TTS ${c.responseCode}")
        return File(activity.cacheDir,"jarvis-clone-${System.currentTimeMillis()}-$index.mp3").also{f->c.inputStream.use{input->f.outputStream().use{input.copyTo(it)}}}
    }

    private fun play(activity:Activity,files:ConcurrentHashMap<Int,File>,chunks:List<String>,index:Int,gen:Int){
        if(generation.get()!=gen||interrupted)return
        if(index>=chunks.size){
            stopBargeRecognizer()
            activity.getSharedPreferences("jarvis_mobile",Activity.MODE_PRIVATE).edit().putLong("voice_session_until",System.currentTimeMillis()+120_000L).apply()
            // Give the speaker/recognizer a short tail window so Jarvis cannot transcribe its own last words.
            activity.window.decorView.postDelayed({if(generation.get()==gen&&!interrupted)relisten(activity,0L)},700L)
            return
        }
        val file=files[index]
        if(file==null){activity.window.decorView.postDelayed({if(generation.get()==gen&&!interrupted)play(activity,files,chunks,index,gen)},45L);return}
        runCatching{player?.release()}
        player=MediaPlayer().apply{setDataSource(file.absolutePath);setOnPreparedListener{if(generation.get()==gen&&!interrupted)it.start()};setOnCompletionListener{p->p.release();file.delete();if(player===p)player=null;play(activity,files,chunks,index+1,gen)};setOnErrorListener{p,_,_->p.release();file.delete();if(player===p)player=null;play(activity,files,chunks,index+1,gen);true};prepareAsync()}
    }

    private fun startBargeIn(activity:Activity,assistantText:String,gen:Int){
        if(!SpeechRecognizer.isRecognitionAvailable(activity))return
        stopBargeRecognizer()
        val assistantNormalized=normalize(assistantText)
        val assistantTokens=assistantNormalized.split(' ').filter{it.length>2}.toSet()
        activity.window.decorView.postDelayed({
            if(generation.get()!=gen||interrupted)return@postDelayed
            val r=SpeechRecognizer.createSpeechRecognizer(activity);bargeRecognizer=r
            r.setRecognitionListener(object:RecognitionListener{
                override fun onReadyForSpeech(params:Bundle?){}
                override fun onBeginningOfSpeech(){}
                override fun onRmsChanged(rmsdB:Float){}
                override fun onBufferReceived(buffer:ByteArray?){}
                override fun onEndOfSpeech(){}
                override fun onError(error:Int){if(generation.get()==gen&&!interrupted)restartBarge(activity,assistantText,gen,320L)}
                override fun onPartialResults(partialResults:Bundle?){val heard=partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty();maybeInterrupt(activity,heard,assistantNormalized,assistantTokens,gen,false)}
                override fun onResults(results:Bundle?){val heard=results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty();if(!maybeInterrupt(activity,heard,assistantNormalized,assistantTokens,gen,true))restartBarge(activity,assistantText,gen,220L)}
                override fun onEvent(eventType:Int,params:Bundle?){}
            })
            val i=Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply{putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);putExtra(RecognizerIntent.EXTRA_LANGUAGE,"es-ES");putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,true);putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,5);putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,520L);putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,340L);putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE,false)}
            runCatching{r.startListening(i)}
        },320L)
    }

    private fun restartBarge(activity:Activity,assistantText:String,gen:Int,delay:Long){activity.window.decorView.postDelayed({if(generation.get()==gen&&!interrupted)startBargeIn(activity,assistantText,gen)},delay)}

    private fun maybeInterrupt(activity:Activity,heardRaw:String,assistantNormalized:String,assistantTokens:Set<String>,gen:Int,final:Boolean):Boolean{
        if(generation.get()!=gen||interrupted)return false
        val heard=heardRaw.trim();val h=normalize(heard);if(h.length<2)return false
        val tokens=h.split(' ').filter{it.isNotBlank()};if(tokens.isEmpty())return false
        val explicitStop=h=="para"||h=="espera"||h=="calla"
        val explicitAddress=h.startsWith("jarvis ")||h.startsWith("ale ")||h.startsWith("leo ")||h.startsWith("lola ")
        val overlap=tokens.count{it in assistantTokens}.toDouble()/tokens.size
        val novel=tokens.count{it !in assistantTokens}
        val novelRatio=novel.toDouble()/tokens.size
        val phraseEcho=h.length>=7 && (assistantNormalized.contains(h) || (h.length>20 && h.contains(assistantNormalized.take(20))))
        val looksLikeEcho=phraseEcho || overlap>=0.68 || novelRatio<0.34

        // Partial recognition is deliberately strict: the loudspeaker often appears here first.
        val naturalInterrupt=if(final){
            !looksLikeEcho && h.length>=6 && tokens.size>=2 && novel>=2 && overlap<0.48
        }else{
            !looksLikeEcho && h.length>=12 && tokens.size>=4 && novel>=3 && overlap<0.32
        }
        if(!(explicitStop||explicitAddress||naturalInterrupt))return false

        interrupted=true
        activity.getSharedPreferences("jarvis_mobile",Activity.MODE_PRIVATE).edit().putLong("voice_session_until",System.currentTimeMillis()+120_000L).apply()
        runCatching{player?.stop()};runCatching{player?.release()};player=null;stopBargeRecognizer()
        if(explicitStop){activity.runOnUiThread{relisten(activity,350L)};return true}

        // When the user addresses Jarvis by name, strip only the wake-name prefix before sending.
        val cleaned=heard.replace(Regex("(?i)^\\s*(jarvis|ale|leo|lola)[,.:;!]?\\s*"),"").trim().ifBlank{heard}
        activity.runOnUiThread{val input=activity.findViewById<EditText>(R.id.input);input.setText(cleaned);input.setSelection(cleaned.length);activity.findViewById<Button>(R.id.send).performClick()}
        return true
    }

    private fun relisten(activity:Activity,delay:Long=0L){
        if(activity.isFinishing||activity.isDestroyed)return
        activity.getSharedPreferences("jarvis_mobile",Activity.MODE_PRIVATE).edit().putLong("voice_session_until",System.currentTimeMillis()+120_000L).apply()
        activity.runOnUiThread{activity.window.decorView.postDelayed({runCatching{activity.findViewById<Button>(R.id.mic).performClick()}},delay)}
    }
    private fun stopBargeRecognizer(){runCatching{bargeRecognizer?.cancel()};runCatching{bargeRecognizer?.destroy()};bargeRecognizer=null}
    private fun normalize(value:String):String=Normalizer.normalize(value.lowercase(Locale.getDefault()),Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"),"").replace(Regex("[^a-z0-9 ]+")," ").replace(Regex("\\s+")," ").trim()
}
