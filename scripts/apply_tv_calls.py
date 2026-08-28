from pathlib import Path

# Add call endpoints to the mobile remote client.
p=Path('app/src/main/java/com/jarvis/tv/MobileRemoteClient.kt')
s=p.read_text()
if 'fun incomingCall()' not in s:
    s=s.replace('''    fun agenda(): JSONObject = get("/agenda", auth = true)''', '''    fun agenda(): JSONObject = get("/agenda", auth = true)

    fun incomingCall(): JSONObject {
        val raw = get("/incoming-call", auth = true)
        val nested = raw.optJSONObject("call")
        return if (nested != null) JSONObject(nested.toString()).put("kind", raw.optString("kind")) else raw
    }

    fun callAction(action: String): JSONObject = get("/call-action?action=${URLEncoder.encode(action, "UTF-8")}", auth = true)''')
p.write_text(s)

# Poll the paired mobile while Jarvis TV is visible and show the same call decision card.
p=Path('app/src/main/java/com/jarvis/tv/MainActivity.kt')
s=p.read_text()
if 'import android.graphics.BitmapFactory' not in s:
    s=s.replace('import android.content.pm.PackageManager\n', 'import android.content.pm.PackageManager\nimport android.graphics.BitmapFactory\nimport android.util.Base64\nimport android.view.Gravity\nimport android.widget.ImageView\n')

field='''    private var conversationId: String = ""\n'''
if 'private var activeCallDialog' not in s:
    s=s.replace(field,field+'''    private var activeCallDialog: AlertDialog? = null
    private var shownCallId: String = ""
    private var ignoredCallId: String = ""
    private val remoteCalls by lazy { MobileRemoteClient(this) }
    private val callPoller = object : Runnable {
        override fun run() {
            pollIncomingCall()
            handler.postDelayed(this, 1500L)
        }
    }
''')

old_resume='''    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) startBubbleService()
    }'''
new_resume='''    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) startBubbleService()
        handler.removeCallbacks(callPoller)
        handler.post(callPoller)
    }

    override fun onPause() {
        handler.removeCallbacks(callPoller)
        super.onPause()
    }'''
if old_resume in s:
    s=s.replace(old_resume,new_resume,1)

marker='''    private fun bindUi() {'''
methods=r'''    private fun pollIncomingCall() {
        if (!remoteCalls.configured()) return
        Thread {
            val call = runCatching { remoteCalls.incomingCall() }.getOrNull() ?: return@Thread
            runOnUiThread {
                val active = call.optBoolean("active", false) && call.optString("state") == "ringing"
                if (!active) {
                    activeCallDialog?.dismiss(); activeCallDialog=null; shownCallId=""
                    return@runOnUiThread
                }
                val id = call.optString("id")
                if (id.isBlank() || id == ignoredCallId || (id == shownCallId && activeCallDialog?.isShowing == true)) return@runOnUiThread
                shownCallId=id
                showIncomingCallFromPhone(call)
            }
        }.start()
    }

    private fun showIncomingCallFromPhone(call: JSONObject) {
        activeCallDialog?.dismiss()
        val root = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER_HORIZONTAL; setPadding(36,26,36,18) }
        val photoData=call.optString("photoData")
        if(photoData.isNotBlank()) {
            runCatching {
                val bytes=Base64.decode(photoData,Base64.DEFAULT)
                root.addView(ImageView(this).apply { layoutParams=LinearLayout.LayoutParams(190,190).apply{bottomMargin=18}; scaleType=ImageView.ScaleType.CENTER_CROP; setImageBitmap(BitmapFactory.decodeByteArray(bytes,0,bytes.size)) })
            }
        }
        val name=call.optString("name").ifBlank{call.optString("number").ifBlank{"Llamada entrante"}}
        root.addView(TextView(this).apply { text=name; textSize=28f; gravity=Gravity.CENTER })
        val detail=when(call.optString("classification")) {
            "spam_probable" -> "⚠ Spam probable · ${call.optInt("spamScore",0)}%"
            "possible_spam" -> "Posible spam"
            "contact" -> if(call.optBoolean("priority",false)) "★ Contacto prioritario" else "Contacto"
            else -> "Número o usuario desconocido"
        }
        root.addView(TextView(this).apply {
            text=buildString { append(detail); call.optString("number").takeIf{it.isNotBlank()&&it!=name}?.let{append("\n").append(it)}; call.optString("app").takeIf{it.isNotBlank()}?.let{append("\n").append(it)}; call.optString("spamSources").takeIf{it.isNotBlank()}?.let{append("\nFuentes: ").append(it)}; if(call.optBoolean("video",false)) append("\nVideollamada · tu cámara sigue desactivada hasta que aceptes") }
            textSize=17f; gravity=Gravity.CENTER; setPadding(0,12,0,0)
        })
        activeCallDialog=AlertDialog.Builder(this)
            .setTitle("Jarvis · llamada entrante")
            .setView(root)
            .setPositiveButton("Contestar") { _,_-> sendTvCallAction("answer") }
            .setNegativeButton("Rechazar") { _,_-> sendTvCallAction("reject") }
            .setNeutralButton("Dejar sonar") { _,_-> ignoredCallId=call.optString("id") }
            .setOnCancelListener { ignoredCallId=call.optString("id") }
            .create().also{it.show()}
    }

    private fun sendTvCallAction(action:String) {
        Thread {
            val result=runCatching{remoteCalls.callAction(action)}
            runOnUiThread {
                activeCallDialog?.dismiss(); activeCallDialog=null
                Toast.makeText(this,result.getOrNull()?.optString("message").orEmpty().ifBlank{if(result.isSuccess)"Acción enviada al móvil" else "No se pudo controlar la llamada"},Toast.LENGTH_LONG).show()
            }
        }.start()
    }

'''
if 'private fun pollIncomingCall()' not in s:
    if marker not in s: raise SystemExit('TV bindUi marker not found')
    s=s.replace(marker,methods+marker,1)
p.write_text(s)
print('TV incoming-call mirror and controls applied')
