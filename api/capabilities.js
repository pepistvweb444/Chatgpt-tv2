export default async function handler(req, res) {
  if (req.method !== 'GET') return res.status(405).json({ error: 'method_not_allowed' });
  const mcps = [];
  if (process.env.JARVIS_MCP_SERVERS_JSON) {
    try {
      const servers = JSON.parse(process.env.JARVIS_MCP_SERVERS_JSON);
      for (const server of Array.isArray(servers) ? servers : []) {
        if (!server?.server_label || !server?.server_url) continue;
        mcps.push({
          label: String(server.server_label),
          approval: String(server.require_approval ?? 'always'),
          configured: true
        });
      }
    } catch (_) {}
  }
  return res.status(200).json({
    backend: 'ok',
    webSearch: true,
    mcpConfigured: mcps.length > 0,
    mcps
  });
}