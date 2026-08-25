from pathlib import Path
import re

p=Path('mobile/src/main/java/com/jarvis/mobile/MainActivity.kt')
s=p.read_text()
mp=Path('mobile/src/main/AndroidManifest.xml')
ms=mp.read_text()

def replace_fun(src,name,new):
    sig=f'    private fun {name}('
    start=src.find(sig)
    if start<0: return src,False
    nxt=src.find('\n    private fun ',start+len(sig))
    if nxt<0: return src,False
    return src[:start]+new.rstrip()+"\n\n"+src[nxt+1:],True

new_begin=r'''    private fun beginHomeConnectLogin() {
        status.text="Home Connect · preparando inicio de sesión…"
        Thread{
            try{
                val state=UUID.randomUUID().toString()
                prefs.edit().putString("homeconnect_oauth_state",state).apply()
                val j=hcBackend("start",JSONObject().put("state",state))
                val url=j.optString("authorization_url")
                if(url.isBlank()) throw IllegalStateException(j.optString("error").ifBlank{"Home Connect no devolvió URL de autorización"})
                runOnUiThread{
                    status.text="Home Connect · inicia sesión y autoriza Jarvis"
                    startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)))
                }
            }catch(e:Throwable){runOnUiThread{status.text="Jarvis listo";Toast.makeText(this,"Home Connect: ${e.message}",Toast.LENGTH_LONG).show()}}
        }.start()
    }'''
s,ok=replace_fun(s,'beginHomeConnectLogin',new_begin)
if not ok: raise SystemExit('beginHomeConnectLogin not found')

new_complete=r'''    private fun completeHomeConnectLogin() {
        Toast.makeText(this,"Termina el inicio de sesión en la página de Home Connect. Jarvis volverá automáticamente al finalizar.",Toast.LENGTH_LONG).show()
    }'''
s,_=replace_fun(s,'completeHomeConnectLogin',new_complete)

marker='    private fun jsonArrayStrings(a: JSONArray?): List<String> {'
methods=r'''    private fun handleHomeConnectOAuthIntent(i: Intent?) {
        val u=i?.data ?: return
        if(!u.scheme.equals("jarvis",true)||!u.host.equals("homeconnect",true)) return
        val error=u.getQueryParameter("error").orEmpty()
        if(error.isNotBlank()){ Toast.makeText(this,"Home Connect: $error",Toast.LENGTH_LONG).show(); return }
        val state=u.getQueryParameter("state").orEmpty()
        val expected=prefs.getString("homeconnect_oauth_state","").orEmpty()
        if(expected.isNotBlank()&&state.isNotBlank()&&state!=expected){Toast.makeText(this,"Home Connect: respuesta OAuth no válida",Toast.LENGTH_LONG).show();return}
        val code=u.getQueryParameter("code").orEmpty(); if(code.isBlank()) return
        status.text="Home Connect · completando autorización…"
        Thread{
            try{
                val j=hcBackend("exchange",JSONObject().put("code",code))
                val access=j.optString("access_token"); val refresh=j.optString("refresh_token")
                if(access.isBlank()||refresh.isBlank()) throw IllegalStateException(j.optString("error_description").ifBlank{j.optString("error").ifBlank{"No se recibieron tokens"}})
                prefs.edit().putString("homeconnect_access_token",access).putString("homeconnect_refresh_token",refresh)
                    .putLong("homeconnect_access_expires",System.currentTimeMillis()+j.optLong("expires_in",86400L)*1000L-60000L)
                    .remove("homeconnect_oauth_state").apply()
                runOnUiThread{status.text="Jarvis listo";Toast.makeText(this,"Home Connect conectado",Toast.LENGTH_LONG).show();showUnifiedDomoticsWidget()}
            }catch(e:Throwable){runOnUiThread{status.text="Jarvis listo";Toast.makeText(this,"Home Connect: ${e.message}",Toast.LENGTH_LONG).show()}}
        }.start()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleHomeConnectOAuthIntent(intent)
    }

'''
if 'handleHomeConnectOAuthIntent' not in s:
    if marker not in s: raise SystemExit('marker not found')
    s=s.replace(marker,methods+marker,1)

# Process callback after views/prefs are initialized.
needle='        warmLocation()\n'
if 'handleHomeConnectOAuthIntent(intent)' not in s and needle in s:
    s=s.replace(needle,needle+'        handleHomeConnectOAuthIntent(intent)\n',1)

# Deep-link callback from Vercel.
if 'android:scheme="jarvis"' not in ms:
    target='''            <intent-filter>\n                <action android:name="android.intent.action.MAIN" />\n                <category android:name="android.intent.category.LAUNCHER" />\n            </intent-filter>'''
    repl=target+'''\n            <intent-filter>\n                <action android:name="android.intent.action.VIEW" />\n                <category android:name="android.intent.category.DEFAULT" />\n                <category android:name="android.intent.category.BROWSABLE" />\n                <data android:scheme="jarvis" android:host="homeconnect" />\n            </intent-filter>'''
    ms=ms.replace(target,repl,1)

p.write_text(s);mp.write_text(ms)
print('Home Connect authorization-code OAuth patch applied')
