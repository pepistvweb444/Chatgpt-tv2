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

async function synthNoiz(speechText, speed) {
  const noizKey = process.env.NOIZ_API_KEY || '';
  if (!noizKey) throw new Error('noiz_not_configured');
  const noizVoiceId = process.env.NOIZ_VOICE_ID || '';
  const noizRefUrl = process.env.NOIZ_REFERENCE_AUDIO_URL || '';
  const form = new FormData();
  form.append('text', speechText.slice(0, 1000));
  form.append('output_format', 'mp3');
  form.append('speed', String(speed));
  form.append('target_lang', process.env.NOIZ_TARGET_LANG || 'es');
  form.append('similarity_enh', 'true');
  if (noizVoiceId) form.append('voice_id', noizVoiceId);
  else if (noizRefUrl) {
    const ref = await fetch(noizRefUrl, { signal: AbortSignal.timeout(3500) });
    if (!ref.ok) throw new Error(`noiz_ref_${ref.status}`);
    form.append('file', new Blob([await ref.arrayBuffer()], { type: ref.headers.get('content-type') || 'audio/mp4' }), 'jarvis-reference.m4a');
  } else throw new Error('noiz_voice_missing');
  const r = await fetch('https://noiz.ai/v1/text-to-speech', { method:'POST', headers:{Authorization:noizKey}, body:form, signal:AbortSignal.timeout(6500) });
  if (!r.ok) throw new Error(`noiz_${r.status}`);
  return { audio:Buffer.from(await r.arrayBuffer()), type:r.headers.get('content-type') || 'audio/mpeg', mode:'noiz-clone' };
}

async function synthOpenVoice(speechText, speed) {
  const openVoiceUrl = (process.env.OPENVOICE_URL || 'http://165.22.83.150:8000').replace(/\/$/, '');
  if (!openVoiceUrl) throw new Error('openvoice_not_configured');
  const r = await fetch(`${openVoiceUrl}/synthesize`, {
    method:'POST', headers:{'Content-Type':'application/json'},
    body:JSON.stringify({ text:speechText.slice(0,1000), profile:process.env.OPENVOICE_PROFILE || 'Jarvis', language:process.env.OPENVOICE_LANGUAGE || 'ES', speed }),
    signal:AbortSignal.timeout(6500)
  });
  if (!r.ok) throw new Error(`openvoice_${r.status}`);
  return { audio:Buffer.from(await r.arrayBuffer()), type:r.headers.get('content-type') || 'audio/wav', mode:'openvoice-clone' };
}

export default async function handler(req, res) {
  if (req.method !== 'POST') return res.status(405).json({ error:'method_not_allowed' });
  const body=req.body || {};
  if (!body.text || typeof body.text !== 'string') return res.status(400).json({ error:'text_required' });
  const speechText=normalizeSpeechText(body.text);
  if (!speechText) return res.status(400).json({ error:'text_required' });
  const requestedProvider=String(body.provider || '').toLowerCase();
  const requestedVoice=String(body.voice || '').toLowerCase();
  const speed=Number(body.speed || process.env.JARVIS_TTS_SPEED || 1.15);
  const cloneRequested=['mi_voz','my_voice','openvoice','noiz'].includes(requestedVoice) || ['noiz','openvoice','race-clone'].includes(requestedProvider);

  try {
    let result;
    if (requestedProvider === 'race-clone') {
      result = await Promise.any([synthNoiz(speechText,speed), synthOpenVoice(speechText,speed)]);
    } else if (requestedProvider === 'openvoice') {
      result = await synthOpenVoice(speechText,speed);
    } else if (requestedProvider === 'noiz' || cloneRequested) {
      try { result = await synthNoiz(speechText,speed); }
      catch (_) { result = await synthOpenVoice(speechText,speed); }
    }
    if (result) {
      res.setHeader('Content-Type',result.type);res.setHeader('Cache-Control','no-store');res.setHeader('X-Jarvis-Voice-Mode',result.mode);
      return res.status(200).send(result.audio);
    }
  } catch (error) {
    if (cloneRequested) return res.status(503).json({ error:'cloned_voice_temporarily_unavailable', detail:error?.message || String(error) });
  }

  const key=process.env.OPENAI_API_KEY;
  if(!key)return res.status(503).json({error:'No TTS provider available.'});
  try {
    const response=await fetch('https://api.openai.com/v1/audio/speech',{method:'POST',headers:{Authorization:`Bearer ${key}`,'Content-Type':'application/json'},body:JSON.stringify({model:process.env.OPENAI_TTS_MODEL || 'gpt-4o-mini-tts',voice:body.voice || process.env.OPENAI_TTS_VOICE || 'coral',input:speechText.slice(0,1000),instructions:body.instructions || 'Habla en español natural, ágil y conversacional.',speed,response_format:'mp3'}),signal:AbortSignal.timeout(10000)});
    if(!response.ok)return res.status(response.status).json({error:await response.text() || 'openai_tts_error'});
    res.setHeader('Content-Type','audio/mpeg');res.setHeader('Cache-Control','no-store');res.setHeader('X-Jarvis-Voice-Mode','stock');
    return res.status(200).send(Buffer.from(await response.arrayBuffer()));
  } catch(error){return res.status(500).json({error:error?.message || 'speech_backend_error'});}
}
