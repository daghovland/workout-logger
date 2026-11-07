package no.daglifts.workout.data.body

import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Body-composition domain + calculations. Pure Kotlin (no Android deps) and kept in step with
 * the PWA's BODY_* functions in docs/index.html, so both clients show the same numbers.
 * Lower is better for every metric.
 */
enum class BodyMetric(
    val key: String,
    val label: String,
    val unit: String,
    val tol: Double,       // "on track" band
    val min: Double,       // sanity range for input
    val max: Double,
    val hasTarget: Boolean,
) {
    WAIST("waist_cm", "Waist", "cm", 0.5, 50.0, 200.0, true),
    WEIGHT("weight_kg", "Weight", "kg", 0.3, 40.0, 250.0, true),
    SUPRAILIAC("suprailiac_mm", "Suprailiac", "mm", 0.5, 1.0, 80.0, true),
    ABDOMINAL("abdominal_mm", "Abdominal", "mm", 0.5, 1.0, 80.0, true),
    CHEST("chest_mm", "Chest", "mm", 0.5, 1.0, 80.0, false),
    TRICEPS("triceps_mm", "Triceps", "mm", 0.5, 1.0, 80.0, false);

    companion object {
        /** Column order for plan entry/paste: weight / waist / suprailiac / abdominal. */
        val PLAN_ORDER = listOf(WEIGHT, WAIST, SUPRAILIAC, ABDOMINAL)
        val TARGETABLE = entries.filter { it.hasTarget }
    }
}

/** One day's measurements. [values] holds only the metrics that were measured. */
data class BodyEntry(val date: LocalDate, val values: Map<BodyMetric, Double>, val note: String? = null)

/** One plan checkpoint. [values] holds only the metrics with a target. */
data class BodyTarget(val date: LocalDate, val values: Map<BodyMetric, Double>)

data class BodyPoint(val date: LocalDate, val day: Double, val v: Double)

/** 7-day trailing average at a weigh-in; [mid] is the mean epoch-day of the readings averaged. */
data class AvgPoint(val date: LocalDate, val day: Double, val v: Double, val n: Int, val mid: Double)

enum class PlanState(val label: String, val wire: String) {
    AHEAD("Ahead", "ahead"), ON_TRACK("On track", "on track"), BEHIND("Behind", "behind")
}

data class TargetValue(val v: Double, val afterEnd: Boolean)

data class BodyStatus(
    val metric: BodyMetric,
    val date: LocalDate,
    val value: Double,
    val n: Int? = null,             // weigh-ins in the 7-day average (weight only)
    val target: Double? = null,
    val afterEnd: Boolean = false,
    val diff: Double? = null,
    val state: PlanState? = null,
)

object BodyCalc {
    private val NB: Locale = Locale.forLanguageTag("nb-NO")

    // ── Formatting / parsing ───────────────────────────────────────────────

    fun fmt(v: Double?, dec: Int = 1): String =
        if (v == null || !v.isFinite()) "–" else String.format(NB, "%.${dec}f", round(v, dec) + 0.0)

    fun fmtSigned(v: Double?, dec: Int = 1): String {
        if (v == null || !v.isFinite()) return "–"
        val r = round(v, dec) + 0.0              // + 0.0 turns -0.0 into 0.0
        return (if (r > 0) "+" else "") + fmt(r, dec)
    }

    fun fmtDate(d: LocalDate, withYear: Boolean = true): String =
        if (withYear) "${d.dayOfMonth}.${d.monthValue}.${d.year}" else "${d.dayOfMonth}.${d.monthValue}."

    /** Value for an input field: 102.5 → "102,5", 102.0 → "102". */
    fun fmtInput(v: Double): String =
        if (v == floor(v)) v.toLong().toString() else v.toString().replace('.', ',')

    fun round(v: Double, dec: Int): Double {
        val p = 10.0.pow(dec)
        return (v * p).roundToLong() / p
    }

    /** Accepts "102,5", "102.5", " 102 ". null for blank, NaN for garbage. */
    fun parseNum(s: String?): Double? {
        if (s == null) return null
        val t = s.trim().replace(Regex("\\s"), "").replaceFirst(',', '.')
        if (t.isEmpty() || t == "-" || t == "–") return null
        return if (Regex("^-?(\\d+\\.?\\d*|\\.\\d+)$").matches(t)) t.toDouble() else Double.NaN
    }

    /**
     * "2026-11-07", "7.11.2026", "7.11.26", "7/11/2026" or "7.11." — a missing year becomes the
     * most recent year that doesn't put the date in the future.
     */
    fun parseDate(s: String?, today: LocalDate = LocalDate.now()): LocalDate? {
        val t = s?.trim() ?: return null
        return try {
            Regex("^(\\d{4})-(\\d{1,2})-(\\d{1,2})$").matchEntire(t)?.let { m ->
                val (y, mo, d) = m.destructured
                return LocalDate.of(y.toInt(), mo.toInt(), d.toInt())
            }
            val m = Regex("^(\\d{1,2})[./](\\d{1,2})[./]?(\\d{2}|\\d{4})?$").matchEntire(t) ?: return null
            val d = m.groupValues[1].toInt()
            val mo = m.groupValues[2].toInt()
            val yStr = m.groupValues[3]
            if (yStr.isNotEmpty()) {
                var y = yStr.toInt()
                if (y < 100) y += 2000
                LocalDate.of(y, mo, d)
            } else {
                val cand = LocalDate.of(today.year, mo, d)
                if (cand.isAfter(today)) LocalDate.of(today.year - 1, mo, d) else cand
            }
        } catch (_: Exception) {
            null
        }
    }

    // ── Series + plan maths ────────────────────────────────────────────────

    private fun LocalDate.day(): Double = toEpochDay().toDouble()

    fun series(entries: List<BodyEntry>, m: BodyMetric): List<BodyPoint> =
        entries.mapNotNull { e -> e.values[m]?.let { BodyPoint(e.date, e.date.day(), it) } }
            .sortedBy { it.day }

    /** 7-day trailing average of weight at each weigh-in. */
    fun weightAvg7(entries: List<BodyEntry>): List<AvgPoint> {
        val w = series(entries, BodyMetric.WEIGHT)
        return w.map { p ->
            val win = w.filter { it.day <= p.day && it.day > p.day - 7 }
            AvgPoint(p.date, p.day, win.sumOf { it.v } / win.size, win.size, win.sumOf { it.day } / win.size)
        }
    }

    fun targetPoints(targets: List<BodyTarget>, m: BodyMetric): List<BodyPoint> =
        targets.mapNotNull { t -> t.values[m]?.let { BodyPoint(t.date, t.date.day(), it) } }
            .sortedBy { it.day }

    /** Linear interpolation between checkpoints; null before the plan starts, final value after it ends. */
    fun targetAt(targets: List<BodyTarget>, m: BodyMetric, day: Double): TargetValue? {
        val pts = targetPoints(targets, m)
        if (pts.isEmpty() || day < pts.first().day) return null
        val last = pts.last()
        if (day >= last.day) return TargetValue(last.v, day > last.day)
        for (i in 0 until pts.size - 1) {
            val a = pts[i]
            val b = pts[i + 1]
            if (day >= a.day && day <= b.day) {
                return TargetValue(a.v + (b.v - a.v) * (day - a.day) / (b.day - a.day), false)
            }
        }
        return null
    }

    /**
     * Latest value vs. the interpolated target. Weight uses the 7-day average, compared with the
     * target at the window's mean date (the average lags by ~3 days on a falling target).
     */
    fun status(entries: List<BodyEntry>, targets: List<BodyTarget>, m: BodyMetric): BodyStatus? {
        val date: LocalDate
        val v: Double
        val cmpDay: Double
        var n: Int? = null
        if (m == BodyMetric.WEIGHT) {
            val a = weightAvg7(entries).lastOrNull() ?: return null
            date = a.date; v = a.v; cmpDay = a.mid; n = a.n
        } else {
            val s = series(entries, m).lastOrNull() ?: return null
            date = s.date; v = s.v; cmpDay = s.day
        }
        // The 7-day average's mid-date can fall just before the plan starts (e.g. right after
        // importing history); once the latest reading is inside the plan, compare from its start.
        val start = if (m.hasTarget) targetPoints(targets, m).firstOrNull()?.day else null
        val cmp = if (start != null && date.day() >= start) maxOf(cmpDay, start) else cmpDay
        val t = (if (m.hasTarget) targetAt(targets, m, cmp) else null)
            ?: return BodyStatus(m, date, v, n)
        val diff = v - t.v
        val state = when {
            diff <= -m.tol -> PlanState.AHEAD
            diff >= m.tol -> PlanState.BEHIND
            else -> PlanState.ON_TRACK
        }
        return BodyStatus(m, date, v, n, t.v, t.afterEnd, diff, state)
    }

    /** Latest value minus the value nearest to (latest − [days]), if one exists close enough. */
    fun change(entries: List<BodyEntry>, m: BodyMetric, days: Int): Double? {
        val s: List<Pair<Double, Double>> =
            if (m == BodyMetric.WEIGHT) weightAvg7(entries).map { it.day to it.v }
            else series(entries, m).map { it.day to it.v }
        val last = s.lastOrNull() ?: return null
        val want = last.first - days
        val tol = maxOf(3, Math.round(days / 4.0).toInt())
        val best = s.filter { it.first < last.first && abs(it.first - want) <= tol }
            .minByOrNull { abs(it.first - want) } ?: return null
        return last.second - best.second
    }

    /**
     * Compact summary for the session-coach edge function (same shape as the PWA's
     * buildBodyCompPayload). Plain maps so this file stays dependency-free.
     */
    fun coachPayload(entries: List<BodyEntry>, targets: List<BodyTarget>, today: LocalDate = LocalDate.now()): Map<String, Any?>? {
        val cutoff = today.toEpochDay() - 60
        fun r2(v: Double?) = v?.let { round(it, 2) }
        val latest = linkedMapOf<String, Any?>()
        val trend = linkedMapOf<String, Any?>()
        val status = linkedMapOf<String, Any?>()
        for (m in BodyMetric.entries) {
            val s = series(entries, m).lastOrNull() ?: continue
            if (s.day < cutoff) continue
            latest[m.key] = mapOf("v" to r2(s.v), "date" to s.date.toString())
            if (m == BodyMetric.WEIGHT) {
                weightAvg7(entries).lastOrNull()?.let {
                    latest["weight_avg7_kg"] = mapOf("v" to r2(it.v), "date" to it.date.toString(), "n" to it.n)
                }
            }
            val d14 = change(entries, m, 14)
            val d28 = change(entries, m, 28)
            if (d14 != null || d28 != null) trend[m.key] = mapOf("d14" to r2(d14), "d28" to r2(d28))
            val st = status(entries, targets, m)
            if (st?.state != null) {
                status[m.key] = buildMap {
                    put("v", r2(st.value)); put("target", r2(st.target)); put("diff", r2(st.diff))
                    put("state", st.state.wire)
                    if (st.afterEnd) put("after_plan_end", true)
                }
            }
        }
        if (latest.isEmpty()) return null
        return mapOf("latest" to latest, "trend" to trend, "status" to status)
    }

    // ── Text import ────────────────────────────────────────────────────────

    /** Plan lines: "1.3.2027 90 / 85 / 6 / 12" (also ; or tab separated; "-" = no target). */
    fun parsePlan(text: String, today: LocalDate = LocalDate.now()): Pair<List<BodyTarget>, List<String>> {
        val rows = mutableListOf<BodyTarget>()
        val errors = mutableListOf<String>()
        text.lines().forEachIndexed { i, raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEachIndexed
            val toks = line.split(Regex("[\\s/;|]+")).filter { it.isNotEmpty() }
            val date = parseDate(toks.firstOrNull(), today)
            if (date == null) { errors += "line ${i + 1}: bad date \"${toks.firstOrNull()}\""; return@forEachIndexed }
            val vals = mutableListOf<Double?>()
            for (tk in toks.drop(1)) {
                val n = parseNum(tk)
                if (n != null && n.isNaN()) break       // trailing text like "(start)"
                vals += n
                if (vals.size == BodyMetric.PLAN_ORDER.size) break
            }
            val map = BodyMetric.PLAN_ORDER.withIndex()
                .mapNotNull { (j, m) -> vals.getOrNull(j)?.let { m to it } }.toMap()
            if (map.isEmpty()) { errors += "line ${i + 1}: no values"; return@forEachIndexed }
            rows += BodyTarget(date, map)
        }
        return rows to errors
    }

    fun splitCsvLine(line: String, delim: Char): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var q = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (q) {
                if (c == '"' && i + 1 < line.length && line[i + 1] == '"') { cur.append('"'); i++ }
                else if (c == '"') q = false
                else cur.append(c)
            } else if (c == '"') q = true
            else if (c == delim) { out += cur.toString(); cur.clear() }
            else cur.append(c)
            i++
        }
        out += cur.toString()
        return out
    }

    /**
     * Spreadsheet history: date (d.m.), weight, waist, suprailiac, abdominal [, chest, triceps].
     * Delimiter auto-detected (; tab ,); header names (English/Norwegian) override the order.
     */
    fun parseCsv(text: String, today: LocalDate = LocalDate.now()): Pair<List<BodyEntry>, List<String>> {
        val lines = text.removePrefix("﻿").lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyList<BodyEntry>() to listOf("empty file")
        val sample = lines.take(5).joinToString("\n")
        val delim = when { ';' in sample -> ';'; '\t' in sample -> '\t'; else -> ',' }
        var cols: List<BodyMetric?> = listOf(null) + listOf(
            BodyMetric.WEIGHT, BodyMetric.WAIST, BodyMetric.SUPRAILIAC, BodyMetric.ABDOMINAL,
            BodyMetric.CHEST, BodyMetric.TRICEPS,
        )
        var dateCol = 0
        val first = splitCsvLine(lines[0], delim)
        var start = 0
        if (parseDate(first.firstOrNull(), today) == null) {
            start = 1
            val patterns = listOf(
                Regex("weight|vekt|kg", RegexOption.IGNORE_CASE) to BodyMetric.WEIGHT,
                Regex("waist|midje|liv", RegexOption.IGNORE_CASE) to BodyMetric.WAIST,
                Regex("supra|hofte", RegexOption.IGNORE_CASE) to BodyMetric.SUPRAILIAC,
                Regex("abd|mage", RegexOption.IGNORE_CASE) to BodyMetric.ABDOMINAL,
                Regex("chest|bryst", RegexOption.IGNORE_CASE) to BodyMetric.CHEST,
                Regex("tric", RegexOption.IGNORE_CASE) to BodyMetric.TRICEPS,
            )
            val dIdx = first.indexOfFirst { Regex("dat", RegexOption.IGNORE_CASE).containsMatchIn(it) }
            val named = first.mapIndexed { j, h ->
                if (j == dIdx) null else patterns.firstOrNull { it.first.containsMatchIn(h) }?.second
            }
            if (dIdx >= 0 && named.count { it != null } >= 1) { cols = named; dateCol = dIdx }
        }
        val rows = mutableListOf<BodyEntry>()
        val errors = mutableListOf<String>()
        for (i in start until lines.size) {
            val cells = splitCsvLine(lines[i], delim)
            val date = parseDate(cells.getOrNull(dateCol), today)
            if (date == null) { errors += "line ${i + 1}: bad date \"${cells.getOrNull(dateCol)}\""; continue }
            val vals = linkedMapOf<BodyMetric, Double>()
            cols.forEachIndexed { j, m ->
                if (m == null || j == dateCol) return@forEachIndexed
                val n = parseNum(cells.getOrNull(j))
                when {
                    n == null -> {}
                    n.isNaN() -> errors += "line ${i + 1}: bad ${m.label.lowercase()} \"${cells[j]}\""
                    else -> vals[m] = n
                }
            }
            if (vals.isNotEmpty()) rows += BodyEntry(date, vals)
        }
        return rows to errors
    }

    /** "Nice" axis step for a span split into ~n intervals. */
    fun niceStep(span: Double, n: Int): Double {
        val raw = span / n
        val p = 10.0.pow(floor(kotlin.math.log10(raw)))
        val f = raw / p
        return (if (f <= 1) 1.0 else if (f <= 2) 2.0 else if (f <= 2.5) 2.5 else if (f <= 5) 5.0 else 10.0) * p
    }
}
