from pathlib import Path

def replace_function(text, signature, replacement):
    start=text.find(signature)
    if start < 0:
        raise SystemExit(f'{signature} not found')
    brace=text.find('{', start)
    depth=0; in_string=False; escaped=False
    for i in range(brace, len(text)):
        ch=text[i]
        if in_string:
            if escaped: escaped=False
            elif ch=='\\': escaped=True
            elif ch=='"': in_string=False
        else:
            if ch=='"': in_string=True
            elif ch=='{': depth += 1
            elif ch=='}':
                depth -= 1
                if depth == 0:
                    return text[:start] + replacement + text[i+1:]
    raise SystemExit(f'end not found for {signature}')

# --- MainActivity: visible webcam/sleep panel + strict calendar + better transcription diagnostics ---
p=Path('app/src/main/java/com/jarvis/tv/MainActivity.kt')
s=p.read_text()

vision=r'''    private fun showVision() {
        title.text = "Webcam y detección de sueño"
        subtitle.text = "Webcam USB · IA local · Homey"
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(34, 18, 34, 12) }
        val homey = edit("Webhook Homey · probable_sleep", prefs.getString("homeySofaWebhook", "").orEmpty())
        val statusView = TextView(this).apply { textSize = 16f; setPadding(4, 8, 4, 14) }

        fun refreshVisionStatus() {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            val ids = runCatching {
                val cm = getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
                cm.cameraIdList.joinToString(", ").ifBlank { "ninguna" }
            }.getOrDefault("error")
            statusView.text = "Permiso cámara: " + if (granted) "CONCEDIDO ✓" else "PENDIENTE" +
                "\nCámaras Android: $ids" +
                "\nDetector: " + if (prefs.getBoolean("sofaVisionEnabled", false)) "ACTIVO" else "APAGADO" +
                "\nEstado: " + prefs.getString("sofaVisionStatus", "sin iniciar")
        }

        val permission = Button(this).apply {
            text = "CONCEDER / REVISAR PERMISO DE CÁMARA"
            setOnClickListener {
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this@MainActivity, arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
                } else Toast.makeText(this@MainActivity, "Permiso de cámara ya concedido", Toast.LENGTH_SHORT).show()
                handler.postDelayed({ refreshVisionStatus() }, 600L)
            }
        }
        val start = Button(this).apply {
            text = "ACTIVAR WEBCAM + DETECCIÓN DE SUEÑO"
            setOnClickListener {
                enableSofaVision(homey.text.toString())
                handler.postDelayed({ refreshVisionStatus() }, 900L)
            }
        }
        val stop = Button(this).apply {
            text = "DETENER WEBCAM / DETECTOR"
            setOnClickListener {
                disableSofaVision()
                handler.postDelayed({ refreshVisionStatus() }, 350L)
            }
        }
        val note = TextView(this).apply {
            text = "El vídeo se analiza localmente en el Fire TV. Javistv combina postura, inmovilidad, ojos y actividad del mando para detectar probable sueño."
            textSize = 14f; setPadding(4, 14, 4, 8)
        }
        box.addView(statusView); box.addView(permission); box.addView(homey); box.addView(start); box.addView(stop); box.addView(note)
        refreshVisionStatus()
        AlertDialog.Builder(this).setTitle("Javistv · Webcam y sueño").setView(box)
            .setPositiveButton("GUARDAR") { _, _ ->
                prefs.edit().putString("homeySofaWebhook", homey.text.toString().trim()).apply()
            }.setNegativeButton("CERRAR", null).show()
    }'''
s=replace_function(s,'    private fun showVision()',vision)

post_audio=r'''    private fun postAudio(endpoint: String, file: File): String {
        if (!file.exists() || file.length() < 512) throw IllegalStateException("Audio vacío o demasiado corto")
        val c = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 12000; readTimeout = 60000; doOutput = true
            setRequestProperty("Content-Type", "audio/mp4")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-Filename", "javistv-voice.m4a")
            setRequestProperty("User-Agent", "Javistv/0.6.21")
        }
        c.outputStream.use { out -> file.inputStream().use { it.copyTo(out) } }
        val code = c.responseCode
        val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            val j = runCatching { JSONObject(body) }.getOrNull()
            val details = j?.optJSONArray("details")
            val detailText = if (details != null && details.length() > 0) {
                (0 until minOf(details.length(), 3)).joinToString(" | ") { details.optString(it) }
            } else j?.optString("message").orEmpty().ifBlank { j?.optString("error").orEmpty() }
            throw IllegalStateException("Transcripción HTTP $code" + if (detailText.isNotBlank()) " · $detailText" else "")
        }
        val json = JSONObject(body)
        return json.optString("text").trim().ifBlank { throw IllegalStateException("Transcripción vacía") }
    }'''
s=replace_function(s,'    private fun postAudio(',post_audio)

# Strict distinction: missing/unpermitted calendar is never presented as an empty real calendar.
old='''        val events=agenda?.optJSONArray("events") ?: JSONArray()
        val reminders=agenda?.optJSONArray("reminders") ?: JSONArray()
        if(events.length()==0 && reminders.length()==0) addDashboardCard("Sin eventos próximos","No hay citas ni recordatorios detectados en los próximos días.")'''
new='''        val events=agenda?.optJSONArray("events") ?: JSONArray()
        val reminders=agenda?.optJSONArray("reminders") ?: JSONArray()
        val calendarPermission=agenda?.optBoolean("calendarPermission",false) ?: false
        when {
            agenda == null -> addDashboardCard("Calendario no sincronizado","Javistv no ha recibido datos reales de calendario desde Jarvis Mobile. No se generará ninguna cita por IA.",Color.rgb(92,58,38))
            !calendarPermission -> addDashboardCard("Permiso de calendario pendiente","Abre Jarvis Mobile y concede permiso de Calendario. Hasta entonces Javistv no mostrará ni supondrá eventos.",Color.rgb(92,58,38))
            events.length()==0 && reminders.length()==0 -> addDashboardCard("Agenda real vacía","El calendario del teléfono está accesible y no devuelve eventos próximos.")
        }'''
if old in s:
    s=s.replace(old,new,1)

# Final dashboard generated by usability patches: keep calendar truth separate from deliveries.
s=s.replace(
    '        val events=agenda?.optJSONArray("events") ?: JSONArray()\n        val reminders=agenda?.optJSONArray("reminders") ?: JSONArray()\n        val mItems=mobility?.optJSONArray("items") ?: JSONArray()',
    '''        val calendarPermission=agenda?.optBoolean("calendarPermission",false) ?: false
        val calendarError=agenda?.optString("queryError").orEmpty()
        val events=if(calendarPermission && calendarError.isBlank()) agenda?.optJSONArray("events") ?: JSONArray() else JSONArray()
        val reminders=if(calendarPermission && calendarError.isBlank()) agenda?.optJSONArray("reminders") ?: JSONArray() else JSONArray()
        val mItems=mobility?.optJSONArray("items") ?: JSONArray()''',
    1
)
s=s.replace(
    '        if(events.length()==0 && reminders.length()==0 && shownDeliveries==0) addDashboardCard("Sin actividad próxima","No hay citas, recordatorios o pedidos recientes detectados.")',
    '''        if(events.length()==0 && reminders.length()==0) {
            when {
                agenda==null -> addDashboardCard("Calendario no sincronizado","Javistv no ha recibido datos reales del calendario desde Jarvis Mobile. No se generará ninguna cita por IA.",Color.rgb(92,58,38))
                !calendarPermission -> addDashboardCard("Permiso de calendario pendiente","Abre Jarvis Mobile y concede permiso de Calendario. Javistv no supondrá eventos mientras falte el permiso.",Color.rgb(92,58,38))
                calendarError.isNotBlank() -> addDashboardCard("Error leyendo calendario",calendarError.take(180),Color.rgb(92,58,38))
                else -> addDashboardCard("Agenda real vacía","El calendario del teléfono está accesible y no devuelve eventos próximos.")
            }
        }
        if(events.length()==0 && reminders.length()==0 && shownDeliveries==0 && agenda!=null && calendarPermission && calendarError.isBlank()) {
            // Calendar is genuinely empty; the card above is authoritative.
        }''',
    1
)

# Preview cards: never leave a blank image. Use actual artwork when available, app icon otherwise.
old_img='''        val image = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, tvDp(98))
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.rgb(30, 35, 46))
        }'''
new_img='''        val image = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, tvDp(98))
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.rgb(30, 35, 46))
            setImageDrawable(runCatching { packageManager.getApplicationIcon(pkg) }.getOrNull())
        }'''
if old_img in s: s=s.replace(old_img,new_img,1)

old_dl='''        if (imageUrl.isNotBlank()) Thread {
            val bmp = runCatching { URL(imageUrl).openConnection().apply { connectTimeout = 3500; readTimeout = 5000 }.getInputStream().use { BitmapFactory.decodeStream(it) } }.getOrNull()
            if (bmp != null) runOnUiThread { image.setImageBitmap(bmp) }
        }.start()'''
new_dl='''        if (imageUrl.isNotBlank()) Thread {
            val bmp = runCatching {
                val conn=URL(imageUrl).openConnection().apply {
                    connectTimeout=5000; readTimeout=8000
                    setRequestProperty("User-Agent","Mozilla/5.0 Javistv/0.6.21")
                }
                conn.getInputStream().use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
            if (bmp != null) runOnUiThread { image.setImageBitmap(bmp) }
        }.start()'''
if old_dl in s: s=s.replace(old_dl,new_dl,1)

s=s.replace('Ajustes de Javistv v0.6.18','Ajustes de Javistv v0.6.21')
s=s.replace('Javistv/0.6.18','Javistv/0.6.21')
p.write_text(s)


# Unified inbox from the paired phone: email/SMS/RCS/app messages inside Mi día/Centro personal.
helper_anchor='    private fun renderPersonalDashboard(agenda:JSONObject?, calls:JSONObject?, mobility:JSONObject?) {'
if 'private fun appendUnifiedInbox(' not in s:
    helper=r'''    private fun appendUnifiedInbox(data: JSONObject?) {
        addDashboardHeading("Mensajes y correo")
        if (data == null) {
            addDashboardCard("Mensajes no sincronizados","Javistv no ha recibido la bandeja del móvil. Comprueba el emparejamiento y el acceso a notificaciones.",Color.rgb(92,58,38))
            return
        }
        val items=data.optJSONArray("items") ?: JSONArray()
        if(items.length()==0) {
            addDashboardCard("Sin mensajes pendientes","No hay SMS, RCS, WhatsApp o correo pendiente detectado en el móvil.")
            return
        }
        for(i in 0 until minOf(items.length(),12)) {
            val m=items.optJSONObject(i)?:continue
            val source=m.optString("source").ifBlank{"Mensaje"}
            val from=m.optString("from").ifBlank{source}
            val body=m.optString("text").trim().take(700)
            addDashboardCard("✉ $source · $from",body,Color.rgb(42,55,78))
        }
    }

'''
    if helper_anchor not in s: raise SystemExit('renderPersonalDashboard anchor missing')
    s=s.replace(helper_anchor,helper+helper_anchor,1)

# showNotifications: fetch inbox together with calendar/calls/mobility.
old='''            val agenda=runCatching{mobileRemote.agenda()}.getOrNull()
            val calls=runCatching{mobileRemote.calls()}.getOrNull()
            val mobility=runCatching{mobileRemote.mobility()}.getOrNull()
            runOnUiThread { renderPersonalDashboard(agenda,calls,mobility); status.text="● Agenda, llamadas y pedidos actualizados" }'''
new='''            val agenda=runCatching{mobileRemote.agenda()}.getOrNull()
            val calls=runCatching{mobileRemote.calls()}.getOrNull()
            val mobility=runCatching{mobileRemote.mobility()}.getOrNull()
            val inbox=runCatching{mobileRemote.unreadMessages()}.getOrNull()
            runOnUiThread {
                renderPersonalDashboard(agenda,calls,mobility)
                appendUnifiedInbox(inbox)
                status.text="● Mi día sincronizado desde el móvil"
            }'''
if old in s: s=s.replace(old,new,1)

# Morning briefing uses same source set.
old2='''            val agenda=runCatching{mobileRemote.agenda()}.getOrNull()
            val calls=runCatching{mobileRemote.calls()}.getOrNull()
            val mobility=runCatching{mobileRemote.mobility()}.getOrNull()
            val home=runCatching{mobileRemote.domotics()}.getOrNull()
            runOnUiThread {
                renderPersonalDashboard(agenda,calls,mobility)'''
new2='''            val agenda=runCatching{mobileRemote.agenda()}.getOrNull()
            val calls=runCatching{mobileRemote.calls()}.getOrNull()
            val mobility=runCatching{mobileRemote.mobility()}.getOrNull()
            val inbox=runCatching{mobileRemote.unreadMessages()}.getOrNull()
            val home=runCatching{mobileRemote.domotics()}.getOrNull()
            runOnUiThread {
                renderPersonalDashboard(agenda,calls,mobility)
                appendUnifiedInbox(inbox)'''
if old2 in s: s=s.replace(old2,new2,1)

s=s.replace('subtitle.text = "Agenda · recordatorios · llamadas · pedidos · finanzas"','subtitle.text = "Agenda · mensajes · correo · llamadas · pedidos · casa"',1)

# --- Accessibility: one permission drives bubble, translated subtitles and app UI reading ---
p=Path('app/src/main/res/xml/accessibility_service_config.xml')
x=p.read_text()
x=x.replace('android:canRetrieveWindowContent="false"','android:canRetrieveWindowContent="true"')
if 'android:accessibilityFlags=' not in x:
    x=x.replace('android:notificationTimeout="100"', 'android:notificationTimeout="100"\n    android:accessibilityFlags="flagReportViewIds|flagRetrieveInteractiveWindows"')
p.write_text(x)

p=Path('app/src/main/java/com/jarvis/tv/JarvisAccessibilityService.kt')
a=p.read_text()
if 'private var translationOverlay: TextView?' not in a:
    a=a.replace('    private var bubble: TextView? = null\n','    private var bubble: TextView? = null\n    private var translationOverlay: TextView? = null\n',1)
    a=a.replace('                ACTION_REFRESH_UI -> persistSnapshot()\n',
'''                ACTION_REFRESH_UI -> persistSnapshot()
                ACTION_SHOW_TRANSLATION -> showTranslationText(intent.getStringExtra("text").orEmpty())
                ACTION_HIDE_TRANSLATION -> hideTranslationText()
''',1)
    a=a.replace('            addAction(ACTION_CLICK_TEXT); addAction(ACTION_SET_TEXT); addAction(ACTION_SCROLL_FORWARD); addAction(ACTION_SCROLL_BACKWARD); addAction(ACTION_BACK); addAction(ACTION_HOME); addAction(ACTION_REFRESH_UI)\n',
'''            addAction(ACTION_CLICK_TEXT); addAction(ACTION_SET_TEXT); addAction(ACTION_SCROLL_FORWARD); addAction(ACTION_SCROLL_BACKWARD); addAction(ACTION_BACK); addAction(ACTION_HOME); addAction(ACTION_REFRESH_UI); addAction(ACTION_SHOW_TRANSLATION); addAction(ACTION_HIDE_TRANSLATION)
''',1)
    marker='    private fun nodes():List<AccessibilityNodeInfo>{\n'
    helpers=r'''    private fun showTranslationText(text: String) {
        if (text.isBlank()) return
        val wm = windowManager ?: (getSystemService(Context.WINDOW_SERVICE) as WindowManager).also { windowManager = it }
        val view = translationOverlay ?: TextView(this).apply {
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xD9000000.toInt())
            setPadding(38, 20, 38, 20)
        }.also { v ->
            val params=WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply { gravity=Gravity.BOTTOM; y=115 }
            runCatching { wm.addView(v,params) }.onFailure { return }
            translationOverlay=v
        }
        view.text=text
    }

    private fun hideTranslationText() {
        translationOverlay?.let { runCatching { windowManager?.removeView(it) } }
        translationOverlay=null
    }

'''
    if marker not in a: raise SystemExit('accessibility helper marker missing')
    a=a.replace(marker,helpers+marker,1)
    a=a.replace('bubble?.let { try { windowManager?.removeView(it) } catch (_: Exception) {} }; bubble=null; super.onDestroy()',
                'bubble?.let { try { windowManager?.removeView(it) } catch (_: Exception) {} }; bubble=null; hideTranslationText(); super.onDestroy()')
    a=a.replace('const val ACTION_REFRESH_UI="com.jarvis.tv.action.REFRESH_UI"',
                'const val ACTION_REFRESH_UI="com.jarvis.tv.action.REFRESH_UI"; const val ACTION_SHOW_TRANSLATION="com.jarvis.tv.action.SHOW_TRANSLATION"; const val ACTION_HIDE_TRANSLATION="com.jarvis.tv.action.HIDE_TRANSLATION"')
p.write_text(a)

# --- TranslationService publishes text to the accessibility overlay ---
p=Path('app/src/main/java/com/jarvis/tv/TranslationService.kt')
if p.exists():
    t=p.read_text()
    needle='''                    prefs().edit()
                        .putString("translation_last_source", transcript.take(500))
                        .putString("translation_last_text", translated.take(1000))
                        .putString("translation_status", "hablando")
                        .apply()
                    speak(translated)'''
    replacement='''                    prefs().edit()
                        .putString("translation_last_source", transcript.take(500))
                        .putString("translation_last_text", translated.take(1000))
                        .putString("translation_status", "hablando")
                        .apply()
                    sendBroadcast(Intent(JarvisAccessibilityService.ACTION_SHOW_TRANSLATION).setPackage(packageName).putExtra("text", translated.take(1000)))
                    speak(translated)'''
    if needle in t: t=t.replace(needle,replacement,1)
    stopneedle='''        prefs().edit().putBoolean("translation_active", false).putString("translation_status", "detenida").apply()
        stopForeground(STOP_FOREGROUND_REMOVE)'''
    stopreplacement='''        prefs().edit().putBoolean("translation_active", false).putString("translation_status", "detenida").apply()
        sendBroadcast(Intent(JarvisAccessibilityService.ACTION_HIDE_TRANSLATION).setPackage(packageName))
        stopForeground(STOP_FOREGROUND_REMOVE)'''
    if stopneedle in t: t=t.replace(stopneedle,stopreplacement,1)
    p.write_text(t)

# Manifest: make the single accessibility permission understandable; avoid modern FGS type on Fire OS 7.
p=Path('app/src/main/AndroidManifest.xml')
m=p.read_text()
m=m.replace('android:label="Javistv bubble"','android:label="Javistv · burbuja y texto siempre visible"')
m=m.replace('            android:stopWithTask="false"\n            android:foregroundServiceType="microphone" />','            android:stopWithTask="false" />')
p.write_text(m)

print('Javistv 0.6.21 fixpack applied')
