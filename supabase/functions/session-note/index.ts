import { createClient } from 'https://esm.sh/@supabase/supabase-js@2'
import { CORS_HEADERS, jsonResp, buildPrompt } from '../_shared/coach-context.ts'

const TASK_PROMPT = `Help the athlete log notes about their training session.

The athlete will describe how the session went — feelings, pain, fatigue, technique, anything relevant.
Respond with:
1. A brief, warm receipt (1–3 sentences) acknowledging what was noted and confirming it will inform future suggestions.
2. A compact, factual note to store on this session for future reference — written in third person, including the key facts: what was reported, which exercises/loads were involved if relevant, and any pattern this might connect to.

Respond ONLY with valid JSON, no markdown:
{"reply": "...", "note": "..."}`

Deno.serve(async (req: Request) => {
  if (req.method === 'OPTIONS') {
    return new Response(null, { headers: CORS_HEADERS })
  }

  try {
    const authHeader = req.headers.get('Authorization')
    if (!authHeader?.startsWith('Bearer ')) {
      return jsonResp({ error: 'Unauthorized' }, 401)
    }

    const supabase = createClient(
      Deno.env.get('SUPABASE_URL')!,
      Deno.env.get('SUPABASE_ANON_KEY')!,
      { global: { headers: { Authorization: authHeader } } }
    )

    const { data: { user }, error: authErr } = await supabase.auth.getUser()
    if (authErr || !user) return jsonResp({ error: 'Unauthorized' }, 401)

    const body = await req.json()
    const { sessionSummary, existingNote, chatHistory, message } = body as {
      sessionSummary: string
      existingNote: string
      chatHistory: Array<{ role: string; content: string }>
      message: string
    }

    // Fetch user profile for context
    const { data: profile } = await supabase
      .from('user_profiles')
      .select('display_name, ai_context')
      .eq('user_id', user.id)
      .maybeSingle()

    const systemPrompt = buildPrompt(TASK_PROMPT, profile?.ai_context)

    const name = profile?.display_name ?? 'the athlete'

    // Fetch notes from recent sessions for pattern context
    const { data: recentSessions } = await supabase
      .from('sessions')
      .select('date, type, notes')
      .not('notes', 'is', null)
      .order('date', { ascending: false })
      .limit(10)

    const recentNotes = (recentSessions ?? [])
      .filter(s => s.notes)
      .map(s => `${new Date(s.date).toLocaleDateString('en-GB')} (${s.type}): ${s.notes}`)
      .join('\n')

    const contextBlock = [
      `Athlete: ${name}`,
      `\nCurrent session:\n${sessionSummary}`,
      existingNote ? `\nExisting note on this session:\n${existingNote}` : '',
      recentNotes ? `\nNotes from recent sessions (for pattern awareness):\n${recentNotes}` : '',
    ].filter(Boolean).join('\n')

    const messages = [
      ...chatHistory,
      { role: 'user', content: `${contextBlock}\n\nAthlete's message: ${message}` },
    ]

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
        max_tokens: 400,
        system: systemPrompt,
        messages,
      }),
    })

    if (!aiResp.ok) {
      const errText = await aiResp.text()
      throw new Error(`Anthropic API error ${aiResp.status}: ${errText}`)
    }

    const aiData = await aiResp.json()
    const rawText: string = aiData.content?.[0]?.text ?? '{}'

    let result: { reply: string; note: string }
    try {
      result = JSON.parse(rawText)
    } catch {
      const match = rawText.match(/\{[\s\S]*\}/)
      result = match ? JSON.parse(match[0]) : { reply: rawText, note: '' }
    }

    return jsonResp(result)
  } catch (err) {
    console.error('session-note error:', err)
    return jsonResp({ error: String(err) }, 500)
  }
})

