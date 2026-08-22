import crypto from 'crypto';

function cfg() {
  const url = process.env.KV_REST_API_URL || process.env.UPSTASH_REDIS_REST_URL || '';
  const token = process.env.KV_REST_API_TOKEN || process.env.UPSTASH_REDIS_REST_TOKEN || '';
  return { url: url.replace(/\/$/, ''), token };
}

async function redis(command) {
  const { url, token } = cfg();
  if (!url || !token) throw new Error('sync_store_not_configured');
  const r = await fetch(`${url}/${command.map(encodeURIComponent).join('/')}`, {
    headers: { Authorization: `Bearer ${token}` },
    signal: AbortSignal.timeout(10000)
  });
  if (!r.ok) throw new Error(`sync_store_http_${r.status}`);
  const j = await r.json();
  return j.result;
}

function keyFor(raw) {
  const key = String(raw || '');
  if (key.length < 16) return null;
  return 'jarvis:sync:' + crypto.createHash('sha256').update(key).digest('hex');
}

export default async function handler(req, res) {
  const storageKey = keyFor(req.headers['x-jarvis-sync-key']);
  if (!storageKey) return res.status(401).json({ error: 'sync_key_required', minLength: 16 });

  try {
    if (req.method === 'GET') {
      const raw = await redis(['get', storageKey]);
      if (!raw) return res.status(200).json({ updatedAt: 0, state: {} });
      return res.status(200).json(JSON.parse(raw));
    }

    if (req.method === 'POST') {
      const incoming = req.body || {};
      const updatedAt = Number(incoming.updatedAt || Date.now());
      const state = incoming.state && typeof incoming.state === 'object' ? incoming.state : {};
      const currentRaw = await redis(['get', storageKey]);
      const current = currentRaw ? JSON.parse(currentRaw) : { updatedAt: 0, state: {} };

      if (Number(current.updatedAt || 0) > updatedAt) {
        return res.status(200).json({ accepted: false, ...current });
      }

      const doc = { updatedAt, state };
      await redis(['set', storageKey, JSON.stringify(doc)]);
      return res.status(200).json({ accepted: true, ...doc });
    }

    return res.status(405).json({ error: 'method_not_allowed' });
  } catch (e) {
    const msg = e?.message || 'sync_error';
    const status = msg === 'sync_store_not_configured' ? 503 : 500;
    return res.status(status).json({ error: msg });
  }
}
