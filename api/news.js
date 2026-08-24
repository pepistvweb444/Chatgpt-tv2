function esc(s=''){return String(s).replace(/&amp;/g,'&').replace(/&quot;/g,'"').replace(/&#39;/g,"'").replace(/&lt;/g,'<').replace(/&gt;/g,'>')}
function strip(s=''){return esc(String(s).replace(/<!\[CDATA\[|\]\]>/g,'')).replace(/<[^>]+>/g,' ').replace(/\s+/g,' ').trim()}
function tag(block,name){const m=block.match(new RegExp(`<${name}[^>]*>([\\s\\S]*?)<\\/${name}>`,'i'));return m?strip(m[1]):''}
function attr(block,tagName,attrName){const m=block.match(new RegExp(`<${tagName}[^>]*${attrName}=["']([^"']+)["'][^>]*>`,'i'));return m?esc(m[1]):''}
function screenshot(url=''){return url?`https://image.thum.io/get/width/1200/crop/700/noanimate/${url}`:''}
function pickImage(html=''){
  return esc((html.match(/<meta[^>]+(?:property|name)=["']og:image(?::url)?["'][^>]+content=["']([^"']+)/i)||
    html.match(/<meta[^>]+content=["']([^"']+)["'][^>]+(?:property|name)=["']og:image(?::url)?["']/i)||
    html.match(/<meta[^>]+(?:property|name)=["']twitter:image(?::src)?["'][^>]+content=["']([^"']+)/i)||
    html.match(/<meta[^>]+content=["']([^"']+)["'][^>]+(?:property|name)=["']twitter:image(?::src)?["']/i)||[])[1]||'')
}
function pickVideo(html=''){
  return esc((html.match(/<meta[^>]+(?:property|name)=["']og:video(?::url)?["'][^>]+content=["']([^"']+)/i)||
    html.match(/<meta[^>]+content=["']([^"']+)["'][^>]+(?:property|name)=["']og:video(?::url)?["']/i)||
    html.match(/<iframe[^>]+src=["']([^"']*(?:youtube\.com|youtu\.be|vimeo\.com)[^"']*)["']/i)||[])[1]||'')
}
async function enrich(item){
  let image=item.image||'';let video=item.video||'';let resolvedUrl=item.url||'';
  try{
    const c=new AbortController();const t=setTimeout(()=>c.abort(),2200);
    const r=await fetch(item.url,{redirect:'follow',signal:c.signal,headers:{'user-agent':'Mozilla/5.0 JarvisNews/1.0','accept':'text/html,application/xhtml+xml'}});
    clearTimeout(t);
    if(r.ok){
      resolvedUrl=r.url||resolvedUrl;
      const html=(await r.text()).slice(0,300000);
      image=image||pickImage(html);video=video||pickVideo(html);
    }
  }catch{}
  if(!image)image=screenshot(resolvedUrl||item.url);
  return {...item,url:resolvedUrl||item.url,image,video,hasVideo:Boolean(video),thumbnailSource:image&&image.includes('image.thum.io')?'screenshot':'article'}
}
export default async function handler(req,res){
  try{
    const topic=String(req.query?.q||'').trim();const country=String(req.query?.country||'ES').toUpperCase();const lang=String(req.query?.lang||'es').toLowerCase();const fast=String(req.query?.fast||'')==='1';
    const q=topic?`search?q=${encodeURIComponent(topic)}&`:'?';const url=`https://news.google.com/rss/${q}hl=${lang}&gl=${country}&ceid=${country}:${lang}`;
    const r=await fetch(url,{headers:{'user-agent':'Mozilla/5.0 JarvisNews/1.0'}});if(!r.ok)return res.status(r.status).json({error:'news_provider_error'});
    const xml=await r.text();const blocks=[...xml.matchAll(/<item>([\s\S]*?)<\/item>/gi)].slice(0,8).map(m=>m[1]);
    let items=blocks.map(b=>({title:tag(b,'title').replace(/\s+-\s+[^-]+$/,''),source:tag(b,'source'),url:tag(b,'link'),published:tag(b,'pubDate'),image:attr(b,'media:content','url')||attr(b,'enclosure','url'),video:'',hasVideo:false}));
    if(!fast)items=await Promise.all(items.map(enrich));
    res.setHeader('Cache-Control','public, max-age=180, s-maxage=180');
    return res.status(200).json({items,fast});
  }catch(e){return res.status(500).json({error:e?.message||'news_error'})}
}
