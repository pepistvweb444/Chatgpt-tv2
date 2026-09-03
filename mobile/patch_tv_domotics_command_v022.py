from pathlib import Path


def insert_before(text, anchor, block):
    if block.strip() in text:
        return text
    if anchor not in text:
        raise SystemExit(f'anchor not found: {anchor[:80]}')
    return text.replace(anchor, block + anchor, 1)

# ---------------------------------------------------------------------------
# Bridge endpoints. The TV starts a real provider action on the phone, then polls
# the result. No LLM is allowed to claim success before the provider confirms it.
# ---------------------------------------------------------------------------
p=Path('mobile/src/main/java/com/jarvis/mobile/PhoneBridgeService.kt')
s=p.read_text()
anchor='        return 404 to JSONObject().put("error", "not-found").toString()\n'
if 'path.startsWith("/domotics-command?")' not in s:
    routes=r'''        if (path.startsWith("/domotics-command?")) {
            val command=URLDecoder.decode(path.substringAfter("command=","").substringBefore("&"),StandardCharsets.UTF_8.name()).trim()
            val confirmed=URLDecoder.decode(path.substringAfter("confirmed=", "false").substringBefore("&"),StandardCharsets.UTF_8.name()).equals("true",true)
            if(command.isBlank()) return 400 to JSONObject().put("error","command-required").toString()
            val id=UUID.randomUUID().toString().replace("-","")
            val prefs=getSharedPreferences("jarvis_mobile",MODE_PRIVATE)
            prefs.edit().putString("tv_domotics_result_$id",JSONObject().put("id",id).put("status","pending").put("command",command).put("at",System.currentTimeMillis()).toString()).apply()
            startActivity(Intent(this,MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("tv_domotics_command",command)
                .putExtra("tv_domotics_command_confirmed",confirmed)
                .putExtra("tv_domotics_result_id",id))
            return 200 to JSONObject().put("ok",true).put("id",id).put("status","accepted").toString()
        }
        if (path.startsWith("/domotics-command-result?")) {
            val id=URLDecoder.decode(path.substringAfter("id=","").substringBefore("&"),StandardCharsets.UTF_8.name()).trim()
            if(id.isBlank()) return 400 to JSONObject().put("error","id-required").toString()
            val raw=getSharedPreferences("jarvis_mobile",MODE_PRIVATE).getString("tv_domotics_result_$id","").orEmpty()
            return 200 to if(raw.isBlank()) JSONObject().put("id",id).put("status","pending").toString() else raw
        }
'''
    s=insert_before(s,anchor,routes)
p.write_text(s)

# ---------------------------------------------------------------------------
# MainActivity provider-native executor.
# ---------------------------------------------------------------------------
p=Path('mobile/src/main/java/com/jarvis/mobile/MainActivity.kt')
s=p.read_text()
marker='    private fun jsonArrayStrings(a: JSONArray?): List<String> {'
if marker not in s: raise SystemExit('MainActivity helper marker not found')
if 'private fun executeTvDomoticsCommand(' not in s:
    methods=r'''    private fun tvNorm(value:String):String = value.lowercase()
        .replace("á","a").replace("é","e").replace("í","i").replace("ó","o").replace("ú","u").replace("ü","u").replace("ñ","n")
        .replace(Regex("[^a-z0-9]+")," ").trim()

    private fun tvTemp(value:String):Double? = Regex("(\\d{1,3}(?:[.,]\\d+)?)\\s*(?:°|grados?)?",RegexOption.IGNORE_CASE)
        .find(value)?.groupValues?.getOrNull(1)?.replace(',','.')?.toDoubleOrNull()

    private fun saveTvDomoticsResult(id:String,statusValue:String,message:String,pendingCommand:String="") {
        val out=JSONObject().put("id",id).put("status",statusValue).put("message",message).put("at",System.currentTimeMillis())
        if(pendingCommand.isNotBlank()) out.put("pendingCommand",pendingCommand)
        val snapshot=prefs.getString("domotics_tv_snapshot_json","").orEmpty()
        if(snapshot.isNotBlank()) runCatching{out.put("home",JSONObject(snapshot))}
        prefs.edit().putString("tv_domotics_result_$id",out.toString()).apply()
    }

    private fun consumeTvDomoticsIntent(source:Intent?) {
        val command=source?.getStringExtra("tv_domotics_command").orEmpty().trim()
        val id=source?.getStringExtra("tv_domotics_result_id").orEmpty().trim()
        val confirmed=source?.getBooleanExtra("tv_domotics_command_confirmed",false)==true
        if(command.isBlank()||id.isBlank()) return
        source?.removeExtra("tv_domotics_command"); source?.removeExtra("tv_domotics_result_id"); source?.removeExtra("tv_domotics_command_confirmed")
        executeTvDomoticsCommand(command,id,confirmed)
    }

    private fun executeTvDomoticsCommand(command:String,id:String,confirmed:Boolean) {
        Thread {
            try {
                saveTvDomoticsResult(id,"running","Ejecutando orden domótica…")
                val q=tvNorm(command)
                val result=when {
                    listOf("tado","aire acondicionado","climatizacion","termostato","calefaccion").any{q.contains(it)} -> tvExecuteTado(command)
                    q.contains("sensibo") -> tvExecuteSensibo(command)
                    listOf("home connect","horno","lavadora","lavavajillas","secadora","placa","vitro","induccion","frigorifico","nevera","congelador","cafetera").any{q.contains(it)} -> tvExecuteHomeConnect(command,confirmed)
                    else -> TvAction("not_supported","No encuentro un dispositivo compatible con esa orden en la domótica sincronizada.")
                }
                if(result.status=="done") {
                    runCatching{refreshDomoticsQuickCard()}; Thread.sleep(500L)
                }
                saveTvDomoticsResult(id,result.status,result.message,result.pendingCommand)
            } catch(e:Throwable) {
                saveTvDomoticsResult(id,"error",e.message ?: e.javaClass.simpleName)
            }
        }.start()
    }

    private data class TvAction(val status:String,val message:String,val pendingCommand:String="")

    private fun tvTadoHomeId(token:String):Long {
        var home=prefs.getLong("tado_home_id",-1L)
        if(home>0)return home
        val(c,r)=tadoRequest("GET","https://my.tado.com/api/v2/me",token)
        if(c !in 200..299) throw IllegalStateException("Tado HTTP $c")
        home=JSONObject(r).optJSONArray("homes")?.optJSONObject(0)?.optLong("id",-1L)?:-1L
        if(home<=0) throw IllegalStateException("No encuentro la casa Tado")
        prefs.edit().putLong("tado_home_id",home).apply(); return home
    }

    private fun tvExecuteTado(command:String):TvAction {
        val token=refreshTadoTokenIfNeeded(); if(token.isBlank()) return TvAction("not_supported","Tado no está conectado en Jarvis Mobile.")
        val home=tvTadoHomeId(token); val zones=fetchTadoZones(token,home); if(zones.isEmpty()) return TvAction("error","Tado no devuelve zonas controlables.")
        val q=tvNorm(command)
        val named=zones.filter{q.contains(tvNorm(it.name))}
        val zone=when { named.size==1->named.first(); zones.size==1->zones.first(); else->zones.firstOrNull{it.type.equals("AIR_CONDITIONING",true)}?:zones.first() }
        val off=q.contains("apaga")||q.contains("apagar")||q.contains("desactiva")
        val on=q.contains("enciende")||q.contains("encender")||q.contains("activa")
        val requested=tvTemp(command)
        val target=when {
            requested!=null -> requested.coerceIn(5.0,30.0)
            q.contains("sube") -> ((if(!zone.target.isNaN())zone.target else if(!zone.current.isNaN())zone.current else 22.0)+1).coerceAtMost(30.0)
            q.contains("baja") -> ((if(!zone.target.isNaN())zone.target else if(!zone.current.isNaN())zone.current else 22.0)-1).coerceAtLeast(5.0)
            else -> null
        }
        if(!off&&!on&&target==null) {
            val current=if(!zone.current.isNaN())"${String.format(java.util.Locale.getDefault(),"%.1f",zone.current)} °C" else "temperatura no disponible"
            val goal=if(!zone.target.isNaN())" · objetivo ${String.format(java.util.Locale.getDefault(),"%.1f",zone.target)} °C" else ""
            return TvAction("done","${zone.name}: ${if(zone.power.equals("ON",true))"encendido" else "apagado"} · $current$goal${if(zone.mode.isNotBlank())" · ${zone.mode}" else ""}.")
        }
        val setting=JSONObject().put("type",zone.type).put("power",if(off)"OFF" else "ON")
        if(!off) {
            if(zone.type.equals("AIR_CONDITIONING",true)) setting.put("mode",zone.mode.ifBlank{"COOL"})
            val t=target ?: if(!zone.target.isNaN())zone.target else if(!zone.current.isNaN())zone.current else if(zone.type.equals("AIR_CONDITIONING",true))24.0 else 21.0
            setting.put("temperature",JSONObject().put("celsius",t))
        }
        val payload=JSONObject().put("setting",setting).put("termination",JSONObject().put("typeSkillBasedApp","MANUAL"))
        val(code,raw)=tadoRequest("PUT","https://my.tado.com/api/v2/homes/$home/zones/${zone.id}/overlay",token,payload)
        if(code !in 200..299) throw IllegalStateException("Tado HTTP $code ${raw.take(120)}")
        return TvAction("done",when { off->"${zone.name} apagado.";target!=null->"${zone.name} ajustado a ${String.format(java.util.Locale.getDefault(),"%.1f",target)} °C.";else->"${zone.name} encendido." })
    }

    private fun tvExecuteSensibo(command:String):TvAction {
        val devices=fetchSensiboDevices(); if(devices.isEmpty()) return TvAction("not_supported","Sensibo no devuelve dispositivos.")
        val q=tvNorm(command); val named=devices.filter{q.contains(tvNorm(it.name))}; val d=if(named.size==1)named.first() else devices.first()
        val off=q.contains("apaga")||q.contains("apagar")||q.contains("desactiva")
        val on=q.contains("enciende")||q.contains("encender")||q.contains("activa")
        val requested=tvTemp(command)
        when {
            off||on -> sensiboBackend("POST",JSONObject().put("id",d.id).put("action","power").put("on",!off))
            requested!=null -> sensiboBackend("POST",JSONObject().put("id",d.id).put("action","temperature").put("value",requested.coerceIn(16.0,30.0)))
            q.contains("frio")||q.contains("cool") -> sensiboBackend("POST",JSONObject().put("id",d.id).put("action","mode").put("value","cool"))
            q.contains("calor")||q.contains("heat") -> sensiboBackend("POST",JSONObject().put("id",d.id).put("action","mode").put("value","heat"))
            else -> {
                val now=if(!d.current.isNaN())"${String.format(java.util.Locale.getDefault(),"%.1f",d.current)} °C" else "temperatura no disponible"
                val goal=if(!d.target.isNaN())" · objetivo ${String.format(java.util.Locale.getDefault(),"%.1f",d.target)} °C" else ""
                return TvAction("done","${d.name}: ${if(d.on==true)"encendido" else "apagado"} · $now$goal${if(d.mode.isNotBlank())" · ${d.mode}" else ""}.")
            }
        }
        return TvAction("done",when { off->"${d.name} apagado.";on->"${d.name} encendido.";requested!=null->"${d.name} ajustado a ${String.format(java.util.Locale.getDefault(),"%.1f",requested.coerceIn(16.0,30.0))} °C.";else->"${d.name} actualizado." })
    }

    private fun tvExecuteHomeConnect(command:String,confirmed:Boolean):TvAction {
        val token=refreshHomeConnectTokenIfNeeded(); if(token.isBlank()) return TvAction("not_supported","Home Connect no está conectado en Jarvis Mobile.")
        val devices=fetchHomeConnectDevices(token); if(devices.isEmpty()) return TvAction("not_supported","Home Connect no devuelve electrodomésticos.")
        val q=tvNorm(command)
        val aliases=mapOf("horno" to listOf("oven"),"lavadora" to listOf("washer","washingmachine"),"lavavajillas" to listOf("dishwasher"),"secadora" to listOf("dryer"),"placa" to listOf("hob","cooktop"),"vitro" to listOf("hob","cooktop"),"induccion" to listOf("hob","cooktop"),"frigorifico" to listOf("fridge","refrigerator"),"nevera" to listOf("fridge","refrigerator"),"cafetera" to listOf("coffee"))
        fun matches(d:HcDeviceCard):Boolean {
            val blob=tvNorm(d.name+" "+d.type)
            if(q.contains(tvNorm(d.name))) return true
            return aliases.any{(word,vals)->q.contains(word)&&vals.any{blob.contains(it)}}
        }
        val matched=devices.filter{matches(it)}
        val d=when { matched.size==1->matched.first(); devices.size==1->devices.first(); matched.isNotEmpty()->matched.first(); else->return TvAction("needs_clarification","Tengo varios electrodomésticos Home Connect. Indica cuál quieres controlar: ${devices.joinToString(", "){it.name}}.") }
        val highHeat=listOf("oven","hob","cooktop").any{tvNorm(d.type+" "+d.name).contains(it)}
        val wantsStart=q.contains("enciende")||q.contains("encender")||q.contains("inicia")||q.contains("iniciar")||q.contains("programa")||q.contains("arranca")
        if(highHeat&&wantsStart&&!confirmed) return TvAction("needs_confirmation","${d.name} puede generar calor. Confirma explícitamente que quieres ejecutar esta orden.",command)
        val powerKey="BSH.Common.Setting.PowerState"
        val off=q.contains("apaga")||q.contains("apagar")||q.contains("desactiva")
        val on=q.contains("enciende")||q.contains("encender")||q.contains("activa")
        if(off||on) {
            if(!d.settingValues.containsKey(powerKey)) return TvAction("not_supported","${d.name} no publica control remoto de encendido/apagado mediante Home Connect.")
            val value=if(off)"BSH.Common.EnumType.PowerState.Off" else "BSH.Common.EnumType.PowerState.On"
            val body=JSONObject().put("data",JSONObject().put("key",powerKey).put("value",value))
            val(code,raw)=hcApi("PUT","/api/homeappliances/${java.net.URLEncoder.encode(d.haId,"UTF-8")}/settings/$powerKey",token,body)
            if(code !in 200..299) return TvAction("error","Home Connect rechazó la orden (${code}): ${raw.take(120)}")
            return TvAction("done","${d.name} ${if(off)"apagado" else "encendido"}.")
        }
        if(q.contains("programa")||q.contains("inicia")||q.contains("arranca")) {
            if(d.programs.isEmpty()) return TvAction("not_supported","${d.name} no publica programas iniciables de forma remota.")
            val selected=d.programs.firstOrNull{(_,label)->q.contains(tvNorm(label))} ?: d.programs.firstOrNull{(key,_)->q.contains(tvNorm(key.substringAfterLast('.')))}
            if(selected==null) return TvAction("needs_clarification","Programas disponibles en ${d.name}: ${d.programs.take(8).joinToString(", "){it.second}}. Indica cuál quieres iniciar.")
            val(key,label)=selected
            val payload=JSONObject().put("data",JSONObject().put("key",key))
            val(code,raw)=hcApi("PUT","/api/homeappliances/${java.net.URLEncoder.encode(d.haId,"UTF-8")}/programs/active",token,payload)
            if(code !in 200..299) return TvAction("error","No se pudo iniciar $label en ${d.name}: HTTP $code ${raw.take(120)}")
            return TvAction("done","${d.name}: programa $label iniciado.")
        }
        val details=mutableListOf<String>(); details += if(d.connected)"conectado" else "sin conexión"
        if(d.activeProgram.isNotBlank()) details += "programa ${d.activeProgram.substringAfterLast('.')}"
        d.statusValues.entries.take(5).forEach{details += "${it.key.substringAfterLast('.')}: ${it.value.substringAfterLast('.')}"}
        return TvAction("done","${d.name}: ${details.joinToString(" · ")}.")
    }

'''
    s=s.replace(marker,methods+marker,1)

# Run a command delivered by the bridge on cold and warm starts.
oncreate_anchor='        warmLocation()\n'
if 'consumeTvDomoticsIntent(intent)' not in s:
    if oncreate_anchor in s:
        s=s.replace(oncreate_anchor,oncreate_anchor+'        consumeTvDomoticsIntent(intent)\n',1)
    else:
        raise SystemExit('warmLocation anchor not found')

# If onNewIntent exists, insert into it. It is generated by the Remote patch.
newintent='''    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
'''
if newintent in s and 'consumeTvDomoticsIntent(intent)' not in s[s.find(newintent):s.find(newintent)+300]:
    s=s.replace(newintent,newintent+'        consumeTvDomoticsIntent(intent)\n',1)
p.write_text(s)
print('TV -> Mobile provider-native domotics command bridge applied')
