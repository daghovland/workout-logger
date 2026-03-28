import { createClient } from 'https://esm.sh/@supabase/supabase-js@2'

const BASE_SYSTEM_PROMPT = `You are a personal strength coach.

Progression principles:
- Progressive overload: suggest small weight increases (2.5 kg for main barbell lifts) when the athlete completed all reps comfortably
- If the last session weight looked like a grind (reps at or below the bottom of the rep range), hold the same weight or suggest a slight deload
- Bodyweight exercises (pull-ups): weight should be null, suggest a rep target only
- When there is no history, suggest a conservative starting weight based on typical beginner/intermediate numbers for the exercise

Respond ONLY with valid JSON and nothing else — no markdown fence, no explanation outside the JSON:
{"weight": number_or_null, "reps": number, "note": "one-sentence rationale"}`

Deno.serve(async (req: Request) => {
  // CORS preflight
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

    // Verify the user's JWT
    const token = authHeader.slice(7)
    const { data: { user }, error: authErr } = await supabaseAdmin.auth.getUser(token)
    if (authErr || !user) return jsonResp({ error: 'Unauthorized' }, 401)

    const body = await req.json()
    const { exercise_id, exercise_name } = body as { exercise_id: string; exercise_name: string }
    if (!exercise_id || !exercise_name) {
      return jsonResp({ error: 'Missing exercise_id or exercise_name' }, 400)
    }

    // Fetch user profile for personalised coaching context
    const { data: profile } = await supabaseAdmin
      .from('user_profiles')
      .select('display_name, ai_context')
      .eq('user_id', user.id)
      .maybeSingle()

    const systemPrompt = profile?.ai_context
      ? `${BASE_SYSTEM_PROMPT}\n\nAthlete context: ${profile.ai_context}`
      : BASE_SYSTEM_PROMPT

    const name = profile?.display_name ?? 'the athlete'

    // Fetch the IDs of all sessions belonging to this user
    const { data: userSessions, error: sessErr } = await supabaseAdmin
      .from('sessions')
      .select('id')
      .eq('user_id', user.id)
    if (sessErr) throw new Error(sessErr.message)

    const sessionIds = (userSessions ?? []).map((s: { id: string }) => s.id)

    // Fetch the last ~25 sets for this exercise (roughly 5 sessions worth)
    let recentSets: Array<{
      weight: number | null
      weight_l: number | null
      weight_r: number | null
      reps: number | null
      notes: string | null
      logged_at: string | null
    }> = []

    if (sessionIds.length > 0) {
      const { data: sets, error: setsErr } = await supabaseAdmin
        .from('sets')
        .select('weight, weight_l, weight_r, reps, notes, logged_at')
        .eq('exercise_id', exercise_id)
        .in('session_id', sessionIds)
        .order('logged_at', { ascending: false })
        .limit(25)
      if (setsErr) throw new Error(setsErr.message)
      recentSets = sets ?? []
    }

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
              const note = s.notes ? ` (note: ${s.notes})` : ''
              return `${date}: ${w} × ${s.reps} reps${note}`
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
      // Try to extract bare JSON object from the response text
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
