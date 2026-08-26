function cleanNumber(v=''){return String(v).replace(/[^+0-9]/g,'').slice(0,32)}
function strip(s=''){return String(s).replace(/<!\[CDATA\[|\]\]>/g,'').replace(/<[^>]+>/g,' ').replace(/&amp;/g,'&').replace(/&quot;/g,'"').replace(/&#39;/g,"'").replace(/\s+/g,' ').trim()}
function tag(block,name){const m=block.match(new RegExp(`<${name}[^>]*>([\\s\\S]*?)<\\/${name}>`,'i'));return m?strip(m[1]):''}
function domain(u=''){try{return new URL(u).hostname.replace(/^www\./,'')}catch{return''}}
async function rss(url,source){try{const c=new AbortController();const t=setTimeout(()=>c.abort(),3500);const r=await fetch(url,{signal:c.signal,headers:{'user-agent':'Mozilla/5.0 JarvisCaller/1.0'}});clearTimeout(t);if(!r.ok)return[];const xml=await r.text();const blocks=[...xml.matchAll(/<item>([\s\S]*?)<\/item>/gi)].slice(0,8).map(m=>m[1]);return blocks.map(b=>({source,title:tag(b,'title'),description:tag(b,'description'),url:tag(b,'link')})).filter(x=>x.title||x.description)}catch{return[]}}
export default async function handler(req,res){
  res.setHeader('Cache-Control','no-store');
  if(req.method!=='GET')return res.status(405).json({error:'method_not_allowed'});
  const number=cleanNumber(req.query?.number||'');
  if(number.length<6)return res.status(400).json({error:'invalid_number'});
  const q=`\"${number}\" spam estafa fraude llamadas`;
  const [bing,news]=await Promise.all([
    rss(`https://www.bing.com/search?format=rss&q=${encodeURIComponent(q)}`,'Bing'),
    rss(`https://news.google.com/rss/search?q=${encodeURIComponent(q)}&hl=es&gl=ES&ceid=ES:es`,'Google News')
  ]);
  const all=[...bing,...news];
  const bad=/\b(spam|estafa|fraude|fraudul|telemarketing|acoso|molest|robocall|scam)\b/i;
  const evidence=[]; const domains=new Set();
  for(const x of all){const blob=`${x.title} ${x.description}`; if(!blob.includes(number)&&!blob.includes(number.replace(/^\+34/,'')))continue; if(!bad.test(blob))continue; const d=domain(x.url)||x.source; domains.add(d); evidence.push({...x,domain:d}); if(evidence.length>=6)break}
  const score=Math.min(100,domains.size*35+evidence.length*10);
  const classification=domains.size>=2?'spam_probable':domains.size===1?'possible_spam':'unknown';
  return res.status(200).json({number,classification,score,sources:[...domains],evidence,checkedAt:new Date().toISOString(),note:'Clasificación conservadora basada en coincidencias públicas; no implica certeza.'});
}
