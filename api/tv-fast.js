// Harvest TV fast path (preview): streamed chat + per-sentence fast TTS in ONE request.
// Response: NDJSON lines {type:'text'|'audio'|'done'|'error', ...}. Audio = base64 mp3 per sentence, in order.
export const config = { maxDuration: 60 };

const SYS = 'Eres Jarvis, el asistente de la TV de Alberto en Pamplona, Navarra. Responde en español en 1 o 2 frases cortas, sin markdown ni emojis: se leerá en voz alta. Puedes hacer llamadas a través del móvil emparejado.';

function chatTarget() {
  if (process.env.OPENAI_API_KEY) return { url: 'https://api.openai.com/v1/chat/completions', key: process.env.OPENAI_API_KEY, model: process.env.JARVIS_FAST_CHAT_MODEL || 'gpt-4o-mini', name: 'openai' };
  if (process.env.DASHSCOPE_API_KEY) return { url: (process.env.QWEN_BASE_URL || 'https://dashscope-intl.aliyuncs.com/compatible-mode/v1') + '/chat/completions', key: process.env.DASHSCOPE_API_KEY, model: process.env.QWEN_FAST_MODEL || 'qwen-turbo', name: 'qwen' };
  if (process.env.GROQ_API_KEY && process.env.GROQ_MODEL) return { url: 'https://api.groq.com/openai/v1/chat/completions', key: process.env.GROQ_API_KEY, model: process.env.GROQ_MODEL, name: 'groq' };
  return null;
}

async function tts(text) {
  const key = process.env.OPENAI_API_KEY;
  if (!key) throw new Error('no_fast_tts');
  const r = await fetch('https://api.openai.com/v1/audio/speech', {
    method: 'POST', headers: { Authorization: `Bearer ${key}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ model: process.env.JARVIS_FAST_TTS_MODEL || 'tts-1', voice: process.env.JARVIS_FAST_TTS_VOICE || process.env.OPENAI_TTS_VOICE || 'onyx', input: text.slice(0, 600), speed: 1.1, response_format: 'mp3' })
  });
  if (!r.ok) throw new Error('tts_http_' + r.status);
  return Buffer.from(await r.arrayBuffer()).toString('base64');
}

export default async function handler(req, res) {
  if (req.method !== 'POST') return res.status(405).json({ error: 'POST only' });
  const t0 = Date.now();
  const body = typeof req.body === 'string' ? JSON.parse(req.body || '{}') : (req.body || {});
  const message = String(body.message || '').slice(0, 4000);
  if (!message) return res.status(400).json({ error: 'message_required' });
  res.setHeader('Content-Type', 'application/x-ndjson; charset=utf-8');
  res.setHeader('Cache-Control', 'no-store');
  res.setHeader('X-Accel-Buffering', 'no');
  const send = o => { res.write(JSON.stringify(o) + '\n'); };
  const target = chatTarget();
  if (!target) { send({ type: 'error', error: 'no_chat_provider' }); return res.end(); }
  const messages = [{ role: 'system', content: SYS + (body.context ? ' ' + String(body.context).slice(0, 1500) : '') }];
  (Array.isArray(body.history) ? body.history.slice(-8) : []).forEach(m => messages.push({ role: m.role === 'user' ? 'user' : 'assistant', content: String(m.text || m.content || '').slice(0, 800) }));
  messages.push({ role: 'user', content: message });

  let full = '', pending = '', idx = 0, firstText = 0;
  const audioJobs = []; let chain = Promise.resolve();
  const speak = (sentence) => {
    const i = idx++; const p = tts(sentence).then(b64 => ({ i, b64 }), e => ({ i, error: e.message }));
    chain = chain.then(() => p.then(r => { if (r.b64) send({ type: 'audio', i: r.i, text: sentence, mime: 'audio/mpeg', b64: r.b64, ms: Date.now() - t0 }); else send({ type: 'audio_error', i: r.i, error: r.error }); }));
    audioJobs.push(p);
  };
  const flush = (final) => {
    // first chunk goes out as soon as there is a short clause; later chunks per sentence
    const re = idx === 0 ? /^(.{12,}?[.!?…]|.{25,}?[,;:])\s/ : /^(.+?[.!?…])\s/;
    let m; while ((m = pending.match(re))) { speak(m[1].trim()); pending = pending.slice(m[0].length); }
    if (final && pending.trim()) { speak(pending.trim()); pending = ''; }
  };
  try {
    const r = await fetch(target.url, { method: 'POST', headers: { Authorization: `Bearer ${target.key}`, 'Content-Type': 'application/json' }, body: JSON.stringify({ model: target.model, messages, temperature: 0.3, max_tokens: 220, stream: true }) });
    if (!r.ok || !r.body) throw new Error(target.name + '_http_' + r.status);
    const reader = r.body.getReader(); const dec = new TextDecoder(); let buf = '';
    for (;;) {
      const { value, done } = await reader.read(); if (done) break;
      buf += dec.decode(value, { stream: true }); const lines = buf.split('\n'); buf = lines.pop();
      for (const line of lines) {
        const l = line.trim(); if (!l.startsWith('data:')) continue; const d = l.slice(5).trim(); if (d === '[DONE]') continue;
        let j; try { j = JSON.parse(d); } catch { continue; }
        const delta = j.choices?.[0]?.delta?.content || '';
        if (delta) { if (!firstText) { firstText = Date.now() - t0; send({ type: 'first_token', ms: firstText, provider: target.name }); } full += delta; pending += delta; send({ type: 'text', delta }); flush(false); }
      }
    }
    flush(true);
    await Promise.all(audioJobs); await chain;
    send({ type: 'done', text: full.trim(), provider: target.name, first_token_ms: firstText, total_ms: Date.now() - t0 });
  } catch (e) { send({ type: 'error', error: String(e.message || e), text: full }); }
  res.end();
}
