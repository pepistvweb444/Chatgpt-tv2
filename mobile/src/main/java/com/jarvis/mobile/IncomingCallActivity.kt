package com.jarvis.mobile

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class IncomingCallActivity : Activity() {
    private lateinit var subtitle: TextView
    private lateinit var nameView: TextView
    private lateinit var photo: ImageView
    private var number: String = ""
    private var source: String = "phone"
    private var notificationKey: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        number = intent.getStringExtra("number").orEmpty()
        source = intent.getStringExtra("source").orEmpty().ifBlank { "phone" }
        notificationKey = intent.getStringExtra("notificationKey").orEmpty()
        val suppliedName = intent.getStringExtra("name").orEmpty()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(36, 48, 36, 36)
            setBackgroundColor(Color.rgb(8, 11, 16))
        }
        root.addView(TextView(this).apply { text = "Llamada entrante · ${sourceLabel()}"; textSize = 18f; setTextColor(Color.rgb(160,180,255)) })
        photo = ImageView(this).apply { layoutParams = LinearLayout.LayoutParams(180,180).apply { topMargin=28; bottomMargin=18 }; scaleType=ImageView.ScaleType.CENTER_CROP; setBackgroundColor(Color.rgb(35,42,58)) }
        root.addView(photo)
        nameView = TextView(this).apply { text = suppliedName.ifBlank { number.ifBlank { "Llamada entrante" } }; textSize=28f; gravity=Gravity.CENTER; setTextColor(Color.WHITE) }
        root.addView(nameView)
        subtitle = TextView(this).apply { text = if(number.isBlank()) "Esperando tu decisión" else "Comprobando contacto…"; textSize=15f; gravity=Gravity.CENTER; setTextColor(Color.rgb(205,213,225)); setPadding(0,10,0,24) }
        root.addView(subtitle)
        fun action(label:String, fn:()->Unit) = root.addView(Button(this).apply { text=label; layoutParams=LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{bottomMargin=10}; setOnClickListener{fn()} })
        action("Contestar") { answerCall() }
        action("Rechazar") { rejectCall() }
        action("Dejar sonar") { finish() }
        setContentView(root)
        if (source == "phone") resolveContactAndReputation() else persistCallCard("voip", nameView.text.toString(), "", false, emptyList())
    }

    private fun sourceLabel():String = when {
        source.contains("whatsapp",true) -> "WhatsApp"
        source.contains("instagram",true) -> "Instagram"
        source.contains("facebook",true) || source.contains("messenger",true) -> "Messenger/Facebook"
        source.contains("zoom",true) -> "Zoom"
        source.contains("telegram",true) -> "Telegram"
        source.contains("teams",true) -> "Teams"
        source.contains("meet",true) -> "Google Meet"
        else -> "Teléfono"
    }

    private fun resolveContactAndReputation() {
        val match = lookupContact(number)
        if (match != null) {
            nameView.text = match.first
            subtitle.text = buildString { append(number); if (match.third) append(" · Favorito") }
            match.second?.let { uri -> runCatching { photo.setImageURI(Uri.parse(uri)) } }
            persistCallCard("contact", match.first, match.second.orEmpty(), false, emptyList())
            return
        }
        nameView.text = if (number.isBlank()) "Número oculto" else number
        subtitle.text = "Número desconocido · comprobando reputación…"
        Thread {
            val rep = runCatching { readReputation(number) }.getOrNull()
            runOnUiThread {
                val cls = rep?.optString("classification").orEmpty()
                val sources = rep?.optJSONArray("sources")
                val sourceList = (0 until (sources?.length() ?: 0)).map { sources?.optString(it).orEmpty() }.filter { it.isNotBlank() }
                val spam = cls == "spam_probable"
                subtitle.text = when (cls) {
                    "spam_probable" -> "⚠ Spam probable · ${sourceList.joinToString(", ").ifBlank { "varias fuentes" }}"
                    "possible_spam", "suspicious" -> "⚠ Posible spam · una fuente pública"
                    else -> "Número desconocido · sin señales suficientes de spam"
                }
                persistCallCard(cls.ifBlank { "unknown" }, number, "", spam, sourceList)
            }
        }.start()
    }

    private fun lookupContact(number:String): Triple<String,String?,Boolean>? {
        if (number.isBlank() || ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        val projection = arrayOf(ContactsContract.PhoneLookup._ID, ContactsContract.PhoneLookup.DISPLAY_NAME, ContactsContract.PhoneLookup.PHOTO_URI)
        contentResolver.query(uri, projection, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val id=c.getLong(0); val name=c.getString(1).orEmpty().ifBlank{number}; val photoUri=c.getString(2)
                var starred=false
                runCatching { contentResolver.query(ContactsContract.Contacts.CONTENT_URI, arrayOf(ContactsContract.Contacts.STARRED), "${ContactsContract.Contacts._ID}=?", arrayOf(id.toString()), null)?.use { x -> if(x.moveToFirst()) starred=x.getInt(0)==1 } }
                return Triple(name,photoUri,starred)
            }
        }
        return null
    }

    private fun readReputation(number:String): JSONObject {
        val c=(URL("https://chatgpt-tv2.vercel.app/api/caller-reputation?number="+Uri.encode(number)).openConnection() as HttpURLConnection).apply { connectTimeout=3500; readTimeout=5000; requestMethod="GET"; setRequestProperty("Accept","application/json") }
        val raw=(if(c.responseCode in 200..299)c.inputStream else c.errorStream)?.bufferedReader()?.use{it.readText()}.orEmpty()
        return JSONObject(raw)
    }

    @Suppress("DEPRECATION") private fun answerCall() {
        if (source == "phone") {
            val ok = ContextCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED && runCatching { getSystemService(TelecomManager::class.java).acceptRingingCall(); true }.getOrDefault(false)
            if (!ok) Toast.makeText(this,"Android no permitió contestar",Toast.LENGTH_SHORT).show() else finish()
        } else {
            sendBroadcast(Intent(JarvisNotificationListener.ACTION_CALL_NOTIFICATION).setPackage(packageName).putExtra("key",notificationKey).putExtra("callAction","answer")); finish()
        }
    }
    @Suppress("DEPRECATION") private fun rejectCall() {
        if (source == "phone") {
            val ok = ContextCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED && runCatching { getSystemService(TelecomManager::class.java).endCall() }.getOrDefault(false)
            if (!ok) Toast.makeText(this,"Android no permitió rechazar",Toast.LENGTH_SHORT).show() else finish()
        } else {
            sendBroadcast(Intent(JarvisNotificationListener.ACTION_CALL_NOTIFICATION).setPackage(packageName).putExtra("key",notificationKey).putExtra("callAction","reject")); finish()
        }
    }
    private fun persistCallCard(classification:String,name:String,photoUri:String,spam:Boolean,sources:List<String>) {
        getSharedPreferences("jarvis_mobile", MODE_PRIVATE).edit().putString("incoming_call_card", JSONObject().put("source",sourceLabel()).put("number",number).put("name",name).put("photoUri",photoUri).put("classification",classification).put("spam",spam).put("sources",org.json.JSONArray(sources)).put("notificationKey",notificationKey).put("time",System.currentTimeMillis()).toString()).apply()
    }
}
