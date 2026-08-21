export const config = { api: { bodyParser: false } };

async function readBody(req) {
  const chunks = [];
  for await (const chunk of req) chunks.push(Buffer.from(chunk));
  return Buffer.concat(chunks);
}

export default async function handler(req, res) {
  if (req.method !== 'POST') return res.status(405).json({ error: 'method_not_allowed' });
  const key = process.env.OPENAI_API_KEY;
  if (!key) return res.status(503).json({ error: 'OPENAI_API_KEY_not_configured' });

  try {
    const bytes = await readBody(req);
    if (!bytes.length) return res.status(400).json({ error: 'audio_required' });

    const form = new FormData();
    form.append('model', process.env.OPENAI_TRANSCRIBE_MODEL || 'gpt-transcribe');
    form.append('language', 'es');
    form.append('file', new Blob([bytes], { type: req.headers['content-type'] || 'audio/mp4' }), req.headers['x-filename'] || 'jarvis-voice.m4a');

    const response = await fetch('https://api.openai.com/v1/audio/transcriptions', {
      method: 'POST',
      headers: { 'Authorization': `Bearer ${key}` },
      body: form
    });

    const data = await response.json();
    if (!response.ok) return res.status(response.status).json({ error: data?.error?.message || 'openai_transcription_error' });
    return res.status(200).json({ text: data.text || '' });
  } catch (error) {
    return res.status(500).json({ error: error?.message || 'transcription_backend_error' });
  }
}
