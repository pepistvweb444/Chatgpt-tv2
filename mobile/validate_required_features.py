from pathlib import Path
import sys

checks = {
    "mobile/src/main/java/com/jarvis/mobile/MainActivity.kt": [
        "toggleEmbeddedVoiceRecognition",
        "showRoomAssignmentDialog",
        "showUnifiedDomoticsWidget",
        "homey_devices_json",
        "PhoneAgentController",
    ],
    "mobile/src/main/java/com/jarvis/mobile/PhoneAgentController.kt": [
        "openWebSearch",
        "openUrl",
        "preferredProvider",
    ],
    "mobile/src/main/java/com/jarvis/mobile/HomeyActivity.kt": [
        "CONECTAR / AUTORIZAR HOMEY CLOUD",
        "/api/domotics/homey",
    ],
    "mobile/src/main/java/com/jarvis/mobile/WakeWordService.kt": [
        "org.vosk",
    ],
    "mobile/src/main/java/com/jarvis/mobile/JarvisOverlayService.kt": [
        "WindowManager",
    ],
    "mobile/src/main/java/com/jarvis/mobile/JarvisCallScreeningService.kt": [
        "IncomingCallPresenter.show",
    ],
    "mobile/src/main/java/com/jarvis/mobile/PhoneBridgeService.kt": [
        "CallStateStore.current(this)",
    ],
    "mobile/src/main/AndroidManifest.xml": [
        ".HomeyActivity",
        ".JarvisAccessibilityService",
        ".WakeWordService",
        ".JarvisCallScreeningService",
    ],
}

failed = []
for filename, markers in checks.items():
    p = Path(filename)
    if not p.exists():
        failed.append(f"MISSING FILE: {filename}")
        continue
    text = p.read_text(errors="replace")
    for marker in markers:
        if marker not in text:
            failed.append(f"MISSING FEATURE: {marker!r} in {filename}")

if failed:
    print("Jarvis feature validation FAILED")
    for item in failed:
        print(" -", item)
    sys.exit(1)

print("Jarvis feature validation OK")
print(" - embedded Vosk voice present")
print(" - persistent wake/overlay present")
print(" - Homey Cloud integration present")
print(" - room assignment/domotics integration present")
print(" - phone agent browser/app helpers present")
print(" - incoming call mobile/TV bridge hooks present")
