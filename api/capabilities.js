export default async function handler(req, res) {
  if (req.method !== 'GET') return res.status(405).json({ error: 'method_not_allowed' });
  const mcps = [];
  if (process.env.JARVIS_MCP_SERVERS_JSON) {
    try {
      const servers = JSON.parse(process.env.JARVIS_MCP_SERVERS_JSON);
      for (const server of Array.isArray(servers) ? servers : []) {
        if (!server?.server_label || !server?.server_url) continue;
        mcps.push({ label: String(server.server_label), approval: String(server.require_approval ?? 'always'), configured: true });
      }
    } catch (_) {}
  }
  const aiProviders = {
    openai: Boolean(process.env.OPENAI_API_KEY),
    qwen: Boolean(process.env.DASHSCOPE_API_KEY),
    gemini: Boolean(process.env.GEMINI_API_KEY),
    groq: Boolean(process.env.GROQ_API_KEY),
    openrouter: Boolean(process.env.OPENROUTER_API_KEY)
  };
  return res.status(200).json({
    backend: 'ok',
    webSearch: true,
    mcpConfigured: mcps.length > 0,
    mcps,
    aiProviders,
    aiProviderOrder: String(process.env.JARVIS_AI_PROVIDER_ORDER || 'openai,qwen,gemini,groq,openrouter').split(',').map(x => x.trim()).filter(Boolean),
    transcriptionOrder: ['groq', 'openai'],
    groqTranscriptionConfigured: Boolean(process.env.GROQ_API_KEY)
  });
}
