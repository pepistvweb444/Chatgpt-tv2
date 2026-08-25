export const config = { api: { bodyParser: false } };

async function readBody(req) {
  const chunks = [];
  for await (const chunk of req) chunks.push(Buffer.from(chunk));
  return Buffer.concat(chunks);
}

async function transcribeWith({ url, key, model, bytes, mime, filename, provider }) {
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
  if (!response.ok) {
    const err = new Error(data?.error?.message || data?.message || `${provider}_transcription_http_${response.status}`);
    err.status = response.status;
    throw err;
  }
  if (!data?.text) throw new Error(`${provider}_empty_transcription`);
  return { text: String(data.text).trim(), provider };
}

export default async function handler(req, res) {
  if (req.method !== 'POST') return res.status(405).json({ error: 'method_not_allowed' });

  try {
    const bytes = await readBody(req);
    if (!bytes.length) return res.status(400).json({ error: 'audio_required' });
    const mime = req.headers['content-type'] || 'audio/mp4';
    const filename = req.headers['x-filename'] || 'jarvis-voice.m4a';
    const errors = [];

    // Groq is deliberately first: voice must keep working even when OpenAI has no credit.
    if (process.env.GROQ_API_KEY) {
      try {
        const out = await transcribeWith({
          url: 'https://api.groq.com/openai/v1/audio/transcriptions',
          key: process.env.GROQ_API_KEY,
          model: process.env.GROQ_TRANSCRIBE_MODEL || 'whisper-large-v3-turbo',
          bytes, mime, filename, provider: 'groq'
        });
        return res.status(200).json(out);
      } catch (e) { errors.push(`groq: ${e?.message || 'error'}`); }
    }

    if (process.env.OPENAI_API_KEY) {
      try {
        const out = await transcribeWith({
          url: 'https://api.openai.com/v1/audio/transcriptions',
          key: process.env.OPENAI_API_KEY,
          model: process.env.OPENAI_TRANSCRIBE_MODEL || 'gpt-transcribe',
          bytes, mime, filename, provider: 'openai'
        });
        return res.status(200).json(out);
      } catch (e) { errors.push(`openai: ${e?.message || 'error'}`); }
    }

    return res.status(503).json({
      error: 'transcription_provider_unavailable',
      message: 'No hay un proveedor de transcripción disponible.',
      details: errors
    });
  } catch (error) {
    return res.status(500).json({ error: error?.message || 'transcription_backend_error' });
  }
}
