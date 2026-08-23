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
    location = null
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
      type: 'mcp',
      server_label: label,
      server_url: url,
      ...(server.authorization ? { authorization: String(server.authorization) } : {}),
      require_approval: server.require_approval ?? 'always'
    });
  };

  if (process.env.JARVIS_MCP_SERVERS_JSON) {
    try {
      const servers = JSON.parse(process.env.JARVIS_MCP_SERVERS_JSON);
      for (const server of Array.isArray(servers) ? servers : []) addMcp(server);
    } catch (_) {}
  }
  for (const server of Array.isArray(clientMcps) ? clientMcps.slice(0, 8) : []) addMcp(server);

  const trimmedHistory = Array.isArray(history)
    ? history.slice(-50).filter(x => x && (x.role === 'user' || x.role === 'assistant') && typeof x.content === 'string')
    : [];

  const validLocation = location && Number.isFinite(Number(location.latitude)) && Number.isFinite(Number(location.longitude))
    ? {
        latitude: Number(location.latitude),
        longitude: Number(location.longitude),
        accuracyMeters: Number.isFinite(Number(location.accuracyMeters)) ? Math.round(Number(location.accuracyMeters)) : null,
        timestamp: Number.isFinite(Number(location.timestamp)) ? Number(location.timestamp) : null
      }
    : null;

  const locationContext = validLocation
    ? ` Ubicación actual proporcionada por el teléfono: latitud ${validLocation.latitude}, longitud ${validLocation.longitude}${validLocation.accuracyMeters ? `, precisión aproximada ${validLocation.accuracyMeters} metros` : ''}. Úsala cuando el usuario pregunte distancias, cómo ir a un sitio, qué hay cerca o tiempos de desplazamiento. No muestres coordenadas salvo que sean útiles o te las pidan.`
    : '';

  const developer = {
    role: 'developer',
    content: `Eres ${assistantName}, el asistente personal de Jarvis para móvil y televisión. Responde en español salvo petición contraria. Mantén continuidad estricta con lo hablado antes. Para temperaturas habladas en español usa expresiones naturales como "20 grados", no "20 C", salvo que el usuario pida la unidad explícitamente. Usa búsqueda web para información actual. Si hay herramientas MCP disponibles, úsalas cuando ayuden y respeta las aprobaciones. Cliente: ${client}. Conversación: ${conversationId}.${locationContext}`
  };

  const payload = {
    model: process.env.OPENAI_MODEL || 'gpt-5.6-luna',
    tools,
    tool_choice: 'auto'
  };

  if (previousResponseId && typeof previousResponseId === 'string') {
    payload.previous_response_id = previousResponseId;
    payload.input = [developer, { role: 'user', content: message }];
  } else {
    payload.input = [developer, ...trimmedHistory, { role: 'user', content: message }];
  }

  try {
    const response = await fetch('https://api.openai.com/v1/responses', {
      method: 'POST',
      headers: { 'Authorization': `Bearer ${key}`, 'Content-Type': 'application/json' },
      body: JSON.stringify(payload)
    });
    const data = await response.json();
    if (!response.ok) return res.status(response.status).json({ error: data?.error?.message || 'openai_error', details: data?.error || null });
    const reply = data.output_text || (data.output || []).flatMap(item => item.content || []).map(part => part.text || '').join('').trim();
    return res.status(200).json({ reply, conversationId, responseId: data.id || null, tools: { webSearch: true, mcp: mcpLabels } });
  } catch (error) {
    return res.status(500).json({ error: error?.message || 'backend_error' });
  }
}