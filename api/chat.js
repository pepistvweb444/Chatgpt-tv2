export default async function handler(req, res) {
  if (req.method !== 'POST') return res.status(405).json({ error: 'method_not_allowed' });
  const key = process.env.OPENAI_API_KEY;
  if (!key) return res.status(503).json({ error: 'OPENAI_API_KEY_not_configured' });

  const { message, assistantName = 'Jarvis', conversationId = 'tv' } = req.body || {};
  if (!message || typeof message !== 'string') return res.status(400).json({ error: 'message_required' });

  try {
    const response = await fetch('https://api.openai.com/v1/responses', {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${key}`,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        model: process.env.OPENAI_MODEL || 'gpt-5.6-luna',
        input: [
          {
            role: 'developer',
            content: `Eres ${assistantName}, un asistente para Android TV. Responde en español salvo que el usuario pida otro idioma. Sé conciso y útil para una interfaz de televisión. Identificador de conversación Jarvis: ${conversationId}.`
          },
          { role: 'user', content: message }
        ]
      })
    });

    const data = await response.json();
    if (!response.ok) return res.status(response.status).json({ error: data?.error?.message || 'openai_error' });
    const reply = data.output_text || (data.output || [])
      .flatMap(item => item.content || [])
      .map(part => part.text || '')
      .join('')
      .trim();
    return res.status(200).json({ reply, conversationId });
  } catch (error) {
    return res.status(500).json({ error: error?.message || 'backend_error' });
  }
}
