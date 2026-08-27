const REGION = { ES: 'EU', PT: 'EU', FR: 'EU', DE: 'EU', IT: 'EU', GB: 'EU', IE: 'EU', US: 'US', CA: 'US', KR: 'KR' };

function cfg() {
  const country = String(process.env.LG_THINQ_COUNTRY || 'ES').toUpperCase();
  const region = String(process.env.LG_THINQ_REGION || REGION[country] || 'EU').toUpperCase();
  return {
    token: String(process.env.LG_THINQ_PAT || '').trim(),
    apiKey: String(process.env.LG_THINQ_API_KEY || '').trim(),
    clientId: String(process.env.LG_THINQ_CLIENT_ID || '').trim(),
    country,
    phase: String(process.env.LG_THINQ_SERVICE_PHASE || 'OP').trim(),
    base: `https://api-${region.toLowerCase()}.lgthinq.com`
  };
}

async function lg(path, options = {}) {
  const c = cfg();
  if (!c.token) throw Object.assign(new Error('LG_THINQ_PAT_not_configured'), { status: 503 });
  const headers = {
    Authorization: `Bearer ${c.token}`,
    'x-country': c.country,
    'x-message-id': crypto.randomUUID(),
    'Accept': 'application/json',
    ...options.headers
  };
  if (c.apiKey) headers['x-api-key'] = c.apiKey;
  if (c.clientId) headers['x-client-id'] = c.clientId;
  if (c.phase) headers['x-service-phase'] = c.phase;
  const r = await fetch(`${c.base}/${path.replace(/^\//,'')}`, { ...options, headers });
  const raw = await r.text();
  let json; try { json = JSON.parse(raw); } catch { json = { raw }; }
  if (!r.ok) throw Object.assign(new Error(json?.message || json?.error || `LG ThinQ HTTP ${r.status}`), { status: r.status, payload: json });
  return json;
}

export default async function handler(req, res) {
  res.setHeader('Cache-Control', 'no-store');
  try {
    if (req.method === 'GET') {
      const id = String(req.query?.id || '').trim();
      if (!id) {
        const devices = await lg('devices');
        return res.status(200).json({ ok: true, source: 'LG ThinQ Connect API', devices });
      }
      const [profile, state] = await Promise.all([
        lg(`devices/${encodeURIComponent(id)}/profile`).catch(e => ({ error: e.message })),
        lg(`devices/${encodeURIComponent(id)}/state`).catch(e => ({ error: e.message }))
      ]);
      return res.status(200).json({ ok: true, source: 'LG ThinQ Connect API', id, profile, state });
    }
    if (req.method === 'POST') {
      const id = String(req.body?.id || '').trim();
      const payload = req.body?.payload;
      if (!id || !payload) return res.status(400).json({ error: 'id_and_payload_required' });
      const result = await lg(`devices/${encodeURIComponent(id)}/control`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'x-conditional-control': 'true' },
        body: JSON.stringify(payload)
      });
      return res.status(200).json({ ok: true, source: 'LG ThinQ Connect API', result });
    }
    return res.status(405).json({ error: 'method_not_allowed' });
  } catch (e) {
    return res.status(e.status || 500).json({ error: e.message || 'thinq_error', details: e.payload || undefined });
  }
}
