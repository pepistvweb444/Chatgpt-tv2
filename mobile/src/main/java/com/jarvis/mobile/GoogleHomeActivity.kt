package com.jarvis.mobile

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.home.ForcePermissionFlow
import com.google.home.Home
import com.google.home.HomeClient
import com.google.home.HomeConfig
import com.google.home.PermissionsResultStatus
import com.google.home.matter.standard.DimmableLightDevice
import com.google.home.matter.standard.OnOffLightDevice
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class GoogleHomeActivity : AppCompatActivity() {
    private lateinit var client: HomeClient
    private lateinit var status: TextView
    private lateinit var list: LinearLayout
    private val prefs by lazy { getSharedPreferences("jarvis_mobile", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Google Home · luces"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            setBackgroundColor(Color.rgb(8, 11, 16))
        }
        root.addView(TextView(this).apply {
            text = "Google Home · luces"
            textSize = 25f
            setTextColor(Color.WHITE)
        })
        root.addView(TextView(this).apply {
            text = "Autoriza tu casa con Google. Jarvis importará únicamente las luces y sus habitaciones."
            textSize = 15f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(8), 0, dp(16))
        })
        val authorize = Button(this).apply { text = "AUTORIZAR GOOGLE HOME" }
        root.addView(authorize, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)))
        status = TextView(this).apply {
            text = "Sin autorizar"
            textSize = 14f
            setTextColor(Color.rgb(111, 191, 255))
            setPadding(0, dp(12), 0, dp(10))
        }
        root.addView(status)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(list) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        client = Home.getClient(applicationContext, HomeConfig())
        client.registerActivityResultCallerForPermissions(this)

        authorize.setOnClickListener { requestGoogleHomePermission() }
        lifecycleScope.launch {
            runCatching {
                val granted = client.hasPermissions().first().name.contains("GRANTED", true)
                if (granted) {
                    status.text = "Google Home autorizado"
                    loadLights()
                }
            }
        }
    }

    private fun requestGoogleHomePermission() {
        status.text = "Abriendo autorización de Google Home…"
        lifecycleScope.launch {
            try {
                val result = client.requestPermissions(forcePermissionFlow = ForcePermissionFlow.FORCE_LAUNCH)
                when (result.status) {
                    PermissionsResultStatus.SUCCESS -> {
                        prefs.edit().putBoolean("google_home_authorized", true).apply()
                        status.text = "Google Home autorizado"
                        loadLights()
                    }
                    PermissionsResultStatus.CANCELLED -> status.text = "Autorización cancelada"
                    else -> status.text = "Google Home: ${result.errorMessage ?: result.status.name}"
                }
            } catch (e: Throwable) {
                status.text = "Google Home: ${e.message ?: "error de autorización"}"
            }
        }
    }

    private suspend fun loadLights() {
        status.text = "Leyendo luces…"
        val all = client.devices().list()
        val lights = all.filter { it.has(OnOffLightDevice) || it.has(DimmableLightDevice) }
        val saved = JSONArray()
        list.removeAllViews()
        for (device in lights.sortedBy { it.name.lowercase() }) {
            val room = runCatching { device.room()?.name.orEmpty() }.getOrDefault("")
            var isOn: Boolean? = null
            var controller: (suspend (Boolean) -> Unit)? = null
            runCatching {
                if (device.has(DimmableLightDevice)) {
                    val typed = device.type(DimmableLightDevice).first()
                    typed.standardTraits.onOff?.let { trait ->
                        isOn = trait.onOff
                        controller = { on -> if (on) trait.on() else trait.off() }
                    }
                } else if (device.has(OnOffLightDevice)) {
                    val typed = device.type(OnOffLightDevice).first()
                    typed.standardTraits.onOff?.let { trait ->
                        isOn = trait.onOff
                        controller = { on -> if (on) trait.on() else trait.off() }
                    }
                }
            }
            val obj = JSONObject()
                .put("id", device.id.toString())
                .put("name", device.name)
                .put("room", room)
                .put("on", isOn)
            saved.put(obj)
            addLightCard(device.name, room, isOn, controller)
        }
        prefs.edit().putString("google_home_lights_json", saved.toString()).apply()
        status.text = if (lights.isEmpty()) "Google Home autorizado · no hay luces compartidas" else "${lights.size} luz/luces disponibles"
    }

    private fun addLightCard(name: String, room: String, isOn: Boolean?, controller: (suspend (Boolean) -> Unit)?) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setBackgroundColor(Color.rgb(21, 35, 53))
        }
        card.addView(TextView(this).apply {
            text = "${if (isOn == true) "●" else "○"}  $name"
            textSize = 18f
            setTextColor(Color.WHITE)
        })
        card.addView(TextView(this).apply {
            text = (room.ifBlank { "Sin habitación" }) + " · " + when (isOn) { true -> "Encendida"; false -> "Apagada"; null -> "Estado no disponible" }
            textSize = 14f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(5), 0, dp(8))
        })
        if (controller != null) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(Button(this).apply {
                text = "ENCENDER"
                setOnClickListener { lifecycleScope.launch { runCatching { controller(true); loadLights() }.onFailure { status.text = it.message } } }
            }, LinearLayout.LayoutParams(0, dp(48), 1f))
            row.addView(Button(this).apply {
                text = "APAGAR"
                setOnClickListener { lifecycleScope.launch { runCatching { controller(false); loadLights() }.onFailure { status.text = it.message } } }
            }, LinearLayout.LayoutParams(0, dp(48), 1f))
            card.addView(row)
        }
        list.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
