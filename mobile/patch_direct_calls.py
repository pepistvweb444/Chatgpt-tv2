from pathlib import Path

# Make contact/number calls direct and remember the order while Android grants permissions.
p = Path('mobile/src/main/java/com/jarvis/mobile/LocalActionRouter.kt')
s = p.read_text()

old_num = '''    private fun callNumber(number: String): Result {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.CALL_PHONE), 70)
            return Result(true, "Necesito permiso de Teléfono. Concédelo y repite la orden.")
        }
        val clean = number.filter { it.isDigit() || it == '+' }
        return try {
            activity.startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(clean)}")))
            Result(true, "Iniciando llamada a $clean.")
        } catch (e: Exception) {
            runCatching { activity.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(clean)}"))) }
            Result(true, "Android no permitió iniciar la llamada directamente (${e.javaClass.simpleName}). He abierto el marcador con el número preparado.")
        }
    }
'''
new_num = '''    private fun callNumber(number: String): Result {
        val clean = number.filter { it.isDigit() || it == '+' }
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            activity.getSharedPreferences("jarvis_mobile", Activity.MODE_PRIVATE).edit().putString("pending_call_number", clean).apply()
            ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.CALL_PHONE), 70)
            return Result(true, "Concede el permiso de Teléfono. Iniciaré la llamada automáticamente después.")
        }
        return try {
            activity.startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(clean)}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Result(true, "Llamando a $clean.")
        } catch (e: Exception) {
            runCatching { activity.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(clean)}"))) }
            Result(true, "Android ha bloqueado la llamada directa. He abierto el marcador con el número preparado.")
        }
    }
'''
if old_num in s:
    s = s.replace(old_num, new_num)

start = s.find('    private fun callContact(name: String): Result {')
end_marker = '    private fun smsContact(name: String, body: String): Result {'
end = s.find(end_marker, start)
if start < 0 or end < 0:
    raise SystemExit('callContact block not found')
new_contact = '''    private fun callContact(name: String): Result {
        if (name.isBlank()) return Result(true, "Dime a quién quieres llamar.")
        val needContacts = ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED
        val needPhone = ContextCompat.checkSelfPermission(activity, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED
        if (needContacts || needPhone) {
            activity.getSharedPreferences("jarvis_mobile", Activity.MODE_PRIVATE).edit().putString("pending_call_name", name).apply()
            val perms = mutableListOf<String>()
            if (needContacts) perms += Manifest.permission.READ_CONTACTS
            if (needPhone) perms += Manifest.permission.CALL_PHONE
            ActivityCompat.requestPermissions(activity, perms.toTypedArray(), 70)
            return Result(true, "Concede ${if (needContacts && needPhone) "Contactos y Teléfono" else if (needContacts) "Contactos" else "Teléfono"}. Iniciaré la llamada automáticamente después.")
        }
        val match = findPhone(name) ?: return Result(true, "No encuentro un contacto llamado $name.")
        val result = callNumber(match.second)
        return if (result.message.startsWith("Llamando")) Result(true, "Llamando a ${match.first}.") else result
    }

    fun resumePendingCall(): Result? {
        val prefs = activity.getSharedPreferences("jarvis_mobile", Activity.MODE_PRIVATE)
        val name = prefs.getString("pending_call_name", "").orEmpty()
        val number = prefs.getString("pending_call_number", "").orEmpty()
        if (name.isBlank() && number.isBlank()) return null
        prefs.edit().remove("pending_call_name").remove("pending_call_number").apply()
        return if (name.isNotBlank()) callContact(name) else callNumber(number)
    }

'''
s = s[:start] + new_contact + s[end:]
p.write_text(s)

# Resume the stored call as soon as Android returns the permission result.
p = Path('mobile/src/main/java/com/jarvis/mobile/MainActivity.kt')
s = p.read_text()
needle = '''        if (requestCode == REQ_LOCATION && grantResults.any { it == PackageManager.PERMISSION_GRANTED }) { warmLocation(); openWeatherForCurrentLocation() }
'''
insert = '''        if (requestCode == 70 && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            val resumed = runCatching { actionRouter.resumePendingCall() }.getOrNull()
            if (resumed?.handled == true && resumed.message.isNotBlank()) {
                renderMessageCard("assistant", resumed.message)
                saveHistory("assistant", resumed.message, false)
                safeSpeak(resumed.message)
            }
        }
'''
if insert not in s:
    if needle not in s:
        raise SystemExit('permission result marker not found')
    s = s.replace(needle, needle + insert)
p.write_text(s)
print('Direct-call patch applied')
