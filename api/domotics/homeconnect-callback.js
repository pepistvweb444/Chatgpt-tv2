export default function handler(req, res) {
  const code = String(req.query?.code || '');
  const state = String(req.query?.state || '');
  const error = String(req.query?.error || '');
  const params = new URLSearchParams();
  if (code) params.set('code', code);
  if (state) params.set('state', state);
  if (error) params.set('error', error);
  res.statusCode = 302;
  res.setHeader('Location', `jarvis://homeconnect?${params.toString()}`);
  res.end();
}
