# Harvest TV (LG webOS) 1.0.110: «🌐 Doblaje» with the voice of the person on screen

IPK: out/com.jarvis.harvesttv_1.0.110_all.ipk (816512 B, sha256 69f881d9ac7e571ed2273f55baa3f31ede83a3fe153b691332b8bd03adb1cd5b)
Base: 1.0.109. Additions only. NOT installed on the TV. Backend NOT deployed.
Branch: pepistvweb444/Chatgpt-tv2 feat/harvest-doblaje-1.0.110 (api/dub-stream.js + harvest-dub-1.0.110/).

## Cloner (the same one Jarvis Mobile uses)
- Jarvis Mobile 0.3.04: BackendConfig.VOICE_CLONE = Contabo https://ctv2.185-245-182-55.sslip.io.
  - /api/voice-clone (enroll) and /api/speech with a profile both go to OpenVoice V2 (MIT, self-hosted, free).
  - It runs in the container others-jarvis-openvoice (jarvis-openvoice:latest, CPU, 3 GB limit), reached as OPENVOICE_URL=http://jarvis-openvoice:8000.
- noiz.ai (paid) is not configured on Contabo (NOIZ_API_KEY unset). The old droplet 165.22.83.150:8000 does not answer; nothing uses it now.
- Health on 25 Nov 2026:
  - GET /api/voice-clone = 200 {"ok":true,"engine":"openvoice"}.
  - OpenVoice logs show enroll 200 (the silero trust issue from 0.2.71 is fixed) and synthesize 200.
- Measured CPU speed, «Jarvis» profile, ES, internal call:

  | Audio length | Time taken |
  |---|---|
  | 3.2 s | 50 s |
  | 6.5 s | 32.5 s |

  That is about 5–15× slower than real time with the server load at about 3. The logs show other single sentences taking 5–18 s.

## How it works
- TV capture (configurable source) is cut into phrases by an energy VAD and sent to /api/dub-stream?action=segment.
  - The backend detects the language and transcribes (Groq whisper verbose_json, then Gemini).
  - If the speech is already in the target language, it is skipped.
  - Speaker fingerprint: 24 log-mel bands plus YIN pitch.
  - The speaker's ~10 s sample is taken from the TV audio itself. It is enrolled provisionally at 3 s and fully at 10 s, as OpenVoice profile dub_<session>_<n>. A new speaker gets a new sample (up to 6 per session).
  - Translation: Gemini, then Groq Llama. OpenAI only if DUB_ALLOW_OPENAI=1.
- The TV shows the translation as a toast (subtitles), then calls action=tts with the speaker's cloned voice.
  - Audio that arrives more than maxLagSec late (25 s by default) is dropped.
- Playback lowers the original first:
  - duck mode «sinkinputs»: pactl lowers the other PulseAudio streams;
  - or «volume»: the TV volume goes ×0.35, and the dub gain is 1.6 with soft clipping.
  - Then paplay/aplay/cmd plays the dub, and the original level comes back 0.7 s later.
  - Capture is gated while the dub plays, so Jarvis does not re-dub itself.
- «🎙 Tomar muestra 10 s» takes the next 10 s of voiced TV audio as the current speaker's voice.

## TV files
- voice/harvest_dub1110.js: daemon module. Routes /hv/dub/status|on|off|target|sample|reset|test-capture|config, all refusing web Origins. Voice commands: «dobla al castellano/inglés…», «activa / quita el doblaje», «toma muestra de la voz», «estado del doblaje».
- jarvis-dub-1.0.110.js: «🌐 Doblaje» button in the top bar and its panel.
- voice/dub-probe.sh: read-only probe. Also in out/, with out/dub-diag-termux.sh.
- Daemon edits: HV_VERSION 1.0.110, one route line, init block (autostart only if enabled), voice hook. index.html gets one script tag. appinfo and packageinfo set to 1.0.110.
- Config: /media/developer/harvest-data/dub.json (0600).
  - enabled:false by default.
  - The token is write-only and goes to curl only through a 0600 -K file, never in argv, the URL or logs.

## Tests
- Backend: scripts/test-dub-stream.mjs, 14/14 ok (mocked providers, no network).
- TV:
  - tests110/dub.test.js 15/15 on Node 8.17 and Node 24.
  - tests110/test_1110_ui.py ok (Chrome headless, 0 page errors; screenshot out/dub110-panel.png).
  - ES5 lint ok.
  - Isolated Node 8 daemon boot logs «[1.0.110] doblaje ready». /hv/dub/status = 200; with an evil Origin it is 403.
- Regression:
  - tests107 core/daemon/series, tests108 buds/cast/yt, tests109 desk, 1101 clip, 1106 remote: all pass. For 1106 the version pin was widened to 1.0.11x.
  - 1102 np fails exactly as on 1.0.109.
- Not tested on the real TV: whether any capture source carries Netflix/Prime/live-TV audio; paplay on the TV; real ducking behaviour.

## To try (in order)
1. Run the probe from Termux with Netflix playing, then again with live TV on:
   `sh /sdcard/Download/dub-diag-termux.sh` (dub-probe.sh must be in the same folder).
2. Backend (needs approval): copy api/dub-stream.js to /opt/others/vercel/chatgpt-tv2/app/api/ on Contabo, add env DUB_TOKEN (random) to others-chatgpt-tv2, and restart that container only.
3. TV (needs approval), the usual way:
   - backup tar;
   - `luna-send … appInstallService/dev/install` with com.jarvis.harvesttv_1.0.110_all.ipk;
   - `sh …/voice/enable-harvest-voice.sh`.
4. In the TV panel: Captura = the source the probe found; Probar captura 5 s; paste the token; Activar.
- Rollback: tar -xzf /media/developer/harvest-backup-antes-1.0.110.tar.gz -C /media/developer/apps/usr/palm/applications, then rerun enable-harvest-voice.sh.

## Backend deployed on Contabo (25 Nov 2026, 16:20 CET, approved by Alberto)
- Files changed:
  - /opt/others/vercel/chatgpt-tv2/app/api/dub-stream.js: branch commit 7d1602f, sha256 f892b602….
  - DUB_TOKEN added to /opt/others/vercel/chatgpt-tv2/.env. A copy is on the box in /workspace/secrets-local/dub_token.txt (0600).
- Container: only others-chatgpt-tv2 was recreated (docker compose up -d --no-deps --force-recreate app). init-staging-* were not touched.
- Backup: /opt/others/backups/dub-stream-1.0.110-20261125-162052 (.env, compose.yaml, docker inspect, api listing).
- Rollback: sh /opt/others/rollback_dub-stream_1.0.110.sh. It removes the file, restores .env and recreates only that container.
- Fix found while deploying: OpenVoice enroll fails («input audio is too short») with less than about 5–6 s of speech. The provisional voice is now enrolled at 6 s (not 3 s); a failed enroll is retried only after 2 s more audio.
- Verified:
  - Jarvis routes are unchanged against the baseline (voice-clone, weather, news and capabilities 200; speech-dub, translate-audio and tv-fast 405 on GET; sync 401).
  - dub-stream GET 200 configured:true.
  - POST without a token or with a wrong token gives 401.
  - With the token, an espeak English test clip (7.2 s): STT en in about 0.3 s, es-ES translation, segment in 3.0 s. The 2nd phrase gives voice clone-provisional (8.9 s sample).
  - tts gives 7.9 s of cloned wav in 38.4 s, so the CPU is the bottleneck.
