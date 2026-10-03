from pathlib import Path
import runpy

# This patch depends on the streaming-app UI/accessibility base. Some build
# pipelines omitted that earlier step, so make the dependency self-healing.
main_source = Path('app/src/main/java/com/jarvis/tv/MainActivity.kt')
if main_source.exists() and 'private fun cachedPersonalTitles(provider: String)' not in main_source.read_text():
    runpy.run_path('scripts/apply_tv_streaming_focus.py', run_name='__main__')


def replace_function(text, signature, replacement):
    start=text.find(signature)
    if start<0: raise SystemExit(f'{signature} not found')
    brace=text.find('{',start)
    depth=0; ins=False; esc=False
    for i in range(brace,len(text)):
        ch=text[i]
        if ins:
            if esc: esc=False
            elif ch=='\\': esc=True
            elif ch=='"': ins=False
        else:
            if ch=='"': ins=True
            elif ch=='{': depth+=1
            elif ch=='}':
                depth-=1
                if depth==0: return text[:start]+replacement+text[i+1:]
    raise SystemExit(f'end not found for {signature}')

p=Path('app/src/main/java/com/jarvis/tv/MainActivity.kt')
s=p.read_text()

cached=r'''    private fun cachedPersonalTitles(provider: String): List<String> {
        val key="streaming_personal_"+provider.lowercase().replace(Regex("[^a-z0-9]+"),"_").trim('_')
        val a=runCatching{JSONArray(prefs.getString(key,"[]"))}.getOrElse{JSONArray()}
        val out=mutableListOf<String>()
        for(i in 0 until a.length()) {
            val o=a.optJSONObject(i)
            val title=if(o!=null)o.optString("title") else a.optString(i)
            val context=o?.optString("context").orEmpty()
            if(title.isNotBlank()) out += if(context.isBlank()) title else "$context · $title"
        }
        return out.distinct().take(16)
    }'''
s=replace_function(s,'    private fun cachedPersonalTitles(provider: String)',cached)

stream=r'''    private fun loadStreamingPreview(spec: StreamingAppSpec, pkg: String) {
        val title=findViewById<TextView>(R.id.streamingPreviewTitle)
        val host=findViewById<LinearLayout>(R.id.streamingPreviewHost)
        host.removeAllViews()
        val key="streaming_personal_"+spec.provider.lowercase().replace(Regex("[^a-z0-9]+"),"_").trim('_')
        val items=runCatching{JSONArray(prefs.getString(key,"[]"))}.getOrElse{JSONArray()}
        if(items.length()==0) {
            title.text="${spec.label} · tu perfil"
            addPreviewTextCard(host,"Sin datos personalizados todavía","Abre ${spec.label} con Accesibilidad de Javistv activa. Solo mostraré Mi lista, Continuar viendo, nuevos episodios/temporadas y estrenos visibles en tu perfil.",pkg)
            return
        }
        title.text="${spec.label} · Mi lista y novedades"
        var shown=0
        for(i in 0 until items.length()) {
            val o=items.optJSONObject(i)?:continue
            val itemTitle=o.optString("title").trim()
            if(itemTitle.isBlank())continue
            val context=o.optString("context").ifBlank{"Tu perfil"}
            addPreviewTextCard(host,itemTitle,context,pkg)
            shown++
            if(shown>=12)break
        }
        if(shown==0)addPreviewTextCard(host,"Sin novedades personales","Abre ${spec.label} para actualizar tu perfil.",pkg)
    }'''
s=replace_function(s,'    private fun loadStreamingPreview(spec: StreamingAppSpec, pkg: String)',stream)

# Mi día must never route through chat/LLM after all other patches.
needle='        input.setOnEditorActionListener { _, _, _ -> sendMessage(); true }\n'
if needle in s and 'REAL_DAY_PHONE_ONLY' not in s:
    s=s.replace(needle,'        findViewById<android.view.View>(R.id.cardNow).setOnClickListener { showNotifications() } // REAL_DAY_PHONE_ONLY\n'+needle,1)

# Remove stale settings view references left by old pairing patches when their controls are not declared.
s=s.replace('; box.addView(mobileHost); box.addView(mobilePin)', '')
s=s.replace('; box.addView(testMobileButton)', '')

p.write_text(s)

p=Path('app/src/main/java/com/jarvis/tv/JarvisAccessibilityService.kt')
a=p.read_text()
cache=r'''    private fun cacheStreamingContent(pkg: String) {
        val provider=streamingProvider(pkg)?:return
        val labels=nodes().map{label(it).trim()}.filter{it.isNotBlank()}
        if(labels.isEmpty())return
        fun section(t:String):String? {
            val x=t.lowercase()
            return when {
                x.contains("mi lista") || x.contains("my list") || x.contains("watchlist") || x.contains("lista de seguimiento") -> "Mi lista"
                x.contains("continuar viendo") || x.contains("continue watching") || x.contains("seguir viendo") -> "Continuar viendo"
                x.contains("nuevo episodio") || x.contains("new episode") -> "Nuevo episodio"
                x.contains("nuevos episodios") || x.contains("new episodes") -> "Nuevos episodios"
                x.contains("nueva temporada") || x.contains("new season") -> "Nueva temporada"
                x.contains("estreno") || x.contains("new release") || x.contains("recién añadido") || x.contains("recently added") -> "Estreno / recién añadido"
                else -> null
            }
        }
        val blocked=listOf("inicio","home","buscar","search","perfil","profiles","reproducir","play","pausa","pause","más información","more info","configuración","settings",provider.lowercase())
        val out=JSONArray()
        var current=""
        var budget=0
        val seen=mutableSetOf<String>()
        for(raw in labels) {
            val sec=section(raw)
            if(sec!=null) { current=sec; budget=36; continue }
            if(budget<=0 || current.isBlank())continue
            budget--
            val t=raw.trim()
            if(t.length !in 2..100)continue
            val low=t.lowercase()
            if(blocked.any{low==it} || low.matches(Regex("^[0-9:.,%+ /-]+$")))continue
            if(section(t)!=null)continue
            val k=(current+"|"+t).lowercase()
            if(!seen.add(k))continue
            out.put(JSONObject().put("title",t).put("context",current).put("seenAt",System.currentTimeMillis()))
            if(out.length()>=30)break
        }
        if(out.length()==0)return
        val key="streaming_personal_"+provider.lowercase().replace(Regex("[^a-z0-9]+"),"_").trim('_')
        getSharedPreferences("jarvis",MODE_PRIVATE).edit().putString(key,out.toString()).apply()
    }'''
a=replace_function(a,'    private fun cacheStreamingContent(pkg: String)',cache)
p.write_text(a)

print('Javistv personalized streaming only + real day finalizer applied')