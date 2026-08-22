export default async function handler(req, res) {
  if (req.method !== 'POST') return res.status(405).json({ error: 'method_not_allowed' });

  const key = process.env.OPENAI_API_KEY;
  if (!key) return res.status(503).json({ error: 'OPENAI_API_KEY_not_configured' });

  const body = req.body || {};
  const requestedVoice = body.voice || process.env.OPENAI_TTS_VOICE || 'coral';
  const customVoiceId = process.env.JARVIS_CUSTOM_VOICE_ID || '';
  const voice = (requestedVoice === 'my_voice' || requestedVoice === 'mi_voz') && customVoiceId
    ? { id: customVoiceId }
    : requestedVoice;
  const instructions = body.instructions ||
    'Habla en español natural, ágil y conversacional, aproximadamente un 20 por ciento más rápido de lo normal. Evita pausas largas, responde con energía moderada y pronunciación clara.';

  if (!body.text || typeof body.text !== 'string') return res.status(400).json({ error: 'text_required' });

  try {
    const response = await fetch('https://api.openai.com/v1/audio/speech', {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${key}`,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        model: process.env.OPENAI_TTS_MODEL || 'gpt-4o-mini-tts',
        voice,
        input: body.text.slice(0, 1400),
        instructions,
        speed: Number(body.speed || process.env.JARVIS_TTS_SPEED || 1.2),
        response_format: 'mp3'
      })
    });

    if (!response.ok) {
      const errorBody = await response.text();
      return res.status(response.status).json({ error: errorBody || 'openai_tts_error' });
    }

    res.setHeader('Content-Type', 'audio/mpeg');
    res.setHeader('Cache-Control', 'no-store');
    res.setHeader('X-Jarvis-Voice-Mode', customVoiceId ? 'custom-fast' : 'fast');
    const audio = Buffer.from(await response.arrayBuffer());
    return res.status(200).send(audio);
  } catch (error) {
    return res.status(500).json({ error: error?.message || 'speech_backend_error' });
  }
}
