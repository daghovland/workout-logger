import { createClient } from 'https://esm.sh/@supabase/supabase-js@2'
import { CORS_HEADERS, jsonResp, COACH_CONTEXT, buildPrompt } from '../_shared/coach-context.ts'

const TASK_PROMPT = `You are the athlete's personal coach in a freeform chat.

Your role:
- Answer questions about training, recovery, nutrition, and progress
- Analyse recent sessions and daily logs when they are relevant to the question
- Suggest concrete adjustments: loads, volume, exercise swaps, rest decisions
- Be direct and specific — reference actual data (dates, weights, reps) in answers
- Keep replies concise: 2–4 sentences unless the athlete asks for detail

Coach notes (below) capture the athlete's current goals, concerns, and active strategies.
Read them to personalise every reply.
If the conversation reveals something genuinely new and important — a new goal, a resolved concern,
a strategy change — include "update_coach_notes" in your JSON with the complete updated document.
Only update when you have real new information, not just to restate what is already there.

Coach notes format (use only when updating):
## Current Goals
- [specific, measurable goals with timelines]
## Current Concerns
- [active issues: injuries, blockers, patterns to watch]
## Current Strategies
- [specific approaches currently in use]

Respond ONLY with valid JSON:
{
  "reply": "2–4 sentence coaching response, specific and direct",
  "update_coach_notes": "optional — complete new coach notes text, only if something important changed"
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
    const { message, chat_history, context_type, session_data } = body as {
      message: string
      chat_history?: { role: string; content: string }[]
      context_type?: string
      session_data?: string | null
    }

    if (!message?.trim()) return jsonResp({ error: 'Empty message' }, 400)

    const twoWeeksAgo = daysAgo(14)
    const thirtyDaysAgo = daysAgo(30)

    const [profileRes, recentSessionsRes, dailyLogsRes] = await Promise.all([
      supabase
        .from('user_profiles')
        .select('display_name, ai_context, coach_notes')
        .eq('user_id', user.id)
        .maybeSingle(),
      supabase
        .from('sessions')
        .select('date, type, notes, duration_ms')
        .gte('date', twoWeeksAgo)
        .order('date', { ascending: false }),
      supabase
        .from('daily_logs')
        .select('date, sleep_hours, activity, notes')
        .gte('date', thirtyDaysAgo)
        .order('date', { ascending: false }),
    ])

    const profile = profileRes.data
    const name = profile?.display_name ?? 'the athlete'
    const coachNotes = profile?.coach_notes?.trim() ?? null

    // Build context block injected into the system prompt each call so the
    // AI always sees fresh data regardless of how long the chat has run.
    const now = new Date()
    const sessions = recentSessionsRes.data ?? []
    const sessionLines = sessions.length === 0
      ? 'No sessions in the last 2 weeks.'
      : sessions.map(s => {
          const d = new Date(s.date).toLocaleDateString('en-GB')
          const dur = s.duration_ms ? ` (${Math.round(s.duration_ms / 60000)}min)` : ''
          return s.notes ? `${d}: ${s.type}${dur} — ${s.notes}` : `${d}: ${s.type}${dur}`
        }).join('\n')

    const logs = (dailyLogsRes.data ?? [])
      .filter(l => l.sleep_hours != null || l.activity || l.notes)
      .map(l => {
        const d = new Date(l.date + 'T12:00:00').toLocaleDateString('en-GB')
        const parts = [
          l.sleep_hours != null ? `${l.sleep_hours}h sleep` : null,
          l.activity,
          l.notes,
        ].filter(Boolean)
        return `${d}: ${parts.join(' — ')}`
      }).join('\n')

    // Explicitly flag sessions already done today so the AI can't miss them
    const todayIso = now.toISOString().slice(0, 10)
    const todaySessions = sessions.filter(s => s.date === todayIso)
    const todayTrainingLine = todaySessions.length > 0
      ? `Already trained today: ${todaySessions.map(s => {
          const dur = s.duration_ms ? ` (${Math.round(s.duration_ms / 60000)}min)` : ''
          return `${s.type}${dur}`
        }).join(', ')}`
      : 'No training recorded in Supabase yet today'

    const contextLines = [
      `Today: ${now.toLocaleDateString('en-GB', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' })}, ${now.toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit' })} UTC`,
      `Athlete: ${name}`,
      todayTrainingLine,
      context_type ? `Current app context: ${context_type}` : null,
      session_data ? `Client-side session data (may include unsynced session):\n${session_data}` : null,
      `\nTraining sessions (last 14 days):\n${sessionLines}`,
      logs ? `\nDaily logs (last 30 days):\n${logs}` : null,
    ].filter(Boolean).join('\n')

    const coachNotesSection = coachNotes
      ? `\n\nCoach working notes:\n${coachNotes}`
      : ''

    const fullTaskPrompt = `${TASK_PROMPT}${coachNotesSection}\n\nAthlete data:\n${contextLines}`
    const systemPrompt = buildPrompt(fullTaskPrompt, profile?.ai_context)

    const history = (chat_history ?? []).slice(-20)
    const messages = [
      ...history,
      { role: 'user', content: message },
    ]

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
        model: 'claude-sonnet-4-6',
        max_tokens: 600,
        system: systemPrompt,
        messages,
      }),
    })

    if (!aiResp.ok) {
      const errBody = await aiResp.json().catch(() => null)
      const errMsg: string = errBody?.error?.message ?? ''
      const status = aiResp.status

      // Surface billing/quota errors clearly so the user knows what to do
      if (status === 402 || errMsg.toLowerCase().includes('credit') || errMsg.toLowerCase().includes('billing')) {
        return jsonResp({ error: 'billing', reply: 'Out of API credits — top up your Anthropic balance to continue.' }, 402)
      }
      if (status === 429) {
        return jsonResp({ error: 'rate_limit', reply: 'Too many requests — wait a moment and try again.' }, 429)
      }
      throw new Error(`Anthropic ${status}: ${errMsg || 'unknown error'}`)
    }

    const aiData = await aiResp.json()
    const rawText: string = aiData.content?.[0]?.text?.trim() ?? '{}'

    // Strip markdown code fences the model sometimes wraps around JSON
    const stripped = rawText.replace(/^```(?:json)?\s*/i, '').replace(/\s*```\s*$/, '').trim()

    // Fix literal newlines inside JSON string values — a common model mistake
    // that produces invalid JSON. Replace \n/\r inside quoted strings with \\n/\\r.
    const cleaned = stripped.replace(/("(?:[^"\\]|\\.)*")/g, (m) =>
      m.replace(/\n/g, '\\n').replace(/\r/g, '\\r')
    )

    let result: { reply: string; update_coach_notes?: string }
    try {
      result = JSON.parse(cleaned)
    } catch {
      // Last resort: pull out the reply field with a regex
      const m = stripped.match(/"reply"\s*:\s*"([\s\S]*?)"(?:\s*[,}])/)
      result = { reply: m ? m[1].replace(/\\n/g, '\n').replace(/\\"/g, '"') : stripped }
    }

    // Persist updated coach notes if the AI proposed changes
    if (result.update_coach_notes?.trim()) {
      await supabase
        .from('user_profiles')
        .update({ coach_notes: result.update_coach_notes })
        .eq('user_id', user.id)
    }

    return jsonResp({
      reply: result.reply,
      updated_coach_notes: result.update_coach_notes ?? null,
    })
  } catch (err) {
    console.error('chat error:', err)
    return jsonResp({ error: String(err) }, 500)
  }
})

function daysAgo(n: number): string {
  const d = new Date()
  d.setDate(d.getDate() - n)
  return d.toISOString().slice(0, 10)
}
