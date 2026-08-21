package com.jarvis.tv

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.content.Intent
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var transcript: TextView
    private lateinit var input: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        transcript = findViewById(R.id.transcript)
        input = findViewById(R.id.messageInput)
        findViewById<Button>(R.id.sendButton).setOnClickListener { sendMessage() }
        findViewById<Button>(R.id.micButton).setOnClickListener { startVoiceInput() }
        findViewById<Button>(R.id.settingsButton).setOnClickListener {
            transcript.append("\n\nJarvis: Ajustes de v0.2. La integración segura con OpenAI se conectará mediante backend, sin guardar claves API en el APK.")
        }
    }

    private fun sendMessage() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        transcript.append("\n\nTú: $text")
        transcript.append("\nJarvis: Mensaje recibido. Jarvis TV v0.2 está funcionando correctamente en modo local.")
        input.text.clear()
    }

    private fun startVoiceInput() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 10)
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Habla con Jarvis")
        }
        try { startActivityForResult(intent, 20) }
        catch (_: Exception) { transcript.append("\n\nJarvis: El reconocimiento de voz no está disponible en este televisor.") }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 20 && resultCode == RESULT_OK) {
            val results = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            input.setText(results?.firstOrNull().orEmpty())
            sendMessage()
        }
    }
}
