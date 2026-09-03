function esc(s=''){return String(s).replace(/&amp;/g,'&').replace(/&quot;/g,'"').replace(/&#39;/g,"'").replace(/&lt;/g,'<').replace(/&gt;/g,'>')}
function strip(s=''){return esc(String(s).replace(/<!\[CDATA\[|\]\]>/g,'')).replace(/<[^>]+>/g,' ').replace(/\s+/g,' ').trim()}
function tag(block,name){const m=block.match(new RegExp(`<${name}[^>]*>([\\s\\S]*?)<\\/${name}>`,'i'));return m?strip(m[1]):''}
async function enrich(item){try{const c=new AbortController();const t=setTimeout(()=>c.abort(),2200);const r=await fetch(item.url,{redirect:'follow',signal:c.signal,headers:{'user-agent':'Mozilla/5.0 JarvisMedia/1.1'}});clearTimeout(t);if(!r.ok)return item;const html=(await r.text()).slice(0,300000);const image=(html.match(/<meta[^>]+(?:property|name)=["']og:image["'][^>]+content=["']([^"']+)/i)||html.match(/<meta[^>]+content=["']([^"']+)["'][^>]+(?:property|name)=["']og:image["']/i)||[])[1]||'';const desc=(html.match(/<meta[^>]+(?:property|name)=["'](?:og:description|description)["'][^>]+content=["']([^"']+)/i)||[])[1]||'';return {...item,image,description:strip(desc)};}catch{return item}}
async function googleNews(q,section,limit=6){try{const c=new AbortController();const t=setTimeout(()=>c.abort(),5500);const url=`https://news.google.com/rss/search?q=${encodeURIComponent(q)}&hl=es&gl=ES&ceid=ES:es`;const r=await fetch(url,{signal:c.signal,headers:{'user-agent':'Mozilla/5.0 JarvisMedia/1.1'}});clearTimeout(t);if(!r.ok)return[];const xml=await r.text();const blocks=[...xml.matchAll(/<item>([\s\S]*?)<\/item>/gi)].slice(0,limit).map(m=>m[1]);const base=blocks.map(b=>({section,title:tag(b,'title').replace(/\s+-\s+[^-]+$/,''),source:tag(b,'source'),url:tag(b,'link'),published:tag(b,'pubDate'),image:'',description:''}));return await Promise.all(base.map(enrich));}catch{return[]}}
const catalog=[
  ['TV','programación televisión hoy España La 1 Antena 3 Telecinco Cuatro La Sexta'],
  ['Netflix','Netflix España estrenos series películas qué ver'],
  ['Prime Video','Prime Video España estrenos series películas qué ver'],
  ['Apple TV+','Apple TV Plus España estrenos series películas'],
  ['Disney+','Disney Plus España estrenos series películas qué ver'],
  ['Max','Max HBO España estrenos series películas qué ver'],
  ['Movistar Plus+','Movistar Plus estrenos cine series hoy España'],
  ['YouTube','YouTube España tendencias vídeos canales novedades']
];
function providerMatch(raw=''){
  const q=String(raw).trim().toLowerCase().replace(/\s+/g,' ');
  if(!q)return null;
  return catalog.find(([name])=>{
    const n=name.toLowerCase();
    return n===q || n.replace('+','')===q.replace('+','') || (q==='prime'&&n==='prime video') || (q==='apple tv'&&n==='apple tv+') || (q==='movistar'&&n==='movistar plus+');
  })||null;
}
export default async function handler(req,res){
  if(req.method!=='GET')return res.status(405).json({error:'method_not_allowed'});
  const extra=String(req.query?.q||'').slice(0,220).trim();
  const requested=providerMatch(req.query?.provider||'');
  const targets=requested?[requested]:catalog;
  const perSection=requested?7:3;
  const groups=await Promise.all(targets.map(([section,query])=>googleNews(`${query}${extra?` ${extra}`:''}`,section,perSection)));
  const seen=new Set();const items=[];
  for(const group of groups){
    for(const item of group){
      const key=(item.title||'').toLowerCase();
      if(!key||seen.has(key))continue;
      seen.add(key);items.push(item);
      if(requested&&items.length>=7)break;
      if(!requested&&items.length>=24)break;
    }
    if((requested&&items.length>=7)||(!requested&&items.length>=24))break;
  }
  return res.status(200).json({items,provider:requested?requested[0]:null,generatedAt:new Date().toISOString(),note:'Fuentes públicas. Favoritos y Continuar viendo personalizados solo se muestran cuando la propia aplicación los expone mediante su interfaz y Accesibilidad de Jarvis.'});
}
