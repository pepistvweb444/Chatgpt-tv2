async function youtube(url, key, token){
  const r=await fetch(url,{headers:token?{Authorization:'Bearer '+token}:{}});
  const j=await r.json().catch(()=>({}));
  if(!r.ok)throw new Error(j?.error?.message||('youtube_'+r.status));
  return j;
}
function thumb(v){
  const t=v?.snippet?.thumbnails||{};
  return (t.maxres||t.standard||t.high||t.medium||t.default||{}).url||'';
}
function norm(v){
  const id=typeof v.id==='string'?v.id:(v.id?.videoId||'');
  return {id,title:v?.snippet?.title||'',image:thumb(v),summary:v?.snippet?.description||'',channel:v?.snippet?.channelTitle||'',publishedAt:v?.snippet?.publishedAt||'',url:id?'https://www.youtube.com/watch?v='+id:''};
}
export default async function handler(req,res){
  res.setHeader('Access-Control-Allow-Origin','*');
  res.setHeader('Access-Control-Allow-Methods','GET,OPTIONS');
  if(req.method==='OPTIONS')return res.status(204).end();
  if(req.method!=='GET')return res.status(405).json({error:'method_not_allowed'});
  const key=process.env.YOUTUBE_API_KEY||'';
  const token=String(req.headers.authorization||'').replace(/^Bearer\s+/i,'').trim();
  const mode=String(req.query?.mode||'home');
  try{
    if(!key&&!token)return res.status(200).json({ok:true,connected:false,rows:[],message:'youtube_credentials_missing'});
    const rows=[];
    if(key){
      const popular=await youtube('https://www.googleapis.com/youtube/v3/videos?part=snippet,contentDetails&chart=mostPopular&regionCode=ES&maxResults=18&key='+encodeURIComponent(key),key,'');
      rows.push({id:'popular',title:'Recomendados y populares',personal:false,items:(popular.items||[]).map(norm)});
      const recent=await youtube('https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&order=date&regionCode=ES&maxResults=18&q='+encodeURIComponent('tecnología noticias entretenimiento')+'&key='+encodeURIComponent(key),key,'');
      rows.push({id:'recent',title:'Subidas recientes',personal:false,items:(recent.items||[]).map(norm)});
    }
    if(token){
      const subs=await youtube('https://www.googleapis.com/youtube/v3/activities?part=snippet,contentDetails&home=true&maxResults=18',key,token).catch(()=>({items:[]}));
      const personal=(subs.items||[]).filter(x=>x.contentDetails?.upload?.videoId).map(x=>norm({...x,id:x.contentDetails.upload.videoId}));
      rows.unshift({id:'foryou',title:'Pensado para ti',personal:true,items:personal});
      const playlists=await youtube('https://www.googleapis.com/youtube/v3/playlists?part=snippet&mine=true&maxResults=50',key,token).catch(()=>({items:[]}));
      const watch=(playlists.items||[]).find(p=>/watch later|ver más tarde/i.test(p.snippet?.title||''));
      if(watch){
        const wl=await youtube('https://www.googleapis.com/youtube/v3/playlistItems?part=snippet,contentDetails&playlistId='+encodeURIComponent(watch.id)+'&maxResults=18',key,token).catch(()=>({items:[]}));
        rows.push({id:'watchlater',title:'Ver más tarde',personal:true,items:(wl.items||[]).map(x=>norm({...x,id:x.contentDetails?.videoId||x.snippet?.resourceId?.videoId||''}))});
      }else rows.push({id:'watchlater',title:'Ver más tarde',personal:true,items:[]});
    }
    return res.status(200).json({ok:true,connected:Boolean(token),rows,generatedAt:new Date().toISOString()});
  }catch(e){return res.status(502).json({ok:false,error:'youtube_unavailable',message:e?.message||'YouTube error'});}
}
