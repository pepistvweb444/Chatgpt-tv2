function cleanNumber(v=''){
  let n=String(v).replace(/[^+0-9]/g,'').slice(0,32);
  if(n.startsWith('00')) n='+'+n.slice(2);
  const digits=n.replace(/\D/g,'');
  if(!n.startsWith('+') && digits.length===9 && /^[6789]/.test(digits)) n='+34'+digits;
  return n;
}
function strip(s=''){return String(s).replace(/<!\[CDATA\[|\]\]>/g,'').replace(/<[^>]+>/g,' ').replace(/&amp;/g,'&').replace(/&quot;/g,'"').replace(/&#39;/g,"'").replace(/\s+/g,' ').trim()}
function tag(block,name){const m=block.match(new RegExp(`<${name}[^>]*>([\\s\\S]*?)<\\/${name}>`,'i'));return m?strip(m[1]):''}
function domain(u=''){try{return new URL(u).hostname.replace(/^www\./,'')}catch{return''}}
async function rss(url,source){try{const c=new AbortController();const t=setTimeout(()=>c.abort(),3200);const r=await fetch(url,{signal:c.signal,headers:{'user-agent':'Mozilla/5.0 JarvisCaller/3.0'}});clearTimeout(t);if(!r.ok)return[];const xml=await r.text();const blocks=[...xml.matchAll(/<item>([\s\S]*?)<\/item>/gi)].slice(0,12).map(m=>m[1]);return blocks.map(b=>({source,title:tag(b,'title'),description:tag(b,'description'),url:tag(b,'link')})).filter(x=>x.title||x.description)}catch{return[]}}
function containsNumber(blob,number){const local=number.replace(/^\+34/,'');const compact=String(blob).replace(/[\s().-]/g,'');return compact.includes(number.replace(/[\s().-]/g,''))||compact.includes(local)}
function possibleLabel(items,number){
  const bad=/\b(spam|estafa|fraude|fraudul|telemarketing|acoso|molest|robocall|scam|quien llama|quién llama)\b/i;
  const generic=/\b(teléfono|telefono|llamadas?|número|numero|spam|quién|quien|desconocido)\b/ig;
  const candidates=[];
  for(const x of items){
    if(!containsNumber(`${x.title} ${x.description}`,number)) continue;
    const raw=strip(x.title).replace(number,'').replace(number.replace(/^\+34/,'')).replace(generic,' ').replace(/[|–—-]+/g,' ').replace(/\s+/g,' ').trim();
    if(raw.length<3||raw.length>90||bad.test(raw)) continue;
    candidates.push({label:raw,source:domain(x.url)||x.source,url:x.url});
  }
  const counts=new Map(); for(const c of candidates){counts.set(c.label,(counts.get(c.label)||0)+1)}
  const best=[...counts.entries()].sort((a,b)=>b[1]-a[1])[0];
  if(!best) return null;
  const sample=candidates.find(c=>c.label===best[0]);
  return {label:best[0],confidence:best[1]>=2?'medium':'low',source:sample?.source||'',url:sample?.url||''};
}
async function jsonFetch(url,headers={}){
  const c=new AbortController(); const t=setTimeout(()=>c.abort(),2600);
  try{const r=await fetch(url,{signal:c.signal,headers:{accept:'application/json',...headers}});const j=await r.json().catch(()=>null);if(!r.ok||!j)throw new Error('http_'+r.status);return j}finally{clearTimeout(t)}
}
function usefulName(v=''){const s=String(v||'').trim();return s && !/^(n\/?a|unknown|desconocido|null)$/i.test(s)?s:''}
async function abstractIdentity(number){
  const key=process.env.ABSTRACT_PHONE_API_KEY||process.env.ABSTRACT_PHONE_INTELLIGENCE_API_KEY||'';
  if(!key)return null;
  try{
    const j=await jsonFetch(`https://phoneintelligence.abstractapi.com/v1/?api_key=${encodeURIComponent(key)}&phone=${encodeURIComponent(number)}`);
    const reg=j.phone_registration||{}; const carrier=j.phone_carrier||{}; const risk=j.phone_risk||{}; const validation=j.phone_validation||{};
    const name=usefulName(reg.name);
    return {
      name,
      type:String(reg.type||''),
      carrier:String(carrier.name||''),
      lineType:String(carrier.line_type||''),
      source:'Abstract Phone Intelligence',
      confidence:name?'provider':'',
      valid:validation.is_valid!==false,
      riskLevel:String(risk.risk_level||''),
      abuse:Boolean(risk.is_abuse_detected)
    };
  }catch{return null}
}
async function ipqsIdentity(number){
  const key=process.env.IPQS_PHONE_API_KEY||'';
  if(!key)return null;
  try{
    const j=await jsonFetch(`https://www.ipqualityscore.com/api/json/phone?phone=${encodeURIComponent(number)}&country[]=ES`,{'IPQS-KEY':key});
    const name=usefulName(j.name);
    return {
      name,
      type:'',
      carrier:String(j.carrier||''),
      lineType:String(j.line_type||''),
      source:'IPQualityScore',
      confidence:name?'provider':'',
      valid:j.valid!==false,
      riskLevel:j.risky?'high':'',
      abuse:Boolean(j.spammer||j.recent_abuse)
    };
  }catch{return null}
}
async function providerIdentity(number){
  const primary=await abstractIdentity(number);
  if(primary && (primary.name||primary.carrier||primary.lineType)) return primary;
  const secondary=await ipqsIdentity(number);
  if(secondary && (secondary.name||secondary.carrier||secondary.lineType)) return secondary;
  return primary||secondary||null;
}
export default async function handler(req,res){
  res.setHeader('Cache-Control','no-store');
  if(req.method!=='GET')return res.status(405).json({error:'method_not_allowed'});
  const number=cleanNumber(req.query?.number||'');
  if(number.replace(/\D/g,'').length<6)return res.status(400).json({error:'invalid_number'});
  const exact=`"${number}"`;
  const local=number.replace(/^\+34/,'');
  const spamQ=`${exact} OR "${local}" spam estafa fraude telemarketing llamadas`;
  const identityQ=`${exact} OR "${local}" empresa contacto teléfono`;
  const [identity,bingSpam,bingIdentity,news]=await Promise.all([
    providerIdentity(number),
    rss(`https://www.bing.com/search?format=rss&q=${encodeURIComponent(spamQ)}`,'Bing'),
    rss(`https://www.bing.com/search?format=rss&q=${encodeURIComponent(identityQ)}`,'Bing'),
    rss(`https://news.google.com/rss/search?q=${encodeURIComponent(spamQ)}&hl=es&gl=ES&ceid=ES:es`,'Google News')
  ]);
  const all=[...bingSpam,...bingIdentity,...news];
  const bad=/\b(spam|estafa|fraude|fraudul|telemarketing|acoso|molest|robocall|scam)\b/i;
  const evidence=[]; const domains=new Set();
  for(const x of all){const blob=`${x.title} ${x.description}`; if(!containsNumber(blob,number)||!bad.test(blob))continue; const d=domain(x.url)||x.source; domains.add(d); evidence.push({...x,domain:d}); if(evidence.length>=8)break}
  const publicMatch=possibleLabel(all,number);
  const score=Math.min(100,domains.size*35+evidence.length*10+(identity?.abuse?25:0));
  let classification=domains.size>=2?'spam_probable':domains.size===1?'possible_spam':'unknown';
  if(identity?.abuse && classification==='unknown') classification='possible_spam';
  if(classification==='unknown' && identity?.name) classification='identified';
  return res.status(200).json({
    number,classification,score,sources:[...domains],evidence,publicMatch,identity,
    checkedAt:new Date().toISOString(),
    providers:{
      abstractConfigured:Boolean(process.env.ABSTRACT_PHONE_API_KEY||process.env.ABSTRACT_PHONE_INTELLIGENCE_API_KEY),
      ipqsConfigured:Boolean(process.env.IPQS_PHONE_API_KEY)
    },
    note:'La identidad puede provenir de un proveedor de phone intelligence o de coincidencias públicas. Puede no existir para números móviles privados.'
  });
}
