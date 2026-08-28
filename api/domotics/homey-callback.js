import crypto from 'crypto';

function secretKey(){
  const raw=process.env.HOMEY_SESSION_SECRET||process.env.HOMEY_CLIENT_SECRET||'';
  if(!raw) throw new Error('HOMEY_SESSION_SECRET_not_configured');
  return crypto.createHash('sha256').update(raw).digest();
}
function seal(obj){
  const iv=crypto.randomBytes(12); const cipher=crypto.createCipheriv('aes-256-gcm',secretKey(),iv);
  const data=Buffer.concat([cipher.update(JSON.stringify(obj),'utf8'),cipher.final()]); const tag=cipher.getAuthTag();
  return Buffer.concat([iv,tag,data]).toString('base64url');
}
export default async function handler(req,res){
  try{
    const code=String(req.query.code||''); if(!code) return res.status(400).send('Falta código de autorización de Homey');
    const clientId=process.env.HOMEY_CLIENT_ID, clientSecret=process.env.HOMEY_CLIENT_SECRET;
    if(!clientId||!clientSecret) return res.status(503).send('Credenciales Homey no configuradas');
    const base=process.env.JARVIS_PUBLIC_BASE_URL||'https://chatgpt-tv2.vercel.app';
    const redirect=process.env.HOMEY_REDIRECT_URI||`${base}/api/domotics/homey-callback`;

    const body=new URLSearchParams({
      client_id:clientId,
      client_secret:clientSecret,
      grant_type:'authorization_code',
      code,
      redirect_uri:redirect
    });
    const r=await fetch('https://api.athom.com/oauth2/token',{
      method:'POST',
      headers:{'Content-Type':'application/x-www-form-urlencoded'},
      body
    });
    const token=await r.json().catch(()=>({}));
    if(!r.ok) return res.status(502).send(token?.error_description||token?.error||'Homey token error');
    const session=seal({token,createdAt:Date.now()});
    return res.redirect(302,`jarvis://homey?session=${encodeURIComponent(session)}`);
  }catch(e){ return res.status(500).send(e?.message||'Homey callback error'); }
}
