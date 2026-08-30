from pathlib import Path

p=Path('mobile/src/main/java/com/jarvis/mobile/JarvisOverlayService.kt')
s=p.read_text()
if 'import android.widget.EditText' not in s:
    s=s.replace('import android.widget.Button\n','import android.widget.Button\nimport android.widget.EditText\n')

old='''    private fun attachOverlay(outer: LinearLayout, userText: String) {
        outer.addView(TextView(this).apply { text="Toca para abrir Jarvis   ·   Mantén pulsado para cerrar"; textSize=11f; setTextColor(Color.rgb(105,109,122)); setPadding(0,dp(11),0,0) })
        outer.setOnClickListener {
            runCatching { startActivity(Intent(this, MainActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP); putExtra("hands_free", true); if(userText.isNotBlank()) putExtra("overlay_command", userText) }) }
        }
        outer.setOnLongClickListener { hide(); true }
        root=outer
        val type=if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val lp=WindowManager.LayoutParams(dp(370),WindowManager.LayoutParams.WRAP_CONTENT,type,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT).apply { gravity=Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y=dp(70) }
        runCatching { wm?.addView(outer,lp) }.onFailure { hide() }
    }
'''
new='''    private fun attachOverlay(outer: LinearLayout, userText: String) {
        val composer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10), 0, 0) }
        val box = EditText(this).apply {
            hint = "Escribe a Jarvis…"; textSize = 15f; setTextColor(Color.rgb(24,27,35)); setHintTextColor(Color.rgb(115,119,132)); setSingleLine(true)
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(18).toFloat(); setStroke(dp(1), Color.rgb(215,218,228)) }
            setPadding(dp(12),0,dp(12),0)
        }
        composer.addView(box, LinearLayout.LayoutParams(0, dp(48), 1f))
        composer.addView(Button(this).apply {
            text = "🎙"; textSize = 18f
            setOnClickListener {
                runCatching { ContextCompat.startForegroundService(this@JarvisOverlayService, Intent(this@JarvisOverlayService, WakeWordService::class.java).apply { action = WakeWordService.ACTION_ARM }) }
                showText("", "Te escucho…")
            }
        }, LinearLayout.LayoutParams(dp(54), dp(48)).apply { marginStart = dp(6) })
        composer.addView(Button(this).apply {
            text = "➤"; textSize = 18f
            setOnClickListener { val q = box.text?.toString().orEmpty().trim(); if (q.isNotBlank()) handleCommand(q) }
        }, LinearLayout.LayoutParams(dp(54), dp(48)).apply { marginStart = dp(6) })
        outer.addView(composer)
        outer.addView(TextView(this).apply { text="Toca fuera del campo para abrir Jarvis   ·   Mantén pulsado para cerrar"; textSize=11f; setTextColor(Color.rgb(105,109,122)); setPadding(0,dp(9),0,0) })
        outer.setOnClickListener {
            runCatching { startActivity(Intent(this, MainActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP); putExtra("hands_free", true); if(userText.isNotBlank()) putExtra("overlay_command", userText) }) }
        }
        outer.setOnLongClickListener { hide(); true }
        root=outer
        val type=if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        val lp=WindowManager.LayoutParams(dp(390),WindowManager.LayoutParams.WRAP_CONTENT,type,flags,PixelFormat.TRANSLUCENT).apply { gravity=Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y=dp(48); softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE }
        runCatching { wm?.addView(outer,lp) }.onFailure { hide() }
    }
'''
if old in s:
    s=s.replace(old,new,1)
elif 'Escribe a Jarvis…' not in s:
    raise SystemExit('attachOverlay anchor not found')
else:
    s=s.replace('singleLine = true','setSingleLine(true)')
if 'import androidx.core.content.ContextCompat' not in s:
    s=s.replace('import android.widget.TextView\n','import android.widget.TextView\nimport androidx.core.content.ContextCompat\n')
p.write_text(s)

p=Path('mobile/src/main/java/com/jarvis/mobile/WakeWordService.kt')
s=p.read_text()
old='''    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY'''
new='''    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_ARM) {
            armedUntil = System.currentTimeMillis() + 10000L
            showOverlay("Te escucho…")
        }
        return START_STICKY
    }'''
if old in s:
    s=s.replace(old,new,1)
if 'const val ACTION_ARM=' not in s:
    s=s.replace('''    companion object { private const val CHANNEL = "jarvis_wake_word" }''','''    companion object { const val ACTION_ARM="com.jarvis.mobile.wake.ARM"; private const val CHANNEL = "jarvis_wake_word" }''')
p.write_text(s)
print('Overlay text composer, mic arming and best-effort lockscreen visibility applied')
