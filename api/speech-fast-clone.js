let cachedReference = null;
let cachedReferenceType = 'audio/mp4';
let cachedReferenceAt = 0;

function normalizeSpeechText(input) {
  return String(input || '')
    .replace(/\r\n?/g, '\n')
    .replace(/^\s*[-*•]\s+/gm, '. ')
    .replace(/^\s*\d+[.)]\s+/gm, '. ')
    .replace(/^\s*#{1,6}\s+/gm, '')
    .replace(/\n+/g, '. ')
    .replace(/\s*[–—]\s*/g, ', ')
    .replace(/\s+-\s+/g, ', ')
    .replace(/(?:\.\s*){2,}/g, '. ')
    .replace(/\s+/g, ' ')
    .trim();
}

async function noizReference() {
  const url = process.env.NOIZ_REFERENCE_AUDIO_URL || '';
  if (!url) throw new Error('NOIZ_REFERENCE_AUDIO_URL missing');
  if (cachedReference && Date.now() - cachedReferenceAt < 30 * 60 * 1000) {
    return { bytes: cachedReference, type: cachedReferenceType };
  }
  const response = await fetch(url, { signal: AbortSignal.timeout(8000) });
  if (!response.ok) throw new Error(`Noiz reference HTTP ${response.status}`);
  cachedReference = Buffer.from(await response.arrayBuffer());
  cachedReferenceType = response.headers.get('content-type') || 'audio/mp4';
  cachedReferenceAt = Date.now();
  return { bytes: cachedReference, type: cachedReferenceType };
}

async function synthNoiz(text, speed) {
  const key = process.env.NOIZ_API_KEY || '';
  if (!key) throw new Error('NOIZ_API_KEY missing');
  const voiceId = process.env.NOIZ_VOICE_ID || '';
  const form = new FormData();
  form.append('text', text.slice(0, 700));
  form.append('output_format', 'mp3');
  form.append('speed', String(speed));
  form.append('target_lang', process.env.NOIZ_TARGET_LANG || 'es');
  form.append('similarity_enh', 'true');
  if (voiceId) {
    form.append('voice_id', voiceId);
  } else {
    const ref = await noizReference();
    form.append('file', new Blob([ref.bytes], { type: ref.type }), 'jarvis-reference.m4a');
  }
  const response = await fetch('https://noiz.ai/v1/text-to-speech', {
    method: 'POST',
    headers: { Authorization: key },
    body: form,
    signal: AbortSignal.timeout(45000)
  });
  if (!response.ok) throw new Error(`Noiz HTTP ${response.status}: ${(await response.text()).slice(0,120)}`);
  return {
    audio: Buffer.from(await response.arrayBuffer()),
    type: response.headers.get('content-type') || 'audio/mpeg',
    mode: 'noiz-clone-fast'
  };
}

async function synthOpenVoice(text, speed) {
  const base = (process.env.OPENVOICE_URL || 'http://165.22.83.150:8000').replace(/\/$/, '');
  if (!base) throw new Error('OPENVOICE_URL missing');
  const response = await fetch(`${base}/synthesize`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      text: text.slice(0, 700),
      profile: process.env.OPENVOICE_PROFILE || 'Jarvis',
      language: process.env.OPENVOICE_LANGUAGE || 'ES',
      speed
    }),
    signal: AbortSignal.timeout(30000)
  });
  if (!response.ok) throw new Error(`OpenVoice HTTP ${response.status}: ${(await response.text()).slice(0,120)}`);
  return {
    audio: Buffer.from(await response.arrayBuffer()),
    type: response.headers.get('content-type') || 'audio/wav',
    mode: 'openvoice-clone-fast'
  };
}

export default async function handler(req, res) {
  if (req.method !== 'POST') return res.status(405).json({ error: 'method_not_allowed' });
  const text = normalizeSpeechText(req.body?.text);
  if (!text) return res.status(400).json({ error: 'text_required' });
  const speed = Math.max(0.85, Math.min(1.25, Number(req.body?.speed || process.env.JARVIS_TTS_SPEED || 1.04)));

  const jobs = [];
  if (process.env.NOIZ_API_KEY) jobs.push(synthNoiz(text, speed));
  if (process.env.OPENVOICE_URL || 'http://165.22.83.150:8000') jobs.push(synthOpenVoice(text, speed));
  if (!jobs.length) return res.status(503).json({ error: 'no_cloned_voice_provider' });

  try {
    // Both candidates are cloned-voice engines. Racing them reduces time-to-first-audio
    // without ever falling back to a generic Android/OpenAI stock voice.
    const winner = await Promise.any(jobs);
    res.setHeader('Content-Type', winner.type);
    res.setHeader('Cache-Control', 'no-store');
    res.setHeader('X-Jarvis-Voice-Mode', winner.mode);
    return res.status(200).send(winner.audio);
  } catch (error) {
    const details = error?.errors?.map(e => e?.message || String(e)).slice(0,4) || [error?.message || 'clone_failed'];
    return res.status(503).json({ error: 'cloned_voice_temporarily_unavailable', details });
  }
}
