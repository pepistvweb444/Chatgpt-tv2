// Harvest TV 1.0.110 «🌐 Doblaje» backend: TV audio -> language detection + transcript -> translation -> the SAME voice
// cloner Jarvis Mobile uses (self-hosted OpenVoice V2, OPENVOICE_URL, /enroll + /synthesize), with the voice of the person
// speaking in the video (sample taken from the TV audio itself, ~10 s per speaker; a new speaker gets a new sample).
//
// Two calls per phrase so subtitles can show before the (slow, CPU) voice is ready:
//   POST /api/dub-stream?action=segment&session=S&target=es   body: PCM s16le mono 16 kHz (one VAD phrase, max 30 s)
//        -> { ok, skip?, seg, lang, transcript, translation, speaker, voice:'pending'|'clone-provisional'|'clone' }
//   POST /api/dub-stream?action=tts&session=S&seg=N           -> { ok, audioB64 (wav), mime, ms }
//   POST /api/dub-stream?action=sample&session=S              body: ~10 s PCM of the current speaker -> re-enroll now
//   POST /api/dub-stream?action=reset&session=S               forget the session speakers (profiles stay on OpenVoice, tiny .pth)
//   GET  /api/dub-stream                                      health, no secrets, no token needed
// Auth: header x-dub-token must equal env DUB_TOKEN. Without DUB_TOKEN configured every POST answers 503 (nothing is open by default).
// Free providers first: STT Groq whisper (free tier) -> Gemini; translation Gemini -> Groq LLM; OpenAI only if DUB_ALLOW_OPENAI=1.
import crypto from 'node:crypto';

export const config = { api: { bodyParser: false } };

const RATE = 16000;
const SESSIONS = new Map();               // in memory, per process
const SESSION_TTL_MS = 2 * 3600 * 1000;
const MAX_SPEAKERS = 6;
// OpenVoice se_extractor splits the VAD'd sample in ~10 s chunks and rounds: under ~5-6 s of speech it fails («input audio is too short»)
const PROVISIONAL_SEC = Math.max(6, Number(process.env.DUB_PROVISIONAL_SEC || 6));
const FULL_SEC = Number(process.env.DUB_SAMPLE_SEC || 10);
const SAME_SPK = Number(process.env.DUB_SPEAKER_SIM || 0.86);

// OpenVoice V2 (MeloTTS base) languages
const OV_LANG = { es: 'ES', en: 'EN', fr: 'FR', zh: 'ZH', ja: 'JP', ko: 'KR' };
const LANG_NAME = { es: 'Spanish from Spain (castellano, es-ES)', en: 'English', fr: 'French', zh: 'Mandarin Chinese', ja: 'Japanese', ko: 'Korean' };
const WHISPER_LANG = { spanish: 'es', english: 'en', french: 'fr', german: 'de', italian: 'it', portuguese: 'pt', chinese: 'zh', japanese: 'ja',
  korean: 'ko', russian: 'ru', arabic: 'ar', dutch: 'nl', turkish: 'tr', polish: 'pl', catalan: 'ca', basque: 'eu', hindi: 'hi' };

export function normTarget(raw) {
  const t = String(raw || 'es').toLowerCase().trim().replace('_', '-');
  const base = t.split('-')[0];
  return OV_LANG[base] ? base : null;
}
export function normLang(raw) {
  const v = String(raw || '').toLowerCase().trim();
  if (!v) return '';
  if (WHISPER_LANG[v]) return WHISPER_LANG[v];
  return v.split(/[-_]/)[0].slice(0, 3);
}
export function safeSession(v) { const s = String(v || '').toLowerCase(); return /^[a-z0-9]{4,24}$/.test(s) ? s : null; }

async function readBody(req) {
  if (Buffer.isBuffer(req.rawBody)) return req.rawBody;      // Contabo runner already read it
  if (Buffer.isBuffer(req.body)) return req.body;
  const chunks = [];
  for await (const c of req) chunks.push(Buffer.from(c));
  return Buffer.concat(chunks);
}

export function wav(pcm, rate = RATE) {
  const h = Buffer.alloc(44);
  h.write('RIFF', 0); h.writeUInt32LE(36 + pcm.length, 4); h.write('WAVE', 8); h.write('fmt ', 12);
  h.writeUInt32LE(16, 16); h.writeUInt16LE(1, 20); h.writeUInt16LE(1, 22); h.writeUInt32LE(rate, 24);
  h.writeUInt32LE(rate * 2, 28); h.writeUInt16LE(2, 32); h.writeUInt16LE(16, 34); h.write('data', 36); h.writeUInt32LE(pcm.length, 40);
  return Buffer.concat([h, pcm]);
}
function samples(pcm) { const n = Math.floor(pcm.length / 2), s = new Float32Array(n); for (let i = 0; i < n; i++) s[i] = pcm.readInt16LE(i * 2) / 32768; return s; }

// ---------- crude speaker fingerprint (no extra model): 24 log band energies of voiced frames + mean pitch ----------
function fftMag(frame) {           // radix-2, frame.length = 512
  const n = frame.length, re = Float64Array.from(frame), im = new Float64Array(n);
  for (let i = 1, j = 0; i < n; i++) { let b = n >> 1; for (; j & b; b >>= 1) j ^= b; j ^= b; if (i < j) { [re[i], re[j]] = [re[j], re[i]]; } }
  for (let len = 2; len <= n; len <<= 1) {
    const a = -2 * Math.PI / len, wr = Math.cos(a), wi = Math.sin(a);
    for (let i = 0; i < n; i += len) { let cr = 1, ci = 0;
      for (let k = 0; k < len / 2; k++) { const ur = re[i + k], ui = im[i + k], vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci, vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr;
        re[i + k] = ur + vr; im[i + k] = ui + vi; re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi; const t = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = t; } }
  }
  const m = new Float64Array(n / 2); for (let i = 0; i < n / 2; i++) m[i] = Math.hypot(re[i], im[i]); return m;
}
function pitch(frame) {             // YIN (cumulative mean normalized difference), 70-400 Hz
  const maxL = Math.floor(RATE / 70), minL = Math.floor(RATE / 400), W = frame.length - maxL; if (W < 160) return 0;
  let run = 0; const d = new Float64Array(maxL + 1);
  for (let l = 1; l <= maxL; l++) { let sum = 0; for (let i = 0; i < W; i++) { const x = frame[i] - frame[i + l]; sum += x * x; } run += sum; d[l] = run ? sum * l / run : 1; }
  for (let l = minL; l < maxL; l++) if (d[l] < 0.15) { while (l + 1 < maxL && d[l + 1] < d[l]) l++; return RATE / l; }
  return 0;
}
export function voiced(pcm) {       // returns { pcm: voiced-only PCM, sec }
  const s = samples(pcm), F = 320, keep = [], es = [];
  for (let i = 0; i + F <= s.length; i += F) { let e = 0; for (let k = 0; k < F; k++) e += s[i + k] * s[i + k]; es.push(Math.sqrt(e / F)); }
  const sorted = es.filter(e => e > 1e-5).sort((a, b) => a - b); if (!sorted.length) return { pcm: Buffer.alloc(0), sec: 0 };
  const p10 = sorted[Math.floor(sorted.length * 0.1)], med = sorted[Math.floor(sorted.length * 0.5)];
  const thr = Math.max(0.006, Math.min(p10 * 3, med * 0.5));
  es.forEach((e, idx) => { if (e > thr && e < 0.99) keep.push(pcm.subarray(idx * F * 2, (idx + 1) * F * 2)); });
  const out = Buffer.concat(keep); return { pcm: out, sec: out.length / 2 / RATE };
}
export function fingerprint(pcm) {
  const s = samples(pcm), N = 512, bands = new Float64Array(24); let frames = 0, f0 = 0, f0n = 0;
  const edges = Array.from({ length: 25 }, (_, i) => { const mel = 2595 * Math.log10(1 + 80 / 700) + i * (2595 * Math.log10(1 + 7600 / 700) - 2595 * Math.log10(1 + 80 / 700)) / 24; return Math.min(N / 2 - 1, Math.round((700 * (10 ** (mel / 2595) - 1)) / (RATE / N))); });
  for (let i = 0; i + N <= s.length; i += N / 2) {
    const fr = new Float64Array(N); let e = 0; for (let k = 0; k < N; k++) { fr[k] = s[i + k] * (0.5 - 0.5 * Math.cos(2 * Math.PI * k / (N - 1))); e += fr[k] * fr[k]; }
    if (Math.sqrt(e / N) < 0.004) continue;
    const m = fftMag(fr); for (let b = 0; b < 24; b++) { let a = 0; for (let k = edges[b]; k <= Math.max(edges[b], edges[b + 1]); k++) a += m[k] * m[k]; bands[b] += Math.log(a + 1e-9); }
    frames++; if (frames % 4 === 0 && i + 640 <= s.length) { const p = pitch(s.subarray(i, i + 640)); if (p) { f0 += p; f0n++; } }
  }
  if (!frames) return null;
  const v = Array.from(bands, x => x / frames); const mean = v.reduce((a, b) => a + b, 0) / v.length;
  return { v: v.map(x => x - mean), f0: f0n ? f0 / f0n : 0, frames };
}
export function similarity(a, b) {
  if (!a || !b) return 0;
  let d = 0, na = 0, nb = 0; for (let i = 0; i < a.v.length; i++) { d += a.v[i] * b.v[i]; na += a.v[i] ** 2; nb += b.v[i] ** 2; }
  let cos = d / (Math.sqrt(na * nb) || 1);
  if (a.f0 && b.f0) { const r = Math.abs(Math.log2(a.f0 / b.f0)); cos -= Math.max(0, r - 0.25) * 0.6; }   // > ~3 semitones apart lowers it
  return cos;
}
function blend(a, b, w) { return { v: a.v.map((x, i) => x * (1 - w) + b.v[i] * w), f0: a.f0 && b.f0 ? a.f0 * (1 - w) + b.f0 * w : (a.f0 || b.f0), frames: a.frames + b.frames }; }

// ---------- session state ----------
export function session(id, now = Date.now()) {
  for (const [k, s] of SESSIONS) if (now - s.at > SESSION_TTL_MS) SESSIONS.delete(k);
  let s = SESSIONS.get(id);
  if (!s) { s = { id, at: now, speakers: [], current: null, seq: 0, segs: new Map(), nextSpk: 1 }; SESSIONS.set(id, s); }
  s.at = now; return s;
}
export function pickSpeaker(s, fp, voicedSec) {
  if (!fp || voicedSec < 1.2) return s.current ? s.speakers.find(x => x.id === s.current) || null : null;   // too short to judge: same speaker
  let best = null, bestSim = -1;
  for (const sp of s.speakers) { const sim = similarity(fp, sp.fp); if (sim > bestSim) { bestSim = sim; best = sp; } }
  if (best && bestSim >= SAME_SPK) { best.fp = blend(best.fp, fp, 0.2); best.at = Date.now(); s.current = best.id; return best; }
  if (s.speakers.length >= MAX_SPEAKERS) s.speakers.sort((a, b) => b.at - a.at).pop();
  const sp = { id: s.nextSpk++, fp, sample: Buffer.alloc(0), enrolledSec: 0, profile: null, at: Date.now(), sim: bestSim, failAt: 0 };
  sp.profileId = `dub_${s.id}_${sp.id}`;
  s.speakers.push(sp); s.current = sp.id; return sp;
}

// ---------- providers ----------
function ovBase() { return (process.env.OPENVOICE_URL || 'http://jarvis-openvoice:8000').replace(/\/$/, ''); }
async function ovEnroll(profile, pcm) {
  const form = new FormData(); form.append('sample', new Blob([wav(pcm)], { type: 'audio/wav' }), 'sample.wav');
  const r = await fetch(`${ovBase()}/enroll/${encodeURIComponent(profile)}`, { method: 'POST', body: form, signal: AbortSignal.timeout(60000) });
  if (!r.ok) throw new Error(`openvoice_enroll_http_${r.status}`);
}
async function ovSynth(profile, text, target, speed) {
  const r = await fetch(`${ovBase()}/synthesize`, { method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ text: text.slice(0, 600), profile, language: OV_LANG[target], speed }), signal: AbortSignal.timeout(120000) });
  if (!r.ok) throw new Error(`openvoice_synth_http_${r.status}`);
  return Buffer.from(await r.arrayBuffer());
}
async function sttGroq(bytes) {
  const key = process.env.GROQ_API_KEY; if (!key) throw new Error('groq_not_configured');
  const form = new FormData();
  form.append('model', process.env.GROQ_TRANSCRIBE_MODEL || 'whisper-large-v3-turbo');
  form.append('response_format', 'verbose_json');
  form.append('file', new Blob([bytes], { type: 'audio/wav' }), 'seg.wav');
  const r = await fetch('https://api.groq.com/openai/v1/audio/transcriptions', { method: 'POST', headers: { Authorization: 'Bearer ' + key }, body: form, signal: AbortSignal.timeout(30000) });
  const d = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error('groq_stt_http_' + r.status);
  const segs = Array.isArray(d.segments) ? d.segments : [];
  const noSpeech = segs.length && segs.every(x => (x.no_speech_prob || 0) > 0.6);
  return { text: noSpeech ? '' : String(d.text || '').trim(), lang: normLang(d.language), provider: 'groq' };
}
async function geminiJson(parts, key) {
  const model = process.env.GEMINI_TRANSLATE_MODEL || process.env.GEMINI_MODEL || 'gemini-2.5-flash';
  const r = await fetch('https://generativelanguage.googleapis.com/v1beta/models/' + encodeURIComponent(model) + ':generateContent', {
    method: 'POST', headers: { 'Content-Type': 'application/json', 'x-goog-api-key': key },
    body: JSON.stringify({ contents: [{ role: 'user', parts }], generationConfig: { temperature: 0, responseMimeType: 'application/json' } }), signal: AbortSignal.timeout(30000) });
  const d = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error('gemini_http_' + r.status);
  const t = (d?.candidates?.[0]?.content?.parts || []).map(p => p.text || '').join('');
  return JSON.parse(t || '{}');
}
async function sttGemini(bytes) {
  const key = process.env.GEMINI_API_KEY; if (!key) throw new Error('gemini_not_configured');
  const j = await geminiJson([{ text: 'Transcribe the spoken dialogue exactly, in its original language. Return JSON {"lang":"<ISO 639-1>","text":"..."}; text "" if there is no speech (music/noise only).' },
    { inlineData: { mimeType: 'audio/wav', data: bytes.toString('base64') } }], key);
  return { text: String(j.text || '').trim(), lang: normLang(j.lang), provider: 'gemini' };
}
const TR_SYS = 'You translate film/TV dialogue for live dubbing. Translate only what is said, keep names, tone and register, natural spoken style, similar length (it must fit the same time). No explanations.';
async function trGemini(text, from, target) {
  const key = process.env.GEMINI_API_KEY; if (!key) throw new Error('gemini_not_configured');
  const j = await geminiJson([{ text: `${TR_SYS}\nSource language: ${from || 'auto'}. Target: ${LANG_NAME[target]}.\nReturn JSON {"t":"<translation>"}.\nDialogue: ${text}` }], key);
  return String(j.t || '').trim();
}
async function trGroq(text, from, target) {
  const key = process.env.GROQ_API_KEY; if (!key) throw new Error('groq_not_configured');
  const r = await fetch('https://api.groq.com/openai/v1/chat/completions', { method: 'POST', headers: { Authorization: 'Bearer ' + key, 'Content-Type': 'application/json' },
    body: JSON.stringify({ model: process.env.GROQ_TRANSLATE_MODEL || 'llama-3.3-70b-versatile', temperature: 0,
      messages: [{ role: 'system', content: `${TR_SYS} Answer with the translation only.` }, { role: 'user', content: `Source language: ${from || 'auto'}. Target: ${LANG_NAME[target]}.\n${text}` }] }), signal: AbortSignal.timeout(30000) });
  const d = await r.json().catch(() => ({})); if (!r.ok) throw new Error('groq_tr_http_' + r.status);
  return String(d?.choices?.[0]?.message?.content || '').trim();
}
async function trOpenAI(text, from, target) {
  const key = process.env.OPENAI_API_KEY; if (!key || process.env.DUB_ALLOW_OPENAI !== '1') throw new Error('openai_disabled');
  const r = await fetch('https://api.openai.com/v1/responses', { method: 'POST', headers: { Authorization: 'Bearer ' + key, 'Content-Type': 'application/json' },
    body: JSON.stringify({ model: process.env.OPENAI_TRANSLATE_MODEL || process.env.OPENAI_MODEL || 'gpt-5.6-luna', input: [{ role: 'developer', content: TR_SYS }, { role: 'user', content: `Target: ${LANG_NAME[target]}\n${text}` }] }), signal: AbortSignal.timeout(30000) });
  const d = await r.json().catch(() => ({})); if (!r.ok) throw new Error('openai_tr_http_' + r.status);
  return String(d.output_text || (d.output || []).flatMap(x => x.content || []).map(x => x.text || '').join('')).trim();
}
async function firstOk(fns, errors) { for (const [name, fn] of fns) { try { const v = await fn(); if (v) return v; } catch (e) { errors.push(name + ': ' + (e?.message || 'error')); } } return null; }

// injectable for tests
export const P = { sttGroq, sttGemini, trGemini, trGroq, trOpenAI, ovEnroll, ovSynth };

function tokenOk(req) {
  const want = process.env.DUB_TOKEN || ''; const got = String(req.headers['x-dub-token'] || '');
  if (!want || !got) return false;
  const a = crypto.createHash('sha256').update(want).digest(), b = crypto.createHash('sha256').update(got).digest();
  return crypto.timingSafeEqual(a, b);
}
function send(res, code, obj) { res.setHeader('Cache-Control', 'no-store'); return res.status(code).json(obj); }

async function enrollIfDue(sp, force = false) {
  const sec = sp.sample.length / 2 / RATE;
  const due = force ? sec >= PROVISIONAL_SEC : (!sp.profile && sec >= PROVISIONAL_SEC) || (sp.enrolledSec < FULL_SEC && sec >= FULL_SEC);
  if (!due) return false;
  if (!force && sp.failAt && sec < sp.failAt + 2) return false;
  try {
  await P.ovEnroll(sp.profileId, sp.sample.subarray(0, Math.min(sp.sample.length, 12 * RATE * 2)));
  } catch (e) { sp.failAt = sec; throw e; }
  sp.profile = sp.profileId; sp.enrolledSec = sec; sp.failAt = 0; return true;
}

export default async function handler(req, res) {
  if (req.method === 'GET') {
    return send(res, 200, { ok: true, service: 'dub-stream', version: '1.0.110', engine: 'openvoice-v2', cloner: 'same as Jarvis Mobile (OPENVOICE_URL)',
      configured: !!process.env.DUB_TOKEN, targets: Object.keys(OV_LANG), sampleSec: FULL_SEC, sessions: SESSIONS.size });
  }
  if (req.method !== 'POST') return send(res, 405, { error: 'method_not_allowed' });
  if (!process.env.DUB_TOKEN) return send(res, 503, { error: 'dub_not_configured' });
  if (!tokenOk(req)) return send(res, 401, { error: 'unauthorized' });
  const q = req.query || {};
  const sid = safeSession(q.session); if (!sid) return send(res, 400, { error: 'session_required' });
  const action = String(q.action || 'segment');
  const s = session(sid);

  if (action === 'reset') { SESSIONS.delete(sid); return send(res, 200, { ok: true }); }

  if (action === 'tts') {
    const seg = s.segs.get(Number(q.seg)); if (!seg) return send(res, 404, { error: 'segment_unknown' });
    const sp = s.speakers.find(x => x.id === seg.speaker);
    if (!sp || !sp.profile) return send(res, 409, { error: 'voice_pending', speaker: seg.speaker });
    const t0 = Date.now();
    try {
      const audio = await P.ovSynth(sp.profile, seg.translation, seg.target, Math.max(0.9, Math.min(1.4, Number(q.speed) || 1.12)));
      s.segs.delete(seg.seq);
      return send(res, 200, { ok: true, seg: seg.seq, speaker: sp.id, mime: 'audio/wav', ms: Date.now() - t0, audioB64: audio.toString('base64') });
    } catch (e) { return send(res, 502, { error: 'tts_failed', detail: String(e?.message || e).slice(0, 120) }); }
  }

  const body = await readBody(req);
  if (body.length < RATE * 2 * 0.4) return send(res, 400, { error: 'audio_too_short' });
  if (body.length > RATE * 2 * 30) return send(res, 413, { error: 'audio_too_long' });
  const pcm = body.subarray(0, body.length - (body.length % 2));
  const v = voiced(pcm);

  if (action === 'sample') {          // manual «🎙 Tomar muestra 10 s» from the TV: becomes the current speaker's voice
    const fp = fingerprint(v.pcm);
    let sp = s.current ? s.speakers.find(x => x.id === s.current) : null;
    if (!sp || (fp && similarity(fp, sp.fp) < SAME_SPK)) sp = pickSpeaker(s, fp, v.sec);
    if (!sp || v.sec < PROVISIONAL_SEC) return send(res, 422, { error: 'no_voice_in_sample', voicedSec: +v.sec.toFixed(1), needSec: PROVISIONAL_SEC });
    sp.sample = v.pcm; if (fp) sp.fp = fp;
    try { await enrollIfDue(sp, true); } catch (e) { return send(res, 502, { error: 'enroll_failed', detail: String(e?.message || e).slice(0, 120) }); }
    return send(res, 200, { ok: true, speaker: sp.id, voicedSec: +v.sec.toFixed(1), voice: sp.enrolledSec >= FULL_SEC ? 'clone' : 'clone-provisional' });
  }

  const target = normTarget(q.target); if (!target) return send(res, 422, { error: 'target_unsupported', supported: Object.keys(OV_LANG) });
  if (v.sec < 0.4) return send(res, 200, { ok: true, skip: 'silence' });
  const t0 = Date.now(), errors = [];
  const w = wav(pcm);
  const stt = await firstOk([['groq', () => P.sttGroq(w)], ['gemini', () => P.sttGemini(w)]], errors);
  if (!stt) return send(res, 503, { error: 'transcription_unavailable', details: errors.slice(0, 4) });
  const tStt = Date.now();
  if (!stt.text || /^[\s.♪…\-]*$/.test(stt.text)) return send(res, 200, { ok: true, skip: 'no_speech', ms: { stt: tStt - t0 } });

  // speaker of this phrase (from the video audio itself) + grow its sample up to ~10 s
  const fp = fingerprint(v.pcm);
  const sp = pickSpeaker(s, fp, v.sec);
  if (sp && sp.sample.length < 12 * RATE * 2) sp.sample = Buffer.concat([sp.sample, v.pcm]).subarray(0, 12 * RATE * 2);
  let enrolled = false;
  if (sp) { try { enrolled = await enrollIfDue(sp); } catch (e) { errors.push('enroll: ' + (e?.message || 'error')); } }
  const voice = !sp || !sp.profile ? 'pending' : sp.enrolledSec >= FULL_SEC ? 'clone' : 'clone-provisional';

  if (stt.lang && stt.lang === target) return send(res, 200, { ok: true, skip: 'same_language', lang: stt.lang, transcript: stt.text, speaker: sp?.id || 0, voice });
  const translation = await firstOk([['gemini', () => P.trGemini(stt.text, stt.lang, target)], ['groq', () => P.trGroq(stt.text, stt.lang, target)], ['openai', () => P.trOpenAI(stt.text, stt.lang, target)]], errors);
  if (!translation) return send(res, 503, { error: 'translation_unavailable', transcript: stt.text, details: errors.slice(0, 4) });
  const seq = ++s.seq;
  s.segs.set(seq, { seq, speaker: sp?.id || 0, translation, target, at: Date.now() });
  if (s.segs.size > 20) s.segs.delete(s.segs.keys().next().value);
  return send(res, 200, { ok: true, seg: seq, lang: stt.lang, transcript: stt.text, translation, target, speaker: sp?.id || 0, newSpeaker: !!(sp && sp.sample.length === v.pcm.length),
    voice, enrolled, voicedSec: +v.sec.toFixed(1), sampleSec: sp ? +(sp.sample.length / 2 / RATE).toFixed(1) : 0, ms: { stt: tStt - t0, total: Date.now() - t0 }, provider: stt.provider,
    warnings: errors.length ? errors.slice(0, 3) : undefined });
}
