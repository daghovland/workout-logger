import { createClient } from 'https://esm.sh/@supabase/supabase-js@2'

const CORS_HEADERS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
}

const SYSTEM_PROMPT = `You are a personal strength coach. Given an athlete's training history, daily logs, and today's session, return a JSON object with a session brief and per-exercise suggestions.

Progression principles:
- Progressive overload: suggest small weight increases (2.5 kg for main barbell lifts, 1.25 kg for dumbbells/cables) when the athlete completed all reps comfortably
- If the working set looked like a grind (reps at or near the bottom of range), hold weight or suggest a small deload
- Bodyweight exercises: weight must be null, suggest reps only
- Rehab exercises: be conservative — prioritise pain-free movement over progression
- Factor in daily logs: poor sleep or heavy activity outside the gym should prompt modest load targets
- When no history exists, suggest conservative starting weights typical for the exercise

Session brief: 2–3 sentences, plain text, no markdown, max 80 words. Include today's priority lifts, one technical focus, and any recovery consideration from recent logs.

Respond ONLY with valid JSON and nothing else — no markdown fences, no explanation:
{
  "brief": "...",
  "exercises": {
    "<exercise_id>": { "weight": number_or_null, "reps": number, "note": "one concrete motivational sentence referencing actual numbers" },
    ...
  }
}`

Deno.serve(async (req: Request) => {
  if (req.method === 'OPTIONS') {
    return new Response(null, { headers: CORS_HEADERS })
  }

  try {
    const authHeader = req.headers.get('Authorization')
    if (!authHeader?.startsWith('Bearer ')) return jsonResp({ error: 'Unauthorized' }, 401)

    const supabase = createClient(
      Deno.env.get('SUPABASE_URL')!,
      Deno.env.get('SUPABASE_ANON_KEY')!,
      { global: { headers: { Authorization: authHeader } } }
    )

    const { data: { user }, error: authErr } = await supabase.auth.getUser()
    if (authErr || !user) return jsonResp({ error: 'Unauthorized' }, 401)

    const body = await req.json()
    const { type, exercises } = body as { type: string; exercises: { id: string; name: string }[] }
    if (!type || !exercises?.length) return jsonResp({ error: 'Missing type or exercises' }, 400)

    // Fetch all context in parallel
    const fourteenDaysAgo = daysAgo(14)
    const [profileRes, allSessionsRes, typeSessionsRes, dailyLogsRes] = await Promise.all([
      supabase
        .from('user_profiles')
        .select('display_name, ai_context')
        .eq('user_id', user.id)
        .maybeSingle(),
      supabase
        .from('sessions')
        .select('date, type, notes')
        .order('date', { ascending: false })
        .limit(10),
      supabase
        .from('sessions')
        .select('date, notes, sets(exercise_id, set_index, weight, weight_l, weight_r, reps, notes)')
        .eq('type', type)
        .order('date', { ascending: false })
        .limit(6),
      supabase
        .from('daily_logs')
        .select('date, sleep_hours, activity')
        .gte('date', fourteenDaysAgo)
        .order('date', { ascending: false }),
    ])

    const profile = profileRes.data
    const name = profile?.display_name ?? 'the athlete'

    const systemPrompt = profile?.ai_context
      ? `${SYSTEM_PROMPT}\n\nAthlete context: ${profile.ai_context}`
      : SYSTEM_PROMPT

    // Format recent sessions (context for the brief)
    const allSessions = allSessionsRes.data ?? []
    const sessionListText = allSessions.length === 0
      ? 'No previous sessions.'
      : allSessions.map(s => {
          const d = new Date(s.date).toLocaleDateString('en-GB')
          return s.notes ? `${d}: ${s.type} — ${s.notes}` : `${d}: ${s.type}`
        }).join('\n')

    // Format daily logs
    const dailyLogs = dailyLogsRes.data ?? []
    const dailyLogText = dailyLogs
      .filter(l => l.sleep_hours != null || l.activity)
      .map(l => {
        const d = new Date(l.date + 'T12:00:00').toLocaleDateString('en-GB')
        const parts: string[] = []
        if (l.sleep_hours != null) parts.push(`${l.sleep_hours}h sleep`)
        if (l.activity) parts.push(l.activity)
        return `${d}: ${parts.join(' — ')}`
      }).join('\n')

    // Build per-exercise history from last 6 type-sessions, first working set per exercise
    const typeSessions = typeSessionsRes.data ?? []
    const exerciseHistory: Record<string, string[]> = {}
    for (const sess of typeSessions) {
      const d = new Date(sess.date).toLocaleDateString('en-GB')
      const firstSets: Record<string, { set_index: number; weight: number | null; weight_l: number | null; weight_r: number | null; reps: number; notes: string | null }> = {}
      for (const set of (sess.sets as any[]) ?? []) {
        const ex = firstSets[set.exercise_id]
        if (!ex || set.set_index < ex.set_index) firstSets[set.exercise_id] = set
      }
      for (const [exId, set] of Object.entries(firstSets)) {
        if (!exerciseHistory[exId]) exerciseHistory[exId] = []
        const w = set.weight_l != null
          ? `${set.weight_l}/${set.weight_r} kg`
          : set.weight != null
          ? `${set.weight} kg`
          : 'BW'
        const setNote = set.notes ? ` (${set.notes})` : ''
        const sessNote = sess.notes ? ` [${sess.notes}]` : ''
        exerciseHistory[exId].push(`${d}: ${w} × ${set.reps} reps${setNote}${sessNote}`)
      }
    }

    const exerciseLines = exercises
      .map(ex => {
        const hist = exerciseHistory[ex.id]
        return hist?.length
          ? `${ex.id} (${ex.name}): ${hist.join(' | ')}`
          : `${ex.id} (${ex.name}): no history`
      })
      .join('\n')

    const today = new Date().toLocaleDateString('en-GB')
    const userMessage =
      `Today: ${today}. Athlete: ${name}. Session type: ${type}.

Recent sessions (last 10, all types):
${sessionListText}
${dailyLogText ? `\nRecent daily logs (last 14 days):\n${dailyLogText}\n` : ''}
Exercise history (first working set per session, last 6 sessions of this type):
${exerciseLines}

Generate the session brief and per-exercise suggestions for all listed exercises.`

    const apiKey = Deno.env.get('ANTHROPIC_API_KEY')
    if (!apiKey) throw new Error('ANTHROPIC_API_KEY not set')

    const aiResp = await fetch('https://api.anthropic.com/v1/messages', {
      method: 'POST',
      headers: {
        'x-api-key': apiKey,
        'anthropic-version': '2023-06-01',
        'content-type': 'application/json',
      },
      body: JSON.stringify({
        model: 'claude-sonnet-4-20250514',
        max_tokens: 1500,
        system: systemPrompt,
        messages: [{ role: 'user', content: userMessage }],
      }),
    })

    if (!aiResp.ok) {
      const errText = await aiResp.text()
      throw new Error(`Anthropic API error ${aiResp.status}: ${errText}`)
    }

    const aiData = await aiResp.json()
    const rawText: string = aiData.content?.[0]?.text?.trim() ?? '{}'

    let result: { brief: string; exercises: Record<string, unknown> }
    try {
      result = JSON.parse(rawText)
    } catch {
      const match = rawText.match(/\{[\s\S]*\}/)
      try {
        result = match ? JSON.parse(match[0]) : { brief: '', exercises: {} }
      } catch {
        result = { brief: '', exercises: {} }
      }
    }

    return jsonResp(result)
  } catch (err) {
    console.error('session-coach error:', err)
    return jsonResp({ error: String(err) }, 500)
  }
})

function daysAgo(n: number): string {
  const d = new Date()
  d.setDate(d.getDate() - n)
  return d.toISOString().slice(0, 10)
}

function jsonResp(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: { 'Content-Type': 'application/json', 'Access-Control-Allow-Origin': '*' },
  })
}
