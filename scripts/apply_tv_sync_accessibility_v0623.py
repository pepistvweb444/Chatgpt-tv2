from pathlib import Path

def replace_function(text, signature, replacement):
    start=text.find(signature)
    if start<0:
        raise SystemExit(f'{signature} not found')
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
                if depth==0:
                    return text[:start]+replacement+text[i+1:]
    raise SystemExit(f'end not found for {signature}')

p=Path('app/src/main/java/com/jarvis/tv/MainActivity.kt')
s=p.read_text()

for imp in [
    'import android.content.ComponentName',
    'import android.text.InputType',
]:
    if imp not in s:
        s=s.replace('import android.content.Context\n', 'import android.content.Context\n'+imp+'\n',1)

helpers=r'''    private fun accessibilityServiceComponent(): String =
        ComponentName(this, JarvisAccessibilityService::class.java).flattenToString()

    private fun isJavistvAccessibilityEnabled(): Boolean {
        val enabled=Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        val full=accessibilityServiceComponent()
        return enabled.split(':').any {
            it.equals(full,true) ||
            (it.contains("com.jarvis.tv",true) && it.contains("JarvisAccessibilityService",true))
        }
    }

    private fun hasSecureSettingsGrant(): Boolean =
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.WRITE_SECURE_SETTINGS
        ) == PackageManager.PERMISSION_GRANTED

    private fun setJavistvAccessibilityEnabled(enabled:Boolean): Boolean {
        if(!hasSecureSettingsGrant()) return false
        val full=accessibilityServiceComponent()
        val current=Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        val services=linkedSetOf<String>()
        current.split(':').map{it.trim()}.filter{it.isNotBlank()}.forEach{services.add(it)}
        services.removeAll {
            it.contains("com.jarvis.tv",true) && it.contains("JarvisAccessibilityService",true)
        }
        if(enabled) services.add(full)
        val ok1=Settings.Secure.putString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            services.joinToString(":")
        )
        val ok2=Settings.Secure.putInt(
            contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            if(services.isEmpty()) 0 else 1
        )
        return ok1 && ok2
    }

    private fun showFireAccessibilityHelp() {
        val cmd="adb shell pm grant com.jarvis.tv android.permission.WRITE_SECURE_SETTINGS"
        AlertDialog.Builder(this)
            .setTitle("Fire TV · activar burbuja")
            .setMessage(
                "Este Fire OS no muestra servicios de terceros en su menú de Accesibilidad.\\n\\n" +
                "Concede una sola vez este permiso por ADB:\\n\\n$cmd\\n\\n" +
                "Después vuelve a Javistv > Ajustes y pulsa ACTIVAR SERVICIO JAVISTV. " +
                "La app añadirá su servicio sin borrar VoiceView ni otras ayudas de Amazon."
            )
            .setPositiveButton("OK",null)
            .show()
    }

'''
anchor='    private fun edit(hint: String, value: String)'
if 'private fun accessibilityServiceComponent()' not in s:
    if anchor not in s: raise SystemExit('edit anchor missing')
    s=s.replace(anchor,helpers+anchor,1)

settings=r'''    private fun showSettings() {
        val box=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(34,18,34,24) }
        val scroll=android.widget.ScrollView(this).apply { addView(box) }

        fun header(text:String)=TextView(this).apply {
            this.text=text; textSize=21f; setPadding(4,16,4,8)
        }

        // --- Teléfono ---
        box.addView(header("Sincronización con teléfono"))
        box.addView(TextView(this).apply {
            text="Estos datos son distintos del backend de IA. El teléfono debe estar en la misma Wi‑Fi y tener Jarvis Mobile con el puente TV activado."
            textSize=14f; setPadding(4,0,4,10)
        })
        val mobileHost=edit(
            "IP o nombre del teléfono · ej. 192.168.1.25",
            prefs.getString("mobile_remote_host","").orEmpty()
        )
        val mobilePin=edit("PIN de 4 dígitos de Jarvis Mobile","").apply {
            inputType=InputType.TYPE_CLASS_NUMBER
        }
        val mobileStatus=TextView(this).apply {
            textSize=16f; setPadding(4,8,4,10)
            text="Teléfono: "+if(mobileRemote.configured())"EMPAREJADO ✓" else "NO EMPAREJADO"+
                "\nDirección: "+prefs.getString("mobile_remote_host","").orEmpty().ifBlank{"sin configurar"}+
                "\nPuerto: 8765"
        }
        val pairButton=Button(this).apply {
            text="EMPAREJAR CON TELÉFONO"
            setOnClickListener {
                val host=mobileHost.text.toString().trim()
                val pin=mobilePin.text.toString().trim()
                if(host.isBlank()) {
                    Toast.makeText(this@MainActivity,"Escribe la IP del teléfono",Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                prefs.edit().putString("mobile_remote_host",host).apply()
                mobileStatus.text="Teléfono: emparejando…"
                Thread {
                    runCatching {
                        mobileRemote.pair(pin)
                        val ping=mobileRemote.ping()
                        val agenda=mobileRemote.agenda()
                        Triple(ping,agenda,mobileRemote.permissions())
                    }.onSuccess { (_,agenda,permissions) ->
                        runOnUiThread {
                            val source=agenda.optString("source").ifBlank{"sin fuente"}
                            val calOk=agenda.optBoolean("calendarPermission")
                            val gCount=agenda.optInt("googleCalendarCount",agenda.optInt("calendarCount",0))
                            val events=agenda.optInt("eventCount",0)
                            val err=agenda.optString("queryError")
                            mobileStatus.text=
                                "Teléfono: EMPAREJADO ✓"+
                                "\nDirección: $host:8765"+
                                "\nGoogle Calendar: "+if(calOk && gCount>0)"OK ✓ ($gCount calendario/s)" else "NO DISPONIBLE"+
                                "\nEventos recibidos: $events"+
                                "\nFuente: $source"+
                                (if(err.isNotBlank())"\nError: $err" else "")+
                                "\nSMS: "+if(permissions.optBoolean("readSms"))"permiso ✓" else "sin permiso"
                        }
                    }.onFailure { e ->
                        runOnUiThread {
                            mobileStatus.text="Teléfono: ERROR\n"+(e.message?:e.javaClass.simpleName)
                        }
                    }
                }.start()
            }
        }
        val testPhoneButton=Button(this).apply {
            text="PROBAR SINCRONIZACIÓN / CALENDARIO"
            setOnClickListener {
                val host=mobileHost.text.toString().trim()
                if(host.isNotBlank())prefs.edit().putString("mobile_remote_host",host).apply()
                if(!mobileRemote.configured()) {
                    mobileStatus.text="Teléfono: primero debes EMPAREJAR con IP + PIN."
                    return@setOnClickListener
                }
                mobileStatus.text="Teléfono: comprobando…"
                Thread {
                    runCatching {
                        val ping=mobileRemote.ping()
                        val agenda=mobileRemote.agenda()
                        val messages=mobileRemote.unreadMessages()
                        Triple(ping,agenda,messages)
                    }.onSuccess { (_,agenda,messages) ->
                        runOnUiThread {
                            val events=agenda.optInt("eventCount",0)
                            val gCount=agenda.optInt("googleCalendarCount",agenda.optInt("calendarCount",0))
                            val source=agenda.optString("source").ifBlank{"sin fuente"}
                            val msgCount=(messages.optJSONArray("items")?:messages.optJSONArray("messages"))?.length()?:0
                            val err=agenda.optString("queryError")
                            mobileStatus.text=
                                "Teléfono: CONECTADO ✓"+
                                "\nGoogle Calendar: "+if(agenda.optBoolean("calendarPermission") && gCount>0)"SINCRONIZADO ✓" else "NO SINCRONIZADO"+
                                "\nEventos: $events · Mensajes: $msgCount"+
                                "\nFuente calendario: $source"+
                                (if(err.isNotBlank())"\nError: $err" else "")
                        }
                    }.onFailure { e ->
                        runOnUiThread {
                            mobileStatus.text="Teléfono: ERROR\n"+(e.message?:e.javaClass.simpleName)
                        }
                    }
                }.start()
            }
        }
        val forgetPhoneButton=Button(this).apply {
            text="OLVIDAR TELÉFONO EMPAREJADO"
            setOnClickListener {
                mobileRemote.forget()
                mobileHost.setText("")
                mobilePin.setText("")
                mobileStatus.text="Teléfono: NO EMPAREJADO"
            }
        }
        box.addView(mobileStatus);box.addView(mobileHost);box.addView(mobilePin)
        box.addView(pairButton);box.addView(testPhoneButton);box.addView(forgetPhoneButton)

        // --- IA ---
        box.addView(header("Backend de IA"))
        val backend=edit(
            "Backend IA / Vercel",
            prefs.getString("backendUrl",DEFAULT_BACKEND).orEmpty().ifBlank{DEFAULT_BACKEND}
        )
        box.addView(TextView(this).apply {
            text="Esta URL es para ChatGPT/transcripción/traducción. NO es la dirección del teléfono."
            textSize=14f
        })
        box.addView(backend)
        box.addView(Button(this).apply {
            text="PROBAR BACKEND IA"
            setOnClickListener { testBackend(backend.text.toString()) }
        })

        // --- Accesibilidad/burbuja ---
        box.addView(header("Burbuja y texto flotante"))
        val accessStatus=TextView(this).apply {
            textSize=16f;setPadding(4,4,4,10)
        }
        fun refreshAccess() {
            accessStatus.text=
                "Servicio Javistv: "+if(isJavistvAccessibilityEnabled())"ACTIVO ✓" else "APAGADO"+
                "\nConectado: "+if(getSharedPreferences("jarvis_tv",MODE_PRIVATE).getBoolean("accessibility_connected",false))"SÍ ✓" else "NO"+
                "\nPermiso ADB seguro: "+if(hasSecureSettingsGrant())"CONCEDIDO ✓" else "NO"+
                "\nOverlay normal: "+if(Build.VERSION.SDK_INT<Build.VERSION_CODES.M || Settings.canDrawOverlays(this))"PERMITIDO ✓" else "NO"
        }
        refreshAccess()
        box.addView(accessStatus)
        box.addView(Button(this).apply {
            text="ACTIVAR SERVICIO JAVISTV"
            setOnClickListener {
                if(!hasSecureSettingsGrant()) {
                    showFireAccessibilityHelp()
                } else {
                    val ok=setJavistvAccessibilityEnabled(true)
                    handler.postDelayed({refreshAccess()},500)
                    Toast.makeText(this@MainActivity,if(ok)"Accesibilidad Javistv activada":"No se pudo activar",Toast.LENGTH_LONG).show()
                }
            }
        })
        box.addView(Button(this).apply {
            text="DESACTIVAR SERVICIO JAVISTV"
            setOnClickListener {
                if(hasSecureSettingsGrant())setJavistvAccessibilityEnabled(false)
                handler.postDelayed({refreshAccess()},400)
            }
        })
        box.addView(Button(this).apply {
            text="ABRIR ACCESIBILIDAD DE FIRE TV"
            setOnClickListener { openAccessibilitySettings() }
        })
        box.addView(Button(this).apply {
            text="BURBUJA NORMAL / PERMISO OVERLAY"
            setOnClickListener { ensureOverlayPermission(true) }
        })
        box.addView(TextView(this).apply {
            text="En algunos Fire OS Amazon oculta los servicios de accesibilidad de terceros. El ajuste 'Banner de texto' que aparece en Fire TV es una función del sistema y no es el servicio de Javistv."
            textSize=13f;setPadding(4,8,4,10)
        })

        // --- Sueño ---
        box.addView(header("Webcam y detección de sueño"))
        val homey=edit(
            "Webhook Homey · probable_sleep",
            prefs.getString("homeySofaWebhook","").orEmpty()
        )
        val sofaStatus=TextView(this).apply {
            text="Detección: "+if(prefs.getBoolean("sofaVisionEnabled",false))"ACTIVA ✓" else "APAGADA"+
                "\n"+prefs.getString("sofaVisionStatus","sin iniciar")
            textSize=16f
        }
        box.addView(sofaStatus);box.addView(homey)
        box.addView(Button(this).apply {
            text="ACTIVAR WEBCAM + DETECCIÓN"
            setOnClickListener {
                enableSofaVision(homey.text.toString())
                handler.postDelayed({
                    sofaStatus.text="Detección: ACTIVA ✓\n"+prefs.getString("sofaVisionStatus","iniciando…")
                },600)
            }
        })
        box.addView(Button(this).apply {
            text="DESACTIVAR WEBCAM / DETECTOR"
            setOnClickListener {
                disableSofaVision();sofaStatus.text="Detección: APAGADA"
            }
        })

        // --- Traducción ---
        box.addView(header("Doblaje / traducción"))
        val translationStatus=TextView(this).apply {
            text="Estado: "+prefs.getString("translation_status","detenida")+
                "\nIdioma: "+prefs.getString("translation_target","es")
            textSize=16f
        }
        box.addView(translationStatus)
        box.addView(Button(this).apply {
            text="ACTIVAR DOBLAJE"
            setOnClickListener {
                startTranslation(prefs.getString("translation_target","es")?:"es")
                handler.postDelayed({
                    translationStatus.text="Estado: "+prefs.getString("translation_status","iniciando")+
                        "\nIdioma: "+prefs.getString("translation_target","es")
                },600)
            }
        })
        box.addView(Button(this).apply {
            text="DETENER DOBLAJE"
            setOnClickListener { stopTranslation();translationStatus.text="Estado: detenida" }
        })

        // --- General ---
        box.addView(header("General"))
        val name=edit("Nombre del asistente",assistantName())
        val wake=edit("Palabra de activación",wakeWord())
        box.addView(name);box.addView(wake)
        box.addView(Button(this).apply {
            text="PROBAR MICRÓFONO / TRANSCRIPCIÓN"
            setOnClickListener { startServerVoiceCapture() }
        })
        box.addView(Button(this).apply {
            text="VER CONEXIONES"
            setOnClickListener { showConnections() }
        })
        box.addView(TextView(this).apply {
            text="\nDispositivo: "+Build.MANUFACTURER+" "+Build.MODEL+
                "\nEntradas de audio:\n"+audioInputs()
            textSize=14f
        })

        AlertDialog.Builder(this)
            .setTitle("Ajustes de Javistv v0.6.23")
            .setView(scroll)
            .setPositiveButton("GUARDAR"){_,_->
                prefs.edit()
                    .putString("assistantName",name.text.toString().trim().ifBlank{"Jarvis"})
                    .putString("wakeWord",wake.text.toString().trim().ifBlank{"Hola Jarvis"})
                    .putString("backendUrl",backend.text.toString().trim().ifBlank{DEFAULT_BACKEND})
                    .putString("mobile_remote_host",mobileHost.text.toString().trim())
                    .putString("homeySofaWebhook",homey.text.toString().trim())
                    .apply()
            }
            .setNegativeButton("CERRAR",null)
            .show()
    }'''
s=replace_function(s,'    private fun showSettings()',settings)

# Keep source XML strong even if an earlier patch reintroduced the restrictive value.
p.write_text(s)

x=Path('app/src/main/res/xml/accessibility_service_config.xml')
if x.exists():
    a=x.read_text()
    a=a.replace('android:canRetrieveWindowContent="false"','android:canRetrieveWindowContent="true"')
    if 'android:accessibilityFlags=' not in a:
        a=a.replace('android:notificationTimeout="100"', 'android:notificationTimeout="100"\\n    android:accessibilityFlags="flagReportViewIds|flagRetrieveInteractiveWindows"')
    x.write_text(a)

m=Path('app/src/main/AndroidManifest.xml')
ms=m.read_text()
ms=ms.replace('android:label="Javistv bubble"','android:label="Javistv · burbuja y traducción"')
m.write_text(ms)

print('Javistv 0.6.23 phone sync and Fire accessibility settings applied')
