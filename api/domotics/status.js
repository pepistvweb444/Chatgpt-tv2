const providers = {
  tado: { name: 'Tado', mode: 'device_code', ready: true },
  smartthings: { name: 'Samsung SmartThings', mode: 'oauth', env: ['SMARTTHINGS_CLIENT_ID','SMARTTHINGS_CLIENT_SECRET'] },
  homeconnect: { name: 'Bosch / Siemens Home Connect', mode: 'oauth', env: ['HOMECONNECT_CLIENT_ID','HOMECONNECT_CLIENT_SECRET'] },
  hue: { name: 'Philips Hue', mode: 'oauth_or_local_bridge', anyOf: [['HUE_CLIENT_ID','HUE_CLIENT_SECRET']] },
  sensibo: { name: 'Sensibo', mode: 'api_key_or_oauth', anyOf: [['SENSIBO_API_KEY'],['SENSIBO_CLIENT_ID','SENSIBO_CLIENT_SECRET']] },
  roborock: { name: 'Roborock', mode: 'authenticated_bridge', anyOf: [['ROBOROCK_BRIDGE_URL','ROBOROCK_BRIDGE_TOKEN']] }
};

function allPresent(keys = []) {
  return keys.every(k => String(process.env[k] || '').trim().length > 0);
}

export default function handler(req, res) {
  const out = {};
  for (const [id, p] of Object.entries(providers)) {
    const ready = p.ready === true || allPresent(p.env || []) || (p.anyOf || []).some(group => allPresent(group));
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
