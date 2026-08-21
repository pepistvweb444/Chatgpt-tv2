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
    previousResponseId = null
  } = req.body || {};
  if (!message || typeof message !== 'string') return res.status(400).json({ error: 'message_required' });

  const tools = [{ type: 'web_search' }];
  const mcpLabels = [];
  if (process.env.JARVIS_MCP_SERVERS_JSON) {
    try {
      const servers = JSON.parse(process.env.JARVIS_MCP_SERVERS_JSON);
      for (const server of Array.isArray(servers) ? servers : []) {
        if (!server?.server_url || !server?.server_label) continue;
        mcpLabels.push(String(server.server_label));
        tools.push({
          type: 'mcp',
          server_label: String(server.server_label),
          server_url: String(server.server_url),
          ...(server.authorization ? { authorization: String(server.authorization) } : {}),
          require_approval: server.require_approval ?? 'always'
        });
      }
    } catch (_) {}
  }

  const trimmedHistory = Array.isArray(history)
    ? history.slice(-50).filter(x => x && (x.role === 'user' || x.role === 'assistant') && typeof x.content === 'string')
    : [];

  const developer = {
    role: 'developer',
    content: `Eres ${assistantName}, el asistente personal de Jarvis para móvil y televisión. Responde en español salvo petición contraria. Mantén continuidad estricta con lo hablado antes: cuando el usuario diga "estos", "los anteriores", "filtra", "ordénalos", "más baratos", "de esos restaurantes" o cualquier referencia similar, debes aplicarla al contexto previo y NO reiniciar la conversación ni volver a preguntar qué quiere salvo que realmente falte un dato imprescindible. Usa búsqueda web cuando la pregunta dependa de información actual o pública. Si hay herramientas MCP disponibles, úsalas cuando ayuden a cumplir la petición y respeta sus aprobaciones. Cliente: ${client}. Conversación: ${conversationId}.`
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
      headers: {
        'Authorization': `Bearer ${key}`,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify(payload)
    });

    const data = await response.json();
    if (!response.ok) return res.status(response.status).json({ error: data?.error?.message || 'openai_error', details: data?.error || null });
    const reply = data.output_text || (data.output || [])
      .flatMap(item => item.content || [])
      .map(part => part.text || '')
      .join('')
      .trim();
    return res.status(200).json({
      reply,
      conversationId,
      responseId: data.id || null,
      tools: { webSearch: true, mcp: mcpLabels }
    });
  } catch (error) {
    return res.status(500).json({ error: error?.message || 'backend_error' });
  }
}