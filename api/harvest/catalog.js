function strip(html=''){return String(html||'').replace(/<[^>]+>/g,' ').replace(/\s+/g,' ').trim()}
function imageOf(x){return x&&x.image?(x.image.original||x.image.medium||''):''}
function normalizeShow(s){
  return {
    id:s.id,
    title:s.name||'',
    image:imageOf(s),
    summary:strip(s.summary||''),
    genres:Array.isArray(s.genres)?s.genres:[],
    rating:s.rating&&s.rating.average||null,
    premiered:s.premiered||null,
    status:s.status||'',
    network:(s.network&&s.network.name)||(s.webChannel&&s.webChannel.name)||'',
    webChannel:(s.webChannel&&s.webChannel.name)||'',
    url:s.url||''
  };
}
async function getJson(url){
  const r=await fetch(url,{headers:{'User-Agent':'JarvisHarvestTV/0.6'}});
  if(!r.ok) throw new Error('tvmaze_'+r.status);
  return await r.json();
}
function providerAliases(raw){
  const p=String(raw||'').toLowerCase();
  if(p.includes('apple')) return ['apple tv+','apple tv plus'];
  if(p.includes('netflix')) return ['netflix'];
  if(p.includes('prime')||p.includes('amazon')) return ['amazon prime video','prime video','amazon'];
  if(p.includes('disney')) return ['disney+','disney plus'];
  if(p.includes('max')||p.includes('hbo')) return ['max','hbo max','hbo'];
  return [];
}
async function providerShows(provider){
  const aliases=providerAliases(provider);
  if(!aliases.length) return [];
  const out=[],seen=new Set();
  for(let page=0;page<5 && out.length<24;page++){
    const arr=await getJson('https://api.tvmaze.com/shows?page='+page).catch(()=>[]);
    for(const s of arr||[]){
      const ch=((s.webChannel&&s.webChannel.name)||(s.network&&s.network.name)||'').toLowerCase();
      if(!aliases.some(a=>ch.includes(a))) continue;
      if(!imageOf(s)||seen.has(s.id)) continue;
      seen.add(s.id); out.push(normalizeShow(s));
      if(out.length>=24) break;
    }
  }
  return out;
}
async function popularShows(){
  const out=[],seen=new Set();
  for(let page=0;page<3 && out.length<24;page++){
    const arr=await getJson('https://api.tvmaze.com/shows?page='+page).catch(()=>[]);
    const scored=(arr||[]).filter(s=>imageOf(s)).sort((a,b)=>((b.rating&&b.rating.average)||0)-((a.rating&&a.rating.average)||0));
    for(const s of scored){if(!seen.has(s.id)){seen.add(s.id);out.push(normalizeShow(s));if(out.length>=24)break;}}
  }
  return out;
}
async function schedule(country,date){
  const raw=await getJson('https://api.tvmaze.com/schedule?country='+encodeURIComponent(country)+'&date='+encodeURIComponent(date));
  const out=[],seen=new Set();
  for(const ep of raw||[]){
    const s=ep.show||{};
    if(!s.id||seen.has(s.id)) continue;
    seen.add(s.id);
    out.push({...normalizeShow(s),episode:{
      id:ep.id,name:ep.name||'',season:ep.season||null,number:ep.number||null,
      image:imageOf(ep),airtime:ep.airtime||'',airdate:ep.airdate||''
    }});
    if(out.length>=24) break;
  }
  return out;
}
export default async function handler(req,res){
  res.setHeader('Access-Control-Allow-Origin','*');
  res.setHeader('Access-Control-Allow-Methods','GET,OPTIONS');
  if(req.method==='OPTIONS') return res.status(204).end();
  if(req.method!=='GET') return res.status(405).json({error:'method_not_allowed'});
  try{
    const q=String(req.query?.q||'').trim();
    const mode=String(req.query?.mode||'home');
    const provider=String(req.query?.provider||'').trim();
    let items=[],rows=[];
    if(q){
      const raw=await getJson('https://api.tvmaze.com/search/shows?q='+encodeURIComponent(q));
      items=(raw||[]).slice(0,24).map(x=>normalizeShow(x.show||{}));
    }else if(provider){
      items=await providerShows(provider);
    }else if(mode==='schedule'){
      const country=String(req.query?.country||'US').slice(0,2).toUpperCase();
      const date=String(req.query?.date||new Date().toISOString().slice(0,10));
      items=await schedule(country,date);
    }else{
      const date=new Date().toISOString().slice(0,10);
      const [popular,today,apple,netflix,prime,disney]=await Promise.all([
        popularShows(),schedule('US',date),
        providerShows('Apple TV+'),providerShows('Netflix'),
        providerShows('Prime Video'),providerShows('Disney+')
      ]);
      rows=[
        {id:'continue',title:'Seguir viendo',kind:'continue',items:today.slice(0,12)},
        {id:'popular',title:'Populares ahora',kind:'catalog',items:popular.slice(0,14)},
        {id:'apple',title:'Apple TV+ · Originals',kind:'provider',provider:'Apple TV',items:apple.slice(0,14)},
        {id:'netflix',title:'Netflix · Series y películas',kind:'provider',provider:'Netflix',items:netflix.slice(0,14)},
        {id:'prime',title:'Prime Video · Destacados',kind:'provider',provider:'Prime Video',items:prime.slice(0,14)},
        {id:'disney',title:'Disney+ · Novedades',kind:'provider',provider:'Disney+',items:disney.slice(0,14)}
      ];
      items=popular;
    }
    res.setHeader('Cache-Control','s-maxage=900, stale-while-revalidate=3600');
    return res.status(200).json({ok:true,source:'TVmaze',items,rows,provider:provider||null,generatedAt:new Date().toISOString()});
  }catch(e){
    return res.status(502).json({ok:false,error:'catalog_unavailable',message:e?.message||'TVmaze error'});
  }
}
