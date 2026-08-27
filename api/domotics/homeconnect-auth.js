const HC = 'https://api.home-connect.com';

function form(data) {
  return new URLSearchParams(Object.entries(data).filter(([,v]) => v !== undefined && v !== null && String(v).length)).toString();
}

function publicBase(req) {
  const configured = String(process.env.JARVIS_PUBLIC_BASE_URL || '').trim().replace(/\/$/, '');
  if (configured) return configured;
  const proto = String(req.headers['x-forwarded-proto'] || 'https').split(',')[0].trim();
  const host = String(req.headers['x-forwarded-host'] || req.headers.host || '').split(',')[0].trim();
  return host ? `${proto}://${host}` : 'https://chatgpt-tv2.vercel.app';
}

function redirectUri(req) {
  // Keep one canonical callback path. Older configuration used
  // /api/domotics/homeconnect/callback, but this repository deploys the
  // serverless function as /api/domotics/homeconnect-callback.
  const canonical = `${publicBase(req)}/api/domotics/homeconnect-callback`;
  const configured = String(process.env.HOMECONNECT_REDIRECT_URI || '').trim();
  if (!configured) return canonical;
  try {
    const u = new URL(configured);
    if (u.pathname === '/api/domotics/homeconnect/callback') return canonical;
    return configured;
  } catch {
    return canonical;
  }
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
  const redirect = redirectUri(req);
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
