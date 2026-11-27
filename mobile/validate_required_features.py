from pathlib import Path
import sys

checks = {
    "mobile/src/main/java/com/jarvis/mobile/FutureVisionActivity.kt": [
        "presentCall",
        "Llamar desde Jarvis",
        "InitTPV",
    ],
    "mobile/src/main/java/com/jarvis/mobile/MainActivity.kt": [
        "PhoneAgentController",
        "DeliveryCoordinator",
        "showCartApproval",
    ],
    "mobile/src/main/java/com/jarvis/mobile/PhoneAgentController.kt": [
        "openWebSearch",
        "openUrl",
        "preferredProvider",
        "CartLoopGuard",
    ],
    "mobile/src/main/java/com/jarvis/mobile/JarvisCallScreeningService.kt": [
        "IncomingCallPresenter.show",
        "CallSessionWatcher.afterCallPublished",
    ],
    "mobile/src/main/java/com/jarvis/mobile/PhoneBridgeService.kt": [
        "CallStateStore.current(this)",
    ],
    "logic/src/main/kotlin/com/jarvis/logic/Cart.kt": [
        "class IdempotentCart",
        "fun payRequest",
    ],
    "mobile/src/main/AndroidManifest.xml": [
        ".FutureVisionActivity",
        ".JarvisAccessibilityService",
        ".JarvisCallScreeningService",
        "WRITE_CONTACTS",
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
print(" - future vision call cards present")
print(" - idempotent delivery carts present")
print(" - phone agent loop guard present")
print(" - incoming call bridge hooks present")
