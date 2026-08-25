from pathlib import Path
import shutil
import tempfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parent
ASSETS = ROOT / "src" / "main" / "assets"
MODEL_DIR = ASSETS / "model-es"
MARKER = MODEL_DIR / "am" / "final.mdl"
URL = "https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip"

if MARKER.exists():
    print("Vosk Spanish model already present")
    raise SystemExit(0)

ASSETS.mkdir(parents=True, exist_ok=True)
with tempfile.TemporaryDirectory(prefix="jarvis-vosk-") as td:
    td = Path(td)
    archive = td / "model.zip"
    print("Downloading embedded Spanish Vosk model...")
    req = urllib.request.Request(URL, headers={"User-Agent": "Jarvis-Mobile-CI/1.0"})
    with urllib.request.urlopen(req, timeout=120) as response, archive.open("wb") as out:
        shutil.copyfileobj(response, out)

    with zipfile.ZipFile(archive) as zf:
        zf.extractall(td / "unpacked")

    source = td / "unpacked" / "vosk-model-small-es-0.42"
    if not (source / "am" / "final.mdl").exists():
        raise SystemExit("Downloaded Vosk model has unexpected structure")

    if MODEL_DIR.exists():
        shutil.rmtree(MODEL_DIR)
    shutil.copytree(source, MODEL_DIR)
    (MODEL_DIR / "uuid").write_text("jarvis-vosk-es-0.42\n", encoding="utf-8")

if not MARKER.exists():
    raise SystemExit("Vosk model preparation failed")
print("Embedded Spanish Vosk model ready")
