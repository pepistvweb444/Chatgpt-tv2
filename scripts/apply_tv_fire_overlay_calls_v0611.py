from pathlib import Path

# ---------- Accessibility overlay: persistent Jarvis bubble + call filter ----------
p=Path('app/src/main/java/com/jarvis/tv/JarvisAccessibilityService.kt')
s=p.read_text()

for imp,anchor in [
    ('import android.os.Handler\n','import android.os.Build\n'),
    ('import android.os.Looper\n','import android.os.Handler\n'),
    ('import android.widget.Button\n','import android.widget.TextView\n'),
    ('import android.widget.LinearLayout\n','import android.widget.Button\n'),
]:
    if imp not in s:
        if anchor in s:s=s.replace(anchor,anchor+imp,1)
        else:s=s.replace('package com.jarvis.tv\n','package com.jarvis.tv\n\n'+imp,1)

field_anchor='    private var bubble: TextView? = null\n'
if 'private val callPoller' not in s:
    fields=r'''    private val overlayHandler = Handler(Looper.getMainLooper())
    private val callRemote by lazy { MobileRemoteClient(this) }
    private var callOverlay: LinearLayout? = null
    private var shownOverlayCallId: String = ""
    private val callPoller = object : Runnable {
        override fun run() {
            if (callRemote.configured()) Thread {
                val call=runCatching{callRemote.incomingCall()}.getOrNull()
                if(call!=null) overlayHandler.post{ updateCallOverlay(call) }
            }.start()
            overlayHandler.postDelayed(this,1400L)
        }
    }
'''
    if field_anchor not in s:raise SystemExit('accessibility bubble field anchor not found')
    s=s.replace(field_anchor,field_anchor+fields,1)

s=s.replace('persistSnapshot(); showBubble()','persistSnapshot(); showBubble(); overlayHandler.removeCallbacks(callPoller); overlayHandler.post(callPoller)',1)

# Keep retrying the bubble on Fire OS if its window manager was not ready on the first event.
old_catch='''        try { wm.addView(view, params) } catch (_: Exception) { bubble = null }'''
new_catch='''        try { wm.addView(view, params) } catch (_: Exception) { bubble = null; overlayHandler.postDelayed({ showBubble() }, 1600L) }'''
if old_catch in s:s=s.replace(old_catch,new_catch,1)

# Streaming patch makes onAccessibilityEvent multiline; keep bubble alive on every app/window change.
if 'cacheStreamingContent(pkg)' in s:
    s=s.replace('''        persistSnapshot()
        cacheStreamingContent(pkg)''','''        persistSnapshot()
        showBubble()
        cacheStreamingContent(pkg)''',1)
else:
    old='''    override fun onAccessibilityEvent(event: AccessibilityEvent?) { getSharedPreferences("jarvis_tv",MODE_PRIVATE).edit().putString("foreground_package",event?.packageName?.toString().orEmpty()).apply(); persistSnapshot() }'''
    new='''    override fun onAccessibilityEvent(event: AccessibilityEvent?) { getSharedPreferences("jarvis_tv",MODE_PRIVATE).edit().putString("foreground_package",event?.packageName?.toString().orEmpty()).apply(); persistSnapshot(); showBubble() }'''
    if old in s:s=s.replace(old,new,1)

helper_anchor='    private fun nodes():List<AccessibilityNodeInfo>{'
if 'private fun updateCallOverlay(call: JSONObject)' not in s:
    helpers=r'''    private fun removeCallOverlay() {
        callOverlay?.let { runCatching { windowManager?.removeView(it) } }
        callOverlay=null; shownOverlayCallId=""
    }

    private fun updateCallOverlay(call: JSONObject) {
        val ringing=call.optBoolean("active",false) && call.optString("state")=="ringing"
        if(!ringing) { removeCallOverlay(); return }
        val id=call.optString("id")
        if(id.isBlank() || (id==shownOverlayCallId && callOverlay!=null)) return
        removeCallOverlay(); shownOverlayCallId=id
        val wm=windowManager ?: (getSystemService(Context.WINDOW_SERVICE) as WindowManager).also{windowManager=it}
        val panel=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER_HORIZONTAL; setPadding(34,28,34,24)
            setBackgroundColor(0xF21A1E29.toInt())
        }
        val name=call.optString("name").ifBlank{call.optString("number").ifBlank{"Llamada entrante"}}
        panel.addView(TextView(this).apply{text="Jarvis · filtro de llamada";textSize=18f;setTextColor(0xFFB8C6FF.toInt());gravity=Gravity.CENTER})
        panel.addView(TextView(this).apply{text=name;textSize=27f;setTextColor(0xFFFFFFFF.toInt());gravity=Gravity.CENTER;setPadding(0,12,0,5)})
        val classification=when(call.optString("classification")) {
            "spam_probable" -> "⚠ Spam probable · ${call.optInt("spamScore",0)}%"
            "possible_spam" -> "⚠ Posible spam"
            "contact" -> if(call.optBoolean("priority",false)) "★ Contacto prioritario" else "Contacto conocido"
            else -> "Número o usuario sin clasificar"
        }
        val detail=buildString {
            append(classification)
            call.optString("number").takeIf{it.isNotBlank()&&it!=name}?.let{append("\n").append(it)}
            call.optString("app").takeIf{it.isNotBlank()}?.let{append("\n").append(it)}
            call.optString("spamSources").takeIf{it.isNotBlank()}?.let{append("\nFuentes: ").append(it)}
            if(call.optBoolean("video",false)) append("\nVideollamada")
        }
        panel.addView(TextView(this).apply{text=detail;textSize=16f;setTextColor(0xFFE5E9F2.toInt());gravity=Gravity.CENTER;setPadding(0,5,0,15)})
        val actions=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER}
        fun actionButton(label:String, action:String)=Button(this).apply {
            text=label;isAllCaps=false;setOnClickListener { Thread { runCatching{callRemote.callAction(action)}; overlayHandler.post{removeCallOverlay()} }.start() }
        }
        actions.addView(actionButton("Contestar","answer"));actions.addView(actionButton("Rechazar","reject"))
        actions.addView(Button(this).apply{text="Dejar sonar";isAllCaps=false;setOnClickListener{removeCallOverlay()}})
        panel.addView(actions)
        val params=WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT).apply{gravity=Gravity.CENTER}
        callOverlay=panel
        runCatching{wm.addView(panel,params)}.onFailure{callOverlay=null}
    }

'''
    if helper_anchor not in s:raise SystemExit('accessibility nodes anchor not found')
    s=s.replace(helper_anchor,helpers+helper_anchor,1)

# Fire TV Prime Video and alternate packages.
s=s.replace('pkg.contains("amazon.avod", true) -> "Prime Video"','pkg.contains("amazon.avod", true) || pkg.contains("firebat", true) || pkg.contains("primevideo", true) -> "Prime Video"')

# Stop poller/panel cleanly.
old_destroy='''    override fun onDestroy() { getSharedPreferences("jarvis_tv",MODE_PRIVATE).edit().putBoolean("accessibility_connected",false).apply(); runCatching{unregisterReceiver(receiver)}; bubble?.let { try { windowManager?.removeView(it) } catch (_: Exception) {} }; bubble=null; super.onDestroy() }'''
new_destroy='''    override fun onDestroy() { getSharedPreferences("jarvis_tv",MODE_PRIVATE).edit().putBoolean("accessibility_connected",false).apply(); overlayHandler.removeCallbacks(callPoller); removeCallOverlay(); runCatching{unregisterReceiver(receiver)}; bubble?.let { try { windowManager?.removeView(it) } catch (_: Exception) {} }; bubble=null; super.onDestroy() }'''
if old_destroy in s:s=s.replace(old_destroy,new_destroy,1)
p.write_text(s)

# ---------- Normal foreground overlay stays running even without SYSTEM_ALERT_WINDOW ----------
p=Path('app/src/main/java/com/jarvis/tv/BootReceiver.kt')
s=p.read_text()
old=r'''        val canOverlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)
        if (canOverlay) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, OverlayService::class.java))
            }
        }'''
new=r'''        // Keep the companion foreground service alive even when Fire OS does not grant
        // SYSTEM_ALERT_WINDOW. The Accessibility overlay can still provide the bubble.
        runCatching {
            ContextCompat.startForegroundService(context, Intent(context, OverlayService::class.java))
        }'''
if old in s:s=s.replace(old,new,1)
p.write_text(s)

# ---------- Manifest ----------
p=Path('app/src/main/AndroidManifest.xml')
s=p.read_text()
if 'android.permission.USE_FULL_SCREEN_INTENT' not in s:
    s=s.replace('    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />','    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />\n    <uses-permission android:name="android.permission.USE_FULL_SCREEN_INTENT" />',1)
p.write_text(s)

# ---------- Aggregate personalized TV favorites / continue-watching ----------
p=Path('app/src/main/java/com/jarvis/tv/MainActivity.kt')
s=p.read_text()
if 'showStreamingFavoritesOverview()' not in s:
    oncreate='''        setupStreamingApps()
        setupTvFocusEffects()'''
    if oncreate in s:
        s=s.replace(oncreate,oncreate+'\n        handler.postDelayed({ showStreamingFavoritesOverview() }, 550L)',1)
    helper='    private fun resolveEndpoint(base: String, endpoint: String): String {'
    method=r'''    private fun showStreamingFavoritesOverview() {
        val titleView=findViewById<TextView>(R.id.streamingPreviewTitle)
        val host=findViewById<LinearLayout>(R.id.streamingPreviewHost)
        host.removeAllViews()
        var count=0
        streamingSpecs().forEach { spec ->
            val pkg=installedPackage(spec) ?: return@forEach
            val titles=cachedPersonalTitles(spec.provider)
            titles.take(6).forEach { item ->
                addPreviewTextCard(host,"${spec.label} · $item","Favoritos / Continuar viendo detectado en tu perfil",pkg);count++
            }
        }
        if(count>0) titleView.text="Tus favoritos, listas y Continuar viendo · $count detectados"
        else {
            titleView.text="Favoritos y recomendaciones de tus aplicaciones"
            streamingSpecs().forEach { spec ->
                val pkg=installedPackage(spec) ?: return@forEach
                addPreviewTextCard(host,spec.label,"Abre la aplicación una vez con Accesibilidad de Jarvis activa para sincronizar Favoritos y Continuar viendo.",pkg)
            }
        }
    }

'''
    if helper in s:s=s.replace(helper,method+helper,1)
    else:raise SystemExit('streaming helper anchor not found')
p.write_text(s)
print('Fire TV persistent bubble, call filter overlay and favorites overview applied')
