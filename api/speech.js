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

export default async function handler(req, res) {
  if (req.method !== 'POST') return res.status(405).json({ error: 'method_not_allowed' });

  const body = req.body || {};
  if (!body.text || typeof body.text !== 'string') return res.status(400).json({ error: 'text_required' });

  // Noiz can pause or stop on Markdown list markers/new lines. Convert the
  // assistant answer to continuous spoken punctuation before sending it to any TTS.
  const speechText = normalizeSpeechText(body.text);
  if (!speechText) return res.status(400).json({ error: 'text_required' });

  const requestedProvider = String(body.provider || '').toLowerCase();
  const requestedVoice = String(body.voice || '').toLowerCase();
  const speed = Number(body.speed || process.env.JARVIS_TTS_SPEED || 1.15);

  // 1) NOIZ AI — preferred when configured. It can clone directly from a short
  // reference sample, so Jarvis does not need to persist a separate local model.
  const noizKey = process.env.NOIZ_API_KEY || '';
  const noizVoiceId = process.env.NOIZ_VOICE_ID || '';
  const noizRefUrl = process.env.NOIZ_REFERENCE_AUDIO_URL || '';
  const preferNoiz = requestedProvider === 'noiz' || process.env.JARVIS_TTS_PROVIDER === 'noiz';

  if (noizKey && (preferNoiz || requestedVoice === 'noiz' || requestedVoice === 'my_voice' || requestedVoice === 'mi_voz')) {
    try {
      const form = new FormData();
      form.append('text', speechText.slice(0, 1800));
      form.append('output_format', 'mp3');
      form.append('speed', String(speed));
      form.append('target_lang', process.env.NOIZ_TARGET_LANG || 'es');
      form.append('similarity_enh', 'true');

      if (noizVoiceId) {
        form.append('voice_id', noizVoiceId);
      } else if (noizRefUrl) {
        const ref = await fetch(noizRefUrl, { signal: AbortSignal.timeout(15000) });
        if (!ref.ok) throw new Error(`Noiz reference audio HTTP ${ref.status}`);
        const refBytes = await ref.arrayBuffer();
        const refType = ref.headers.get('content-type') || 'audio/mp4';
        form.append('file', new Blob([refBytes], { type: refType }), 'jarvis-reference.m4a');
      } else {
        throw new Error('NOIZ_VOICE_ID or NOIZ_REFERENCE_AUDIO_URL is required');
      }

      const noiz = await fetch('https://noiz.ai/v1/text-to-speech', {
        method: 'POST',
        headers: { Authorization: noizKey },
        body: form,
        signal: AbortSignal.timeout(90000)
      });

      if (noiz.ok) {
        const audio = Buffer.from(await noiz.arrayBuffer());
        res.setHeader('Content-Type', noiz.headers.get('content-type') || 'audio/mpeg');
        res.setHeader('Cache-Control', 'no-store');
        res.setHeader('X-Jarvis-Voice-Mode', 'noiz-clone');
        return res.status(200).send(audio);
      }
      console.warn('Noiz synthesize failed', noiz.status, await noiz.text());
    } catch (error) {
      console.warn('Noiz unavailable; falling back', error?.message || error);
    }
  }

  // 2) Self-hosted OpenVoice fallback on DigitalOcean.
  const openVoiceUrl = (process.env.OPENVOICE_URL || 'http://165.22.83.150:8000').replace(/\/$/, '');
  const forceOpenAI = requestedProvider === 'openai';
  const preferOpenVoice = !forceOpenAI && Boolean(openVoiceUrl);

  if (preferOpenVoice) {
    try {
      const ov = await fetch(`${openVoiceUrl}/synthesize`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          text: speechText.slice(0, 1800),
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

  // 3) OpenAI final fallback so Jarvis never loses speech entirely.
  const key = process.env.OPENAI_API_KEY;
  if (!key) return res.status(503).json({ error: 'No TTS provider available. Configure NOIZ_API_KEY, OPENVOICE_URL or OPENAI_API_KEY.' });

  const openAiRequestedVoice = body.voice || process.env.OPENAI_TTS_VOICE || 'coral';
  const customVoiceId = process.env.JARVIS_CUSTOM_VOICE_ID || '';
  const voice = (requestedVoice === 'my_voice' || requestedVoice === 'mi_voz') && customVoiceId
    ? { id: customVoiceId }
    : ((requestedVoice === 'openvoice' || requestedVoice === 'noiz') ? (process.env.OPENAI_TTS_VOICE || 'coral') : openAiRequestedVoice);
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
        input: speechText.slice(0, 1400),
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
