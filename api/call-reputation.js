const SOURCES = [
  { name: 'tellows', url: n => `https://www.tellows.es/num/${encodeURIComponent(n)}` },
  { name: 'ListaSpam', url: n => `https://www.listaspam.com/busca.php?Telefono=${encodeURIComponent(n)}` },
  { name: 'QuienHaLlamado', url: n => `https://www.quienhallamado.es/telefono/${encodeURIComponent(n)}` },
];

const BAD = ['spam','estafa','fraude','telemarketing','publicidad','llamada molesta','acoso','scam'];

async function probe(source, number) {
  try {
    const r = await fetch(source.url(number), {
      headers: { 'user-agent': 'Mozilla/5.0 JarvisCallCheck/1.0', 'accept': 'text/html,*/*' },
      signal: AbortSignal.timeout(4500)
    });
    const text = (await r.text()).toLowerCase().slice(0, 300000);
    const hits = BAD.filter(k => text.includes(k));
    return { source: source.name, reachable: r.ok, suspicious: hits.length > 0, hits: hits.slice(0,4) };
  } catch (e) {
    return { source: source.name, reachable: false, suspicious: false, error: String(e?.message || e).slice(0,120) };
  }
}

export default async function handler(req, res) {
  res.setHeader('Cache-Control','no-store');
  const number = String(req.query?.number || req.body?.number || '').replace(/[^+0-9]/g,'').slice(0,24);
  if (!number) return res.status(400).json({ error:'number_required' });
  const results = await Promise.all(SOURCES.map(s => probe(s, number)));
  const reachable = results.filter(x => x.reachable).length;
  const positives = results.filter(x => x.suspicious).length;
  const classification = positives >= 2 ? 'spam_probable' : positives === 1 ? 'suspicious' : 'unknown';
  return res.status(200).json({ number, classification, positives, reachable, sources: results });
}
