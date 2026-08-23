const PROVIDERS = {
  tado: { name: 'Tado', env: 'TADO_OAUTH_READY' },
  sensibo: { name: 'Sensibo', env: 'SENSIBO_OAUTH_READY' },
  smartthings: { name: 'Samsung SmartThings', env: 'SMARTTHINGS_OAUTH_READY' },
  homeconnect: { name: 'Bosch / Siemens Home Connect', env: 'HOMECONNECT_OAUTH_READY' },
  hue: { name: 'Philips Hue', env: 'HUE_OAUTH_READY' },
  roborock: { name: 'Roborock', env: 'ROBOROCK_OAUTH_READY' }
};

function page(title, body, status = 200) {
  return { status, html: `<!doctype html><html lang="es"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${title}</title><style>body{font-family:system-ui,-apple-system,sans-serif;background:#f5f5f7;color:#111;margin:0;padding:28px}.card{max-width:620px;margin:30px auto;background:#fff;border-radius:22px;padding:26px;box-shadow:0 8px 30px #0001}h1{font-size:24px;margin-top:0}.muted{color:#666;line-height:1.5}.ok{background:#eaf7ee;padding:14px;border-radius:14px}.warn{background:#fff4db;padding:14px;border-radius:14px}</style></head><body><div class="card"><h1>${title}</h1>${body}</div></body></html>` };
}

export default async function handler(req, res) {
  const providerId = String(req.query?.provider || '').toLowerCase();
  const p = PROVIDERS[providerId];
  if (!p) {
    const r = page('Proveedor no compatible', '<p class="warn">Jarvis no reconoce esta plataforma.</p>', 400);
    res.status(r.status).setHeader('Content-Type','text/html; charset=utf-8').send(r.html); return;
  }

  // Never ask the end user for API keys. The server-side OAuth registration is a one-time Jarvis setup.
  const ready = String(process.env[p.env] || '').toLowerCase() === 'true';
  if (!ready) {
    const r = page(`Conectar ${p.name}`, `<p class="warn"><b>La conexión todavía no está activada en el servidor de Jarvis.</b></p><p class="muted">No necesitas buscar API keys ni copiar tokens. Esta integración requiere que Jarvis quede registrado una sola vez con ${p.name}; después aquí aparecerá el inicio de sesión oficial de tu cuenta.</p><p class="muted">Puedes volver a Jarvis. La aplicación ya no debería mostrar una página de error 404 para este acceso.</p>`);
    res.status(r.status).setHeader('Cache-Control','no-store').setHeader('Content-Type','text/html; charset=utf-8').send(r.html); return;
  }

  const r = page(`Conectar ${p.name}`, `<p class="ok">La integración de ${p.name} está preparada en Jarvis.</p><p class="muted">El siguiente paso es completar el flujo OAuth oficial del proveedor. Jarvis no guardará tu contraseña.</p>`);
  res.status(r.status).setHeader('Cache-Control','no-store').setHeader('Content-Type','text/html; charset=utf-8').send(r.html);
}
