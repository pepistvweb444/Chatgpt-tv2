from pathlib import Path

p=Path('app/src/main/AndroidManifest.xml')
s=p.read_text()
if 'android:name=".TranslationService"' not in s:
    anchor='''        <service
            android:name=".WakeWordService"'''
    block='''        <service
            android:name=".TranslationService"
            android:enabled="true"
            android:exported="false"
            android:stopWithTask="false"
            android:foregroundServiceType="microphone" />

'''
    if anchor not in s: raise SystemExit('WakeWordService manifest anchor missing')
    s=s.replace(anchor, block+anchor, 1)
p.write_text(s)

p=Path('app/src/main/java/com/jarvis/tv/WakeWordService.kt')
s=p.read_text()
old='''    private fun inspect(results: ArrayList<String>?) {
        val configured = getSharedPreferences("jarvis", MODE_PRIVATE)
            .getString("wakeWord", "Hola Jarvis").orEmpty().trim().lowercase()
        val accepted = listOf(configured, "hola jarvis", "jarvis").filter { it.isNotBlank() }
        val heard = results.orEmpty().joinToString(" ").lowercase()
        if (accepted.any { heard.contains(it) }) activateJarvis()
    }
'''
new='''    private fun inspect(results: ArrayList<String>?) {
        val configured = getSharedPreferences("jarvis", MODE_PRIVATE)
            .getString("wakeWord", "Hola Jarvis").orEmpty().trim().lowercase()
        val accepted = listOf(configured, "hola jarvis", "jarvis").filter { it.isNotBlank() }
        val heard = results.orEmpty().joinToString(" ").lowercase()
        if (!accepted.any { heard.contains(it) }) return

        if (heard.contains("para la tradu") || heard.contains("deten la tradu") || heard.contains("detén la tradu") || heard.contains("deja de traduc")) {
            runCatching { startService(Intent(this, TranslationService::class.java).setAction(TranslationService.ACTION_STOP)) }
            return
        }
        val language = when {
            heard.contains("francés") || heard.contains("frances") -> "fr"
            heard.contains("inglés") || heard.contains("ingles") -> "en"
            heard.contains("alemán") || heard.contains("aleman") -> "de"
            heard.contains("italiano") -> "it"
            heard.contains("portugués") || heard.contains("portugues") -> "pt"
            else -> "es"
        }
        if (heard.contains("tradu") || heard.contains("dobla")) {
            recognizer?.cancel()
            getSharedPreferences("jarvis", MODE_PRIVATE).edit().putString("translation_target", language).apply()
            runCatching {
                ContextCompat.startForegroundService(this, Intent(this, TranslationService::class.java)
                    .setAction(TranslationService.ACTION_START)
                    .putExtra(TranslationService.EXTRA_LANGUAGE, language))
            }
            stopSelf()
            return
        }
        activateJarvis()
    }
'''
if old not in s: raise SystemExit('WakeWord inspect anchor missing')
s=s.replace(old,new,1)
p.write_text(s)

p=Path('app/src/main/java/com/jarvis/tv/MainActivity.kt')
s=p.read_text()
anchor='''    private fun startSofaVisionService() {
'''
if 'private fun toggleTranslation()' not in s:
    helper='''    private fun startTranslation(language: String = prefs.getString("translation_target", "es") ?: "es") {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO)
            return
        }
        prefs.edit().putString("translation_target", language).apply()
        runCatching {
            ContextCompat.startForegroundService(this, Intent(this, TranslationService::class.java)
                .setAction(TranslationService.ACTION_START)
                .putExtra(TranslationService.EXTRA_LANGUAGE, language))
        }
        Toast.makeText(this, "Traducción activada · " + language, Toast.LENGTH_SHORT).show()
    }

    private fun stopTranslation() {
        runCatching { startService(Intent(this, TranslationService::class.java).setAction(TranslationService.ACTION_STOP)) }
        Toast.makeText(this, "Traducción detenida", Toast.LENGTH_SHORT).show()
    }

    private fun toggleTranslation() {
        if (prefs.getBoolean("translation_active", false)) stopTranslation() else startTranslation()
    }

'''
    if anchor not in s: raise SystemExit('MainActivity service anchor missing')
    s=s.replace(anchor,helper+anchor,1)

marker='''        return super.dispatchKeyEvent(event)
    }
'''
if 'KEYCODE_MENU) { toggleTranslation()' not in s:
    if marker not in s: raise SystemExit('dispatchKeyEvent end missing')
    s=s.replace(marker,'''        if (event.action == KeyEvent.ACTION_UP && event.keyCode == KeyEvent.KEYCODE_MENU) { toggleTranslation(); return true }
        return super.dispatchKeyEvent(event)
    }
''',1)

if 'ACTIVAR TRADUCCIÓN A ESPAÑOL' not in s:
    btn_anchor='''        val micTestButton = Button(this).apply {'''
    btns='''        val translationStartButton = Button(this).apply { text = "ACTIVAR TRADUCCIÓN A ESPAÑOL"; setOnClickListener { startTranslation("es") } }
        val translationStopButton = Button(this).apply { text = "DETENER TRADUCCIÓN"; setOnClickListener { stopTranslation() } }
'''
    if btn_anchor in s:
        s=s.replace(btn_anchor,btns+btn_anchor,1)
    add_anchor='box.addView(name);'
    if add_anchor in s:
        s=s.replace(add_anchor,'box.addView(translationStartButton); box.addView(translationStopButton); '+add_anchor,1)

s=s.replace('Ajustes de Javistv v0.6.16','Ajustes de Javistv v0.6.17')
s=s.replace('Javistv/0.6.16','Javistv/0.6.17')
p.write_text(s)

p=Path('app/src/main/java/com/jarvis/tv/TvCommandService.kt')
s=p.read_text()
if 'import androidx.core.content.ContextCompat' not in s:
    s=s.replace('import androidx.core.app.NotificationCompat\n','import androidx.core.app.NotificationCompat\nimport androidx.core.content.ContextCompat\n',1)

brief='''        if (path.startsWith("/briefing")) {
'''
if '/translate/start' not in s:
    block='''        if (path.startsWith("/translate/status")) {
            val p=getSharedPreferences("jarvis",MODE_PRIVATE)
            return 200 to JSONObject().put("ok",true).put("active",p.getBoolean("translation_active",false)).put("target",p.getString("translation_target","es")).put("status",p.getString("translation_status","")).toString()
        }
        if (path.startsWith("/translate/stop")) {
            runCatching { startService(Intent(this, TranslationService::class.java).setAction(TranslationService.ACTION_STOP)) }
            return 200 to JSONObject().put("ok",true).put("status","translation-stopping").toString()
        }
        if (path.startsWith("/translate/start")) {
            val lang=Regex("[?&]lang=([^&]+)").find(path)?.groupValues?.getOrNull(1)?.take(8) ?: "es"
            getSharedPreferences("jarvis",MODE_PRIVATE).edit().putString("translation_target",lang).apply()
            runCatching { ContextCompat.startForegroundService(this, Intent(this, TranslationService::class.java).setAction(TranslationService.ACTION_START).putExtra(TranslationService.EXTRA_LANGUAGE,lang)) }
            return 200 to JSONObject().put("ok",true).put("status","translation-started").put("target",lang).toString()
        }
'''
    if brief not in s: raise SystemExit('TvCommand briefing anchor missing')
    s=s.replace(brief,block+brief,1)
p.write_text(s)

print('Javistv 0.6.17 translation patch applied')
