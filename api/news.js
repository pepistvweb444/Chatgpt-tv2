function decodeXml(value = '') {
  return String(value)
    .replace(/<!\[CDATA\[([\s\S]*?)\]\]>/g, '$1')
    .replace(/&amp;/g, '&')
    .replace(/&quot;/g, '"')
    .replace(/&#39;|&apos;/g, "'")
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>');
}

function stripHtml(value = '') {
  return decodeXml(String(value).replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').trim());
}

function tag(block, name) {
  const m = block.match(new RegExp(`<${name}(?:\\s[^>]*)?>([\\s\\S]*?)<\\/${name}>`, 'i'));
  return m ? decodeXml(m[1]).trim() : '';
}

function attr(block, tagName, attrName) {
  const m = block.match(new RegExp(`<${tagName}[^>]*\\s${attrName}=["']([^"']+)["'][^>]*>`, 'i'));
  return m ? decodeXml(m[1]).trim() : '';
}

async function enrichImage(item) {
  if (!item.url) return item;
  try {
    const r = await fetch(item.url, {
      redirect: 'follow',
      headers: { 'User-Agent': 'Mozilla/5.0 JarvisNews/1.0' },
      signal: AbortSignal.timeout(3500)
    });
    if (!r.ok) return item;
    const html = (await r.text()).slice(0, 500000);
    const image =
      html.match(/<meta[^>]+property=["']og:image["'][^>]+content=["']([^"']+)["']/i)?.[1] ||
      html.match(/<meta[^>]+content=["']([^"']+)["'][^>]+property=["']og:image["']/i)?.[1] ||
      html.match(/<meta[^>]+name=["']twitter:image["'][^>]+content=["']([^"']+)["']/i)?.[1] || '';
    const video =
      html.match(/<meta[^>]+property=["']og:video(?::url)?["'][^>]+content=["']([^"']+)["']/i)?.[1] || '';
    return { ...item, image: decodeXml(image), video: decodeXml(video) };
  } catch (_) {
    return item;
  }
}

export default async function handler(req, res) {
  if (req.method !== 'GET') return res.status(405).json({ error: 'method_not_allowed' });
  try {
    const q = String(req.query?.q || '').trim();
    const rss = q
      ? `https://news.google.com/rss/search?q=${encodeURIComponent(q)}&hl=es&gl=ES&ceid=ES:es`
      : 'https://news.google.com/rss?hl=es&gl=ES&ceid=ES:es';
    const r = await fetch(rss, {
      headers: { 'User-Agent': 'Mozilla/5.0 JarvisNews/1.0', 'Accept': 'application/rss+xml, application/xml, text/xml' },
      signal: AbortSignal.timeout(7000)
    });
    if (!r.ok) return res.status(r.status).json({ error: 'news_provider_error' });
    const xml = await r.text();
    const blocks = xml.match(/<item>[\s\S]*?<\/item>/gi) || [];
    let items = blocks.slice(0, 8).map(block => {
      const title = stripHtml(tag(block, 'title'));
      const url = stripHtml(tag(block, 'link'));
      const description = stripHtml(tag(block, 'description'));
      const pubDate = stripHtml(tag(block, 'pubDate'));
      const source = stripHtml(tag(block, 'source')) || title.split(' - ').pop() || '';
      const mediaImage = attr(block, 'media:content', 'url') || attr(block, 'media:thumbnail', 'url');
      return { title, source, url, image: mediaImage, video: '', description, publishedAt: pubDate };
    }).filter(x => x.title && x.url);

    // Enrich only the first cards to keep the widget fast.
    const enriched = await Promise.all(items.slice(0, 4).map(enrichImage));
    items = enriched.concat(items.slice(4)).slice(0, 6);

    res.setHeader('Cache-Control', 'public, max-age=120, s-maxage=120');
    return res.status(200).json({ query: q || null, items });
  } catch (e) {
    return res.status(500).json({ error: e?.message || 'news_error' });
  }
}
