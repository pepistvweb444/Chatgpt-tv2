package com.jarvis.mobile

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

class NowBriefView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle
) : AppCompatTextView(context, attrs, defStyleAttr) {

    @Volatile private var loadingTado = false
    private var baseText: CharSequence = ""

    override fun setText(text: CharSequence?, type: BufferType?) {
        baseText = text ?: ""
        super.setText(baseText, type)
        if (baseText.toString().contains("Now Brief", true) || baseText.toString().contains("NOW BRIEF", true)) {
            appendTadoStatus()
        }
    }

    private fun appendTadoStatus() {
        if (loadingTado || !TadoClient.isConnected(context)) return
        loadingTado = true
        Thread {
            try {
                val zones = TadoClient.zones(context)
                if (zones.isNotEmpty()) {
                    val summary = zones.joinToString("\n") { z ->
                        val current = z.temperature?.let { "%.1f°".format(it) } ?: "--°"
                        val target = z.target?.let { "%.1f°".format(it) } ?: "--°"
                        "🏠 ${z.name}: $current · objetivo $target · ${if (z.power.equals("ON", true)) "encendido" else "apagado"}"
                    }
                    post {
                        val clean = baseText.toString().substringBefore("\n🏠 ")
                        super.setText("$clean\n\n$summary", BufferType.NORMAL)
                    }
                }
            } catch (_: Exception) {
                // Now Brief sigue funcionando aunque Tado no responda.
            } finally {
                loadingTado = false
            }
        }.start()
    }
}
