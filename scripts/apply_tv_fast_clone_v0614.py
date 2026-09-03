from pathlib import Path


def replace_function(text, signature, replacement):
    start=text.find(signature)
    if start<0: raise SystemExit(f'{signature} not found')
    brace=text.find('{',start);depth=0;inside=False;esc=False
    for i in range(brace,len(text)):
        ch=text[i]
        if inside:
            if esc:esc=False
            elif ch=='\\':esc=True
            elif ch=='"':inside=False
        else:
            if ch=='"':inside=True
            elif ch=='{':depth+=1
            elif ch=='}':
                depth-=1
                if depth==0:return text[:start]+replacement+text[i+1:]
    raise SystemExit('function end not found')

p=Path('app/src/main/java/com/jarvis/tv/MainActivity.kt')
s=p.read_text()
field='    private var voicePlayer: MediaPlayer? = null\n'
if 'private var cloneSpeechGeneration' not in s:
    s=s.replace(field,field+'    private var cloneSpeechGeneration = 0\n',1)

helper_anchor='    private fun updateChatMeta(text: String, role: String) {'
if 'private fun scrollToView(view: View)' not in s:
    scroll_helper=r'''    private fun scrollToView(view: View) {
        val scroll=findViewById<android.widget.ScrollView>(R.id.mainScroll)
        scroll.post {
            val target=(view.top-pDp(18)).coerceAtLeast(0)
            scroll.smoothScrollTo(0,target)
            view.requestFocus()
        }
    }

'''
    if helper_anchor not in s:raise SystemExit('helper anchor missing for scrollToView')
    s=s.replace(helper_anchor,scroll_helper+helper_anchor,1)

if 'private fun splitCloneSpeech(' not in s:
    helpers=r'''    private fun splitCloneSpeech(text:String,max:Int=360):List<String> {
        val clean=text.replace(Regex("\\s+")," ").trim(); if(clean.isBlank())return emptyList()
        val sentences=Regex("(?<=[.!?])\\s+").split(clean);val out=mutableListOf<String>();var cur=""
        for(sentence in sentences){
            if(sentence.length>max){
                if(cur.isNotBlank()){out+=cur.trim();cur=""}
                var rest=sentence.trim();while(rest.length>max){var cut=rest.lastIndexOf(' ',max);if(cut<max/2)cut=max;out+=rest.substring(0,cut).trim();rest=rest.substring(cut).trim()};if(rest.isNotBlank())cur=rest
            } else if(cur.isBlank())cur=sentence else if(cur.length+1+sentence.length<=max)cur+=" $sentence" else {out+=cur.trim();cur=sentence}
        }
        if(cur.isNotBlank())out+=cur.trim();return out.take(12)
    }

    private fun playCloneChunk(files:Array<File?>,index:Int,generation:Int,retries:Int=0) {
        if(generation!=cloneSpeechGeneration||index>=files.size)return
        val file=files[index]
        if(file==null||!file.exists()||file.length()==0L){
            if(retries<80)handler.postDelayed({playCloneChunk(files,index,generation,retries+1)},75L)
            else status.text="● Voz clonada: fragmento no disponible"
            return
        }
        voicePlayer?.release();voicePlayer=MediaPlayer().apply {
            setDataSource(file.absolutePath)
            setOnPreparedListener{p->if(generation==cloneSpeechGeneration){status.text="● Jarvis · voz clonada";p.start()}else p.release()}
            setOnCompletionListener{p->p.release();if(voicePlayer===p)voicePlayer=null;file.delete();playCloneChunk(files,index+1,generation)}
            setOnErrorListener{p,_,_->p.release();if(voicePlayer===p)voicePlayer=null;file.delete();playCloneChunk(files,index+1,generation);true}
            prepareAsync()
        }
    }

'''
    if helper_anchor not in s:raise SystemExit('helper anchor missing')
    s=s.replace(helper_anchor,helpers+helper_anchor,1)

speech=r'''    private fun speakWithOpenAI(text: String) {
        val backend=prefs.getString("backendUrl",DEFAULT_BACKEND).orEmpty().ifBlank{DEFAULT_BACKEND}
        val chunks=splitCloneSpeech(text)
        if(chunks.isEmpty())return
        cloneSpeechGeneration += 1
        val generation=cloneSpeechGeneration
        voicePlayer?.stop();voicePlayer?.release();voicePlayer=null;tts?.stop()
        val files=arrayOfNulls<File>(chunks.size)
        status.text="● Preparando voz clonada…"
        chunks.forEachIndexed { index,chunk ->
            Thread {
                val file=File(cacheDir,"jarvis-clone-$generation-$index.audio")
                try {
                    downloadSpeech(resolveEndpoint(backend,"speech-fast-clone"),chunk,file)
                    if(generation!=cloneSpeechGeneration){file.delete();return@Thread}
                    files[index]=file
                    if(index==0)runOnUiThread{playCloneChunk(files,0,generation)}
                } catch(e:Throwable) {
                    file.delete()
                    if(index==0)runOnUiThread{status.text="● Voz clonada temporalmente no disponible";Toast.makeText(this,"Voz clonada: ${e.message}",Toast.LENGTH_LONG).show()}
                }
            }.start()
        }
    }'''
s=replace_function(s,'    private fun speakWithOpenAI(text: String)',speech)
p.write_text(s)
print('TV 0.6.14 dashboard scroll fix + fast chunked cloned voice applied')
