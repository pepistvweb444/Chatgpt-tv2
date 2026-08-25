export default async function handler(req,res){
  const clientId=process.env.HOMEY_CLIENT_ID;
  if(!clientId) return res.status(503).send('HOMEY_CLIENT_ID no configurado');
  const base=process.env.JARVIS_PUBLIC_BASE_URL||'https://chatgpt-tv2.vercel.app';
  const redirect=process.env.HOMEY_REDIRECT_URI||`${base}/api/domotics/homey-callback`;
  const state=Buffer.from(JSON.stringify({app:true,t:Date.now()})).toString('base64url');
  const url='https://api.athom.com/oauth2/authorise?'+new URLSearchParams({authorization_type:'code',client_id:clientId,redirect_uri:redirect,state}).toString();
  res.redirect(302,url);
}
