from pathlib import Path

# Add a rich-news container to the TV layout, immediately after the textual transcript.
layout = Path('app/src/main/res/layout/activity_main.xml')
x = layout.read_text()
if '@+id/newsWidgetContainer' not in x:
    anchor = '''                <HorizontalScrollView
                    android:layout_width="match_parent"
                    android:layout_height="52dp"'''
    block = '''                <LinearLayout
                    android:id="@+id/newsWidgetContainer"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:orientation="vertical"
                    android:visibility="gone"
                    android:paddingTop="12dp"
                    android:paddingBottom="8dp" />

'''
    if anchor not in x:
        raise SystemExit('TV layout anchor for news widgets not found')
    x = x.replace(anchor, block + anchor, 1)
    layout.write_text(x)

p = Path('app/src/main/java/com/jarvis/tv/MainActivity.kt')
s = p.read_text()

# Imports used by the media cards.
imports = {
    'import android.graphics.BitmapFactory\n': 'import android.graphics.BitmapFactory\n',
    'import android.graphics.Color\n': 'import android.graphics.Color\n',
    'import android.graphics.drawable.GradientDrawable\n': 'import android.graphics.drawable.GradientDrawable\n',
    'import android.view.Gravity\n': 'import android.view.Gravity\n',
    'import android.view.View\n': 'import android.view.View\n',
    'import android.widget.ImageView\n': 'import android.widget.ImageView\n',
}
insert_after = 'import android.content.pm.PackageManager\n'
for imp in imports:
    if imp not in s:
        s = s.replace(insert_after, insert_after + imp, 1)

# Field and view binding.
field_anchor = '    private lateinit var currentChatLabel: TextView\n'
if 'private lateinit var newsWidgetContainer: LinearLayout' not in s:
    s = s.replace(field_anchor, field_anchor + '    private lateinit var newsWidgetContainer: LinearLayout\n', 1)

bind_anchor = '        currentChatLabel = findViewById(R.id.currentChatLabel)\n'
if 'newsWidgetContainer = findViewById(R.id.newsWidgetContainer)' not in s:
    s = s.replace(bind_anchor, bind_anchor + '        newsWidgetContainer = findViewById(R.id.newsWidgetContainer)\n', 1)

# Render assistant news as widgets instead of raw Markdown.
old_append = '        transcript.append(if (role == "user") "\\nTÚ\\n$text\\n" else "\\n${assistantName().uppercase()}\\n$text\\n")\n'
new_append = '''        if (role == "assistant" && looksLikeNewsResponse(text)) {
            renderNewsWidgets(text)
        } else {
            if (role == "assistant") {
                newsWidgetContainer.removeAllViews()
                newsWidgetContainer.visibility = View.GONE
            }
            transcript.append(if (role == "user") "\\nTÚ\\n$text\\n" else "\\n${assistantName().uppercase()}\\n$text\\n")
        }
'''
if old_append in s:
    s = s.replace(old_append, new_append, 1)
elif 'renderNewsWidgets(text)' not in s:
    raise SystemExit('append() anchor not found for TV news widget patch')

# When reopening a conversation, recover the newest news widget response and suppress its raw Markdown.
old_load_line = '            out.append(item.optString("content")).append("\\n")\n'
new_load_line = '''            val content = item.optString("content")
            if (role == "assistant" && looksLikeNewsResponse(content)) {
                renderNewsWidgets(content)
            } else {
                out.append(content).append("\\n")
            }
'''
if old_load_line in s:
    s = s.replace(old_load_line, new_load_line, 1)

# Helpers are inserted before updateChatMeta.
helper_anchor = '    private fun updateChatMeta(text: String, role: String) {\n'
if 'private fun renderNewsWidgets(text: String)' not in s:
    helpers = r'''    private data class TvNewsItem(val title: String, val body: String, val why: String, val url: String, val source: String)

    private fun looksLikeNewsResponse(text: String): Boolean {
        val t = text.lowercase(Locale.ROOT)
        return (Regex("(?i)widget\\s*\\d+").containsMatchIn(text) && (t.contains("noticia") || t.contains("por qué importa") || t.contains("por que importa")))
            || (t.contains("noticias importantes") && Regex("https?://").containsMatchIn(text))
    }

    private fun cleanNewsMarkdown(value: String): String = value
        .replace(Regex("\\[([^]]+)]\\((https?://[^)]+)\\)"), "$1")
        .replace(Regex("[*_`#>|]+"), " ")
        .replace(Regex("(?m)^\\s*[-–—]{3,}\\s*$"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun parseNewsItems(text: String): List<TvNewsItem> {
        val starts = Regex("(?im)^\\s*#{2,4}\\s*[^\\n]*?Widget\\s*\\d+\\s*[—-]\\s*(.+)$").findAll(text).toList()
        val out = mutableListOf<TvNewsItem>()
        for ((idx, match) in starts.withIndex()) {
            val from = match.range.last + 1
            val to = if (idx + 1 < starts.size) starts[idx + 1].range.first else text.length
            val section = text.substring(from, to).trim()
            val title = cleanNewsMarkdown(match.groupValues[1])
            val url = Regex("https?://[^\\s)]+", RegexOption.IGNORE_CASE).find(section)?.value?.trimEnd(')', ']', '.', ',').orEmpty()
            val whyMatch = Regex("(?is)\\*{0,2}Por qu[eé] importa:?\\*{0,2}\\s*(.+?)(?=\\n\\s*[-–—]{3,}|$)").find(section)
            val why = whyMatch?.groupValues?.getOrNull(1)?.let(::cleanNewsMarkdown).orEmpty()
            val beforeWhy = if (whyMatch != null) section.substring(0, whyMatch.range.first) else section
            val body = cleanNewsMarkdown(beforeWhy).replace(url, "").trim().take(650)
            val source = runCatching { URL(url).host.removePrefix("www.") }.getOrDefault("")
            if (title.isNotBlank()) out.add(TvNewsItem(title, body, why, url, source))
        }
        return out.take(10)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radiusDp: Float = 20f): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = dp(radiusDp.toInt()).toFloat()
        setStroke(dp(1), Color.rgb(63, 74, 94))
    }

    private fun textView(text: String, size: Float, bold: Boolean = false, color: Int = Color.WHITE): TextView = TextView(this).apply {
        this.text = text
        setTextColor(color)
        textSize = size
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setLineSpacing(0f, 1.08f)
    }

    private fun renderNewsWidgets(text: String) {
        val items = parseNewsItems(text)
        if (items.isEmpty()) {
            transcript.append("\\n${assistantName().uppercase()} · Noticias\\n${cleanNewsMarkdown(text)}\\n")
            return
        }
        newsWidgetContainer.removeAllViews()
        newsWidgetContainer.visibility = View.VISIBLE

        val heading = textView("Jarvis · Noticias", 18f, true, Color.rgb(205, 213, 255)).apply {
            setPadding(dp(4), dp(6), dp(4), dp(12))
        }
        newsWidgetContainer.addView(heading)

        items.forEach { item ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(Color.rgb(20, 25, 36))
                setPadding(dp(14), dp(14), dp(16), dp(14))
                isFocusable = true
                isClickable = true
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(12)
                }
                if (item.url.isNotBlank()) setOnClickListener {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.url))) }
                }
            }

            val image = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(260), dp(154)).apply { marginEnd = dp(18) }
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Color.rgb(32, 39, 54))
                contentDescription = item.title
            }
            card.addView(image)

            val copy = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            copy.addView(textView(item.title, 20f, true))
            if (item.source.isNotBlank()) copy.addView(textView(item.source, 13f, false, Color.rgb(145, 163, 193)).apply { setPadding(0, dp(5), 0, dp(7)) })
            if (item.body.isNotBlank()) copy.addView(textView(item.body, 16f, false, Color.rgb(232, 236, 244)))
            if (item.why.isNotBlank()) copy.addView(textView("Por qué importa: ${item.why}", 14f, true, Color.rgb(178, 190, 255)).apply { setPadding(0, dp(9), 0, 0) })
            if (isVideoUrl(item.url)) copy.addView(textView("▶  Vídeo", 14f, true, Color.rgb(120, 200, 255)).apply { setPadding(0, dp(9), 0, 0) })
            card.addView(copy)
            newsWidgetContainer.addView(card)

            if (item.url.isNotBlank()) loadNewsPreview(item.url, image)
        }
    }

    private fun isVideoUrl(url: String): Boolean {
        val u = url.lowercase(Locale.ROOT)
        return u.contains("youtube.com") || u.contains("youtu.be") || u.contains("/video/") || u.contains("vimeo.com")
    }

    private fun youtubeThumbnail(url: String): String? {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        val host = uri.host.orEmpty().lowercase(Locale.ROOT)
        val id = when {
            host.contains("youtu.be") -> uri.lastPathSegment
            uri.getQueryParameter("v") != null -> uri.getQueryParameter("v")
            uri.pathSegments.contains("shorts") -> uri.pathSegments.getOrNull(uri.pathSegments.indexOf("shorts") + 1)
            uri.pathSegments.contains("embed") -> uri.pathSegments.getOrNull(uri.pathSegments.indexOf("embed") + 1)
            else -> null
        }
        return id?.takeIf { it.isNotBlank() }?.let { "https://img.youtube.com/vi/$it/hqdefault.jpg" }
    }

    private fun discoverPreviewImage(articleUrl: String): String? {
        youtubeThumbnail(articleUrl)?.let { return it }
        return runCatching {
            val c = (URL(articleUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 7000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0 JarvisTV/0.6.6")
                setRequestProperty("Accept", "text/html,application/xhtml+xml")
            }
            val html = c.inputStream.bufferedReader().use { it.readText().take(700000) }
            val patterns = listOf(
                Regex("(?is)<meta[^>]+property=[\\\"']og:image[\\\"'][^>]+content=[\\\"']([^\\\"']+)", RegexOption.IGNORE_CASE),
                Regex("(?is)<meta[^>]+content=[\\\"']([^\\\"']+)[\\\"'][^>]+property=[\\\"']og:image[\\\"']", RegexOption.IGNORE_CASE),
                Regex("(?is)<meta[^>]+name=[\\\"']twitter:image(?::src)?[\\\"'][^>]+content=[\\\"']([^\\\"']+)", RegexOption.IGNORE_CASE),
                Regex("(?is)<meta[^>]+content=[\\\"']([^\\\"']+)[\\\"'][^>]+name=[\\\"']twitter:image(?::src)?[\\\"']", RegexOption.IGNORE_CASE)
            )
            patterns.firstNotNullOfOrNull { it.find(html)?.groupValues?.getOrNull(1) }
                ?.replace("&amp;", "&")
                ?.let { if (it.startsWith("//")) "https:$it" else it }
        }.getOrNull()
    }

    private fun loadNewsPreview(articleUrl: String, target: ImageView) {
        Thread {
            val preview = discoverPreviewImage(articleUrl) ?: return@Thread
            val bmp = runCatching {
                val c = URL(preview).openConnection().apply {
                    connectTimeout = 6000
                    readTimeout = 7000
                    setRequestProperty("User-Agent", "Mozilla/5.0 JarvisTV/0.6.6")
                }
                c.getInputStream().use { BitmapFactory.decodeStream(it) }
            }.getOrNull() ?: return@Thread
            runOnUiThread { target.setImageBitmap(bmp) }
        }.start()
    }

'''
    if helper_anchor not in s:
        raise SystemExit('updateChatMeta anchor not found for news helpers')
    s = s.replace(helper_anchor, helpers + helper_anchor, 1)

p.write_text(s)
print('TV rich news widgets applied')
