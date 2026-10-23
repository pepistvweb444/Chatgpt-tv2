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
    network:(s.network&&s.network.name)||(s.webChannel&&s.webChannel.name)||'',
    url:s.url||''
  };
}
async function getJson(url){
  const r=await fetch(url,{headers:{'User-Agent':'JarvisHarvestTV/0.3'}});
  if(!r.ok) throw new Error('tvmaze_'+r.status);
  return await r.json();
}
export default async function handler(req,res){
  res.setHeader('Access-Control-Allow-Origin','*');
  res.setHeader('Access-Control-Allow-Methods','GET,OPTIONS');
  if(req.method==='OPTIONS') return res.status(204).end();
  if(req.method!=='GET') return res.status(405).json({error:'method_not_allowed'});
  try{
    const q=String(req.query?.q||'').trim();
    const mode=String(req.query?.mode||'schedule');
    let items=[];
    if(q){
      const raw=await getJson('https://api.tvmaze.com/search/shows?q='+encodeURIComponent(q));
      items=(raw||[]).slice(0,18).map(x=>normalizeShow(x.show||{}));
    }else if(mode==='schedule'){
      const country=String(req.query?.country||'US').slice(0,2).toUpperCase();
      const date=String(req.query?.date||new Date().toISOString().slice(0,10));
      const raw=await getJson('https://api.tvmaze.com/schedule?country='+encodeURIComponent(country)+'&date='+encodeURIComponent(date));
      const seen=new Set();
      for(const ep of raw||[]){
        const s=ep.show||{};
        if(!s.id||seen.has(s.id)) continue;
        seen.add(s.id);
        items.push({...normalizeShow(s),episode:{
          id:ep.id,name:ep.name||'',season:ep.season||null,number:ep.number||null,
          image:imageOf(ep),airtime:ep.airtime||'',airdate:ep.airdate||''
        }});
        if(items.length>=18) break;
      }
    }else{
      const raw=await getJson('https://api.tvmaze.com/shows?page=1');
      items=(raw||[]).filter(s=>imageOf(s)).slice(0,18).map(normalizeShow);
    }
    res.setHeader('Cache-Control','s-maxage=900, stale-while-revalidate=3600');
    return res.status(200).json({ok:true,source:'TVmaze',items,generatedAt:new Date().toISOString()});
  }catch(e){
    return res.status(502).json({ok:false,error:'catalog_unavailable',message:e?.message||'TVmaze error'});
  }
}
