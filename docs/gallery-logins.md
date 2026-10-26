# Jarvis Galería (TV) — fuentes e inicios de sesión
Launcher "Jarvis Galería" (activity `com.jarvis.tv.gallery.GalleryActivity`).
- Móvil (Wi-Fi, sin login): usa el emparejamiento de Javistv (IP + PIN) y el servidor :8766 de Jarvis Mobile.
- Google Fotos: OAuth device flow (QR) + Photos **Picker** API (scope `photospicker.mediaitems.readonly`); el usuario elige fotos/álbumes en el móvil.
- OneDrive / Samsung Gallery: device-code de Microsoft identity, Graph `me/drive/special/cameraroll` y `Pictures/Samsung Gallery`.
- Cuenta Jarvis: Supabase Auth email+contraseña; fotos en bucket privado `jarvis-gallery/<uid>/` (migración en supabase/migrations, aplicar en preview).
Samsung Account no ofrece OAuth a terceros: no implementado.
Config: `/sdcard/Android/data/com.jarvis.tv/files/gallery-config.json` (o `/sdcard/Download/jarvis-gallery-config.json`). Ejemplo: docs/gallery-config.example.json.
