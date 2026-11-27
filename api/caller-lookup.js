function cleanNumber(value = '') {
  let n = String(value).replace(/[^+0-9]/g, '').slice(0, 32);
  if (n.startsWith('00')) n = '+' + n.slice(2);
  const digits = n.replace(/\D/g, '');
  if (!n.startsWith('+') && digits.length === 9 && /^[6789]/.test(digits)) n = '+34' + digits;
  return n;
}

function digitsOf(value = '') {
  return String(value).replace(/\D/g, '');
}

function samePhone(a, b) {
  const left = digitsOf(a);
  const right = digitsOf(b);
  if (left.length < 6 || right.length < 6) return false;
  return left.endsWith(right.slice(-9)) || right.endsWith(left.slice(-9));
}

function strip(value = '') {
  return String(value).replace(/<!\[CDATA\[|\]\]>/g, '').replace(/<[^>]+>/g, ' ').replace(/&amp;/g, '&').replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/\s+/g, ' ').trim();
}

function tag(block, name) {
  const match = block.match(new RegExp(`<${name}[^>]*>([\\s\\S]*?)<\\/${name}>`, 'i'));
  return match ? strip(match[1]) : '';
}

async function rss(url) {
  try {
    const response = await fetch(url, { headers: { 'user-agent': 'Mozilla/5.0 JarvisCaller/4.0' }, signal: AbortSignal.timeout(3500) });
    if (!response.ok) return [];
    const xml = await response.text();
    return [...xml.matchAll(/<item>([\s\S]*?)<\/item>/gi)].slice(0, 8).map(match => ({
      title: tag(match[1], 'title'),
      description: tag(match[1], 'description'),
      url: tag(match[1], 'link')
    }));
  } catch {
    return [];
  }
}

function webLabel(items, number) {
  const bad = /\b(spam|estafa|fraude|telemarketing|scam|robocall)\b/i;
  for (const item of items) {
    const blob = `${item.title} ${item.description}`;
    if (!samePhone(blob, number) || bad.test(blob)) continue;
    const label = strip(item.title).replace(number, '').replace(/[|–—-]+/g, ' ').replace(/\s+/g, ' ').trim();
    if (label.length >= 3 && label.length <= 90) {
      let host = '';
      try { host = new URL(item.url).hostname.replace(/^www\./, ''); } catch { host = 'Web'; }
      return { name: label, source: host || 'Web' };
    }
  }
  return null;
}

async function places(number) {
  const key = process.env.GOOGLE_PLACES_API_KEY || process.env.GOOGLE_MAPS_API_KEY || '';
  if (!key) return null;
  try {
    const response = await fetch('https://places.googleapis.com/v1/places:searchText', {
      method: 'POST',
      signal: AbortSignal.timeout(4000),
      headers: {
        'Content-Type': 'application/json',
        'X-Goog-Api-Key': key,
        'X-Goog-FieldMask': 'places.displayName,places.formattedAddress,places.rating,places.userRatingCount,places.nationalPhoneNumber,places.internationalPhoneNumber,places.photos'
      },
      body: JSON.stringify({ textQuery: number, languageCode: 'es', regionCode: 'ES' })
    });
    if (!response.ok) return null;
    const payload = await response.json();
    const place = (payload.places || []).find(item => samePhone(item.nationalPhoneNumber || item.internationalPhoneNumber || '', number));
    if (!place) return null;
    const rating = typeof place.rating === 'number' ? place.rating.toFixed(1).replace('.', ',') : '';
    const reviews = place.userRatingCount ? `${place.userRatingCount} opiniones` : '';
    let photoBase64 = '';
    const photoName = place.photos?.[0]?.name;
    if (photoName) {
      const media = await fetch(`https://places.googleapis.com/v1/${photoName}/media?maxWidthPx=480&key=${encodeURIComponent(key)}`, { signal: AbortSignal.timeout(4000) });
      if (media.ok && (media.headers.get('content-type') || '').startsWith('image/')) {
        const bytes = new Uint8Array(await media.arrayBuffer());
        if (bytes.length > 32 && bytes.length < 180000) {
          let binary = '';
          for (let i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i]);
          photoBase64 = btoa(binary);
        }
      }
    }
    return {
      name: place.displayName?.text || '',
      source: 'Google Maps',
      origin: 'maps',
      rating,
      reviews,
      address: place.formattedAddress || '',
      photoBase64
    };
  } catch {
    return null;
  }
}

export default async function handler(req, res) {
  res.setHeader('Cache-Control', 'no-store');
  if (req.method !== 'GET') return res.status(405).json({ error: 'method_not_allowed' });
  const number = cleanNumber(req.query?.number || '');
  if (digitsOf(number).length < 6) return res.status(400).json({ error: 'invalid_number' });
  const [business, pages] = await Promise.all([
    places(number),
    rss(`https://www.bing.com/search?format=rss&q=${encodeURIComponent(`"${number}" empresa teléfono`)}`)
  ]);
  if (business?.name) return res.status(200).json({ number, ...business, placesConfigured: true });
  const web = webLabel(pages, number);
  return res.status(200).json({
    number,
    name: web?.name || '',
    source: web?.source || '',
    origin: web ? 'web' : 'unknown',
    rating: '',
    reviews: '',
    address: '',
    photoBase64: '',
    placesConfigured: Boolean(process.env.GOOGLE_PLACES_API_KEY || process.env.GOOGLE_MAPS_API_KEY),
    note: 'Solo se usa una ficha pública de Google Places si el teléfono coincide, o una mención web que contiene el número. Jarvis no entra en redes sociales.'
  });
}
