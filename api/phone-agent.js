const OPENAI_URL = 'https://api.openai.com/v1/responses';

function outputText(data) {
  return data.output_text || (data.output || []).flatMap(x => x.content || []).map(x => x.text || '').join('').trim();
}

export default async function handler(req, res) {
  if (req.method !== 'POST') return res.status(405).json({ error:'method_not_allowed' });
  const key = process.env.OPENAI_API_KEY;
  if (!key) return res.status(503).json({ error:'OPENAI_API_KEY_not_configured' });
  const { task='', ui=[], packageName='', step=0 } = req.body || {};
  if (!task) return res.status(400).json({ error:'task_required' });

  const developer = `Eres el planificador de control de un teléfono Android para Jarvis. Recibes una tarea y un árbol de accesibilidad simplificado de la pantalla actual. Devuelve SOLO JSON válido, sin markdown, con una única siguiente acción. Acciones permitidas: {"action":"open_app","app":"nombre"}, {"action":"click","text":"texto visible"}, {"action":"type","text":"valor","target":"etiqueta opcional"}, {"action":"scroll","direction":"forward|backward"}, {"action":"back"}, {"action":"home"}, {"action":"wait","ms":1000}, {"action":"confirm","message":"explicación del paso irreversible"}, {"action":"done","message":"resultado"}, {"action":"fail","message":"motivo"}. Nunca inventes que una acción ocurrió. Usa únicamente controles visibles en ui para click/type. Si la tarea implica compra, pedido, pago, transferencia, publicación, envío de mensaje/correo, borrado o cualquier acción externa irreversible, navega y rellena todo lo posible pero devuelve confirm INMEDIATAMENTE ANTES del toque final que la ejecuta. Tras confirmación, el cliente volverá a llamarte y podrás indicar ese click final. No leas ni escribas campos de contraseña. Máximo 25 pasos.`;
  const payload = {
    model: process.env.OPENAI_MODEL || 'gpt-5.6-luna',
    input: [
      { role:'developer', content:developer },
      { role:'user', content:JSON.stringify({ task:String(task).slice(0,1200), packageName, step:Number(step)||0, ui:Array.isArray(ui)?ui.slice(0,140):[] }) }
    ]
  };
  try {
    const r = await fetch(OPENAI_URL, { method:'POST', headers:{ Authorization:`Bearer ${key}`, 'Content-Type':'application/json' }, body:JSON.stringify(payload) });
    const data = await r.json();
    if (!r.ok) throw new Error(data?.error?.message || 'openai_error');
    const raw = outputText(data).replace(/^```json\s*|\s*```$/g,'').trim();
    const action = JSON.parse(raw);
    return res.status(200).json(action);
  } catch (e) {
    return res.status(500).json({ action:'fail', message:e?.message || 'planner_error' });
  }
}
