import { createClient } from 'https://esm.sh/@supabase/supabase-js@2'

const SYSTEM_PROMPT = `You are a strength coach for Dag, a Norwegian powerlifter and barbell athlete in his 40s.
He is recovering from a left patellar tendon rupture (surgery approximately 18 months ago).
He trains at a gym in Oslo and an outdoor barbell park.

Write a single short message (1–2 sentences, plain text, no markdown) for the top of Dag's session screen.
Cover ONE of: recovery readiness (is it too soon after the last session?), what to focus on today based on recent trends, or a brief motivational nudge.
Be specific and direct — avoid generic filler. Use the session history to say something concrete.`

Deno.serve(async (req: Request) => {
  if (req.method === 'OPTIONS') {
    return new Response(null, {
      headers: {
        'Access-Control-Allow-Origin': '*',
        'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
      },
    })
  }

  try {
    const authHeader = req.headers.get('Authorization')
    if (!authHeader?.startsWith('Bearer ')) {
      return jsonResp({ error: 'Unauthorized' }, 401)
    }

    const supabaseAdmin = createClient(
      Deno.env.get('SUPABASE_URL')!,
      Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!,
    )

    const token = authHeader.slice(7)
    const { data: { user }, error: authErr } = await supabaseAdmin.auth.getUser(token)
    if (authErr || !user) return jsonResp({ error: 'Unauthorized' }, 401)

    const body = await req.json()
    const { type } = body as { type: string }

    // Fetch last 10 sessions for this user
    const { data: sessions, error: sessErr } = await supabaseAdmin
      .from('sessions')
      .select('id, date, type, duration_ms')
      .eq('user_id', user.id)
      .order('date', { ascending: false })
      .limit(10)
    if (sessErr) throw new Error(sessErr.message)

    const sessionList = (sessions ?? [])
      .map(s => {
        const d = new Date(s.date).toLocaleDateString('en-GB')
        const dur = s.duration_ms ? ` (${Math.round(s.duration_ms / 60000)} min)` : ''
        return `${d}: ${s.type}${dur}`
      })
      .join('\n')

    const today = new Date().toLocaleDateString('en-GB')
    const userMessage = `Today is ${today}. Dag is about to start a ${type} session.

Recent sessions (newest first):
${sessionList || 'No previous sessions.'}

Write the brief message.`

    const apiKey = Deno.env.get('ANTHROPIC_API_KEY')
    if (!apiKey) throw new Error('ANTHROPIC_API_KEY secret not set')

    const aiResp = await fetch('https://api.anthropic.com/v1/messages', {
      method: 'POST',
      headers: {
        'x-api-key': apiKey,
        'anthropic-version': '2023-06-01',
        'content-type': 'application/json',
      },
      body: JSON.stringify({
        model: 'claude-sonnet-4-20250514',
        max_tokens: 120,
        system: SYSTEM_PROMPT,
        messages: [{ role: 'user', content: userMessage }],
      }),
    })

    if (!aiResp.ok) {
      const errText = await aiResp.text()
      throw new Error(`Anthropic API error ${aiResp.status}: ${errText}`)
    }

    const aiData = await aiResp.json()
    const message: string = aiData.content?.[0]?.text?.trim() ?? ''

    return jsonResp({ message })
  } catch (err) {
    console.error('session-brief error:', err)
    return jsonResp({ error: String(err) }, 500)
  }
})

function jsonResp(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      'Content-Type': 'application/json',
      'Access-Control-Allow-Origin': '*',
    },
  })
}
