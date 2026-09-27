export const config = { api: { bodyParser: false } };

async function readBody(req) {
  const chunks = [];
  for await (const chunk of req) chunks.push(Buffer.from(chunk));
  return Buffer.concat(chunks);
}

function decodeHeader(value) {
  try { return Buffer.from(String(value || ''), 'base64').toString('utf8').trim(); }
  catch { return ''; }
}

function safeSession(value) {
  return String(value || 'default').toLowerCase().replace(/[^a-z0-9_-]+/g, '').slice(0, 40) || 'default';
}

async function synthNoiz({ text, sample, mime, filename, target, speed }) {
  const key = process.env.NOIZ_API_KEY || '';
  if (!key) throw new Error('noiz_not_configured');
  const form = new FormData();
  form.append('text', text.slice(0, 900));
  form.append('output_format', 'mp3');
  form.append('speed', String(speed));
  form.append('target_lang', target);
  form.append('similarity_enh', 'true');
  form.append('file', new Blob([sample], { type: mime }), filename);
  const response = await fetch('https://noiz.ai/v1/text-to-speech', {
    method: 'POST',
    headers: { Authorization: key },
    body: form,
    signal: AbortSignal.timeout(45000)
  });
  if (!response.ok) throw new Error(`noiz_http_${response.status}: ${(await response.text()).slice(0,120)}`);
  return {
    audio: Buffer.from(await response.arrayBuffer()),
    type: response.headers.get('content-type') || 'audio/mpeg',
    mode: 'noiz-transient-reference'
  };
}

async function synthOpenVoice({ text, sample, mime, filename, target, speed, session }) {
  const base = (process.env.OPENVOICE_URL || 'http://165.22.83.150:8000').replace(/\/$/, '');
  if (!base) throw new Error('openvoice_not_configured');
  const profile = 'dub_' + safeSession(session);

  const enroll = new FormData();
  enroll.append('sample', new Blob([sample], { type: mime }), filename);
  const er = await fetch(`${base}/enroll/${encodeURIComponent(profile)}`, {
    method: 'POST',
    body: enroll,
    signal: AbortSignal.timeout(30000)
  });
  if (!er.ok) throw new Error(`openvoice_enroll_http_${er.status}: ${(await er.text()).slice(0,120)}`);

  const sr = await fetch(`${base}/synthesize`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      text: text.slice(0, 900),
      profile,
      language: String(target || 'es').toUpperCase(),
      speed
    }),
    signal: AbortSignal.timeout(35000)
  });
  if (!sr.ok) throw new Error(`openvoice_synth_http_${sr.status}: ${(await sr.text()).slice(0,120)}`);
  return {
    audio: Buffer.from(await sr.arrayBuffer()),
    type: sr.headers.get('content-type') || 'audio/wav',
    mode: 'openvoice-session-reference'
  };
}

async function synthGeneric(text, target, speed) {
  const key = process.env.OPENAI_API_KEY || '';
  if (!key) throw new Error('openai_not_configured');
  const voiceByLanguage = {
    es: 'coral', en: 'coral', fr: 'coral', de: 'coral', it: 'coral', pt: 'coral'
  };
  const response = await fetch('https://api.openai.com/v1/audio/speech', {
    method: 'POST',
    headers: { Authorization: `Bearer ${key}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({
      model: process.env.OPENAI_TTS_MODEL || 'gpt-4o-mini-tts',
      voice: voiceByLanguage[target] || 'coral',
      input: text.slice(0, 900),
      instructions: 'Dub the translated dialogue naturally. Match the apparent age, energy, pacing and emotion of the source speaker without claiming to be that person.',
      speed,
      response_format: 'mp3'
    }),
    signal: AbortSignal.timeout(30000)
  });
  if (!response.ok) throw new Error(`openai_tts_http_${response.status}: ${(await response.text()).slice(0,120)}`);
  return {
    audio: Buffer.from(await response.arrayBuffer()),
    type: response.headers.get('content-type') || 'audio/mpeg',
    mode: 'generic-voice-match'
  };
}

export default async function handler(req, res) {
  if (req.method !== 'POST') return res.status(405).json({ error: 'method_not_allowed' });
  const sample = await readBody(req);
  if (!sample.length) return res.status(400).json({ error: 'audio_reference_required' });
  if (sample.length > 8 * 1024 * 1024) return res.status(413).json({ error: 'audio_reference_too_large' });

  const text = decodeHeader(req.headers['x-dub-text-b64']);
  if (!text) return res.status(400).json({ error: 'dub_text_required' });

  const mime = String(req.headers['content-type'] || 'audio/mp4').split(';')[0].trim() || 'audio/mp4';
  const filename = String(req.headers['x-filename'] || 'dub-reference.m4a');
  const target = String(req.headers['x-target-language'] || 'es').slice(0,8).toLowerCase();
  const session = String(req.headers['x-dub-session'] || 'default');
  const speed = Math.max(0.85, Math.min(1.25, Number(req.headers['x-dub-speed'] || 1.05)));
  const errors = [];

  let result = null;
  try { result = await synthNoiz({ text, sample, mime, filename, target, speed }); }
  catch (e) { errors.push('noiz: ' + (e?.message || 'error')); }

  if (!result) {
    try { result = await synthOpenVoice({ text, sample, mime, filename, target, speed, session }); }
    catch (e) { errors.push('openvoice: ' + (e?.message || 'error')); }
  }

  if (!result) {
    try { result = await synthGeneric(text, target, speed); }
    catch (e) { errors.push('generic: ' + (e?.message || 'error')); }
  }

  if (!result) {
    console.error('[speech-dub] all providers failed', errors);
    return res.status(503).json({ error: 'dub_voice_unavailable', details: errors.slice(0,6) });
  }

  res.setHeader('Content-Type', result.type);
  res.setHeader('Cache-Control', 'no-store');
  res.setHeader('X-Javistv-Voice-Mode', result.mode);
  res.setHeader('X-Javistv-Private-Playback', '1');
  return res.status(200).send(result.audio);
}
