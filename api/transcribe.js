export const config = { api: { bodyParser: false } };

async function readBody(req) {
  const chunks = [];
  for await (const chunk of req) chunks.push(Buffer.from(chunk));
  return Buffer.concat(chunks);
}

async function transcribeMultipart({ url, key, model, bytes, mime, filename, provider }) {
  const form = new FormData();
  form.append('model', model);
  form.append('language', 'es');
  form.append('response_format', 'json');
  form.append('file', new Blob([bytes], { type: mime }), filename);
  const response = await fetch(url, {
    method: 'POST',
    headers: { Authorization: `Bearer ${key}` },
    body: form
  });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(data?.error?.message || data?.message || `${provider}_${model}_http_${response.status}`);
  const text = String(data?.text || '').trim();
  if (!text) throw new Error(`${provider}_${model}_empty_transcription`);
  return { text, provider, model };
}

async function transcribeGemini({ key, model, bytes, mime }) {
  const response = await fetch(
    `https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(model)}:generateContent?key=${encodeURIComponent(key)}`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        contents: [{ role: 'user', parts: [
          { text: 'Transcribe exactamente el habla de este audio en español. Devuelve únicamente la transcripción, sin explicación ni comillas.' },
          { inlineData: { mimeType: mime, data: Buffer.from(bytes).toString('base64') } }
        ] }],
        generationConfig: { temperature: 0 }
      })
    }
  );
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(data?.error?.message || `gemini_${model}_http_${response.status}`);
  const text = (data?.candidates?.[0]?.content?.parts || []).map(p => p.text || '').join('').trim();
  if (!text) throw new Error(`gemini_${model}_empty_transcription`);
  return { text, provider: 'gemini', model };
}

export default async function handler(req, res) {
  if (req.method !== 'POST') return res.status(405).json({ error: 'method_not_allowed' });
  try {
    const bytes = await readBody(req);
    if (!bytes.length) return res.status(400).json({ error: 'audio_required' });
    if (bytes.length > 24 * 1024 * 1024) return res.status(413).json({ error: 'audio_too_large' });

    const mime = String(req.headers['content-type'] || 'audio/mp4').split(';')[0].trim() || 'audio/mp4';
    const filename = req.headers['x-filename'] || (mime.includes('wav') ? 'javistv-voice.wav' : 'javistv-voice.m4a');
    const errors = [];

    const tryProvider = async (label, fn) => {
      try { return await fn(); }
      catch (e) {
        const message = e?.message || 'error';
        errors.push(`${label}: ${message}`);
        console.error('[transcribe]', label, message);
        return null;
      }
    };

    if (process.env.GROQ_API_KEY) {
      const r = await tryProvider('groq', () => transcribeMultipart({
        url: 'https://api.groq.com/openai/v1/audio/transcriptions',
        key: process.env.GROQ_API_KEY,
        model: process.env.GROQ_TRANSCRIBE_MODEL || 'whisper-large-v3-turbo',
        bytes, mime, filename, provider: 'groq'
      }));
      if (r) return res.status(200).json(r);
    }

    if (process.env.GEMINI_API_KEY && bytes.length < 19 * 1024 * 1024) {
      const geminiModels = [
        process.env.GEMINI_TRANSCRIBE_MODEL,
        'gemini-2.5-flash'
      ].filter(Boolean);
      for (const model of [...new Set(geminiModels)]) {
        const r = await tryProvider(`gemini/${model}`, () => transcribeGemini({
          key: process.env.GEMINI_API_KEY, model, bytes, mime
        }));
        if (r) return res.status(200).json(r);
      }
    }

    if (process.env.OPENAI_API_KEY) {
      const models = [
        process.env.OPENAI_TRANSCRIBE_MODEL,
        'gpt-transcribe',
        'gpt-4o-mini-transcribe',
        'whisper-1'
      ].filter(Boolean);
      for (const model of [...new Set(models)]) {
        const r = await tryProvider(`openai/${model}`, () => transcribeMultipart({
          url: 'https://api.openai.com/v1/audio/transcriptions',
          key: process.env.OPENAI_API_KEY,
          model, bytes, mime, filename, provider: 'openai'
        }));
        if (r) return res.status(200).json(r);
      }
    }

    return res.status(503).json({
      error: 'transcription_provider_unavailable',
      message: 'No se pudo transcribir el audio con los proveedores configurados.',
      details: errors.slice(0, 10)
    });
  } catch (error) {
    console.error('[transcribe] fatal', error);
    return res.status(500).json({ error: error?.message || 'transcription_backend_error' });
  }
}
