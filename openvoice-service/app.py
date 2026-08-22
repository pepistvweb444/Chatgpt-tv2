import os
import tempfile
from pathlib import Path

import torch
from fastapi import FastAPI, File, HTTPException, UploadFile
from fastapi.responses import FileResponse
from pydantic import BaseModel
from melo.api import TTS
from openvoice import se_extractor
from openvoice.api import ToneColorConverter

app = FastAPI(title="Jarvis OpenVoice", version="0.1")
DEVICE = "cuda:0" if torch.cuda.is_available() else "cpu"
ROOT = Path(os.environ.get("OPENVOICE_DATA", "/data"))
ROOT.mkdir(parents=True, exist_ok=True)
PROFILE_DIR = ROOT / "profiles"
PROFILE_DIR.mkdir(exist_ok=True)
CHECKPOINTS = Path(os.environ.get("OPENVOICE_CHECKPOINTS", "/opt/OpenVoice/checkpoints_v2"))
CONVERTER_DIR = CHECKPOINTS / "converter"
converter = ToneColorConverter(str(CONVERTER_DIR / "config.json"), device=DEVICE)
converter.load_ckpt(str(CONVERTER_DIR / "checkpoint.pth"))
_tts = {}

class SynthesisRequest(BaseModel):
    text: str
    profile: str = "jarvis"
    language: str = "ES"
    speed: float = 1.15


def tts_for(language: str):
    lang = language.upper()
    if lang not in _tts:
        _tts[lang] = TTS(language=lang, device=DEVICE)
    return _tts[lang]


def profile_se(profile: str):
    path = PROFILE_DIR / f"{profile}.pth"
    if not path.exists():
        raise HTTPException(409, f"Voice profile '{profile}' is not enrolled")
    return torch.load(path, map_location=DEVICE)

@app.get("/health")
def health():
    return {"ok": True, "device": DEVICE, "profiles": [p.stem for p in PROFILE_DIR.glob("*.pth")]}

@app.post("/enroll/{profile}")
async def enroll(profile: str, sample: UploadFile = File(...)):
    suffix = Path(sample.filename or "sample.m4a").suffix or ".m4a"
    with tempfile.TemporaryDirectory() as td:
        audio = Path(td) / f"sample{suffix}"
        audio.write_bytes(await sample.read())
        se, _ = se_extractor.get_se(str(audio), converter, vad=True)
        torch.save(se.cpu(), PROFILE_DIR / f"{profile}.pth")
    return {"ok": True, "profile": profile}

@app.post("/synthesize")
def synthesize(req: SynthesisRequest):
    text = req.text.strip()
    if not text:
        raise HTTPException(400, "text_required")
    target_se = profile_se(req.profile).to(DEVICE)
    tts = tts_for(req.language)
    speaker_ids = tts.hps.data.spk2id
    speaker_key = next(iter(speaker_ids.keys()))
    speaker_id = speaker_ids[speaker_key]
    source_se_path = CHECKPOINTS / "base_speakers" / "ses" / f"{speaker_key.lower().replace(' ', '_')}.pth"
    if not source_se_path.exists():
        candidates = list((CHECKPOINTS / "base_speakers" / "ses").glob("*.pth"))
        if not candidates:
            raise HTTPException(500, "No base speaker embeddings found")
        source_se_path = candidates[0]
    source_se = torch.load(source_se_path, map_location=DEVICE)

    with tempfile.TemporaryDirectory() as td:
        base = Path(td) / "base.wav"
        out = Path(td) / "jarvis.wav"
        tts.tts_to_file(text, speaker_id, str(base), speed=max(0.7, min(req.speed, 1.5)))
        converter.convert(
            audio_src_path=str(base),
            src_se=source_se,
            tgt_se=target_se,
            output_path=str(out),
            message="Jarvis"
        )
        final = ROOT / "last.wav"
        final.write_bytes(out.read_bytes())
    return FileResponse(final, media_type="audio/wav", filename="jarvis.wav")
