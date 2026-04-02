import { createClient } from 'https://esm.sh/@supabase/supabase-js@2'
import { CORS_HEADERS, jsonResp, buildPrompt } from '../_shared/coach-context.ts'

const TASK_PROMPT = `Given the athlete's training history, daily logs, and today's session, return a JSON object with a session brief and per-exercise suggestions.

Factor in daily logs: poor sleep or heavy activity outside the gym should prompt modest load targets.

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
    const { type, exercises, rich_logs } = body as {
      type: string;
      exercises: { id: string; name: string; unit: string }[];
      rich_logs?: { date: string; notes?: string; activity?: unknown }[];
    }
    if (!type || !exercises?.length) return jsonResp({ error: 'Missing type or exercises' }, 400)

    // Fetch all context in parallel:
    // - recent sessions: all types, last 2 weeks, with sets (cross-type context)
    // - prev type session: the most recent session of this type regardless of date (fallback)
    // - supabase daily logs: last 30 days
    const twoWeeksAgo = daysAgo(14)
    const thirtyDaysAgo = daysAgo(30)
    const [profileRes, allSessionsRes, recentSessionsRes, prevTypeSessionRes, dailyLogsRes] = await Promise.all([
      supabase
        .from('user_profiles')
        .select('display_name, ai_context')
        .eq('user_id', user.id)
        .maybeSingle(),
      supabase
        .from('sessions')
        .select('date, type, notes')
        .order('date', { ascending: false })
        .limit(15),
      supabase
        .from('sessions')
        .select('date, type, notes, sets(exercise_id, weight, weight_l, weight_r, reps, notes)')
        .gte('date', twoWeeksAgo)
        .order('date', { ascending: false }),
      supabase
        .from('sessions')
        .select('date, type, notes, sets(exercise_id, weight, weight_l, weight_r, reps, notes)')
        .eq('type', type)
        .order('date', { ascending: false })
        .limit(1),
      supabase
        .from('daily_logs')
        .select('date, sleep_hours, activity')
        .gte('date', thirtyDaysAgo)
        .order('date', { ascending: false }),
    ])

    const profile = profileRes.data
    const name = profile?.display_name ?? 'the athlete'

    const systemPrompt = buildPrompt(TASK_PROMPT, profile?.ai_context)

    // Format recent sessions (context for the brief)
    const allSessions = allSessionsRes.data ?? []
    const sessionListText = allSessions.length === 0
      ? 'No previous sessions.'
      : allSessions.map(s => {
          const d = new Date(s.date).toLocaleDateString('en-GB')
          return s.notes ? `${d}: ${s.type} — ${s.notes}` : `${d}: ${s.type}`
        }).join('\n')

    // Format Supabase daily logs (last 30 days)
    const sbDailyLogs = dailyLogsRes.data ?? []
    const sbLogText = sbDailyLogs
      .filter(l => l.sleep_hours != null || l.activity)
      .map(l => {
        const d = new Date(l.date + 'T12:00:00').toLocaleDateString('en-GB')
        const parts: string[] = []
        if (l.sleep_hours != null) parts.push(`${l.sleep_hours}h sleep`)
        if (l.activity) parts.push(l.activity)
        return `${d}: ${parts.join(' — ')}`
      }).join('\n')

    // Format rich daily logs passed from client (imported logs with notes + structured activity)
    const richLogText = (rich_logs ?? []).map(l => {
      const d = new Date(l.date).toLocaleDateString('en-GB')
      const activity = l.activity && typeof l.activity === 'object'
        ? Object.entries(l.activity as Record<string, unknown>)
            .map(([k, v]) => `${k}:${v}`)
            .join(', ')
        : String(l.activity ?? '')
      const notes = l.notes ? l.notes.replace(/\n/g, ' ') : ''
      return `${d}: ${notes}${activity ? ` [${activity}]` : ''}`
    }).join('\n')

    const dailyLogText = [sbLogText, richLogText].filter(Boolean).join('\n')

    // Merge recent sessions (last 2 weeks, all types) with previous session of this type
    // Deduplicate by date string to avoid counting the same session twice
    const recentSessions = recentSessionsRes.data ?? []
    const prevTypeSessions = prevTypeSessionRes.data ?? []
    const seenDates = new Set(recentSessions.map((s: any) => s.date))
    const mergedSessions: any[] = [
      ...recentSessions,
      ...prevTypeSessions.filter((s: any) => !seenDates.has(s.date)),
    ].sort((a, b) => new Date(b.date).getTime() - new Date(a.date).getTime())

    // Build per-exercise history: max weight/reps set per exercise per session
    const exerciseHistory: Record<string, string[]> = {}
    for (const sess of mergedSessions) {
      const d = new Date(sess.date).toLocaleDateString('en-GB')
      const sessionType = sess.type ?? type
      // Group sets by exercise, build full set list
      const setsByEx: Record<string, any[]> = {}
      for (const set of (sess.sets as any[]) ?? []) {
        if (!setsByEx[set.exercise_id]) setsByEx[set.exercise_id] = []
        setsByEx[set.exercise_id].push(set)
      }
      const sessNote = sess.notes ? ` [${sess.notes}]` : ''
      for (const [exId, sets] of Object.entries(setsByEx)) {
        if (!exerciseHistory[exId]) exerciseHistory[exId] = []
        const exUnit = exercises.find(e => e.id === exId)?.unit ?? 'BW'
        const isBW = !exUnit || exUnit === 'BW' || exUnit === 'sec'
        // Format all sets: "80×5, 80×5, 77.5×4" or "12, 10, 9 reps"
        const setsStr = sets.map(s => {
          const w = s.weight_l != null ? `${s.weight_l}/${s.weight_r}` : s.weight != null ? `${s.weight}` : null
          if (exUnit === 'sec') return `${s.reps}s`
          if (isBW) return `${s.reps}`
          return w ? `${w}×${s.reps}` : `${s.reps}`
        }).join(', ')
        const unit = isBW ? (exUnit === 'sec' ? '' : ' reps') : ' kg'
        const setNote = sets.some(s => s.notes) ? ` (${sets.map(s => s.notes).filter(Boolean).join('; ')})` : ''
        exerciseHistory[exId].push(`${d}[${sessionType}]: ${setsStr}${unit}${setNote}${sessNote}`)
      }
    }

    const exerciseLines = exercises
      .map(ex => {
        const unitLabel = ex.unit === 'sec' ? ' [timed, reps=seconds]' : ex.unit === 'BW' ? ' [bodyweight]' : ` [${ex.unit}]`
        const hist = exerciseHistory[ex.id]
        return hist?.length
          ? `${ex.id} (${ex.name}${unitLabel}): ${hist.join(' | ')}`
          : `${ex.id} (${ex.name}${unitLabel}): no history`
      })
      .join('\n')

    const today = new Date().toLocaleDateString('en-GB')
    const userMessage =
      `Today: ${today}. Athlete: ${name}. Session type: ${type}.

Recent sessions (last 15, all types):
${sessionListText}
${dailyLogText ? `\nDaily logs (last 30 days):\n${dailyLogText}\n` : ''}
Exercise history (all sets per session shown as "weight×reps, weight×reps"; last 2 weeks all types + previous ${type} session; date[type] — use cross-type data for sibling movements):
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
        max_tokens: 2500,
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
