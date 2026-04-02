import { createClient } from 'https://esm.sh/@supabase/supabase-js@2'
import { CORS_HEADERS, jsonResp, buildPrompt } from '../_shared/coach-context.ts'

const TASK_PROMPT = `Every time the athlete opens the app, they implicitly ask: "Should I train today, and should I hit the gym if I can?"

Additional checks:
- Check daily logs for gym accessibility blockers: travel without gym access, illness, injury flare-up → recommend home/outdoor/rest accordingly

Respond ONLY with valid JSON:
{
  "message": "2–3 sentences. Reference specific data (days since last session, sleep hours, relevant log notes). Name the recommended session type if not rest. Be direct.",
  "recommendation": "gym" | "outdoor" | "home" | "rest" | "light"
}`

Deno.serve(async (req: Request) => {
  if (req.method === 'OPTIONS') return new Response(null, { headers: CORS_HEADERS })

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
    const { rich_logs } = body as {
      rich_logs?: { date: string; notes?: string; activity?: unknown }[]
    }

    const twoWeeksAgo = daysAgo(14)
    const thirtyDaysAgo = daysAgo(30)
    const today = new Date().toISOString().slice(0, 10)

    const [profileRes, recentSessionsRes, dailyLogsRes, todayLogRes] = await Promise.all([
      supabase
        .from('user_profiles')
        .select('display_name, ai_context')
        .eq('user_id', user.id)
        .maybeSingle(),
      supabase
        .from('sessions')
        .select('date, type, notes, duration_ms')
        .gte('date', twoWeeksAgo)
        .order('date', { ascending: false }),
      supabase
        .from('daily_logs')
        .select('date, sleep_hours, activity')
        .gte('date', thirtyDaysAgo)
        .order('date', { ascending: false }),
      supabase
        .from('daily_logs')
        .select('date, sleep_hours, activity')
        .eq('date', today)
        .maybeSingle(),
    ])

    const profile = profileRes.data
    const name = profile?.display_name ?? 'the athlete'

    const systemPrompt = buildPrompt(TASK_PROMPT, profile?.ai_context)

    // Recent training sessions
    const sessions = recentSessionsRes.data ?? []
    const sessionLines = sessions.length === 0
      ? 'No sessions in the last 2 weeks.'
      : sessions.map(s => {
          const d = new Date(s.date).toLocaleDateString('en-GB')
          const dur = s.duration_ms ? ` (${Math.round(s.duration_ms / 60000)}min)` : ''
          return s.notes ? `${d}: ${s.type}${dur} — ${s.notes}` : `${d}: ${s.type}${dur}`
        }).join('\n')

    // Days since last session of each type
    const lastByType: Record<string, string> = {}
    for (const s of sessions) {
      if (!lastByType[s.type]) lastByType[s.type] = s.date
    }
    const lastSummary = Object.entries(lastByType)
      .map(([t, d]) => {
        const days = Math.round((Date.now() - new Date(d).getTime()) / 864e5)
        return `${t}: ${days === 0 ? 'today' : days === 1 ? 'yesterday' : `${days} days ago`}`
      }).join(', ')

    // Today's Supabase daily log
    const todayLog = todayLogRes.data
    const todayLogText = todayLog
      ? [todayLog.sleep_hours != null ? `${todayLog.sleep_hours}h sleep` : null, todayLog.activity].filter(Boolean).join(', ')
      : null

    // Supabase daily logs (last 30 days)
    const sbLogs = (dailyLogsRes.data ?? [])
      .filter(l => l.sleep_hours != null || l.activity)
      .map(l => {
        const d = new Date(l.date + 'T12:00:00').toLocaleDateString('en-GB')
        const parts = [l.sleep_hours != null ? `${l.sleep_hours}h sleep` : null, l.activity].filter(Boolean)
        return `${d}: ${parts.join(' — ')}`
      }).join('\n')

    // Rich daily logs from client (imported, structured)
    const richLogLines = (rich_logs ?? []).map(l => {
      const d = new Date(l.date).toLocaleDateString('en-GB')
      const activity = l.activity && typeof l.activity === 'object'
        ? Object.entries(l.activity as Record<string, unknown>)
            .map(([k, v]) => `${k}:${v}`).join(', ')
        : String(l.activity ?? '')
      return `${d}: ${l.notes ?? ''}${activity ? ` [${activity}]` : ''}`
    }).join('\n')

    const allLogText = [sbLogs, richLogLines].filter(Boolean).join('\n')

    const userMessage = `Today: ${new Date().toLocaleDateString('en-GB')}. Athlete: ${name}.

Last session by type: ${lastSummary || 'none in last 2 weeks'}
${todayLogText ? `Today's log: ${todayLogText}` : "Today's log: not yet entered"}

Training sessions (last 2 weeks):
${sessionLines}

Daily logs (last 30 days):
${allLogText || 'No logs.'}

Should ${name} train today, and what type of session is best?`

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
        model: 'claude-haiku-4-5-20251001',
        max_tokens: 200,
        system: systemPrompt,
        messages: [{ role: 'user', content: userMessage }],
      }),
    })

    if (!aiResp.ok) {
      const t = await aiResp.text()
      throw new Error(`Anthropic ${aiResp.status}: ${t}`)
    }

    const aiData = await aiResp.json()
    const rawText: string = aiData.content?.[0]?.text?.trim() ?? '{}'

    let result: { message: string; recommendation: string }
    try {
      result = JSON.parse(rawText)
    } catch {
      const match = rawText.match(/\{[\s\S]*\}/)
      try { result = match ? JSON.parse(match[0]) : { message: '', recommendation: 'gym' } }
      catch { result = { message: '', recommendation: 'gym' } }
    }

    return jsonResp(result)
  } catch (err) {
    console.error('home-brief error:', err)
    return jsonResp({ error: String(err) }, 500)
  }
})

function daysAgo(n: number): string {
  const d = new Date()
  d.setDate(d.getDate() - n)
  return d.toISOString().slice(0, 10)
}
