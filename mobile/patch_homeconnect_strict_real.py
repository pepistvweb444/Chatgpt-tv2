from pathlib import Path
p=Path('mobile/src/main/java/com/jarvis/mobile/MainActivity.kt')
s=p.read_text()

# Make Home Connect cards visibly provenance-safe. These cards are only created
# from fetchHomeConnectDevices(), which reads GET /api/homeappliances directly.
old='''        card.addView(TextView(this).apply { text = d.name; textSize=17f; setTextColor(Color.WHITE); setTypeface(typeface, android.graphics.Typeface.BOLD) })'''
new='''        card.addView(TextView(this).apply { text = d.name; textSize=17f; setTextColor(Color.WHITE); setTypeface(typeface, android.graphics.Typeface.BOLD) })
        card.addView(TextView(this).apply {
            text = "Home Connect API · ${d.type.ifBlank { "tipo no informado" }} · ID ${d.haId.takeLast(8)}"
            textSize = 11f
            setTextColor(Color.rgb(166, 205, 190))
            setPadding(0, dp(3), 0, dp(3))
        })'''
if old in s and 'Home Connect API · ${d.type' not in s:
    s=s.replace(old,new,1)

# Never present inferred/generic devices as Home Connect. Empty official API means empty.
old2='''                    if (devices.isEmpty()) addTextWidget("home", "Home Connect", "No hay electrodomésticos disponibles")'''
new2='''                    if (devices.isEmpty()) addTextWidget("home", "Home Connect · API oficial", "La API de tu cuenta ha devuelto 0 electrodomésticos. Jarvis no añadirá dispositivos simulados ni inferidos.")'''
if old2 in s:
    s=s.replace(old2,new2,1)

# Make connection failures explicit instead of falling back to generated text.
old3='''runOnUiThread { beginWidgetGroup("Home Connect"); addTextWidget("home", "No puedo leer Home Connect", e.message ?: "Error"); status.text = "Jarvis listo" }'''
new3='''runOnUiThread { beginWidgetGroup("Home Connect · API oficial"); addTextWidget("home", "No puedo leer tus electrodomésticos reales", e.message ?: "Error de Home Connect"); status.text = "Jarvis listo" }'''
if old3 in s:
    s=s.replace(old3,new3,1)

p.write_text(s)
print('Strict real Home Connect API provenance applied')
