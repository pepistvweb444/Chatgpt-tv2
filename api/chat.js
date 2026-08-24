const OPENAI_URL = 'https://api.openai.com/v1/responses';

async function openai(key, payload) {
  const response = await fetch(OPENAI_URL, {
    method: 'POST',
    headers: { Authorization: `Bearer ${key}`, 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  });
  const data = await response.json();
  if (!response.ok) throw new Error(data?.error?.message || 'openai_error');
  const text = data.output_text || (data.output || []).flatMap(item => item.content || []).map(part => part.text || '').join('').trim();
  return { data, text };
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
    const items = blocks.map(b => ({
      title: tag(b,'title').replace(/\s+-\s+[^-]+$/,''),
      source: tag(b,'source'),
      url: tag(b,'link'),
      published: tag(b,'pubDate'),
      image: '', video: ''
    }));
    return await Promise.all(items.map(enrichNews));
  } catch { return []; }
}

export default async function handler(req, res) {
  if (req.method !== 'POST') return res.status(405).json({ error: 'method_not_allowed' });
  const key = process.env.OPENAI_API_KEY;
  if (!key) return res.status(503).json({ error: 'OPENAI_API_KEY_not_configured' });

  const {
    message,
    assistantName = 'Jarvis',
    conversationId = 'default',
    history = [],
    client = 'jarvis',
    previousResponseId = null,
    clientMcps = [],
    location = null,
    agentsEnabled = true
  } = req.body || {};
  if (!message || typeof message !== 'string') return res.status(400).json({ error: 'message_required' });

  const tools = [{ type: 'web_search' }];
  const mcpLabels = [];
  const addMcp = (server) => {
    if (!server?.server_url || !server?.server_label) return;
    const url = String(server.server_url);
    if (!/^https:\/\//i.test(url)) return;
    const label = String(server.server_label).replace(/[^a-zA-Z0-9_-]/g, '_').slice(0, 40) || 'mcp';
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
    ? history.slice(-50).filter(x => x && (x.role === 'user' || x.role === 'assistant') && typeof x.content === 'string')
    : [];

  const validLocation = location && Number.isFinite(Number(location.latitude)) && Number.isFinite(Number(location.longitude))
    ? { latitude:Number(location.latitude), longitude:Number(location.longitude), accuracyMeters:Number.isFinite(Number(location.accuracyMeters)) ? Math.round(Number(location.accuracyMeters)) : null }
    : null;
  const locationContext = validLocation
    ? ` Ubicación actual: latitud ${validLocation.latitude}, longitud ${validLocation.longitude}${validLocation.accuracyMeters ? `, precisión aproximada ${validLocation.accuracyMeters} metros` : ''}. Úsala cuando aporte valor y no muestres coordenadas salvo petición.`
    : '';

  const isNews = /(noticia|noticias|actualidad|últim[ao]s?|hoy|ahora|prensa|titulares|news)/i.test(message);
  const needsAgents = Boolean(agentsEnabled) && (isNews || message.length > 180 || /(investiga|compara|analiza|planifica|busca|revisa todo)/i.test(message));
  const model = process.env.OPENAI_MODEL || 'gpt-5.6-luna';
  const agentsUsed = [];
  const specialistContext = [];

  try {
    if (needsAgents) {
      const jobs = [];
      jobs.push(openai(key, {
        model,
        tools: [{ type:'web_search' }], tool_choice:'auto',
        input: [{ role:'developer', content:'Eres el agente investigador de Jarvis. Investiga hechos actuales, verifica fuentes y devuelve un informe breve en español para otro agente, no para el usuario final.' }, { role:'user', content:message }]
      }).then(r => { agentsUsed.push('research'); specialistContext.push(`AGENTE INVESTIGADOR:\n${r.text}`); }).catch(() => {}));
      if (isNews) {
        jobs.push(openai(key, {
          model,
          tools: [{ type:'web_search' }], tool_choice:'auto',
          input: [{ role:'developer', content:'Eres el agente de noticias de Jarvis. Selecciona las novedades más relevantes, evita duplicados, indica por qué importan y prioriza información reciente. Devuelve notas para el orquestador.' }, { role:'user', content:message }]
        }).then(r => { agentsUsed.push('news'); specialistContext.push(`AGENTE DE NOTICIAS:\n${r.text}`); }).catch(() => {}));
      }
      await Promise.all(jobs);
    }

    const developer = {
      role: 'developer',
      content: `Eres ${assistantName}, orquestador principal de Jarvis para móvil y televisión. Responde en español salvo petición contraria. Mantén continuidad estricta. Habla de forma natural y fácil de leer en voz alta: frases completas, puntuación clara, sin leer URLs largas. Usa búsqueda web para información actual y MCP cuando ayude. Si recibes informes de agentes especialistas, intégralos, comprueba coherencia y entrega una única respuesta final. Para noticias, menciona titulares y contexto; la interfaz mostrará aparte imágenes o vídeos disponibles. Cliente: ${client}. Conversación: ${conversationId}.${locationContext}`
    };

    const input = previousResponseId && typeof previousResponseId === 'string'
      ? [developer, ...specialistContext.map(content => ({ role:'developer', content })), { role:'user', content:message }]
      : [developer, ...trimmedHistory, ...specialistContext.map(content => ({ role:'developer', content })), { role:'user', content:message }];

    const payload = { model, tools, tool_choice:'auto', input };
    if (previousResponseId && typeof previousResponseId === 'string') payload.previous_response_id = previousResponseId;

    const final = await openai(key, payload);
    const news = isNews ? await newsMedia(message) : [];
    return res.status(200).json({
      reply: final.text,
      conversationId,
      responseId: final.data.id || null,
      tools: { webSearch:true, mcp:mcpLabels },
      agents: { enabled:Boolean(agentsEnabled), used:agentsUsed },
      news,
      images: news.map(n => n.image).filter(Boolean),
      videos: news.map(n => n.video).filter(Boolean)
    });
  } catch (error) {
    return res.status(500).json({ error: error?.message || 'backend_error' });
  }
}
