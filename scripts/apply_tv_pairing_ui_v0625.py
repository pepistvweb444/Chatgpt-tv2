from pathlib import Path

p=Path("app/src/main/java/com/jarvis/tv/MainActivity.kt")
s=p.read_text()

# Make phone pairing and Accessibility directly reachable from the TV side menu.
bind_anchor='        input.setOnEditorActionListener { _, _, _ -> sendMessage(); true }'
if 'R.id.pairPhoneButton' not in s:
    if bind_anchor not in s:
        raise SystemExit("bindUi anchor missing")
    s=s.replace(
        bind_anchor,
        '''        findViewById<Button>(R.id.pairPhoneButton).setOnClickListener {
            findViewById<LinearLayout>(R.id.tvSideMenu).visibility = android.view.View.GONE
            showPhonePairing()
        }
        findViewById<Button>(R.id.accessibilitySetupButton).setOnClickListener {
            findViewById<LinearLayout>(R.id.tvSideMenu).visibility = android.view.View.GONE
            showAccessibilityPanel()
        }
''' + bind_anchor,
        1
    )

helpers=r'''    private fun showPhonePairing() {
        val box=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(34,18,34,24)
        }
        val scroll=android.widget.ScrollView(this).apply { addView(box) }
        val intro=TextView(this).apply {
            text="En el teléfono abre Jarvis Mobile → Control del teléfono → PREPARAR SINCRONIZACIÓN CON JAVISTV. Introduce aquí la IP y el PIN de 4 dígitos que muestra el móvil."
            textSize=15f
            setPadding(4,0,4,14)
        }
        val host=edit(
            "Dirección del teléfono · ej. 192.168.1.25 o http://192.168.1.25:8765",
            prefs.getString("mobile_remote_host","").orEmpty()
        )
        val pin=edit("PIN de 4 dígitos","").apply {
            inputType=android.text.InputType.TYPE_CLASS_NUMBER
        }
        val statusView=TextView(this).apply {
            textSize=16f
            setPadding(4,12,4,12)
        }
        fun shownUrl():String {
            val raw=host.text.toString().trim()
            if(raw.isBlank()) return "sin configurar"
            val noScheme=raw.removePrefix("http://").removePrefix("https://").trimEnd('/')
            val withPort=if(Regex(""".+:\\d+$""").matches(noScheme)) noScheme else "$noScheme:8765"
            return "http://$withPort"
        }
        fun refresh(extra:String="") {
            val paired=mobileRemote.configured()
            statusView.text=buildString {
                append("Estado: "); append(if(paired) "EMPAREJADO ✓" else "NO EMPAREJADO")
                append("\nURL TV → móvil: "); append(shownUrl())
                append("\nPuerto: 8765")
                if(extra.isNotBlank()) { append("\n"); append(extra) }
            }
        }
        val pair=Button(this).apply {
            text="EMPAREJAR TV CON TELÉFONO"
            setOnClickListener {
                val hostValue=host.text.toString().trim()
                val pinValue=pin.text.toString().trim()
                if(hostValue.isBlank()) {
                    Toast.makeText(this@MainActivity,"Introduce la IP del teléfono",Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                if(!pinValue.matches(Regex("""\\d{4}"""))) {
                    Toast.makeText(this@MainActivity,"El PIN debe tener 4 dígitos",Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                prefs.edit().putString("mobile_remote_host",hostValue).apply()
                refresh("Emparejando…")
                Thread {
                    runCatching {
                        mobileRemote.pair(pinValue)
                        mobileRemote.ping()
                    }.onSuccess {
                        runOnUiThread {
                            pin.setText("")
                            refresh("Conexión comprobada ✓")
                            Toast.makeText(this@MainActivity,"TV y teléfono enlazados",Toast.LENGTH_LONG).show()
                        }
                    }.onFailure { e ->
                        runOnUiThread { refresh("ERROR: "+(e.message?:e.javaClass.simpleName)) }
                    }
                }.start()
            }
        }
        val test=Button(this).apply {
            text="PROBAR CONEXIÓN"
            setOnClickListener {
                val hostValue=host.text.toString().trim()
                if(hostValue.isNotBlank()) prefs.edit().putString("mobile_remote_host",hostValue).apply()
                if(!mobileRemote.configured()) {
                    refresh("Falta emparejar con IP + PIN")
                    return@setOnClickListener
                }
                refresh("Comprobando…")
                Thread {
                    runCatching {
                        val ping=mobileRemote.ping()
                        val agenda=mobileRemote.agenda()
                        Pair(ping,agenda)
                    }.onSuccess { (_,agenda) ->
                        runOnUiThread {
                            val events=agenda.optInt("eventCount",0)
                            val cal=agenda.optBoolean("calendarPermission",false)
                            refresh("Móvil conectado ✓ · Calendario " + (if (cal) "OK ($events eventos)" else "sin permiso"))
                        }
                    }.onFailure { e ->
                        runOnUiThread { refresh("ERROR: "+(e.message?:e.javaClass.simpleName)) }
                    }
                }.start()
            }
        }
        val forget=Button(this).apply {
            text="OLVIDAR TELÉFONO"
            setOnClickListener {
                mobileRemote.forget()
                host.setText("")
                pin.setText("")
                refresh()
            }
        }
        box.addView(intro)
        box.addView(statusView)
        box.addView(host)
        box.addView(pin)
        box.addView(pair)
        box.addView(test)
        box.addView(forget)
        refresh()

        AlertDialog.Builder(this)
            .setTitle("Vincular Javistv con el teléfono")
            .setView(scroll)
            .setNegativeButton("CERRAR",null)
            .show()
    }

    private fun showAccessibilityPanel() {
        val box=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(34,18,34,24)
        }
        val statusView=TextView(this).apply {
            textSize=16f
            setPadding(4,0,4,12)
        }
        fun refresh() {
            val jp=getSharedPreferences("jarvis_tv",MODE_PRIVATE)
            statusView.text=buildString {
                append("Servicio Javistv: "); append(if(isJavistvAccessibilityEnabled()) "ACTIVO ✓" else "APAGADO")
                append("\nServicio conectado: "); append(if(jp.getBoolean("accessibility_connected",false)) "SÍ ✓" else "NO")
                append("\nApp visible ahora: "); append(jp.getString("foreground_package","").orEmpty().ifBlank{"sin datos"})
                append("\nPermiso seguro ADB: "); append(if(hasSecureSettingsGrant()) "CONCEDIDO ✓" else "NO")
                append("\n\nCon el servicio activo, abre YouTube, Netflix o Prime Video y navega por tu perfil. Javistv podrá actualizar los accesos personalizados visibles en esas apps.")
            }
        }
        val enable=Button(this).apply {
            text="ACTIVAR ACCESIBILIDAD JAVISTV"
            setOnClickListener {
                if(hasSecureSettingsGrant()) {
                    val ok=setJavistvAccessibilityEnabled(true)
                    Toast.makeText(this@MainActivity,if(ok)"Accesibilidad activada" else "No se pudo activar",Toast.LENGTH_LONG).show()
                    handler.postDelayed({refresh()},600)
                } else {
                    openAccessibilitySettings()
                    handler.postDelayed({refresh()},900)
                }
            }
        }
        val system=Button(this).apply {
            text="ABRIR ACCESIBILIDAD DE FIRE TV"
            setOnClickListener { openAccessibilitySettings() }
        }
        val help=Button(this).apply {
            text="AYUDA FIRE TV / ADB"
            setOnClickListener { showFireAccessibilityHelp() }
        }
        val update=Button(this).apply {
            text="ACTUALIZAR ESTADO"
            setOnClickListener { refresh() }
        }
        box.addView(statusView)
        box.addView(enable)
        box.addView(system)
        box.addView(help)
        box.addView(update)
        refresh()

        AlertDialog.Builder(this)
            .setTitle("Accesibilidad y apps TV")
            .setView(box)
            .setNegativeButton("CERRAR",null)
            .show()
    }

'''
settings_anchor='    private fun showSettings()'
if 'private fun showPhonePairing()' not in s:
    if settings_anchor not in s:
        raise SystemExit("showSettings anchor missing")
    s=s.replace(settings_anchor,helpers+settings_anchor,1)

# Fix camera diagnostics: always show Camera2 and USB/UVC state, even when permission is already granted.
old=r'''            statusView.text = "Permiso cámara: " + if (granted) "CONCEDIDO ✓" else "PENDIENTE" +
                "\nCámaras Android: $ids" +
                "\nDetector: " + if (prefs.getBoolean("sofaVisionEnabled", false)) "ACTIVO" else "APAGADO" +
                "\nEstado: " + prefs.getString("sofaVisionStatus", "sin iniciar")'''
new=r'''            val usbSummary=runCatching {
                val um=getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager
                val devices=um.deviceList.values.toList()
                if(devices.isEmpty()) "ninguno" else devices.joinToString("\n") { d ->
                    val isVideo=(0 until d.interfaceCount).any { i ->
                        d.getInterface(i).interfaceClass==android.hardware.usb.UsbConstants.USB_CLASS_VIDEO
                    }
                    val kind=if(isVideo) "VIDEO/UVC" else "USB"
                    (d.productName ?: d.deviceName) + " · " + kind +
                        " · VID " + d.vendorId + " PID " + d.productId +
                        " · permiso " + if(um.hasPermission(d)) "sí" else "no"
                }
            }.getOrDefault("error leyendo USB")
            statusView.text=buildString {
                append("Permiso cámara: "); append(if(granted) "CONCEDIDO ✓" else "PENDIENTE")
                append("\nCámaras Camera2: "); append(ids)
                append("\nUSB detectados: "); append(usbSummary)
                append("\nDetector: "); append(if(prefs.getBoolean("sofaVisionEnabled",false)) "ACTIVO" else "APAGADO")
                append("\nEstado: "); append(prefs.getString("sofaVisionStatus","sin iniciar"))
                if(ids=="ninguna" && usbSummary.contains("VIDEO/UVC")) {
                    append("\nAVISO: Fire TV ve la webcam por USB/UVC pero no la expone a Camera2.")
                }
            }'''
if old in s:
    s=s.replace(old,new,1)

s=s.replace('Ajustes de Javistv v0.6.24','Ajustes de Javistv v0.6.25')
s=s.replace('Javistv/0.6.24','Javistv/0.6.25')
p.write_text(s)

# Add the direct menu entries to the generated TV UI.
x=Path("app/src/main/res/layout/activity_main.xml")
xml=x.read_text()
anchor='''        <Button android:id="@+id/connectionsButton" android:layout_width="match_parent" android:layout_height="54dp" android:text="⛓  Complementos" android:textAllCaps="false" />'''
if '@+id/pairPhoneButton' not in xml:
    if anchor not in xml:
        raise SystemExit("side menu XML anchor missing")
    xml=xml.replace(
        anchor,
        anchor + '''
        <Button android:id="@+id/pairPhoneButton" android:layout_width="match_parent" android:layout_height="54dp" android:text="📱  Vincular móvil" android:textAllCaps="false" />
        <Button android:id="@+id/accessibilitySetupButton" android:layout_width="match_parent" android:layout_height="54dp" android:text="◉  Accesibilidad / apps TV" android:textAllCaps="false" />''',
        1
    )
x.write_text(xml)

print("Javistv 0.6.25 pairing/accessibility/camera diagnostics patch applied")
