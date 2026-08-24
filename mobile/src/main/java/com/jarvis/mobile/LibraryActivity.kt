package com.jarvis.mobile

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LibraryActivity : AppCompatActivity() {
    private lateinit var listHost: LinearLayout
    private lateinit var empty: TextView
    private var filter = "all"

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(12))
            setBackgroundColor(Color.rgb(8, 11, 16))
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(this).apply {
            text = "‹"; textSize = 34f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(48))
            setOnClickListener { finish() }
        })
        head.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(this@LibraryActivity).apply { text = "Biblioteca"; textSize = 26f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD) })
            addView(TextView(this@LibraryActivity).apply { text = "Todo lo creado y usado con Jarvis"; textSize = 12f; setTextColor(Color.rgb(150, 161, 180)) })
        })
        head.addView(TextView(this).apply {
            text = "+"; textSize = 28f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            background = pill(Color.rgb(26, 31, 43))
            setOnClickListener { importFile() }
        })
        root.addView(head)

        val filters = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val filterRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(14), 0, dp(10)) }
        listOf(
            "all" to "Todo", "image" to "Imágenes", "video" to "Vídeos", "document" to "Documentos",
            "web" to "Web", "apk" to "APKs", "project" to "Proyectos", "mail" to "Correos"
        ).forEach { (key, label) ->
            filterRow.addView(TextView(this).apply {
                text = label; textSize = 13f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                setPadding(dp(14), dp(9), dp(14), dp(9)); background = pill(if (key == filter) Color.rgb(66, 54, 150) else Color.rgb(25, 31, 43))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) }
                setOnClickListener { filter = key; recreateFilter(filterRow); refresh() }
                tag = key
            })
        }
        filters.addView(filterRow); root.addView(filters)

        empty = TextView(this).apply {
            text = "Todavía no hay elementos en esta sección.\nLas imágenes, vídeos, documentos, APKs y proyectos creados con Jarvis aparecerán aquí automáticamente."
            textSize = 15f; setTextColor(Color.rgb(160, 170, 188)); gravity = Gravity.CENTER; setPadding(dp(20), dp(70), dp(20), dp(30))
        }
        listHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, dp(24)) }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(empty); addView(listHost) }
        root.addView(ScrollView(this).apply { addView(content); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f) })
        setContentView(root)
        refresh()
    }

    private fun recreateFilter(row: LinearLayout) {
        for (i in 0 until row.childCount) {
            val v = row.getChildAt(i) as? TextView ?: continue
            v.background = pill(if (v.tag == filter) Color.rgb(66, 54, 150) else Color.rgb(25, 31, 43))
        }
    }

    private fun refresh() {
        listHost.removeAllViews()
        val items = LibraryStore.all(this).filter { filter == "all" || it.type == filter }
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        items.forEach { item -> listHost.addView(itemCard(item)) }
    }

    private fun itemCard(item: LibraryStore.Item): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12)); background = pill(Color.rgb(18, 24, 35))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
            setOnClickListener { openItem(item) }
        }
        val preview: View = if (item.type == "image") ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(74), dp(74)).apply { marginEnd = dp(12) }
            scaleType = ImageView.ScaleType.CENTER_CROP; background = pill(Color.rgb(28, 35, 48)); clipToOutline = true
            loadImage(this, item.uri)
        } else TextView(this).apply {
            text = iconFor(item.type); textSize = 27f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(dp(58), dp(58)).apply { marginEnd = dp(12) }; background = pill(Color.rgb(31, 38, 53))
        }
        row.addView(preview)
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(this@LibraryActivity).apply { text = item.title; textSize = 16f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD); maxLines = 2 })
            addView(TextView(this@LibraryActivity).apply { text = "${labelFor(item.type)} · ${item.source}"; textSize = 12f; setTextColor(Color.rgb(164, 177, 198)); setPadding(0, dp(4), 0, 0) })
            addView(TextView(this@LibraryActivity).apply { text = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(item.createdAt)); textSize = 11f; setTextColor(Color.rgb(120, 131, 149)); setPadding(0, dp(3), 0, 0) })
        })
        if (item.conversationId.isNotBlank()) row.addView(TextView(this).apply {
            text = "Chat"; textSize = 12f; setTextColor(Color.rgb(185, 195, 255)); setPadding(dp(10), dp(8), dp(10), dp(8)); background = pill(Color.rgb(41, 37, 77))
            setOnClickListener {
                getSharedPreferences("jarvis_mobile", MODE_PRIVATE).edit().putString("currentConversation", item.conversationId).apply()
                startActivity(Intent(this@LibraryActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
        })
        return row
    }

    private fun openItem(item: LibraryStore.Item) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW).apply { data = Uri.parse(item.uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) })
        }.onFailure { Toast.makeText(this, "No encuentro una aplicación para abrir este elemento", Toast.LENGTH_LONG).show() }
    }

    private fun importFile() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }, REQ_IMPORT)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_IMPORT && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val mime = contentResolver.getType(uri).orEmpty()
            val type = when {
                mime.startsWith("image/") -> "image"
                mime.startsWith("video/") -> "video"
                mime.contains("android.package") -> "apk"
                mime.contains("html") -> "web"
                else -> "document"
            }
            LibraryStore.add(this, type, uri.lastPathSegment?.substringAfterLast('/') ?: "Archivo", uri.toString(), mime.ifBlank { "*/*" }, "Importado")
            refresh()
        }
    }

    private fun loadImage(view: ImageView, uri: String) {
        Thread {
            val bmp = runCatching {
                if (uri.startsWith("http")) URL(uri).openConnection().apply { connectTimeout = 4500; readTimeout = 6500 }.getInputStream().use { BitmapFactory.decodeStream(it) }
                else contentResolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
            if (bmp != null) runOnUiThread { view.setImageBitmap(bmp) }
        }.start()
    }

    private fun pill(color: Int) = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(color) }
    private fun iconFor(type: String) = when (type) { "video" -> "▶"; "document" -> "▤"; "web" -> "◎"; "apk" -> "⬡"; "project" -> "⌘"; "mail" -> "✉"; else -> "▣" }
    private fun labelFor(type: String) = when (type) { "image" -> "Imagen"; "video" -> "Vídeo"; "document" -> "Documento"; "web" -> "Aplicación web"; "apk" -> "APK"; "project" -> "Proyecto"; "mail" -> "Resumen de correo"; else -> "Archivo" }

    companion object { private const val REQ_IMPORT = 850 }
}
