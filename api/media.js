function esc(s=''){return String(s).replace(/&amp;/g,'&').replace(/&quot;/g,'"').replace(/&#39;/g,"'").replace(/&lt;/g,'<').replace(/&gt;/g,'>')}
function strip(s=''){return esc(String(s).replace(/<!\[CDATA\[|\]\]>/g,'')).replace(/<[^>]+>/g,' ').replace(/\s+/g,' ').trim()}
function tag(block,name){const m=block.match(new RegExp(`<${name}[^>]*>([\\s\\S]*?)<\\/${name}>`,'i'));return m?strip(m[1]):''}
async function enrich(item){try{const c=new AbortController();const t=setTimeout(()=>c.abort(),2500);const r=await fetch(item.url,{redirect:'follow',signal:c.signal,headers:{'user-agent':'Mozilla/5.0 JarvisMedia/1.0'}});clearTimeout(t);if(!r.ok)return item;const html=(await r.text()).slice(0,350000);const image=(html.match(/<meta[^>]+(?:property|name)=["']og:image["'][^>]+content=["']([^"']+)/i)||html.match(/<meta[^>]+content=["']([^"']+)["'][^>]+(?:property|name)=["']og:image["']/i)||[])[1]||'';const desc=(html.match(/<meta[^>]+(?:property|name)=["'](?:og:description|description)["'][^>]+content=["']([^"']+)/i)||[])[1]||'';return {...item,image,description:strip(desc)};}catch{return item}}
async function googleNews(q,section){try{const url=`https://news.google.com/rss/search?q=${encodeURIComponent(q)}&hl=es&gl=ES&ceid=ES:es`;const r=await fetch(url,{headers:{'user-agent':'Mozilla/5.0 JarvisMedia/1.0'}});if(!r.ok)return[];const xml=await r.text();const blocks=[...xml.matchAll(/<item>([\s\S]*?)<\/item>/gi)].slice(0,5).map(m=>m[1]);const base=blocks.map(b=>({section,title:tag(b,'title').replace(/\s+-\s+[^-]+$/,''),source:tag(b,'source'),url:tag(b,'link'),published:tag(b,'pubDate'),image:'',description:''}));return await Promise.all(base.map(enrich));}catch{return[]}}
export default async function handler(req,res){
  if(req.method!=='GET')return res.status(405).json({error:'method_not_allowed'});
  const q=String(req.query?.q||'qué hay hoy en televisión').slice(0,300);
  const queries=[
    ['TV',`programación televisión hoy España La 1 Antena 3 Telecinco Cuatro La Sexta ${q}`],
    ['Netflix',`Netflix España estrenos hoy qué ver ${q}`],
    ['Prime Video',`Prime Video España estrenos hoy qué ver ${q}`],
    ['Disney+',`Disney Plus España estrenos hoy qué ver ${q}`],
    ['Max',`Max HBO España estrenos hoy qué ver ${q}`]
  ];
  const groups=await Promise.all(queries.map(([section,query])=>googleNews(query,section)));
  const seen=new Set();const items=[];
  for(const group of groups)for(const item of group){const key=(item.title||'').toLowerCase();if(!key||seen.has(key))continue;seen.add(key);items.push(item);if(items.length>=14)break}
  return res.status(200).json({items,generatedAt:new Date().toISOString(),note:'Fuentes públicas. El contenido personalizado de continuar viendo requiere autorización de cada servicio.'});
}
