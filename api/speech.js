export default async function handler(req, res) {
  if (req.method !== 'POST') return res.status(405).json({ error: 'method_not_allowed' });

  const body = req.body || {};
  if (!body.text || typeof body.text !== 'string') return res.status(400).json({ error: 'text_required' });

  const openVoiceUrl = (process.env.OPENVOICE_URL || 'http://165.22.83.150:8000').replace(/\/$/, '');
  const requestedProvider = String(body.provider || '').toLowerCase();
  const requestedVoice = String(body.voice || '').toLowerCase();
  const forceOpenAI = requestedProvider === 'openai';
  const preferOpenVoice = !forceOpenAI && Boolean(openVoiceUrl);
  const speed = Number(body.speed || process.env.JARVIS_TTS_SPEED || 1.15);

  if (preferOpenVoice) {
    try {
      const ov = await fetch(`${openVoiceUrl}/synthesize`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          text: body.text.slice(0, 1800),
          profile: process.env.OPENVOICE_PROFILE || 'Jarvis',
          language: process.env.OPENVOICE_LANGUAGE || 'ES',
          speed
        }),
        signal: AbortSignal.timeout(60000)
      });
      if (ov.ok) {
        const audio = Buffer.from(await ov.arrayBuffer());
        res.setHeader('Content-Type', ov.headers.get('content-type') || 'audio/wav');
        res.setHeader('Cache-Control', 'no-store');
        res.setHeader('X-Jarvis-Voice-Mode', 'openvoice-digitalocean');
        return res.status(200).send(audio);
      }
      console.warn('OpenVoice synthesize failed', ov.status, await ov.text());
    } catch (error) {
      console.warn('OpenVoice unavailable; falling back to OpenAI', error?.message || error);
    }
  }

  const key = process.env.OPENAI_API_KEY;
  if (!key) return res.status(503).json({ error: 'No TTS provider available. Configure OPENVOICE_URL or OPENAI_API_KEY.' });

  const openAiRequestedVoice = body.voice || process.env.OPENAI_TTS_VOICE || 'coral';
  const customVoiceId = process.env.JARVIS_CUSTOM_VOICE_ID || '';
  const voice = (requestedVoice === 'my_voice' || requestedVoice === 'mi_voz') && customVoiceId
    ? { id: customVoiceId }
    : (requestedVoice === 'openvoice' ? (process.env.OPENAI_TTS_VOICE || 'coral') : openAiRequestedVoice);
  const instructions = body.instructions ||
    'Habla en español natural, ágil y conversacional, aproximadamente un 15 por ciento más rápido de lo normal. Evita pausas largas, responde con energía moderada y pronunciación clara.';

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
        speed,
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
