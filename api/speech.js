export default async function handler(req, res) {
  if (req.method !== 'POST') return res.status(405).json({ error: 'method_not_allowed' });

  const key = process.env.OPENAI_API_KEY;
  if (!key) return res.status(503).json({ error: 'OPENAI_API_KEY_not_configured' });

  const {
    text,
    voice = process.env.OPENAI_TTS_VOICE || 'coral',
    instructions = 'Habla en español natural, con ritmo ágil y conversacional, aproximadamente un 20 por ciento más rápido de lo normal. Evita pausas largas y mantén una entonación cálida y clara.'
  } = req.body || {};
  if (!text || typeof text !== 'string') return res.status(400).json({ error: 'text_required' });

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
        input: text.slice(0, 1800),
        instructions,
        response_format: 'mp3'
      })
    });

    if (!response.ok) {
      const body = await response.text();
      return res.status(response.status).json({ error: body || 'openai_tts_error' });
    }

    res.setHeader('Content-Type', 'audio/mpeg');
    res.setHeader('Cache-Control', 'no-store');
    res.setHeader('X-Jarvis-Voice-Mode', 'low-latency-fast');
    const audio = Buffer.from(await response.arrayBuffer());
    return res.status(200).send(audio);
  } catch (error) {
    return res.status(500).json({ error: error?.message || 'speech_backend_error' });
  }
}
