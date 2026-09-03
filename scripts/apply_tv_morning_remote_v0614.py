from pathlib import Path

# ---------------------------------------------------------------------------
# MobileRemoteClient: register the TV host in Jarvis Mobile after pairing.
# ---------------------------------------------------------------------------
p=Path('app/src/main/java/com/jarvis/tv/MobileRemoteClient.kt')
s=p.read_text()
anchor='    fun ping(): JSONObject = get("/ping")\n'
if 'fun registerTv(' not in s:
    extra='    fun registerTv(host: String): JSONObject = get("/register-tv?host=${URLEncoder.encode(host, "UTF-8")}", auth = true)\n'
    if anchor not in s: raise SystemExit('MobileRemoteClient ping anchor missing')
    s=s.replace(anchor,anchor+extra,1)
p.write_text(s)

# ---------------------------------------------------------------------------
# MainActivity: local IP helper + reverse registration during pairing + keep local
# command server alive whenever Jarvis TV is opened.
# ---------------------------------------------------------------------------
p=Path('app/src/main/java/com/jarvis/tv/MainActivity.kt')
s=p.read_text()
if 'import java.net.NetworkInterface' not in s:
    s=s.replace('import java.net.HttpURLConnection\n','import java.net.HttpURLConnection\nimport java.net.NetworkInterface\n',1)
helper_anchor='    private fun assistantName() = prefs.getString("assistantName", "Jarvis") ?: "Jarvis"\n'
if 'private fun localTvIp()' not in s:
    helper=r'''    private fun localTvIp(): String {
        return runCatching {
            NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains(':') == false }
                ?.hostAddress.orEmpty()
        }.getOrDefault("")
    }

'''
    if helper_anchor not in s: raise SystemExit('assistantName anchor missing')
    s=s.replace(helper_anchor,helper+helper_anchor,1)

# Pairing patch contains this exact success sequence.
old='''                        runCatching { mobileRemote.ping() }
                        runOnUiThread { Toast.makeText(this@MainActivity, "Jarvis Mobile emparejado ✓", Toast.LENGTH_LONG).show() }'''
new='''                        runCatching { mobileRemote.ping() }
                        localTvIp().takeIf { it.isNotBlank() }?.let { host -> runCatching { mobileRemote.registerTv(host) } }
                        runOnUiThread { Toast.makeText(this@MainActivity, "Jarvis Mobile emparejado ✓ · TV registrada para rutinas", Toast.LENGTH_LONG).show() }'''
if old in s:
    s=s.replace(old,new,1)

onresume='''    override fun onResume() {
        super.onResume()
'''
if onresume in s and 'TvCommandService::class.java' not in s[s.find(onresume):s.find(onresume)+500]:
    s=s.replace(onresume,onresume+'        runCatching { ContextCompat.startForegroundService(this, Intent(this, TvCommandService::class.java)) }\n',1)

# Wake screen when a routine opens Jarvis.
oncreate='''        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)'''
if oncreate in s and 'setTurnScreenOn(true)' not in s:
    replacement='''        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setTurnScreenOn(true)
            setShowWhenLocked(true)
        }
        setContentView(R.layout.activity_main)'''
    s=s.replace(oncreate,replacement,1)
p.write_text(s)

# ---------------------------------------------------------------------------
# Boot receiver keeps the local control server alive.
# ---------------------------------------------------------------------------
p=Path('app/src/main/java/com/jarvis/tv/BootReceiver.kt')
b=p.read_text()
if 'TvCommandService::class.java' not in b:
    anchor='''        // Some Android TV builds restrict microphone foreground services directly
'''
    block='''        runCatching {
            ContextCompat.startForegroundService(context, Intent(context, TvCommandService::class.java))
        }
'''
    if anchor not in b: raise SystemExit('BootReceiver anchor missing')
    b=b.replace(anchor,block+anchor,1)
p.write_text(b)

# ---------------------------------------------------------------------------
# Manifest permissions/service.
# ---------------------------------------------------------------------------
p=Path('app/src/main/AndroidManifest.xml')
x=p.read_text()
if 'android.permission.WAKE_LOCK' not in x:
    x=x.replace('    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />','    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />\n    <uses-permission android:name="android.permission.WAKE_LOCK" />',1)
service_anchor='''        <service
            android:name=".WakeWordService"'''
if 'android:name=".TvCommandService"' not in x:
    block='''        <service
            android:name=".TvCommandService"
            android:enabled="true"
            android:exported="false"
            android:stopWithTask="false"
            android:foregroundServiceType="specialUse">
            <property
                android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                android:value="Local paired-phone command listener for user scheduled Jarvis TV briefings" />
        </service>

'''
    if service_anchor not in x: raise SystemExit('TV manifest service anchor missing')
    x=x.replace(service_anchor,block+service_anchor,1)
p.write_text(x)
print('TV morning routine reverse bridge applied')
