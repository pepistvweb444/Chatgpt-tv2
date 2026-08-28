function cleanNumber(v=''){return String(v).replace(/[^+0-9]/g,'').slice(0,32)}
function strip(s=''){return String(s).replace(/<!\[CDATA\[|\]\]>/g,'').replace(/<[^>]+>/g,' ').replace(/&amp;/g,'&').replace(/&quot;/g,'"').replace(/&#39;/g,"'").replace(/\s+/g,' ').trim()}
function tag(block,name){const m=block.match(new RegExp(`<${name}[^>]*>([\\s\\S]*?)<\\/${name}>`,'i'));return m?strip(m[1]):''}
function domain(u=''){try{return new URL(u).hostname.replace(/^www\./,'')}catch{return''}}
async function rss(url,source){try{const c=new AbortController();const t=setTimeout(()=>c.abort(),3500);const r=await fetch(url,{signal:c.signal,headers:{'user-agent':'Mozilla/5.0 JarvisCaller/2.0'}});clearTimeout(t);if(!r.ok)return[];const xml=await r.text();const blocks=[...xml.matchAll(/<item>([\s\S]*?)<\/item>/gi)].slice(0,10).map(m=>m[1]);return blocks.map(b=>({source,title:tag(b,'title'),description:tag(b,'description'),url:tag(b,'link')})).filter(x=>x.title||x.description)}catch{return[]}}
function containsNumber(blob,number){const local=number.replace(/^\+34/,'');const compact=String(blob).replace(/[\s().-]/g,'');return compact.includes(number.replace(/[\s().-]/g,''))||compact.includes(local)}
function possibleLabel(items,number){
  const bad=/\b(spam|estafa|fraude|fraudul|telemarketing|acoso|molest|robocall|scam|quien llama|quién llama)\b/i;
  const generic=/\b(teléfono|telefono|llamadas?|número|numero|spam|quién|quien|desconocido)\b/ig;
  const candidates=[];
  for(const x of items){
    if(!containsNumber(`${x.title} ${x.description}`,number)) continue;
    const raw=strip(x.title).replace(number,'').replace(number.replace(/^\+34/,''),'').replace(generic,' ').replace(/[|–—-]+/g,' ').replace(/\s+/g,' ').trim();
    if(raw.length<3||raw.length>90||bad.test(raw)) continue;
    candidates.push({label:raw,source:domain(x.url)||x.source,url:x.url});
  }
  const counts=new Map(); for(const c of candidates){counts.set(c.label,(counts.get(c.label)||0)+1)}
  const best=[...counts.entries()].sort((a,b)=>b[1]-a[1])[0];
  if(!best) return null;
  const sample=candidates.find(c=>c.label===best[0]);
  return {label:best[0],confidence:best[1]>=2?'medium':'low',source:sample?.source||'',url:sample?.url||''};
}
export default async function handler(req,res){
  res.setHeader('Cache-Control','no-store');
  if(req.method!=='GET')return res.status(405).json({error:'method_not_allowed'});
  const number=cleanNumber(req.query?.number||'');
  if(number.length<6)return res.status(400).json({error:'invalid_number'});
  const exact=`\"${number}\"`;
  const local=number.replace(/^\+34/,'');
  const spamQ=`${exact} OR \"${local}\" spam estafa fraude telemarketing llamadas`;
  const identityQ=`${exact} OR \"${local}\" empresa contacto teléfono`;
  const [bingSpam,bingIdentity,news]=await Promise.all([
    rss(`https://www.bing.com/search?format=rss&q=${encodeURIComponent(spamQ)}`,'Bing'),
    rss(`https://www.bing.com/search?format=rss&q=${encodeURIComponent(identityQ)}`,'Bing'),
    rss(`https://news.google.com/rss/search?q=${encodeURIComponent(spamQ)}&hl=es&gl=ES&ceid=ES:es`,'Google News')
  ]);
  const all=[...bingSpam,...bingIdentity,...news];
  const bad=/\b(spam|estafa|fraude|fraudul|telemarketing|acoso|molest|robocall|scam)\b/i;
  const evidence=[]; const domains=new Set();
  for(const x of all){const blob=`${x.title} ${x.description}`; if(!containsNumber(blob,number)||!bad.test(blob))continue; const d=domain(x.url)||x.source; domains.add(d); evidence.push({...x,domain:d}); if(evidence.length>=8)break}
  const publicMatch=possibleLabel(all,number);
  const score=Math.min(100,domains.size*35+evidence.length*10);
  const classification=domains.size>=2?'spam_probable':domains.size===1?'possible_spam':'unknown';
  return res.status(200).json({number,classification,score,sources:[...domains],evidence,publicMatch,checkedAt:new Date().toISOString(),note:'Identidad y reputación son coincidencias públicas orientativas; Jarvis no debe tratarlas como certeza.'});
}
