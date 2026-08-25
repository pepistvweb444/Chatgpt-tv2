const OPENAI_URL = 'https://api.openai.com/v1/responses';

async function openai(key, payload) {
  const response = await fetch(OPENAI_URL, {
    method: 'POST',
    headers: { Authorization: `Bearer ${key}`, 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) {
    const err = new Error(data?.error?.message || `openai_http_${response.status}`);
    err.status = response.status;
    throw err;
  }
  const text = data.output_text || (data.output || []).flatMap(item => item.content || []).map(part => part.text || '').join('').trim();
  return { data, text, provider: 'openai' };
}

function toChatMessages(input = []) {
  return input.map(m => ({ role: m.role === 'developer' ? 'system' : m.role, content: String(m.content || '') }));
}

async function openAICompatible({ provider, key, baseUrl, model, input }) {
  const response = await fetch(`${String(baseUrl).replace(/\/$/, '')}/chat/completions`, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${key}`,
      'Content-Type': 'application/json',
      ...(provider === 'openrouter' ? { 'HTTP-Referer': process.env.JARVIS_PUBLIC_BASE_URL || 'https://chatgpt-tv2.vercel.app', 'X-Title': 'Jarvis AI Companion' } : {})
    },
    body: JSON.stringify({ model, messages: toChatMessages(input), temperature: 0.3 })
  });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(data?.error?.message || data?.message || `${provider}_http_${response.status}`);
  const text = data?.choices?.[0]?.message?.content;
  if (!text) throw new Error(`${provider}_empty_response`);
  return { data, text: String(text).trim(), provider };
}

async function gemini(key, model, input) {
  const system = input.filter(m => m.role === 'developer').map(m => String(m.content || '')).join('\n\n');
  const contents = input.filter(m => m.role !== 'developer').map(m => ({
    role: m.role === 'assistant' ? 'model' : 'user',
    parts: [{ text: String(m.content || '') }]
  }));
  const response = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(model)}:generateContent?key=${encodeURIComponent(key)}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      ...(system ? { systemInstruction: { parts: [{ text: system }] } } : {}),
      contents,
      generationConfig: { temperature: 0.3 }
    })
  });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(data?.error?.message || `gemini_http_${response.status}`);
  const text = (data?.candidates?.[0]?.content?.parts || []).map(p => p.text || '').join('').trim();
  if (!text) throw new Error('gemini_empty_response');
  return { data, text, provider: 'gemini' };
}

async function providerFallback(input, openaiPayload) {
  const order = String(process.env.JARVIS_AI_PROVIDER_ORDER || 'openai,qwen,gemini,groq,openrouter')
    .split(',').map(x => x.trim().toLowerCase()).filter(Boolean);
  const errors = [];
  for (const provider of order) {
    try {
      if (provider === 'openai' && process.env.OPENAI_API_KEY) {
        return await openai(process.env.OPENAI_API_KEY, openaiPayload);
      }
      if (provider === 'qwen' && process.env.DASHSCOPE_API_KEY) {
        return await openAICompatible({ provider, key: process.env.DASHSCOPE_API_KEY, baseUrl: process.env.QWEN_BASE_URL || 'https://dashscope-intl.aliyuncs.com/compatible-mode/v1', model: process.env.QWEN_MODEL || 'qwen-plus', input });
      }
      if (provider === 'gemini' && process.env.GEMINI_API_KEY) {
        return await gemini(process.env.GEMINI_API_KEY, process.env.GEMINI_MODEL || 'gemini-2.5-flash', input);
      }
      if (provider === 'groq' && process.env.GROQ_API_KEY && process.env.GROQ_MODEL) {
        return await openAICompatible({ provider, key: process.env.GROQ_API_KEY, baseUrl: 'https://api.groq.com/openai/v1', model: process.env.GROQ_MODEL, input });
      }
      if (provider === 'openrouter' && process.env.OPENROUTER_API_KEY) {
        return await openAICompatible({ provider, key: process.env.OPENROUTER_API_KEY, baseUrl: process.env.OPENROUTER_BASE_URL || 'https://openrouter.ai/api/v1', model: process.env.OPENROUTER_MODEL || 'openrouter/free', input });
      }
    } catch (e) {
      errors.push(`${provider}: ${e?.message || 'error'}`);
    }
  }
  const err = new Error(errors.length ? `Todos los proveedores de IA fallaron (${errors.join(' | ')})` : 'No hay ningún proveedor de IA configurado');
  err.providerErrors = errors;
  throw err;
}

function esc(s=''){return String(s).replace(/&amp;/g,'&').replace(/&quot;/g,'"').replace(/&#39;/g,"'").replace(/&lt;/g,'<').replace(/&gt;/g,'>')}
function strip(s=''){return esc(String(s).replace(/<!\[CDATA\[|\]\]>/g,'')).replace(/<[^>]+>/g,' ').replace(/\s+/g,' ').trim()}
function tag(block,name){const m=block.match(new RegExp(`<${name}[^>]*>([\\s\\S]*?)<\\/${name}>`,'i'));return m?strip(m[1]):''}

async function enrichNews(item) {
  try {
    const c = new AbortController(); const t = setTimeout(() => c.abort(), 2200);
    const r = await fetch(item.url, { redirect:'follow', signal:c.signal, headers:{'user-agent':'Mozilla/5.0 JarvisNews/1.1'} });
    clearTimeout(t); if (!r.ok) return item;
    const html = (await r.text()).slice(0, 300000);
    const image = (html.match(/<meta[^>]+(?:property|name)=["']og:image["'][^>]+content=["']([^"']+)/i)||html.match(/<meta[^>]+content=["']([^"']+)["'][^>]+(?:property|name)=["']og:image["']/i)||[])[1] || '';
    const video = (html.match(/<meta[^>]+(?:property|name)=["']og:video(?::url)?["'][^>]+content=["']([^"']+)/i)||[])[1] || '';
    return { ...item, image, video };
  } catch { return item; }
}

async function newsMedia(query) {
  try {
    const url = `https://news.google.com/rss/search?q=${encodeURIComponent(query)}&hl=es&gl=ES&ceid=ES:es`;
    const r = await fetch(url, { headers:{'user-agent':'Mozilla/5.0 JarvisNews/1.1'} });
    if (!r.ok) return [];
    const xml = await r.text();
    const blocks = [...xml.matchAll(/<item>([\s\S]*?)<\/item>/gi)].slice(0, 5).map(m => m[1]);
    const items = blocks.map(b => ({ title:tag(b,'title').replace(/\s+-\s+[^-]+$/,''), source:tag(b,'source'), url:tag(b,'link'), published:tag(b,'pubDate'), image:'', video:'' }));
    return await Promise.all(items.map(enrichNews));
  } catch { return []; }
}

export default async function handler(req, res) {
  if (req.method !== 'POST') return res.status(405).json({ error: 'method_not_allowed' });

  const {
    message,
    assistantName = 'Jarvis',
    conversationId = 'default',
    history = [],
    client = 'jarvis',
    previousResponseId = null,
    clientMcps = [],
    selectedTools = [],
    location = null,
    agentsEnabled = true,
    agentsConfig = {}
  } = req.body || {};
  if (!message || typeof message !== 'string') return res.status(400).json({ error: 'message_required' });

  const agentCfg = {
    research: agentsConfig?.research !== false,
    news: agentsConfig?.news !== false,
    home: agentsConfig?.home !== false
  };
  const selected = Array.isArray(selectedTools) ? selectedTools.map(String).slice(0, 20) : [];

  const tools = [{ type: 'web_search' }];
  const mcpLabels = [];
  const addMcp = (server) => {
    if (!server?.server_url || !server?.server_label) return;
    const url = String(server.server_url);
    if (!/^https:\/\//i.test(url)) return;
    const label = String(server.server_label).replace(/[^a-zA-Z0-9_-]/g, '_').slice(0, 40) || 'mcp';
    if (mcpLabels.includes(label)) return;
    mcpLabels.push(label);
    tools.push({
      type: 'mcp', server_label: label, server_url: url,
      ...(server.authorization ? { authorization: String(server.authorization) } : {}),
      require_approval: server.require_approval ?? 'always'
    });
  };

  if (process.env.JARVIS_MCP_SERVERS_JSON) {
    try { for (const server of (JSON.parse(process.env.JARVIS_MCP_SERVERS_JSON) || [])) addMcp(server); } catch (_) {}
  }
  for (const server of Array.isArray(clientMcps) ? clientMcps.slice(0, 8) : []) addMcp(server);

  const trimmedHistory = Array.isArray(history)
    ? history.slice(-50).filter(x => x && (x.role === 'user' || x.role === 'assistant') && typeof x.content === 'string').map(x => ({ role:x.role, content:x.content }))
    : [];

  const validLocation = location && Number.isFinite(Number(location.latitude)) && Number.isFinite(Number(location.longitude))
    ? { latitude:Number(location.latitude), longitude:Number(location.longitude), accuracyMeters:Number.isFinite(Number(location.accuracyMeters)) ? Math.round(Number(location.accuracyMeters)) : null }
    : null;
  const locationContext = validLocation
    ? ` Ubicación actual: latitud ${validLocation.latitude}, longitud ${validLocation.longitude}${validLocation.accuracyMeters ? `, precisión aproximada ${validLocation.accuracyMeters} metros` : ''}. Úsala cuando aporte valor y no muestres coordenadas salvo petición.`
    : '';

  const isNews = /(noticia|noticias|actualidad|últim[ao]s?|hoy|ahora|prensa|titulares|news)/i.test(message);
  const isHome = /(tado|sensibo|home connect|aire acondicionado|climatiz|temperatura de casa|domótica|luces|persianas|termostato)/i.test(message);
  const researchRequested = message.length > 180 || /(investiga|compara|analiza|planifica|busca|revisa todo)/i.test(message);
  const needsAgents = Boolean(agentsEnabled) && ((agentCfg.news && isNews) || (agentCfg.research && researchRequested));
  const model = process.env.OPENAI_MODEL || 'gpt-5.6-luna';
  const agentsUsed = [];
  const specialistContext = [];

  try {
    // Specialist agents remain an optimization. If OpenAI is unavailable or out of
    // credit they are skipped; the final answer still uses the provider router.
    if (needsAgents && process.env.OPENAI_API_KEY) {
      const jobs = [];
      if (agentCfg.research && (researchRequested || isNews)) {
        jobs.push(openai(process.env.OPENAI_API_KEY, {
          model, tools:[{ type:'web_search' }], tool_choice:'auto',
          input:[{ role:'developer', content:'Eres el agente investigador de Jarvis. Investiga hechos actuales, verifica fuentes y devuelve un informe breve en español para otro agente, no para el usuario final.' }, { role:'user', content:message }]
        }).then(r => { agentsUsed.push('research'); specialistContext.push(`AGENTE INVESTIGADOR:\n${r.text}`); }).catch(() => {}));
      }
      if (agentCfg.news && isNews) {
        jobs.push(openai(process.env.OPENAI_API_KEY, {
          model, tools:[{ type:'web_search' }], tool_choice:'auto',
          input:[{ role:'developer', content:'Eres el agente de noticias de Jarvis. Selecciona las novedades más relevantes, evita duplicados, indica por qué importan y prioriza información reciente. Devuelve notas para el orquestador.' }, { role:'user', content:message }]
        }).then(r => { agentsUsed.push('news'); specialistContext.push(`AGENTE DE NOTICIAS:\n${r.text}`); }).catch(() => {}));
      }
      await Promise.all(jobs);
    }

    const selectedContext = selected.length ? ` Herramientas seleccionadas por el usuario: ${selected.join(', ')}.` : '';
    const homeContext = isHome && agentCfg.home
      ? ' Para domótica y climatización, las acciones directas deben ejecutarse en la app/API del proveedor antes de usar un LLM. No afirmes haber cambiado un dispositivo si la herramienta no confirma la acción.'
      : '';
    const developer = {
      role: 'developer',
      content: `Eres ${assistantName}, orquestador principal de Jarvis para móvil y televisión. Responde en español salvo petición contraria. Mantén continuidad estricta. Habla de forma natural y fácil de leer en voz alta: frases completas, puntuación clara, sin leer URLs largas. Si dispones de búsqueda o herramientas úsalas cuando ayuden. Si recibes informes de agentes especialistas, intégralos y entrega una única respuesta final. Cliente: ${client}. Conversación: ${conversationId}.${locationContext}${selectedContext}${homeContext}`
    };

    const input = previousResponseId && typeof previousResponseId === 'string'
      ? [developer, ...specialistContext.map(content => ({ role:'developer', content })), { role:'user', content:message }]
      : [developer, ...trimmedHistory, ...specialistContext.map(content => ({ role:'developer', content })), { role:'user', content:message }];

    const payload = { model, tools, tool_choice:'auto', input };
    if (previousResponseId && typeof previousResponseId === 'string') payload.previous_response_id = previousResponseId;

    const final = await providerFallback(input, payload);
    const news = isNews ? await newsMedia(message) : [];
    return res.status(200).json({
      reply: final.text,
      conversationId,
      responseId: final.provider === 'openai' ? (final.data.id || null) : null,
      provider: final.provider,
      tools:{ webSearch:final.provider === 'openai', mcp:final.provider === 'openai' ? mcpLabels : [], selected },
      agents:{ enabled:Boolean(agentsEnabled), config:agentCfg, used:agentsUsed },
      news,
      images:news.map(n => n.image).filter(Boolean),
      videos:news.map(n => n.video).filter(Boolean)
    });
  } catch (error) {
    return res.status(503).json({ error:'ai_provider_unavailable', message:'Jarvis no ha podido conectar con ningún proveedor de IA disponible.', details:error?.providerErrors || [] });
  }
}
