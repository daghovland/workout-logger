import { createClient } from 'https://esm.sh/@supabase/supabase-js@2'

const BASE_SYSTEM_PROMPT = `You are a personal strength coach.

Progression principles:
- Progressive overload: suggest small weight increases (2.5 kg for main barbell lifts) when the athlete completed all reps comfortably
- If the last session weight looked like a grind (reps at or below the bottom of the rep range), hold the same weight or suggest a slight deload
- Bodyweight exercises (pull-ups): weight should be null, suggest a rep target only
- When there is no history, suggest a conservative starting weight based on typical beginner/intermediate numbers for the exercise
- The note added is shown to the gymnast at session, so use short motivational language 
(For example: Improve from last gym session by hitting 9 reps on 32,5 kilo. Go!)

Respond ONLY with valid JSON and nothing else — no markdown fence, no explanation outside the JSON:
{"weight": number_or_null, "reps": number, "note": "one-sentence rationale plus motivation"}`

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

    // Use user-scoped client — gateway already verified the JWT, RLS handles data access
    const supabase = createClient(
      Deno.env.get('SUPABASE_URL')!,
      Deno.env.get('SUPABASE_ANON_KEY')!,
      { global: { headers: { Authorization: authHeader } } }
    )

    const { data: { user }, error: authErr } = await supabase.auth.getUser()
    if (authErr || !user) return jsonResp({ error: 'Unauthorized' }, 401)

    const body = await req.json()
    const { exercise_id, exercise_name } = body as { exercise_id: string; exercise_name: string }
    if (!exercise_id || !exercise_name) {
      return jsonResp({ error: 'Missing exercise_id or exercise_name' }, 400)
    }

    // Fetch user profile for personalised coaching context (RLS: own row only)
    const { data: profile } = await supabase
      .from('user_profiles')
      .select('display_name, ai_context')
      .eq('user_id', user.id)
      .maybeSingle()

    const systemPrompt = profile?.ai_context
      ? `${BASE_SYSTEM_PROMPT}\n\nAthlete context: ${profile.ai_context}`
      : BASE_SYSTEM_PROMPT

    const name = profile?.display_name ?? 'the athlete'

    // Fetch last ~25 sets for this exercise with their session notes (RLS restricts to own sessions/sets)
    const { data: sets, error: setsErr } = await supabase
      .from('sets')
      .select('weight, weight_l, weight_r, reps, notes, logged_at, session_id')
      .eq('exercise_id', exercise_id)
      .order('logged_at', { ascending: false })
      .limit(25)
    if (setsErr) throw new Error(setsErr.message)

    // Fetch session-level notes for the sessions that contain these sets
    const sessionIds = [...new Set((sets ?? []).map(s => s.session_id).filter(Boolean))]
    let sessionNotes: Record<string, string> = {}
    if (sessionIds.length > 0) {
      const { data: sessionRows } = await supabase
        .from('sessions')
        .select('id, notes')
        .in('local_id', sessionIds)
        .not('notes', 'is', null)
      for (const row of sessionRows ?? []) {
        if (row.notes) sessionNotes[row.id] = row.notes
      }
    }

    const recentSets = sets ?? []
    const historyText =
      recentSets.length === 0
        ? 'No history yet — this is the first time logging this exercise.'
        : recentSets
            .map(s => {
              const date = s.logged_at
                ? new Date(s.logged_at).toLocaleDateString('en-GB')
                : '?'
              const w =
                s.weight_l !== null
                  ? `${s.weight_l} kg left / ${s.weight_r} kg right`
                  : s.weight !== null
                  ? `${s.weight} kg`
                  : 'bodyweight'
              const setNote = s.notes ? ` (set note: ${s.notes})` : ''
              const sessNote = s.session_id && sessionNotes[s.session_id]
                ? ` [session: ${sessionNotes[s.session_id]}]`
                : ''
              return `${date}: ${w} × ${s.reps} reps${setNote}${sessNote}`
            })
            .join('\n')

    const userMessage = `Athlete: ${name}
Exercise: ${exercise_name}

Recent sets (newest first):
${historyText}

Suggest the next working set.`

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
        max_tokens: 200,
        system: systemPrompt,
        messages: [{ role: 'user', content: userMessage }],
      }),
    })

    if (!aiResp.ok) {
      const errText = await aiResp.text()
      throw new Error(`Anthropic API error ${aiResp.status}: ${errText}`)
    }

    const aiData = await aiResp.json()
    const rawText: string = aiData.content?.[0]?.text ?? '{}'

    let suggestion: { weight: number | null; reps: number; note: string }
    try {
      suggestion = JSON.parse(rawText)
    } catch {
      const match = rawText.match(/\{[\s\S]*?\}/)
      suggestion = match
        ? JSON.parse(match[0])
        : { weight: null, reps: 5, note: 'Could not parse AI response' }
    }

    return jsonResp(suggestion)
  } catch (err) {
    console.error('suggest-next-set error:', err)
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
