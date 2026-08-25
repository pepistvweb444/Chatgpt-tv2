const OPENAI_URL = 'https://api.openai.com/v1/responses';

function textFromOpenAI(data) {
  return data.output_text || (data.output || []).flatMap(x => x.content || []).map(x => x.text || '').join('').trim();
}
function messages(input = []) { return input.map(m => ({ role: m.role === 'developer' ? 'system' : m.role, content: String(m.content || '') })); }
function cleanJson(raw='') {
  let s = String(raw || '').trim().replace(/^```(?:json)?\s*/i,'').replace(/\s*```$/,'').trim();
  const a = s.indexOf('{'), b = s.lastIndexOf('}');
  if (a >= 0 && b > a) s = s.slice(a,b+1);
  return JSON.parse(s);
}
async function openai(key, model, input) {
  const r = await fetch(OPENAI_URL,{method:'POST',headers:{Authorization:`Bearer ${key}`,'Content-Type':'application/json'},body:JSON.stringify({model,input})});
  const d = await r.json().catch(()=>({}));
  if(!r.ok) throw new Error(d?.error?.message||`openai_http_${r.status}`);
  return {provider:'openai',text:textFromOpenAI(d)};
}
async function compatible(provider,key,baseUrl,model,input) {
  const r=await fetch(`${String(baseUrl).replace(/\/$/,'')}/chat/completions`,{method:'POST',headers:{Authorization:`Bearer ${key}`,'Content-Type':'application/json',...(provider==='openrouter'?{'HTTP-Referer':process.env.JARVIS_PUBLIC_BASE_URL||'https://chatgpt-tv2.vercel.app','X-Title':'Jarvis Phone Agent'}:{})},body:JSON.stringify({model,messages:messages(input),temperature:0.05,response_format:{type:'json_object'}})});
  const d=await r.json().catch(()=>({})); if(!r.ok) throw new Error(d?.error?.message||d?.message||`${provider}_http_${r.status}`);
  const t=d?.choices?.[0]?.message?.content; if(!t) throw new Error(`${provider}_empty_response`); return {provider,text:String(t)};
}
async function gemini(key,model,input) {
  const system=input.filter(m=>m.role==='developer').map(m=>String(m.content||'')).join('\n\n');
  const contents=input.filter(m=>m.role!=='developer').map(m=>({role:m.role==='assistant'?'model':'user',parts:[{text:String(m.content||'')}]}));
  const r=await fetch(`https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(model)}:generateContent?key=${encodeURIComponent(key)}`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({systemInstruction:{parts:[{text:system}]},contents,generationConfig:{temperature:0.05,responseMimeType:'application/json'}})});
  const d=await r.json().catch(()=>({})); if(!r.ok) throw new Error(d?.error?.message||`gemini_http_${r.status}`);
  const t=(d?.candidates?.[0]?.content?.parts||[]).map(p=>p.text||'').join('').trim(); if(!t) throw new Error('gemini_empty_response'); return {provider:'gemini',text:t};
}
function order(preferred='auto') {
  const known=['qwen','gemini','groq','openrouter','openai']; const p=String(preferred||'auto').toLowerCase();
  if(known.includes(p)) return [p,...known.filter(x=>x!==p)];
  return ['qwen','gemini','groq','openrouter','openai'];
}
async function planWithFallback(input,preferred='auto') {
  const errors=[];
  for(const p of order(preferred)) try {
    if(p==='qwen'&&process.env.DASHSCOPE_API_KEY) return await compatible('qwen',process.env.DASHSCOPE_API_KEY,process.env.QWEN_BASE_URL||'https://dashscope-intl.aliyuncs.com/compatible-mode/v1',process.env.QWEN_MODEL||'qwen-plus',input);
    if(p==='gemini'&&process.env.GEMINI_API_KEY) return await gemini(process.env.GEMINI_API_KEY,process.env.GEMINI_MODEL||'gemini-2.5-flash',input);
    if(p==='groq'&&process.env.GROQ_API_KEY&&process.env.GROQ_MODEL) return await compatible('groq',process.env.GROQ_API_KEY,'https://api.groq.com/openai/v1',process.env.GROQ_MODEL,input);
    if(p==='openrouter'&&process.env.OPENROUTER_API_KEY) return await compatible('openrouter',process.env.OPENROUTER_API_KEY,process.env.OPENROUTER_BASE_URL||'https://openrouter.ai/api/v1',process.env.OPENROUTER_MODEL||'openrouter/free',input);
    if(p==='openai'&&process.env.OPENAI_API_KEY) return await openai(process.env.OPENAI_API_KEY,process.env.OPENAI_MODEL||'gpt-5.6-luna',input);
  } catch(e) { errors.push(`${p}: ${e?.message||'error'}`); }
  const e=new Error(errors.join(' | ')||'no_ai_provider'); e.details=errors; throw e;
}

export default async function handler(req,res){
  if(req.method!=='POST') return res.status(405).json({error:'method_not_allowed'});
  const {task='',ui=[],packageName='',step=0,preferredProvider='auto'}=req.body||{};
  if(!task) return res.status(400).json({error:'task_required'});
  const developer=`Eres el planificador de control de un teléfono Android para Jarvis. Recibes una tarea y un árbol de accesibilidad simplificado de la pantalla actual. Devuelve SOLO un objeto JSON válido con UNA siguiente acción. Acciones: {"action":"open_app","app":"nombre"}, {"action":"open_url","url":"https://..."}, {"action":"web_search","query":"texto"}, {"action":"click","text":"texto visible"}, {"action":"type","text":"valor","target":"etiqueta opcional"}, {"action":"scroll","direction":"forward|backward"}, {"action":"back"}, {"action":"home"}, {"action":"wait","ms":1000}, {"action":"confirm","message":"explicación del paso irreversible"}, {"action":"done","message":"resultado comprobado"}, {"action":"fail","message":"motivo"}. Usa controles visibles para click/type. Puedes abrir cualquier app instalada por nombre. Si no existe una app o la búsqueda es web, usa web_search/open_url. IMPORTANTE: buscar productos, seleccionar variantes/cantidades y pulsar 'Añadir al carrito', 'Añadir a la cesta' o equivalente son acciones REVERSIBLES y debes realizarlas SIN pedir confirmación. Para una lista de varios productos, repite búsqueda + selección + añadir hasta dejar el carrito preparado. Solo devuelve confirm JUSTO ANTES del acto realmente irreversible: 'Comprar ahora', 'Realizar pedido', 'Pagar', 'Confirmar reserva', 'Enviar', 'Publicar', transferencia, borrado irreversible o equivalente. Si la tarea del usuario dice explícitamente que solo quiere preparar/llenar el carrito, termina con done cuando el carrito esté listo y NO pulses comprar/pagar. Nunca leas ni escribas contraseñas, PIN bancario, CVV ni OTP. Nunca afirmes que algo se completó si la UI no lo confirma. Máximo 25 pasos.`;
  const input=[{role:'developer',content:developer},{role:'user',content:JSON.stringify({task:String(task).slice(0,1600),packageName,step:Number(step)||0,ui:Array.isArray(ui)?ui.slice(0,180):[]})}];
  try { const r=await planWithFallback(input,preferredProvider); const action=cleanJson(r.text); action.provider=r.provider; return res.status(200).json(action); }
  catch(e){ return res.status(503).json({action:'fail',error:'phone_agent_provider_unavailable',message:'No hay un motor disponible para controlar el teléfono.',details:e?.details||[e?.message||'planner_error']}); }
}
