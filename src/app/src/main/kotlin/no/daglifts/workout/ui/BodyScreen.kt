package no.daglifts.workout.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import no.daglifts.workout.data.body.BodyCalc
import no.daglifts.workout.data.body.BodyEntry
import no.daglifts.workout.data.body.BodyMetric
import no.daglifts.workout.data.body.BodyStatus
import no.daglifts.workout.data.body.BodyTarget
import no.daglifts.workout.data.body.PlanState
import no.daglifts.workout.ui.theme.DangerColor
import no.daglifts.workout.ui.theme.LocalWorkoutColors
import no.daglifts.workout.ui.theme.MutedColor
import no.daglifts.workout.ui.theme.SurfaceColor
import no.daglifts.workout.ui.theme.TextColor
import no.daglifts.workout.viewmodel.BodyCsvPreview
import no.daglifts.workout.viewmodel.BodyPlanPreview
import no.daglifts.workout.viewmodel.BodyViewModel
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

private val GoodColor = Color(0xFFC8F542)
private val WarnColor = Color(0xFFFF9F1C)

private enum class BodyRange { PLAN, ALL }

@Composable
fun BodyScreen(vm: BodyViewModel, onBack: () -> Unit) {
    val entries by vm.entries.collectAsState()
    val targets by vm.targets.collectAsState()
    val colors = LocalWorkoutColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()

    var planOpen by remember { mutableStateOf(false) }
    var planText by remember { mutableStateOf("") }
    var range by remember { mutableStateOf(BodyRange.PLAN) }
    var date by remember { mutableStateOf(LocalDate.now()) }
    val fields = remember { mutableStateMapOf<BodyMetric, String>() }
    var note by remember { mutableStateOf("") }
    var csvPreview by remember { mutableStateOf<BodyCsvPreview?>(null) }
    var planPreview by remember { mutableStateOf<BodyPlanPreview?>(null) }
    var confirmDelete by remember { mutableStateOf<LocalDate?>(null) }
    var confirmDeleteTarget by remember { mutableStateOf<LocalDate?>(null) }

    // Prefill the form from the chosen day (re-run once data has loaded)
    val loaded = entries.isNotEmpty()
    LaunchedEffect(date, loaded) {
        val e = vm.entries.value.firstOrNull { it.date == date }
        BodyMetric.entries.forEach { m -> fields[m] = e?.values?.get(m)?.let(BodyCalc::fmtInput) ?: "" }
        note = e?.note ?: ""
    }

    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            if (text != null) csvPreview = vm.previewCsv(text)
        }
    }

    Column(Modifier.fillMaxSize().background(colors.bg)) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.bg)
                .border(1.dp, colors.border, RoundedCornerShape(0.dp))
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = colors.text)
            }
            Text(
                "Body",
                modifier = Modifier.weight(1f),
                fontSize = 17.sp, fontWeight = FontWeight.Bold, color = colors.text,
            )
            TextButton(onClick = { planOpen = !planOpen }) {
                Text(if (planOpen) "Close plan" else "🎯 Plan", color = colors.text)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (planOpen) {
                PlanCard(
                    targets = targets,
                    text = planText,
                    onText = { planText = it },
                    onAdd = {
                        vm.previewPlan(planText, replace = false)?.let { vm.commitPlan(it); planText = "" }
                    },
                    onReplace = {
                        vm.previewPlan(planText, replace = true)?.let {
                            if (it.existing > 0) planPreview = it else { vm.commitPlan(it); planText = "" }
                        }
                    },
                    onDelete = { confirmDeleteTarget = it },
                )
            }

            StatusSection(entries, targets)

            // ── Entry form ──────────────────────────────────────────────────
            val today = LocalDate.now()
            val existing = entries.any { it.date == date }
            BodyCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (existing) "Edit ${BodyCalc.fmtDate(date, false)}" else "Log measurement",
                        modifier = Modifier.weight(1f),
                        fontSize = 13.sp, fontWeight = FontWeight.Bold, color = colors.text,
                    )
                    IconButton(onClick = { date = date.minusDays(1) }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous day", tint = colors.text)
                    }
                    Text(
                        if (date == today) "Today" else BodyCalc.fmtDate(date),
                        modifier = Modifier.clickable { date = today },
                        fontSize = 13.sp, color = colors.text,
                    )
                    IconButton(onClick = { date = date.plusDays(1) }, enabled = date.isBefore(today)) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next day",
                            tint = if (date.isBefore(today)) colors.text else colors.border,
                        )
                    }
                }
                BodyMetric.entries.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { m ->
                            NumField(
                                label = "${m.label} (${m.unit})",
                                value = fields[m] ?: "",
                                onChange = { fields[m] = it },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                TextField(
                    value = note,
                    onValueChange = { note = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Note (optional)", color = colors.muted) },
                    singleLine = true,
                    colors = fieldColors(),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Button(
                        onClick = { vm.save(date, fields.toMap(), note) },
                        colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = Color.Black),
                    ) { Text("Save", fontWeight = FontWeight.Bold) }
                }
            }

            // ── Charts ──────────────────────────────────────────────────────
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RangeChip("Plan period", range == BodyRange.PLAN) { range = BodyRange.PLAN }
                RangeChip("All", range == BodyRange.ALL) { range = BodyRange.ALL }
            }
            BodyMetric.entries.forEach { m -> BodyChart(m, entries, targets, range) }

            // ── Entries ─────────────────────────────────────────────────────
            BodyCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Entries", modifier = Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = colors.text)
                    TextButton(onClick = { csvLauncher.launch("*/*") }) { Text("⬆ Import CSV", color = colors.text) }
                }
                if (entries.isEmpty()) {
                    Text("No measurements yet.", fontSize = 12.sp, color = colors.muted)
                }
                entries.asReversed().take(60).forEach { e ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                date = e.date
                                scope.launch { scroll.animateScrollTo(0) }
                            }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(BodyCalc.fmtDate(e.date), modifier = Modifier.width(84.dp), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = colors.text)
                        Text(
                            BodyMetric.entries.mapNotNull { m -> e.values[m]?.let { "${m.label.lowercase()} ${BodyCalc.fmt(it)}" } }
                                .joinToString(" · ") + (e.note?.let { "\n$it" } ?: ""),
                            modifier = Modifier.weight(1f),
                            fontSize = 12.sp, color = colors.muted, lineHeight = 17.sp,
                        )
                        IconButton(onClick = { confirmDelete = e.date }) {
                            Icon(Icons.Default.Close, contentDescription = "Delete", tint = colors.muted)
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    // ── Dialogs ───────────────────────────────────────────────────────────────

    csvPreview?.let { p ->
        ConfirmDialog(
            title = "Import ${p.rows.size} rows?",
            text = "${BodyCalc.fmtDate(p.rows.first().date)} – ${BodyCalc.fmtDate(p.rows.last().date)}" +
                (if (p.overlap > 0) "\n${p.overlap} dates already exist: imported values replace them, blank cells keep the existing value." else "") +
                (if (p.errors.isNotEmpty()) "\n\n${p.errors.size} cells/rows skipped:\n" + p.errors.take(6).joinToString("\n") else ""),
            confirm = "Import",
            onConfirm = { vm.commitCsv(p); csvPreview = null },
            onDismiss = { csvPreview = null },
        )
    }
    planPreview?.let { p ->
        ConfirmDialog(
            title = "Replace plan?",
            text = "Replace all ${p.existing} checkpoints with ${p.rows.size} new ones?",
            confirm = "Replace",
            onConfirm = { vm.commitPlan(p); planPreview = null; planText = "" },
            onDismiss = { planPreview = null },
        )
    }
    confirmDelete?.let { d ->
        ConfirmDialog(
            title = "Delete ${BodyCalc.fmtDate(d)}?",
            text = "Deletes all measurements for this day.",
            confirm = "Delete", danger = true,
            onConfirm = { vm.delete(d); confirmDelete = null },
            onDismiss = { confirmDelete = null },
        )
    }
    confirmDeleteTarget?.let { d ->
        ConfirmDialog(
            title = "Delete checkpoint ${BodyCalc.fmtDate(d)}?",
            text = "",
            confirm = "Delete", danger = true,
            onConfirm = { vm.deleteTarget(d); confirmDeleteTarget = null },
            onDismiss = { confirmDeleteTarget = null },
        )
    }
}

// ── Status ────────────────────────────────────────────────────────────────────

@Composable
private fun StatusSection(entries: List<BodyEntry>, targets: List<BodyTarget>) {
    val colors = LocalWorkoutColors.current
    val tiles = BodyMetric.entries.mapNotNull { m ->
        val st = BodyCalc.status(entries, targets, m)
        if (st == null && !m.hasTarget) null else m to st
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.firstOrNull()?.let { (m, st) ->
            StatusTile(m = m, st = st, hasPlan = targets.isNotEmpty(), primary = true, modifier = Modifier.fillMaxWidth())
        }
        tiles.drop(1).chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { (m, st) ->
                    StatusTile(m = m, st = st, hasPlan = targets.isNotEmpty(), primary = false, modifier = Modifier.weight(1f))
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        val today = LocalDate.now()
        val next = targets.firstOrNull { !it.date.isBefore(today) }
        val line = when {
            next != null -> "Next checkpoint ${BodyCalc.fmtDate(next.date)}: " +
                BodyMetric.PLAN_ORDER.mapNotNull { m -> next.values[m]?.let { "${m.label.lowercase()} ${BodyCalc.fmt(it)}" } }.joinToString(" · ")
            targets.isEmpty() -> "No target plan yet — tap 🎯 Plan to add your checkpoints."
            else -> null
        }
        line?.let { Text(it, fontSize = 12.sp, color = colors.muted, lineHeight = 17.sp) }
    }
}

@Composable
private fun StatusTile(m: BodyMetric, st: BodyStatus?, hasPlan: Boolean, primary: Boolean, modifier: Modifier) {
    val colors = LocalWorkoutColors.current
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(m.label.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp, color = colors.muted)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                if (st != null) BodyCalc.fmt(st.value) else "–",
                fontSize = if (primary) 28.sp else 22.sp, fontWeight = FontWeight.ExtraBold, color = colors.text,
            )
            Text(" ${m.unit}", fontSize = 13.sp, color = colors.muted, modifier = Modifier.padding(bottom = 3.dp))
        }
        if (st == null) {
            Text("No data", fontSize = 11.sp, color = colors.muted)
        } else {
            val n = st.n
            val meta = (if (n != null) "7-day avg · $n weigh-in${if (n == 1) "" else "s"} · " else "") +
                BodyCalc.fmtDate(st.date, false) +
                (st.target?.let { " · target ${BodyCalc.fmt(it)}${if (st.afterEnd) " (final)" else ""}" } ?: "")
            Text(meta, fontSize = 11.sp, color = colors.muted, lineHeight = 15.sp)
            val state = st.state
            if (state != null) {
                Pill(
                    "${state.label} ${BodyCalc.fmtSigned(st.diff)} ${m.unit}",
                    if (state == PlanState.BEHIND) WarnColor else GoodColor,
                )
            } else if (m.hasTarget) {
                Pill(if (hasPlan) "Before plan start" else "No plan set", colors.muted)
            }
        }
    }
}

@Composable
private fun Pill(text: String, dot: Color) {
    val colors = LocalWorkoutColors.current
    Row(
        Modifier
            .padding(top = 6.dp)
            .clip(RoundedCornerShape(100.dp))
            .background(colors.surface2)
            .border(1.dp, dot.copy(alpha = 0.5f), RoundedCornerShape(100.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = colors.text)
    }
}

// ── Chart ─────────────────────────────────────────────────────────────────────

@Composable
private fun BodyChart(m: BodyMetric, entries: List<BodyEntry>, targets: List<BodyTarget>, range: BodyRange) {
    val colors = LocalWorkoutColors.current
    val isW = m == BodyMetric.WEIGHT
    val pts = remember(entries, m) { BodyCalc.series(entries, m) }
    val tps = remember(targets, m) { if (m.hasTarget) BodyCalc.targetPoints(targets, m) else emptyList() }
    val avg = remember(entries, m) { if (isW) BodyCalc.weightAvg7(entries) else emptyList() }
    if (pts.isEmpty() && tps.isEmpty()) return

    val today = LocalDate.now().toEpochDay().toDouble()
    val days = pts.map { it.day } + tps.map { it.day }
    val (x0, x1) = when {
        range == BodyRange.PLAN && tps.isNotEmpty() ->
            (tps.first().day - 14) to (maxOf(tps.last().day, pts.lastOrNull()?.day ?: 0.0) + 2)
        range == BodyRange.PLAN -> { val e = maxOf(today, days.max()) + 2; (e - 92) to e }
        else -> (days.min() - 2) to (maxOf(today, days.max()) + 2)
    }
    fun inX(d: Double) = d in x0..x1

    var vis = pts.filter { inX(it.day) }.map { it.v } + tps.filter { inX(it.day) }.map { it.v } +
        avg.filter { inX(it.day) }.map { it.v } +
        (if (m.hasTarget) listOfNotNull(BodyCalc.targetAt(targets, m, x0)?.v, BodyCalc.targetAt(targets, m, x1)?.v) else emptyList())
    if (vis.isEmpty()) vis = pts.map { it.v } + tps.map { it.v }
    var lo = vis.min()
    var hi = vis.max()
    val minSpan = if (m.unit == "mm") 4.0 else 3.0
    if (hi - lo < minSpan) { val c = (hi + lo) / 2; lo = c - minSpan / 2; hi = c + minSpan / 2 }
    val step = BodyCalc.niceStep(hi - lo, 4)
    lo = floor(lo / step) * step
    hi = ceil(hi / step) * step

    // Points the tap readout snaps to: weekly average for weight, raw readings otherwise
    val snap: List<Pair<Double, Double>> = if (isW) avg.map { it.day to it.v } else pts.map { it.day to it.v }
    var selected by remember(m, entries, range) { mutableStateOf<Double?>(null) }

    val measurer = rememberTextMeasurer()
    val axisStyle = TextStyle(color = colors.muted, fontSize = 10.sp)
    val accent = colors.accent
    val muted = colors.muted
    val grid = colors.border
    val surface = colors.surface
    val textColor = colors.text

    BodyCard {
        Text("${m.label} (${m.unit})", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = colors.text)
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(170.dp)
                .pointerInput(snap, x0, x1) {
                    detectTapGestures { off ->
                        val l = 34.dp.toPx()
                        val r = 8.dp.toPx()
                        val d = x0 + (off.x - l) / (size.width - l - r) * (x1 - x0)
                        selected = snap.filter { inX(it.first) }.minByOrNull { abs(it.first - d) }?.first
                    }
                },
        ) {
            val l = 34.dp.toPx()
            val r = 8.dp.toPx()
            val t = 10.dp.toPx()
            val b = 22.dp.toPx()
            fun x(d: Double) = (l + (d - x0) / (x1 - x0) * (size.width - l - r)).toFloat()
            fun y(v: Double) = (t + (hi - v) / (hi - lo) * (size.height - t - b)).toFloat()
            fun pathOf(list: List<Pair<Double, Double>>) = Path().apply {
                list.forEachIndexed { i, (d, v) -> if (i == 0) moveTo(x(d), y(v)) else lineTo(x(d), y(v)) }
            }

            var v = lo
            while (v <= hi + 1e-9) {
                drawLine(grid, Offset(l, y(v)), Offset(size.width - r, y(v)), strokeWidth = 1f)
                val lay = measurer.measure(BodyCalc.fmt(v, if (step < 1) 1 else 0), axisStyle)
                drawText(lay, topLeft = Offset(l - 5.dp.toPx() - lay.size.width, y(v) - lay.size.height / 2f))
                v += step
            }
            val xStep = maxOf(7.0, ceil((x1 - x0) / 4 / 7) * 7)
            var d = ceil(x0)
            while (d <= x1) {
                val lay = measurer.measure(BodyCalc.fmtDate(LocalDate.ofEpochDay(d.toLong()), false), axisStyle)
                drawText(lay, topLeft = Offset(x(d) - lay.size.width / 2f, size.height - lay.size.height))
                d += xStep
            }
            if (inX(today)) {
                drawLine(
                    muted.copy(alpha = 0.6f), Offset(x(today), t), Offset(x(today), size.height - b),
                    strokeWidth = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f)),
                )
            }
            clipRect(left = l, top = 0f, right = size.width - r, bottom = size.height - b + 1f) {
                val stroke2 = 2.dp.toPx()
                if (tps.size > 1) {
                    drawPath(
                        pathOf(tps.map { it.day to it.v }), muted,
                        style = Stroke(stroke2, cap = StrokeCap.Round, join = StrokeJoin.Round,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
                    )
                }
                if (isW) {
                    pts.forEach { drawCircle(accent.copy(alpha = 0.45f), 3.dp.toPx(), Offset(x(it.day), y(it.v))) }
                    if (avg.size > 1) {
                        drawPath(pathOf(avg.map { it.day to it.v }), accent,
                            style = Stroke(stroke2, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                } else {
                    if (pts.size > 1) {
                        drawPath(pathOf(pts.map { it.day to it.v }), accent.copy(alpha = 0.7f),
                            style = Stroke(stroke2, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                    pts.forEach {
                        drawCircle(surface, 5.5.dp.toPx(), Offset(x(it.day), y(it.v)))
                        drawCircle(accent, 4.dp.toPx(), Offset(x(it.day), y(it.v)))
                    }
                }
                selected?.let { sd ->
                    drawLine(textColor.copy(alpha = 0.4f), Offset(x(sd), t), Offset(x(sd), size.height - b), strokeWidth = 1.dp.toPx())
                }
            }
        }

        // Readout for the tapped (or latest) point
        val sel = selected ?: snap.lastOrNull { inX(it.first) }?.first
        if (sel != null) {
            val value = snap.first { it.first == sel }.second
            val a = if (isW) avg.firstOrNull { it.day == sel } else null
            val tgt = if (m.hasTarget) BodyCalc.targetAt(targets, m, a?.mid ?: sel) else null
            val raw = if (isW) pts.firstOrNull { it.day == sel } else null
            Text(
                "${BodyCalc.fmt(value)} ${m.unit} · ${BodyCalc.fmtDate(LocalDate.ofEpochDay(sel.toLong()))}" +
                    (a?.let { " · avg of ${it.n}" } ?: "") +
                    (raw?.let { " · weigh-in ${BodyCalc.fmt(it.v)}" } ?: "") +
                    (tgt?.let { " · target ${BodyCalc.fmt(it.v)} (${BodyCalc.fmtSigned(value - it.v)})" } ?: ""),
                fontSize = 12.sp, color = colors.muted,
            )
        }
        // Legend (only with more than one series)
        val legend = buildList {
            if (isW) { add(LegendItem("7-day avg", accent, LegendKind.LINE)); add(LegendItem("Weigh-ins", accent.copy(alpha = 0.45f), LegendKind.DOT)) }
            else if (tps.isNotEmpty()) add(LegendItem("Measured", accent, LegendKind.LINE))
            if (tps.isNotEmpty()) add(LegendItem("Target", muted, LegendKind.DASH))
        }
        if (legend.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { legend.forEach { LegendKey(it) } }
        }
    }
}

private enum class LegendKind { LINE, DASH, DOT }
private data class LegendItem(val label: String, val color: Color, val kind: LegendKind)

@Composable
private fun LegendKey(item: LegendItem) {
    val colors = LocalWorkoutColors.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Canvas(Modifier.width(16.dp).height(8.dp)) {
            val mid = size.height / 2
            when (item.kind) {
                LegendKind.LINE -> drawLine(item.color, Offset(0f, mid), Offset(size.width, mid), strokeWidth = 2.dp.toPx())
                LegendKind.DASH -> drawLine(item.color, Offset(0f, mid), Offset(size.width, mid), strokeWidth = 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())))
                LegendKind.DOT -> drawCircle(item.color, 3.5.dp.toPx(), Offset(size.width / 2, mid))
            }
        }
        Text(item.label, fontSize = 11.sp, color = colors.muted)
    }
}

// ── Plan editor ───────────────────────────────────────────────────────────────

@Composable
private fun PlanCard(
    targets: List<BodyTarget>,
    text: String,
    onText: (String) -> Unit,
    onAdd: () -> Unit,
    onReplace: () -> Unit,
    onDelete: (LocalDate) -> Unit,
) {
    val colors = LocalWorkoutColors.current
    BodyCard {
        Text("Target plan", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = colors.text)
        if (targets.isEmpty()) {
            Text("No checkpoints yet.", fontSize = 12.sp, color = colors.muted)
        } else {
            Row {
                Text("Date", Modifier.weight(1.4f), fontSize = 11.sp, color = colors.muted)
                BodyMetric.PLAN_ORDER.forEach { m ->
                    Text("${m.label.take(5)} ${m.unit}", Modifier.weight(1f), fontSize = 11.sp, color = colors.muted, textAlign = TextAlign.End)
                }
                Spacer(Modifier.width(32.dp))
            }
            targets.forEach { t ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(BodyCalc.fmtDate(t.date), Modifier.weight(1.4f), fontSize = 12.sp, color = colors.text)
                    BodyMetric.PLAN_ORDER.forEach { m ->
                        Text(BodyCalc.fmt(t.values[m]), Modifier.weight(1f), fontSize = 12.sp, color = colors.text, textAlign = TextAlign.End)
                    }
                    IconButton(onClick = { onDelete(t.date) }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Delete checkpoint", tint = colors.muted, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
        Text(
            "Paste checkpoints, one per line: date weight / waist / suprailiac / abdominal. " +
                "Use - for a value with no target. Example: 1.3.2027 90 / 85 / 6 / 12",
            fontSize = 12.sp, color = colors.muted, lineHeight = 17.sp,
        )
        TextField(
            value = text,
            onValueChange = onText,
            modifier = Modifier.fillMaxWidth(),
            minLines = 4,
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = colors.text),
            placeholder = { Text("1.3.2027 90 / 85 / 6 / 12", color = colors.muted, fontFamily = FontFamily.Monospace, fontSize = 13.sp) },
            colors = fieldColors(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TextButton(onClick = onAdd) { Text("Add / update", color = colors.text) }
            Button(
                onClick = onReplace,
                colors = ButtonDefaults.buttonColors(containerColor = colors.surface2, contentColor = colors.text),
            ) { Text("Replace plan") }
        }
    }
}

// ── Small pieces ──────────────────────────────────────────────────────────────

@Composable
private fun BodyCard(content: @Composable ColumnScope.() -> Unit) {
    val colors = LocalWorkoutColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
private fun NumField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier) {
    val colors = LocalWorkoutColors.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = colors.muted)
        TextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("–", color = colors.muted, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.End) },
            textStyle = TextStyle(color = colors.text, fontSize = 16.sp, textAlign = TextAlign.End),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
            colors = fieldColors(),
        )
    }
}

@Composable
private fun fieldColors() = LocalWorkoutColors.current.let { c ->
    TextFieldDefaults.colors(
        focusedContainerColor = c.surface2,
        unfocusedContainerColor = c.surface2,
        focusedIndicatorColor = c.accent,
        unfocusedIndicatorColor = c.border,
        focusedTextColor = c.text,
        unfocusedTextColor = c.text,
        cursorColor = c.accent,
    )
}

@Composable
private fun RangeChip(label: String, on: Boolean, onClick: () -> Unit) {
    val colors = LocalWorkoutColors.current
    Text(
        label,
        modifier = Modifier
            .clip(RoundedCornerShape(100.dp))
            .background(colors.surface2)
            .border(1.dp, if (on) colors.accent else colors.border, RoundedCornerShape(100.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
        fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        color = if (on) colors.text else colors.muted,
    )
}

@Composable
private fun ConfirmDialog(
    title: String,
    text: String,
    confirm: String,
    danger: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { if (text.isNotEmpty()) Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirm, color = if (danger) DangerColor else TextColor) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = SurfaceColor,
        titleContentColor = TextColor,
        textContentColor = MutedColor,
    )
}
