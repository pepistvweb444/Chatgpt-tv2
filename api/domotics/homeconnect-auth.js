const HC = 'https://api.home-connect.com';
const HOMECONNECT_CALLBACK = 'https://chatgpt-tv2.vercel.app/api/domotics/homeconnect-callback';

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
  const redirect = HOMECONNECT_CALLBACK;
  const scope = String(process.env.HOMECONNECT_SCOPES || process.env.HOMECONNECT_SCOPE || 'IdentifyAppliance Monitor Control Settings').trim();
  if (!clientId) return res.status(503).json({ error: 'HOMECONNECT_CLIENT_ID_not_configured' });

  const action = String(req.body?.action || '').toLowerCase();
  try {
    if (action === 'start') {
      const state = String(req.body?.state || Math.random().toString(36).slice(2));
      const q = new URLSearchParams({ client_id: clientId, redirect_uri: redirect, response_type: 'code', scope, state });
      return res.status(200).json({ authorization_url: `${HC}/security/oauth/authorize?${q.toString()}`, state, redirect_uri: redirect });
    }

    if (action === 'exchange') {
      const code = String(req.body?.code || '');
      if (!code) return res.status(400).json({ error: 'code_required' });
      if (!clientSecret) return res.status(503).json({ error: 'HOMECONNECT_CLIENT_SECRET_not_configured' });
      const out = await postForm(`${HC}/security/oauth/token`, {
        grant_type: 'authorization_code', code, client_id: clientId, client_secret: clientSecret, redirect_uri: redirect
      });
      return res.status(out.status).json(out.json);
    }

    if (action === 'refresh') {
      const refreshToken = String(req.body?.refreshToken || '');
      if (!refreshToken) return res.status(400).json({ error: 'refreshToken_required' });
      if (!clientSecret) return res.status(503).json({ error: 'HOMECONNECT_CLIENT_SECRET_not_configured' });
      const out = await postForm(`${HC}/security/oauth/token`, {
        grant_type: 'refresh_token', refresh_token: refreshToken, client_id: clientId, client_secret: clientSecret
      });
      return res.status(out.status).json(out.json);
    }

    return res.status(400).json({ error: 'unknown_action' });
  } catch (e) {
    return res.status(500).json({ error: e?.message || 'homeconnect_auth_error' });
  }
}
