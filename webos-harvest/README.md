# Harvest TV for LG webOS

## 0.2.0
Primera Home diseñada específicamente para LG GX / webOS 5.

### Interfaz
- Home 16:9 fija y legible a distancia.
- Tiles tipo Live Tiles + Apple TV.
- "Ahora" con información priorizada por Jarvis.
- Carrusel de streaming.
- Panel contextual inferior al enfocar cada servicio.
- Widgets de casa/Homey.
- Dock estable para Inicio, Ver, Casa, Ahora, Jarvis y Ajustes.
- JavaScript compatible con el Chromium de webOS 5.

### Botón Voice del Magic Remote
Harvest incluye un target seguro para LG Input Hook:
`voice/launch-harvest.sh`

Instala **LG Input Hook** desde Homebrew Channel, lánzalo una vez y usa su visor de logs para identificar el ID exacto del botón Voice de tu mando. El proyecto upstream indica que el ID se obtiene pulsando el botón y leyendo una línea tipo `id => N`.

Crea después un keybind:
- ID: el ID de Voice detectado
- Acción: Execute
- Comando:
  `/usr/bin/luna-send-pub -n 1 -f luna://com.webos.applicationManager/launch '{"id":"com.jarvis.harvesttv"}'`

Así el botón físico abrirá Harvest/Jarvis en lugar de dejar el flujo en la guía/asistente de LG. El audio real del Magic Remote se conectará al daemon Jarvis en la siguiente fase.

### Seguridad
No modifica kernel, rootfs ni TVService. El remapeo de Voice debe probarse primero solo con ese botón y conservar BACK/HOME sin modificar hasta verificar el comportamiento.
