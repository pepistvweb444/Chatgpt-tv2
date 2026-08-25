package com.jarvis.mobile

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Safe launcher used when the proprietary Google Home SDK AARs are not present
 * in the build. The CI workflow replaces this file with the SDK-backed template
 * automatically when the two AARs are supplied through GitHub Actions secrets.
 */
class GoogleHomeActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Google Home · luces"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(24), dp(22), dp(24))
            setBackgroundColor(Color.rgb(8, 11, 16))
        }
        root.addView(TextView(this).apply {
            text = "Google Home · luces"
            textSize = 26f
            setTextColor(Color.WHITE)
        })
        root.addView(TextView(this).apply {
            text = "El proyecto y OAuth pueden estar configurados, pero Google distribuye Home APIs como dos AAR privados que deben incluirse en la APK. Esta compilación no los contiene todavía, por lo que no puede abrir el consentimiento de la casa."
            textSize = 16f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(14), 0, dp(16))
        })
        root.addView(TextView(this).apply {
            text = "Necesarios: play-services-home*.aar y play-services-home-types*.aar. Cuando se añadan al build, Jarvis sustituirá automáticamente esta pantalla por AUTORIZAR GOOGLE HOME y mostrará las luces por habitación."
            textSize = 14f
            setTextColor(Color.rgb(120, 195, 255))
            setPadding(0, 0, 0, dp(18))
        })
        root.addView(Button(this).apply {
            text = "ABRIR GOOGLE HOME DEVELOPERS"
            setOnClickListener {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://developers.home.google.com/apis/android/sdk"))) }
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)))
        root.addView(Button(this).apply {
            text = "ABRIR CONSOLA GOOGLE HOME"
            setOnClickListener {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.home.google.com/"))) }
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply { topMargin = dp(10) })
        setContentView(root)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
