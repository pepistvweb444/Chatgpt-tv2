export const config = { api: { bodyParser: false } };

async function readBody(req) {
  const chunks = [];
  for await (const chunk of req) chunks.push(Buffer.from(chunk));
  return Buffer.concat(chunks);
}

async function transcribeMultipart(opts) {
  const form = new FormData();
  form.append('model', opts.model);
  form.append('response_format', 'json');
  form.append('file', new Blob([opts.bytes], { type: opts.mime }), opts.filename);
  const response = await fetch(opts.url, { method: 'POST', headers: { Authorization: 'Bearer ' + opts.key }, body: form });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(data?.error?.message || data?.message || (opts.provider + '_transcription_http_' + response.status));
  return { text: String(data?.text || '').trim(), provider: opts.provider };
}

async function transcribeGeminiAudio({ key, model, bytes, mime }) {
  const response = await fetch(
    'https://generativelanguage.googleapis.com/v1beta/models/' + encodeURIComponent(model) + ':generateContent?key=' + encodeURIComponent(key),
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        contents: [{ role: 'user', parts: [
          { text: 'Transcribe exactly the spoken dialogue in this audio. Return only the transcript. Keep the original spoken language. If there is no speech, return an empty string.' },
          { inlineData: { mimeType: mime, data: Buffer.from(bytes).toString('base64') } }
        ] }],
        generationConfig: { temperature: 0 }
      })
    }
  );
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(data?.error?.message || ('gemini_transcription_http_' + response.status));
  const text = (data?.candidates?.[0]?.content?.parts || []).map(p => p.text || '').join('').trim();
  if (!text) throw new Error('gemini_empty_transcription');
  return { text, provider: 'gemini' };
}

async function translateOpenAI(text, target) {
  const key = process.env.OPENAI_API_KEY;
  if (!key) throw new Error('openai_not_configured');
  const model = process.env.OPENAI_TRANSLATE_MODEL || process.env.OPENAI_MODEL || 'gpt-5.6-luna';
  const response = await fetch('https://api.openai.com/v1/responses', {
    method: 'POST',
    headers: { Authorization: 'Bearer ' + key, 'Content-Type': 'application/json' },
    body: JSON.stringify({
      model,
      input: [
        { role: 'developer', content: 'You are a low-latency audiovisual dubbing translator. Translate only spoken content. Preserve meaning, names, tone and sentence order. Do not add explanations. If the text is empty/noise/music only, return an empty string.' },
        { role: 'user', content: 'Target language: ' + target + '\nSpeech: ' + text }
      ]
    })
  });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(data?.error?.message || ('openai_translate_http_' + response.status));
  return String(data.output_text || (data.output || []).flatMap(x => x.content || []).map(x => x.text || '').join('')).trim();
}

async function translateGemini(text, target) {
  const key = process.env.GEMINI_API_KEY;
  if (!key) throw new Error('gemini_not_configured');
  const model = process.env.GEMINI_TRANSLATE_MODEL || process.env.GEMINI_MODEL || 'gemini-2.5-flash';
  const url = 'https://generativelanguage.googleapis.com/v1beta/models/' + encodeURIComponent(model) + ':generateContent?key=' + encodeURIComponent(key);
  const response = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      contents: [{ role: 'user', parts: [{ text: 'Translate this spoken dialogue to ' + target + '. Return only the translation, no explanation. If it is only noise/music, return an empty string.\n\n' + text }] }],
      generationConfig: { temperature: 0 }
    })
  });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(data?.error?.message || ('gemini_translate_http_' + response.status));
  return (data?.candidates?.[0]?.content?.parts || []).map(p => p.text || '').join('').trim();
}

export default async function handler(req, res) {
  if (req.method !== 'POST') return res.status(405).json({ error: 'method_not_allowed' });
  try {
    const bytes = await readBody(req);
    if (!bytes.length) return res.status(400).json({ error: 'audio_required' });
    if (bytes.length > 12 * 1024 * 1024) return res.status(413).json({ error: 'audio_too_large' });

    const target = String(req.query?.target || 'es').slice(0, 24);
    const mime = req.headers['content-type'] || 'audio/mp4';
    const filename = req.headers['x-filename'] || 'firetv-translate.m4a';
    const errors = [];
    let transcription = null;

    if (process.env.GROQ_API_KEY) {
      try {
        transcription = await transcribeMultipart({
          url: 'https://api.groq.com/openai/v1/audio/transcriptions',
          key: process.env.GROQ_API_KEY,
          model: process.env.GROQ_TRANSCRIBE_MODEL || 'whisper-large-v3-turbo',
          bytes, mime, filename, provider: 'groq'
        });
      } catch (e) { errors.push('groq: ' + (e?.message || 'error')); }
    }

    if (!transcription && process.env.GEMINI_API_KEY && bytes.length < 19 * 1024 * 1024) {
      try {
        transcription = await transcribeGeminiAudio({
          key: process.env.GEMINI_API_KEY,
          model: process.env.GEMINI_TRANSCRIBE_MODEL || 'gemini-2.5-flash',
          bytes, mime
        });
      } catch (e) { errors.push('gemini-stt: ' + (e?.message || 'error')); }
    }

    if (!transcription && process.env.OPENAI_API_KEY) {
      const models = [process.env.OPENAI_TRANSCRIBE_MODEL, 'gpt-4o-mini-transcribe', 'whisper-1'].filter(Boolean);
      for (const model of [...new Set(models)]) {
        try {
          transcription = await transcribeMultipart({
            url: 'https://api.openai.com/v1/audio/transcriptions',
            key: process.env.OPENAI_API_KEY,
            model,
            bytes, mime, filename, provider: 'openai'
          });
          if (transcription?.text) break;
        } catch (e) { errors.push('openai-stt/' + model + ': ' + (e?.message || 'error')); }
      }
    }

    if (!transcription) {
      console.error('[translate-audio] transcription unavailable', errors);
      return res.status(503).json({ error: 'transcription_unavailable', details: errors });
    }
    const transcript = String(transcription.text || '').trim();
    if (!transcript) return res.status(200).json({ transcript: '', translation: '', provider: transcription.provider });

    let translation = '';
    try {
      translation = await translateOpenAI(transcript, target);
    } catch (e) {
      errors.push('openai-translate: ' + (e?.message || 'error'));
      try {
        translation = await translateGemini(transcript, target);
      } catch (g) {
        errors.push('gemini-translate: ' + (g?.message || 'error'));
      }
    }

    if (!translation) {
      console.error('[translate-audio] translation unavailable', errors);
      return res.status(503).json({ error: 'translation_unavailable', transcript, details: errors });
    }
    return res.status(200).json({ transcript, translation, target, provider: transcription.provider });
  } catch (error) {
    return res.status(500).json({ error: error?.message || 'translate_audio_backend_error' });
  }
}
