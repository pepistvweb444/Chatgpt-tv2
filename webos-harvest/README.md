# Harvest TV for LG webOS

Primera versión nativa webOS de la interfaz Jarvis/Harvest TV.

## Incluye
- Home dinámica estilo Apple TV / Live Tiles.
- Navegación con flechas + OK del Magic Remote.
- Fila de streaming con panel contextual.
- Apertura de apps instaladas mediante Luna applicationManager.
- Tiles de casa preparados para Homey.
- Panel Jarvis preparado para conectar al backend común.
- Diseño pensado para OLED 4K y webOS 5.

## Empaquetado local
Con webOS CLI:
```
ares-package webos-harvest
```

## Instalación
```
ares-install -d tv com.jarvis.harvesttv_0.1.0_all.ipk
```

La integración con voz, Homey, notificaciones del móvil, chats sincronizados y tiles generados por IA se añadirá sobre esta base.
