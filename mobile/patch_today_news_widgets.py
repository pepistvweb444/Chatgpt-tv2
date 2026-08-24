from pathlib import Path

p = Path('mobile/src/main/java/com/jarvis/mobile/MainActivity.kt')
s = p.read_text()

old = '''        return when {
            s.contains("noticia") || s.contains("titular") -> "news"
            s.contains("tiempo") || s.contains("previsión") || s.contains("temperatura") -> "weather"
            s.contains("domótica") || s.contains("luces") || s.contains("persianas") || s.contains("casa") -> "home"
            s.contains("agenda") || s.contains("resumen del día") || s.contains("recordatorio") -> "day"
            else -> null
        }'''
new = '''        return when {
            s.contains("noticia") || s.contains("titular") || s.contains("actualidad") ||
                s.contains("qué me cuentas hoy") || s.contains("que me cuentas hoy") ||
                s.contains("qué me cuenta hoy") || s.contains("que me cuenta hoy") ||
                s.contains("ponme al día") || s.contains("ponme al dia") ||
                s.contains("qué ha pasado hoy") || s.contains("que ha pasado hoy") ||
                s.contains("qué pasa hoy") || s.contains("que pasa hoy") -> "news"
            s.contains("tiempo") || s.contains("previsión") || s.contains("temperatura") -> "weather"
            s.contains("domótica") || s.contains("luces") || s.contains("persianas") || s.contains("casa") -> "home"
            s.contains("agenda") || s.contains("resumen del día") || s.contains("recordatorio") -> "day"
            else -> null
        }'''
if old in s:
    s = s.replace(old, new)
else:
    # Fallback for already patched variants: inject broad news phrases into first news condition.
    s = s.replace('s.contains("noticia") || s.contains("titular") -> "news"',
                  's.contains("noticia") || s.contains("titular") || s.contains("actualidad") || s.contains("qué me cuentas hoy") || s.contains("que me cuentas hoy") || s.contains("qué me cuenta hoy") || s.contains("que me cuenta hoy") || s.contains("ponme al día") || s.contains("ponme al dia") || s.contains("qué ha pasado hoy") || s.contains("que ha pasado hoy") || s.contains("qué pasa hoy") || s.contains("que pasa hoy") -> "news"')

p.write_text(s)
print('Today/news phrases now route to rich news widgets')
