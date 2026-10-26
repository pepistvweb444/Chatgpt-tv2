// Jarvis cloud sync (TV <-> phone <-> cloud). One function for all /api/sync/* routes.
// Auth: "Authorization: Bearer <household token>". The token is verified inside
// Supabase (sha256 match in jarvis_sync.households) by SECURITY DEFINER RPCs.
// Tables live in the non-exposed schema jarvis_sync with RLS on and no policies,
// so the public key alone cannot read anything.
// Env: JARVIS_SYNC_SUPABASE_URL, JARVIS_SYNC_SUPABASE_KEY (publishable key).
const URL_ = process.env.JARVIS_SYNC_SUPABASE_URL;
const KEY = process.env.JARVIS_SYNC_SUPABASE_KEY;

async function rpc(fn, args) {
  const r = await fetch(`${URL_}/rest/v1/rpc/${fn}`, {
    method: 'POST',
    headers: { apikey: KEY, Authorization: `Bearer ${KEY}`, 'Content-Type': 'application/json' },
    body: JSON.stringify(args),
  });
  const text = await r.text();
  let data; try { data = JSON.parse(text); } catch { data = text; }
  if (!r.ok) {
    const e = new Error((data && data.message) || 'supabase_error');
    e.status = data && data.code === '28000' ? 401 : 502;
    throw e;
  }
  return data;
}

async function readBody(req) {
  if (req.body && typeof req.body === 'object') return req.body;
  if (typeof req.body === 'string') { try { return JSON.parse(req.body); } catch { return {}; } }
  const chunks = []; for await (const c of req) chunks.push(c);
  try { return JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}'); } catch { return {}; }
}

export default async function handler(req, res) {
  res.setHeader('Cache-Control', 'no-store');
  if (!URL_ || !KEY) return res.status(503).json({ error: 'sync_not_configured' });
  const auth = String(req.headers.authorization || '');
  const token = auth.startsWith('Bearer ') ? auth.slice(7).trim() : '';
  if (!token) return res.status(401).json({ error: 'unauthorized' });

  let parts = req.query.path;
  if (!Array.isArray(parts)) parts = String(parts || '').split('/').filter(Boolean);
  const since = req.query.since ? String(req.query.since) : null;

  try {
    // GET/POST /api/sync/conversations
    if (parts.length === 1 && parts[0] === 'conversations') {
      if (req.method === 'GET') {
        return res.json({ conversations: await rpc('jarvis_sync_list_conversations', { p_token: token, p_since: since }) });
      }
      if (req.method === 'POST') {
        const b = await readBody(req);
        const id = String(b.id || '').slice(0, 120);
        if (!id) return res.status(400).json({ error: 'id_required' });
        const out = await rpc('jarvis_sync_append_messages', {
          p_token: token, p_conversation_id: id, p_title: b.title || '', p_source: b.source || 'tv',
          p_device_id: b.deviceId || null, p_messages: Array.isArray(b.messages) ? b.messages : [],
        });
        return res.json({ id, ...out });
      }
    }
    // GET/POST /api/sync/conversations/:id/messages
    if (parts.length === 3 && parts[0] === 'conversations' && parts[2] === 'messages') {
      const id = decodeURIComponent(parts[1]).slice(0, 120);
      if (req.method === 'GET') {
        return res.json(await rpc('jarvis_sync_get_messages', { p_token: token, p_conversation_id: id, p_since: since }));
      }
      if (req.method === 'POST') {
        const b = await readBody(req);
        const msgs = Array.isArray(b.messages) ? b.messages : (b.text ? [b] : []);
        const out = await rpc('jarvis_sync_append_messages', {
          p_token: token, p_conversation_id: id, p_title: b.title || '', p_source: b.source || 'tv',
          p_device_id: b.deviceId || null, p_messages: msgs,
        });
        return res.json({ id, ...out });
      }
    }
    // GET/POST /api/sync/phone
    if (parts.length === 1 && parts[0] === 'phone') {
      if (req.method === 'GET') {
        const kind = req.query.kind ? String(req.query.kind) : null;
        return res.json({ items: await rpc('jarvis_sync_get_phone_items', { p_token: token, p_kind: kind, p_since: since }) });
      }
      if (req.method === 'POST') {
        const b = await readBody(req);
        return res.json(await rpc('jarvis_sync_put_phone_items', { p_token: token, p_items: Array.isArray(b.items) ? b.items : [] }));
      }
    }
    return res.status(404).json({ error: 'not_found' });
  } catch (e) {
    return res.status(e.status || 500).json({ error: e.status === 401 ? 'unauthorized' : 'sync_failed' });
  }
}
