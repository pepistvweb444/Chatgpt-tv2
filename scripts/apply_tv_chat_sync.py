from pathlib import Path

# Extend MobileRemoteClient with chat sync methods.
p=Path('app/src/main/java/com/jarvis/tv/MobileRemoteClient.kt')
s=p.read_text()
if 'import org.json.JSONArray' not in s:
    s=s.replace('import org.json.JSONObject\n','import org.json.JSONObject\nimport org.json.JSONArray\n')
if 'fun chats()' not in s:
    insert='''\n    fun chats(): JSONObject = get("/chats", auth = true)\n\n    fun chat(id: String): JSONObject {\n        val encoded = URLEncoder.encode(id, "UTF-8")\n        return get("/chat?id=$encoded", auth = true)\n    }\n\n    fun syncChat(id: String, title: String, history: JSONArray, updated: Long): JSONObject {\n        return post("/chat-sync", JSONObject().put("id", id).put("title", title).put("history", history).put("updated", updated), auth = true)\n    }\n\n'''
    s=s.replace('    private fun get(path: String, auth: Boolean = false): JSONObject {',insert+'    private fun get(path: String, auth: Boolean = false): JSONObject {',1)
    post='''\n    private fun post(path: String, body: JSONObject, auth: Boolean = false): JSONObject {\n        val h = host()\n        if (h.isBlank()) throw IllegalStateException("Configura primero la IP o nombre del móvil")\n        val c = (URL("http://$h:8765$path").openConnection() as HttpURLConnection).apply {\n            requestMethod = "POST"\n            doOutput = true\n            connectTimeout = 5000\n            readTimeout = 12000\n            setRequestProperty("Accept", "application/json")\n            setRequestProperty("Content-Type", "application/json")\n            if (auth) setRequestProperty("Authorization", "Bearer ${token()}")\n        }\n        c.outputStream.use { it.write(body.toString().toByteArray()) }\n        val raw = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()\n        if (c.responseCode !in 200..299) throw IllegalStateException("Móvil HTTP ${c.responseCode}: ${runCatching { JSONObject(raw).optString("error") }.getOrNull().orEmpty().ifBlank { raw.take(120) }}")\n        return runCatching { JSONObject(raw) }.getOrElse { JSONObject().put("raw", raw) }\n    }\n'''
    s=s.replace('\n}\n',post+'\n}\n',1)
p.write_text(s)

# Patch TV MainActivity to pull chats from mobile and push each changed thread back.
p=Path('app/src/main/java/com/jarvis/tv/MainActivity.kt')
s=p.read_text()
field='    private val prefs by lazy { getSharedPreferences("jarvis", MODE_PRIVATE) }\n'
if 'mobileRemote by lazy' not in s:
    s=s.replace(field,field+'    private val mobileRemote by lazy { MobileRemoteClient(this) }\n',1)

marker='    private fun chatIndex(): JSONArray = runCatching { JSONArray(prefs.getString("chatIndex", "[]")) }.getOrElse { JSONArray() }\n'
helpers=r'''    private fun replaceLocalChatIndex(arr: JSONArray) {
        prefs.edit().putString("chatIndex", arr.toString()).apply()
    }

    private fun importChatFromPhone(id: String): Boolean {
        if (!mobileRemote.configured()) return false
        val j = mobileRemote.chat(id)
        val history = j.optJSONArray("history") ?: JSONArray()
        val titleText = j.optString("title").ifBlank { "Chat" }
        val updated = j.optLong("updated", System.currentTimeMillis())
        prefs.edit().putString("chat_$id", history.toString()).apply()
        val idx = chatIndex(); var found=false
        for(i in 0 until idx.length()) idx.optJSONObject(i)?.let { o -> if(o.optString("id")==id){o.put("title",titleText).put("updated",updated);found=true} }
        if(!found) idx.put(JSONObject().put("id",id).put("title",titleText).put("updated",updated))
        replaceLocalChatIndex(idx)
        return true
    }

    private fun pullChatsFromPhone(): JSONArray {
        if (!mobileRemote.configured()) return chatIndex()
        val j = mobileRemote.chats()
        val remote = j.optJSONArray("chats") ?: JSONArray()
        replaceLocalChatIndex(remote)
        val current = j.optString("currentConversation")
        if(current.isNotBlank()) prefs.edit().putString("mobileCurrentConversation",current).apply()
        return remote
    }

    private fun pushCurrentChatToPhone() {
        if (!mobileRemote.configured()) return
        val id = conversationId
        val titleText = sortedChats().firstOrNull { it.optString("id") == id }?.optString("title").orEmpty().ifBlank { "Chat" }
        val history = historyArray()
        val updated = System.currentTimeMillis()
        Thread { runCatching { mobileRemote.syncChat(id, titleText, history, updated) } }.start()
    }

'''
if 'private fun pullChatsFromPhone()' not in s:
    if marker not in s: raise SystemExit('chatIndex marker not found')
    s=s.replace(marker,marker+'\n'+helpers,1)

# Replace showChats with synchronized version.
start=s.find('    private fun showChats() {')
end=s.find('\n    private fun showConnections()',start)
if start<0 or end<0: raise SystemExit('showChats block not found')
show=r'''    private fun showChats() {
        status.text = "● Sincronizando chats con el móvil…"
        Thread {
            val syncError = runCatching { pullChatsFromPhone() }.exceptionOrNull()
            val items = sortedChats()
            runOnUiThread {
                status.text = if(syncError==null) "● Chats sincronizados" else "● Chats locales · móvil no disponible"
                val labels=mutableListOf<String>();labels.add("＋ Nuevo chat");labels.addAll(items.map{it.optString("title").ifBlank{"Chat"}})
                AlertDialog.Builder(this).setTitle("Mis chats · móvil + TV").setItems(labels.toTypedArray()){_,which->
                    if(which==0){createConversation(true);pushCurrentChatToPhone()}
                    else {
                        val item=items[which-1];val id=item.optString("id")
                        if(id.isNotBlank()){
                            status.text="● Cargando conversación…"
                            Thread{
                                runCatching { importChatFromPhone(id) }
                                runOnUiThread{conversationId=id;prefs.edit().putString("currentConversation",id).apply();loadConversation(id);status.text="● Chat sincronizado · puedes continuar aquí"}
                            }.start()
                        }
                    }
                }.setNegativeButton("Cerrar",null).show()
            }
        }.start()
    }
'''
s=s[:start]+show+s[end:]

# Push any local append (user or assistant) to phone after metadata update.
needle='''        updateChatMeta(text, role)\n        if (role == "assistant" && speak) speakWithOpenAI(text)'''
repl='''        updateChatMeta(text, role)\n        pushCurrentChatToPhone()\n        if (role == "assistant" && speak) speakWithOpenAI(text)'''
if needle in s:
    s=s.replace(needle,repl,1)

# On resume, refresh current thread if phone has it.
needle='''    override fun onResume() {\n        super.onResume()\n        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) startBubbleService()\n    }'''
repl='''    override fun onResume() {\n        super.onResume()\n        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) startBubbleService()\n        if (mobileRemote.configured() && conversationId.isNotBlank()) {\n            Thread {\n                runCatching { importChatFromPhone(conversationId) }.onSuccess { runOnUiThread { loadConversation(conversationId) } }\n            }.start()\n        }\n    }'''
if needle in s:
    s=s.replace(needle,repl,1)

p.write_text(s)
print('TV bidirectional chat sync with mobile applied')
