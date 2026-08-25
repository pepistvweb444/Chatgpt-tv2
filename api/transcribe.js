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
  const response = await fetch(url, { method: 'POST', headers: { Authorization: `Bearer ${key}` }, body: form });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(data?.error?.message || data?.message || `${provider}_transcription_http_${response.status}`);
  if (!data?.text) throw new Error(`${provider}_empty_transcription`);
  return { text: String(data.text).trim(), provider };
}

async function transcribeGemini({ key, model, bytes, mime }) {
  const response = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(model)}:generateContent?key=${encodeURIComponent(key)}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      contents: [{ role: 'user', parts: [
        { text: 'Transcribe exactamente el habla de este audio en español. Devuelve únicamente la transcripción, sin explicación, sin comillas y respetando nombres propios y la letra ñ.' },
        { inlineData: { mimeType: mime, data: Buffer.from(bytes).toString('base64') } }
      ] }],
      generationConfig: { temperature: 0 }
    })
  });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(data?.error?.message || `gemini_transcription_http_${response.status}`);
  const text = (data?.candidates?.[0]?.content?.parts || []).map(p => p.text || '').join('').trim();
  if (!text) throw new Error('gemini_empty_transcription');
  return { text, provider: 'gemini' };
}

export default async function handler(req, res) {
  if (req.method !== 'POST') return res.status(405).json({ error: 'method_not_allowed' });
  try {
    const bytes = await readBody(req);
    if (!bytes.length) return res.status(400).json({ error: 'audio_required' });
    const mime = req.headers['content-type'] || 'audio/mp4';
    const filename = req.headers['x-filename'] || 'jarvis-voice.m4a';
    const errors = [];

    if (process.env.GROQ_API_KEY) {
      try {
        return res.status(200).json(await transcribeMultipart({
          url: 'https://api.groq.com/openai/v1/audio/transcriptions', key: process.env.GROQ_API_KEY,
          model: process.env.GROQ_TRANSCRIBE_MODEL || 'whisper-large-v3-turbo', bytes, mime, filename, provider: 'groq'
        }));
      } catch (e) { errors.push(`groq: ${e?.message || 'error'}`); }
    }

    if (process.env.GEMINI_API_KEY && bytes.length < 19 * 1024 * 1024) {
      try {
        return res.status(200).json(await transcribeGemini({ key: process.env.GEMINI_API_KEY, model: process.env.GEMINI_TRANSCRIBE_MODEL || process.env.GEMINI_MODEL || 'gemini-3.6-flash', bytes, mime }));
      } catch (e) { errors.push(`gemini: ${e?.message || 'error'}`); }
    }

    if (process.env.OPENAI_API_KEY) {
      try {
        return res.status(200).json(await transcribeMultipart({
          url: 'https://api.openai.com/v1/audio/transcriptions', key: process.env.OPENAI_API_KEY,
          model: process.env.OPENAI_TRANSCRIBE_MODEL || 'gpt-transcribe', bytes, mime, filename, provider: 'openai'
        }));
      } catch (e) { errors.push(`openai: ${e?.message || 'error'}`); }
    }

    return res.status(503).json({ error: 'transcription_provider_unavailable', message: 'No hay un proveedor de transcripción disponible.', details: errors });
  } catch (error) {
    return res.status(500).json({ error: error?.message || 'transcription_backend_error' });
  }
}
