export default function handler(req, res) {
  const code = String(req.query?.code || '');
  const state = String(req.query?.state || '');
  const error = String(req.query?.error || '');
  const params = new URLSearchParams();
  if (code) params.set('code', code);
  if (state) params.set('state', state);
  if (error) params.set('error', error);
  const deepLink = `jarvis://homeconnect?${params.toString()}`;

  res.setHeader('Cache-Control', 'no-store');
  res.setHeader('Content-Type', 'text/html; charset=utf-8');
  res.status(200).send(`<!doctype html>
<html lang="es"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Volver a Jarvis</title>
<style>body{font-family:system-ui,-apple-system,sans-serif;background:#0b0e14;color:#fff;margin:0;display:grid;place-items:center;min-height:100vh}.card{max-width:520px;margin:24px;padding:28px;border-radius:24px;background:#151b27;text-align:center}.btn{display:inline-block;margin-top:18px;padding:15px 22px;border-radius:16px;background:#5e46d8;color:white;text-decoration:none;font-weight:700}</style></head>
<body><div class="card"><h2>Home Connect autorizado</h2><p>La autorización ha terminado. Vuelve a Jarvis para completar la conexión.</p><a class="btn" href="${deepLink}">Volver a Jarvis</a><p style="opacity:.7;font-size:13px">Si Jarvis no se abre automáticamente, pulsa el botón.</p></div>
<script>setTimeout(function(){ window.location.href=${JSON.stringify(deepLink)}; },350);</script></body></html>`);
}
