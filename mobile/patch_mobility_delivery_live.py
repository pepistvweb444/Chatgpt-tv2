from pathlib import Path

# Extend the existing mobility patch with rides, public transit and active delivery orders.
# Everything shown as live state comes from a captured app notification or a direct
# inspection of the corresponding app/site. Jarvis must never invent an ETA/status.

# 1) Notification listener: broaden sources and tag each event by kind.
p = Path('mobile/src/main/java/com/jarvis/mobile/JarvisNotificationListener.kt')
s = p.read_text()

old = '''    private fun mobilitySource(pkg: String, blob: String): String? = when {
        pkg.contains("cabify", true) || blob.contains("cabify", true) -> "Cabify"
        pkg.contains("pidetaxi", true) || blob.contains("pidetaxi", true) || blob.contains("pide taxi", true) -> "PideTaxi"
        pkg.contains("villavesa", true) || blob.contains("villavesa", true) || blob.contains("tu villavesa", true) -> "Villavesa"
        else -> null
    }'''
new = '''    private fun mobilitySource(pkg: String, blob: String): String? = when {
        pkg.contains("bolt", true) || pkg.contains("mtakso", true) || blob.contains("bolt", true) -> "Bolt"
        pkg.contains("uber", true) && (pkg.contains("eats", true) || blob.contains("uber eats", true)) -> "Uber Eats"
        pkg.contains("uber", true) || blob.contains("uber", true) -> "Uber"
        pkg.contains("cabify", true) || blob.contains("cabify", true) -> "Cabify"
        pkg.contains("pidetaxi", true) || blob.contains("pidetaxi", true) || blob.contains("pide taxi", true) -> "PideTaxi"
        pkg.contains("freenow", true) || blob.contains("free now", true) || blob.contains("freenow", true) -> "FREE NOW"
        pkg.contains("glovo", true) || blob.contains("glovo", true) -> "Glovo"
        pkg.contains("justeat", true) || blob.contains("just eat", true) -> "Just Eat"
        pkg.contains("deliveroo", true) || blob.contains("deliveroo", true) -> "Deliveroo"
        pkg.contains("villavesa", true) || blob.contains("villavesa", true) || blob.contains("tu villavesa", true) ||
            blob.contains("transporte urbano comarcal", true) || blob.contains("tuc", true) -> "Villavesa"
        else -> null
    }

    private fun mobilityKind(source: String): String = when (source) {
        "Bolt", "Uber", "Cabify", "PideTaxi", "FREE NOW" -> "ride"
        "Villavesa" -> "transit"
        "Glovo", "Uber Eats", "Just Eat", "Deliveroo" -> "delivery"
        else -> "mobility"
    }'''
if old in s:
    s = s.replace(old, new, 1)

# Add kind/ETA extraction and a foreground broadcast to refresh visible chat widgets.
old_item = '''        val item=JSONObject().put("source",source).put("title",title).put("text",body).put("package",pkg).put("time",now)'''
new_item = '''        val normalized=(title+" "+body).lowercase()
        val eta = Regex("(?i)(?:llega(?:da)?|llegará|llegara|en|eta|arrival|arrives? in)\\s*(?:aprox\\.?\\s*)?(\\d{1,3})\\s*(?:min|minutos?|minutes?)").find(normalized)?.groupValues?.getOrNull(1).orEmpty()
        val item=JSONObject().put("source",source).put("kind",mobilityKind(source)).put("title",title).put("text",body).put("package",pkg).put("etaMinutes",eta).put("time",now)'''
if old_item in s:
    s = s.replace(old_item, new_item, 1)
    # Existing patch declares normalized again later; remove only the next duplicate declaration.
    s = s.replace('''
        val normalized=(title+" "+body).lowercase()
        val important=''', '''
        val important=''', 1)

old_important = '''val important=listOf("ha llegado","llegado","está esperando","esta esperando","en el punto de recogida","recogida","en camino","llega en","minuto","arrived","driver is here","pickup").any{normalized.contains(it)}'''
new_important = '''val important=listOf(
            "ha llegado","llegado","está esperando","esta esperando","en el punto de recogida","recogida","en camino","llega en","minuto","arrived","driver is here","pickup",
            "reserva confirmada","viaje confirmado","conductor asignado","pedido confirmado","preparando","en preparación","en preparacion","repartidor","recogió tu pedido","recogio tu pedido","en reparto","delivery","courier","pedido en camino"
        ).any{normalized.contains(it)}'''
if old_important in s:
    s = s.replace(old_important, new_important, 1)

anchor = '''        prefs.edit().putString("mobility_feed",trimmed.toString()).putString("last_mobility_status",item.toString()).apply()'''
if anchor in s and 'MOBILITY_UPDATE' not in s:
    s = s.replace(anchor, anchor + '''
        runCatching { sendBroadcast(android.content.Intent("com.jarvis.mobile.action.MOBILITY_UPDATE").setPackage(packageName).putExtra("payload", item.toString())) }''', 1)
p.write_text(s)

# 2) Local router: status questions for rides, Villavesa and orders all return widgets.
p = Path('mobile/src/main/java/com/jarvis/mobile/LocalActionRouter.kt')
s = p.read_text()
# Existing generated route from patch_mobility_realtime.py.
old_route = '''            ((lower.contains("cabify") || lower.contains("pidetaxi") || lower.contains("pide taxi") || lower.contains("taxi") || lower.contains("villavesa")) &&
             (lower.contains("estado") || lower.contains("dónde") || lower.contains("donde") || lower.contains("llega") || lower.contains("recoge") || lower.contains("cuánto") || lower.contains("cuanto") || lower.contains("avisa"))) -> return readMobilityStatus(lower)'''
new_route = '''            ((listOf("bolt","uber","cabify","pidetaxi","pide taxi","taxi","free now","freenow","villavesa","tuc","glovo","uber eats","just eat","deliveroo","pedido","repartidor","reserva").any { lower.contains(it) }) &&
             (listOf("estado","dónde","donde","llega","llegada","recoge","cuánto","cuanto","avisa","tiempo","parada","línea","linea","autobús","autobus","pedido","reserva").any { lower.contains(it) })) -> return readMobilityStatus(lower)'''
if old_route in s:
    s = s.replace(old_route, new_route, 1)

# Replace wanted mapping and make empty state request a direct app/web inspection rather than fabricate data.
s = s.replace('''            query.contains("cabify") -> "cabify"
            query.contains("pidetaxi") || query.contains("pide taxi") || query.contains("taxi") -> "pidetaxi"
            query.contains("villavesa") -> "villavesa"
            else -> ""''', '''            query.contains("bolt") -> "bolt"
            query.contains("uber eats") -> "uber eats"
            query.contains("uber") -> "uber"
            query.contains("cabify") -> "cabify"
            query.contains("free now") || query.contains("freenow") -> "free now"
            query.contains("pidetaxi") || query.contains("pide taxi") -> "pidetaxi"
            query.contains("villavesa") || query.contains("tuc") || query.contains("autob") || query.contains("parada") -> "villavesa"
            query.contains("glovo") -> "glovo"
            query.contains("just eat") -> "just eat"
            query.contains("deliveroo") -> "deliveroo"
            query.contains("pedido") || query.contains("repartidor") -> "delivery"
            query.contains("taxi") || query.contains("reserva") -> "ride"
            else -> ""''', 1)

s = s.replace('''            if(wanted.isNotBlank() && !source.lowercase().contains(wanted)) continue''', '''            if (wanted.isNotBlank()) {
                val src = source.lowercase()
                val matches = when (wanted) {
                    "ride" -> listOf("bolt","uber","cabify","pidetaxi","free now").any { src.contains(it) }
                    "delivery" -> listOf("glovo","uber eats","just eat","deliveroo").any { src.contains(it) }
                    else -> src.contains(wanted)
                }
                if (!matches) continue
            }''', 1)

s = s.replace('''Result(true,"__MOBILITY_EMPTY__|No tengo todavía una actualización reciente de esa reserva o trayecto. Mantendré vigilancia sobre sus notificaciones.")''', '''Result(true,"__MOBILITY_REMOTE__|$query")''', 1)
p.write_text(s)

# 3) Phone agent: explicitly recognise ride reservations and mobility/delivery apps.
p = Path('mobile/src/main/java/com/jarvis/mobile/PhoneAgentController.kt')
s = p.read_text()
if '"bolt"' not in s:
    s = s.replace('''"booking","thefork","restaurante","hotel","vuelo")''', '''"booking","thefork","restaurante","hotel","vuelo","bolt","uber","cabify","pidetaxi","pide taxi","free now","freenow","villavesa","tuc","uber eats","just eat","deliveroo")''')
# Give reservations enough steps while retaining explicit confirmation for final booking/payment through the planner.
s = s.replace('''val verbs = listOf("abre ","ve a ","entra en ","compra ","comprar ","reserva ","reservar ","busca ","buscar ","navega ","rellena ","añade ","agrega ","pide ","pedir ")''', '''val verbs = listOf("abre ","ve a ","entra en ","compra ","comprar ","reserva ","reservar ","pide un taxi","pide taxi","busca ","buscar ","navega ","rellena ","añade ","agrega ","pide ","pedir ")''')
p.write_text(s)

# 4) MainActivity: fallback to direct app/site inspection, always rendering the result as an inline widget.
p = Path('mobile/src/main/java/com/jarvis/mobile/MainActivity.kt')
s = p.read_text()
if '__MOBILITY_REMOTE__|' not in s:
    needles = [
        '            if (local.message.startsWith("__MOBILITY_WIDGET__")) {',
        '            if (local.message.startsWith("__NEW_MESSAGES__|")) {',
        '            if (local.message.startsWith("__MESSAGES_WIDGET__")) {'
    ]
    needle = next((x for x in needles if x in s), None)
    if needle:
        remote = r'''            if (local.message.startsWith("__MOBILITY_REMOTE__|")) {
                val q = local.message.substringAfter("|")
                val isTransit = q.contains("villavesa", true) || q.contains("tuc", true) || q.contains("parada", true) || q.contains("autob", true)
                val isDelivery = listOf("glovo","uber eats","just eat","deliveroo","pedido","repartidor").any { q.contains(it, true) }
                val task = when {
                    isTransit -> "Consulta la información ACTUAL y REAL de Villavesa/TUC para la petición: $q. Usa la app instalada o la web oficial de Transporte Urbano Comarcal/Mancomunidad. Devuelve línea, destino, parada y minutos reales para las próximas llegadas. Si no puedes verificar un dato en la interfaz/fuente actual, dilo; no inventes horarios ni ETA."
                    isDelivery -> "Comprueba el estado REAL del pedido activo relacionado con: $q. Abre la app correspondiente (Glovo, Uber Eats, Just Eat u otra), lee el estado visible, restaurante/comercio, repartidor si aparece y ETA real. No cambies ni canceles el pedido."
                    else -> "Comprueba el estado REAL de la reserva o viaje de taxi/VTC relacionado con: $q. Abre Bolt/Uber/Cabify/PideTaxi/FREE NOW según corresponda y devuelve estado, conductor/vehículo si aparece, punto de recogida y ETA real. No confirmes una nueva reserva ni cobro sin permiso explícito del usuario."
                }
                status.text = "Consultando estado en tiempo real…"
                phoneAgent.run(task,
                    onUpdate = { t -> runOnUiThread { status.text = t } },
                    onDone = { result -> runOnUiThread {
                        beginWidgetGroup(if (isTransit) "Villavesa · próximas llegadas" else if (isDelivery) "Pedido · seguimiento" else "Movilidad · reserva")
                        addTextWidget("day", if (isTransit) "Información verificada" else "Estado actual", result)
                        status.text = "Jarvis listo"
                    } })
            } else ''' + needle.strip()
        s = s.replace(needle, remote, 1)
p.write_text(s)

# 5) Bridge already exposes /mobility. Expanded feed automatically reaches Jarvis TV.
print('Bolt/taxi reservations, Villavesa realtime and delivery-order widgets applied')
