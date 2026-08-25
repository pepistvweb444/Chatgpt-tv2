const HC = 'https://api.home-connect.com';

function form(data) {
  return new URLSearchParams(Object.entries(data).filter(([,v]) => v !== undefined && v !== null && String(v).length)).toString();
}

async function postForm(url, data) {
  const r = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded', 'Accept': 'application/json' },
    body: form(data)
  });
  const raw = await r.text();
  let json = {};
  try { json = JSON.parse(raw); } catch { json = { raw }; }
  return { ok: r.ok, status: r.status, json };
}

export default async function handler(req, res) {
  res.setHeader('Cache-Control', 'no-store');
  if (req.method !== 'POST') return res.status(405).json({ error: 'method_not_allowed' });

  const clientId = String(process.env.HOMECONNECT_CLIENT_ID || '').trim();
  const clientSecret = String(process.env.HOMECONNECT_CLIENT_SECRET || '').trim();
  const scope = String(process.env.HOMECONNECT_SCOPE || 'IdentifyAppliance Monitor Control Settings').trim();
  if (!clientId) return res.status(503).json({ error: 'HOMECONNECT_CLIENT_ID_not_configured' });

  const action = String(req.body?.action || '').toLowerCase();
  try {
    if (action === 'start') {
      const out = await postForm(`${HC}/security/oauth/device_authorization`, { client_id: clientId, scope });
      return res.status(out.status).json(out.json);
    }

    if (action === 'token') {
      const deviceCode = String(req.body?.deviceCode || '');
      if (!deviceCode) return res.status(400).json({ error: 'deviceCode_required' });
      const out = await postForm(`${HC}/security/oauth/token`, {
        grant_type: 'device_code', device_code: deviceCode, client_id: clientId
      });
      return res.status(out.status).json(out.json);
    }

    if (action === 'refresh') {
      const refreshToken = String(req.body?.refreshToken || '');
      if (!refreshToken) return res.status(400).json({ error: 'refreshToken_required' });
      if (!clientSecret) return res.status(503).json({ error: 'HOMECONNECT_CLIENT_SECRET_not_configured' });
      const out = await postForm(`${HC}/security/oauth/token`, {
        grant_type: 'refresh_token', refresh_token: refreshToken, client_secret: clientSecret
      });
      return res.status(out.status).json(out.json);
    }

    return res.status(400).json({ error: 'unknown_action' });
  } catch (e) {
    return res.status(500).json({ error: e?.message || 'homeconnect_auth_error' });
  }
}
