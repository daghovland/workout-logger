import { createClient } from 'https://esm.sh/@supabase/supabase-js@2'
import { CORS_HEADERS, jsonResp } from '../_shared/coach-context.ts'

// session-note uses Haiku — it's conversational note-taking, not strategic coaching.
// No COACH_CONTEXT needed here; keep the system prompt minimal.
const SYSTEM_PROMPT = `You are a training log assistant. Help the athlete record notes about their session.

The athlete describes how the session went — feelings, pain, fatigue, technique, anything relevant.
Respond with:
1. A brief, warm receipt (1–3 sentences) acknowledging what was noted.
2. A compact, factual note to store — third person, key facts, exercises/loads if relevant, any pattern worth tracking.

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

    // Fetch user profile (name + optional ai_context override)
    const { data: profile } = await supabase
      .from('user_profiles')
      .select('display_name, ai_context')
      .eq('user_id', user.id)
      .maybeSingle()

    const name = profile?.display_name ?? 'the athlete'
    const systemPrompt = profile?.ai_context
      ? `${SYSTEM_PROMPT}\n\nAthlete context: ${profile.ai_context}`
      : SYSTEM_PROMPT

    // Only fetch recent session notes on the first message — no need to repeat
    // them on every follow-up turn since they're already in the conversation history.
    const isFirstMessage = !chatHistory?.length
    let recentNotes = ''
    if (isFirstMessage) {
      const { data: recentSessions } = await supabase
        .from('sessions')
        .select('date, type, notes')
        .not('notes', 'is', null)
        .order('date', { ascending: false })
        .limit(6)
      recentNotes = (recentSessions ?? [])
        .filter(s => s.notes)
        .map(s => `${new Date(s.date).toLocaleDateString('en-GB')} (${s.type}): ${s.notes}`)
        .join('\n')
    }

    // Build user message — full context only on first turn
    const contextParts = [
      `Athlete: ${name}`,
      `Current session:\n${sessionSummary}`,
      existingNote ? `Existing note:\n${existingNote}` : '',
      recentNotes ? `Recent session notes:\n${recentNotes}` : '',
    ].filter(Boolean)

    const userContent = isFirstMessage
      ? `${contextParts.join('\n\n')}\n\nMessage: ${message}`
      : `Note so far: ${existingNote || '(none)'}\n\nMessage: ${message}`

    // Keep only the last 6 messages of history to cap token cost
    const trimmedHistory = (chatHistory ?? []).slice(-6)

    const messages = [
      ...trimmedHistory,
      { role: 'user', content: userContent },
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
        model: 'claude-haiku-4-5-20251001',
        max_tokens: 300,
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
