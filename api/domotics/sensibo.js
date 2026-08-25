const BASE = 'https://home.sensibo.com/api/v2';

function apiKey() {
  return String(process.env.SENSIBO_API_KEY || '').trim();
}

async function sensibo(path, options = {}) {
  const key = apiKey();
  if (!key) throw Object.assign(new Error('SENSIBO_API_KEY no configurada'), { status: 503 });
  const sep = path.includes('?') ? '&' : '?';
  const url = `${BASE}${path}${sep}apiKey=${encodeURIComponent(key)}`;
  const r = await fetch(url, {
    ...options,
    headers: {
      'Accept': 'application/json',
      'Accept-Encoding': 'gzip',
      'Content-Type': 'application/json',
      ...(options.headers || {})
    }
  });
  const raw = await r.text();
  let data;
  try { data = raw ? JSON.parse(raw) : {}; } catch { data = { raw }; }
  if (!r.ok) {
    const e = new Error(data?.message || data?.error || `Sensibo HTTP ${r.status}`);
    e.status = r.status;
    e.data = data;
    throw e;
  }
  return data;
}

function normalizePod(p = {}) {
  const state = p.acState || p.acStateHistory?.[0]?.acState || {};
  const measurements = p.measurements || p.currentMeasurements || {};
  return {
    id: p.id || p.podUid || '',
    name: p.room?.name || p.name || p.roomName || 'Sensibo',
    connected: p.connectionStatus?.isAlive ?? p.isAlive ?? true,
    product: p.productModel || p.product || '',
    firmware: p.firmwareVersion || '',
    state: {
      on: state.on ?? null,
      mode: state.mode || null,
      targetTemperature: state.targetTemperature ?? null,
      temperatureUnit: state.temperatureUnit || 'C',
      fanLevel: state.fanLevel || null,
      swing: state.swing || null
    },
    measurements: {
      temperature: measurements.temperature ?? null,
      humidity: measurements.humidity ?? null,
      feelsLike: measurements.feelsLike ?? null
    },
    remoteCapabilities: p.remoteCapabilities || {}
  };
}

export default async function handler(req, res) {
  res.setHeader('Cache-Control', 'no-store');
  try {
    if (req.method === 'GET') {
      const id = String(req.query?.id || '').trim();
      if (id) {
        const p = await sensibo(`/pods/${encodeURIComponent(id)}?fields=*`);
        const pod = p?.result || p;
        let states = null;
        try { states = await sensibo(`/pods/${encodeURIComponent(id)}/acStates?limit=1`); } catch {}
        if (states?.result?.length) pod.acState = states.result[0]?.acState || states.result[0];
        return res.status(200).json({ ok: true, device: normalizePod(pod) });
      }
      const data = await sensibo('/users/me/pods?fields=*');
      const pods = Array.isArray(data?.result) ? data.result : [];
      return res.status(200).json({ ok: true, provider: 'sensibo', devices: pods.map(normalizePod) });
    }

    if (req.method === 'POST') {
      const body = typeof req.body === 'string' ? JSON.parse(req.body || '{}') : (req.body || {});
      const id = String(body.id || '').trim();
      if (!id) return res.status(400).json({ ok: false, error: 'Falta id del dispositivo' });
      const action = String(body.action || '').toLowerCase();

      if (action === 'power') {
        const value = !!body.on;
        const data = await sensibo(`/pods/${encodeURIComponent(id)}/acStates/on`, {
          method: 'PATCH', body: JSON.stringify({ newValue: value })
        });
        return res.status(200).json({ ok: true, action, result: data?.result ?? data });
      }

      if (action === 'temperature') {
        const value = Number(body.value);
        if (!Number.isFinite(value)) return res.status(400).json({ ok: false, error: 'Temperatura no válida' });
        const data = await sensibo(`/pods/${encodeURIComponent(id)}/acStates/targetTemperature`, {
          method: 'PATCH', body: JSON.stringify({ newValue: value })
        });
        return res.status(200).json({ ok: true, action, result: data?.result ?? data });
      }

      if (action === 'mode') {
        const value = String(body.value || '').toLowerCase();
        const allowed = new Set(['cool','heat','fan','auto','dry']);
        if (!allowed.has(value)) return res.status(400).json({ ok: false, error: 'Modo no válido' });
        const data = await sensibo(`/pods/${encodeURIComponent(id)}/acStates/mode`, {
          method: 'PATCH', body: JSON.stringify({ newValue: value })
        });
        return res.status(200).json({ ok: true, action, result: data?.result ?? data });
      }

      if (action === 'fan') {
        const value = String(body.value || '').trim();
        if (!value) return res.status(400).json({ ok: false, error: 'Nivel de ventilador no válido' });
        const data = await sensibo(`/pods/${encodeURIComponent(id)}/acStates/fanLevel`, {
          method: 'PATCH', body: JSON.stringify({ newValue: value })
        });
        return res.status(200).json({ ok: true, action, result: data?.result ?? data });
      }

      return res.status(400).json({ ok: false, error: 'Acción no compatible' });
    }

    res.setHeader('Allow', 'GET, POST');
    return res.status(405).json({ ok: false, error: 'Método no permitido' });
  } catch (e) {
    return res.status(e.status || 500).json({ ok: false, error: e.message || 'Error Sensibo', details: e.data || undefined });
  }
}
