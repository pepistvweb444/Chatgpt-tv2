const providers = {
  tado: { name: 'Tado', mode: 'device_code', ready: true },
  smartthings: { name: 'Samsung SmartThings', mode: 'oauth', env: ['SMARTTHINGS_CLIENT_ID','SMARTTHINGS_CLIENT_SECRET'] },
  homeconnect: { name: 'Bosch / Siemens Home Connect', mode: 'oauth', env: ['HOMECONNECT_CLIENT_ID','HOMECONNECT_CLIENT_SECRET'] },
  hue: { name: 'Philips Hue', mode: 'oauth', env: ['HUE_CLIENT_ID','HUE_CLIENT_SECRET'] },
  sensibo: { name: 'Sensibo', mode: 'account_or_api', env: ['SENSIBO_CLIENT_ID'] },
  roborock: { name: 'Roborock', mode: 'account_bridge', env: ['ROBOROCK_BRIDGE_READY'] }
};

export default function handler(req, res) {
  const out = {};
  for (const [id, p] of Object.entries(providers)) {
    const ready = p.ready === true || (p.env || []).every(k => String(process.env[k] || '').trim().length > 0);
    out[id] = {
      name: p.name,
      mode: p.mode,
      deployed: true,
      ready,
      missingServerRegistration: !ready
    };
  }
  res.setHeader('Cache-Control','no-store');
  return res.status(200).json({ ok: true, providers: out });
}
