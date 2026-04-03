package no.daglifts.workout.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import no.daglifts.workout.data.Exercise
import no.daglifts.workout.data.ExerciseDefinitions
import no.daglifts.workout.data.SessionType
import no.daglifts.workout.data.model.ExerciseSet
import no.daglifts.workout.data.model.SetInputs
import no.daglifts.workout.ui.components.SetChip
import no.daglifts.workout.ui.components.Stepper
import no.daglifts.workout.ui.theme.LocalWorkoutColors
import no.daglifts.workout.viewmodel.SessionUiState
import no.daglifts.workout.viewmodel.SuggestionState
import no.daglifts.workout.viewmodel.WorkoutViewModel
import no.daglifts.workout.viewmodel.formatSetShort

@Composable
fun SessionScreen(
    vm: WorkoutViewModel,
    onFinished: () -> Unit,
    onCancel: () -> Unit,
) {
    val sessionState by vm.session.collectAsState()
    val s = sessionState as? SessionUiState.Active ?: return
    val colors = LocalWorkoutColors.current

    var elapsedSecs by remember { mutableStateOf(0L) }
    LaunchedEffect(s.startTimeMs) {
        while (true) {
            elapsedSecs = (System.currentTimeMillis() - s.startTimeMs) / 1000
            delay(1000)
        }
    }

    Box(Modifier.fillMaxSize().background(colors.bg)) {
        Column(Modifier.fillMaxSize()) {
            // ── Header ───────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.bg)
                    .border(width = 1.dp, color = colors.border, shape = RoundedCornerShape(0.dp))
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    when (s.type) {
                        SessionType.GYM     -> "Gym Session"
                        SessionType.OUTDOOR -> "Outdoor Session"
                        SessionType.NOEQUIP -> "No Equipment"
                    },
                    modifier = Modifier.weight(1f),
                    fontSize = 17.sp, fontWeight = FontWeight.Bold, color = colors.text,
                )
                Text(fmtDuration(elapsedSecs), fontSize = 13.sp, color = colors.muted)
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onCancel) {
                    Icon(Icons.Default.Close, contentDescription = "Cancel", tint = colors.text)
                }
            }

            // ── Coach brief ───────────────────────────────────────────────
            s.coachBrief?.let { brief ->
                Text(
                    brief,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.surface2)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    fontSize = 13.sp, color = colors.muted, lineHeight = 18.sp,
                )
            }

            // ── Exercise cards ────────────────────────────────────────────
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (group in ExerciseDefinitions.groupsForType(s.type)) {
                    if (group.label.isNotEmpty()) {
                        Text(
                            group.label,
                            fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
                            color = colors.muted, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                        )
                    }
                    for (ex in group.exercises) {
                        val sets = s.session.exercises[ex.id]?.sets ?: emptyList()
                        val inputs = s.inputs[ex.id] ?: SetInputs(reps = ex.defaultReps)
                        val suggestion = s.suggestions[ex.id]
                        val isOpen = s.openExerciseId == ex.id

                        ExerciseCard(
                            ex         = ex,
                            sets       = sets,
                            inputs     = inputs,
                            suggestion = suggestion,
                            isOpen     = isOpen,
                            lastVal    = s.lastVals[ex.id],
                            onToggle   = { vm.openExercise(ex.id) },
                            onLogSet   = { vm.logSet(ex.id) },
                            onDeleteSet= { idx -> vm.deleteSet(ex.id, idx) },
                            onInputChange = { vm.updateInputs(ex.id, it) },
                        )
                    }
                }
                Spacer(Modifier.height(80.dp)) // space for finish bar
            }
        }

        // ── Finish bar (floating at bottom) ───────────────────────────────
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 14.dp, vertical = 12.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(colors.surface)
                .border(1.dp, colors.border, RoundedCornerShape(16.dp))
                .padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(fmtDuration(elapsedSecs), fontSize = 13.sp, color = colors.muted)
            Button(
                onClick = onFinished,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.accent,
                    contentColor = Color.Black,
                ),
            ) {
                Text("Finish Workout ✓", fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExerciseCard(
    ex: Exercise,
    sets: List<ExerciseSet>,
    inputs: SetInputs,
    suggestion: SuggestionState?,
    isOpen: Boolean,
    lastVal: ExerciseSet?,
    onToggle: () -> Unit,
    onLogSet: () -> Unit,
    onDeleteSet: (Int) -> Unit,
    onInputChange: (SetInputs) -> Unit,
) {
    val colors = LocalWorkoutColors.current
    val isDone = sets.isNotEmpty() && !isOpen

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface)
            .border(
                width = 1.dp,
                color = when { isOpen -> colors.accent; else -> colors.border },
                shape = RoundedCornerShape(12.dp),
            ),
    ) {
        // Card header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(if (isDone) colors.surface2 else Color.Transparent)
                    .border(2.dp, if (isOpen) colors.accent else colors.border, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (sets.isNotEmpty()) Text("✓", fontSize = 13.sp, color = colors.muted)
            }
            Column(Modifier.weight(1f)) {
                Text(ex.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.text)
                // Meta line: suggestion or last value
                val metaText = when (suggestion) {
                    is SuggestionState.Loading -> "⚡ …"
                    is SuggestionState.Ready -> {
                        val s = suggestion.suggestion
                        val sugStr = if (ex.unit != null && ex.unit != "BW")
                            "${s.weight ?: "?"} ${ex.unit} × ${s.reps ?: "?"}"
                        else "${s.reps ?: "?"} reps"
                        val lastStr = lastVal?.let { " · ${formatSetShort(ex, it)}" } ?: ""
                        "⚡ $sugStr$lastStr"
                    }
                    else -> lastVal?.let { formatSetShort(ex, it) } ?: "No previous data"
                }
                Text(metaText, fontSize = 12.sp, color = colors.muted)
            }
            if (sets.isNotEmpty()) {
                Text("${sets.size}×", fontSize = 12.sp, color = colors.accent, fontWeight = FontWeight.Bold)
            }
            Icon(
                if (isOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null, tint = colors.muted,
            )
        }

        // Card body (expanded)
        if (isOpen) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(width = 0.dp, color = Color.Transparent, shape = RoundedCornerShape(0.dp))
                    .padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // AI tip
                if (suggestion is SuggestionState.Ready && suggestion.suggestion.note != null) {
                    Text(
                        "⚡ ${suggestion.suggestion.note}",
                        fontSize = 13.sp, color = colors.muted, lineHeight = 18.sp,
                    )
                }

                // Previously logged set chips
                if (sets.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        sets.forEachIndexed { i, set ->
                            SetChip(
                                label    = formatSetShort(ex, set),
                                index    = i,
                                isLatest = i == sets.size - 1,
                                onDelete = { onDeleteSet(i) },
                            )
                        }
                    }
                }

                // Input controls
                SetLogger(
                    ex       = ex,
                    inputs   = inputs,
                    setNum   = sets.size + 1,
                    onChange = onInputChange,
                    onLog    = onLogSet,
                )
            }
        }
    }
}

@Composable
private fun SetLogger(
    ex: Exercise,
    inputs: SetInputs,
    setNum: Int,
    onChange: (SetInputs) -> Unit,
    onLog: () -> Unit,
) {
    val colors = LocalWorkoutColors.current

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (ex.asym) {
            // Left / Right weight inputs
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Weight (${ex.unit})", fontSize = 12.sp, color = colors.muted, modifier = Modifier.width(56.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Stepper(
                        value = inputs.weightL?.toString() ?: "",
                        placeholder = "BW",
                        onDecrement = { onChange(inputs.copy(weightL = ((inputs.weightL ?: 0.0) - (ex.step ?: 1.0)).coerceAtLeast(0.0))) },
                        onIncrement = { onChange(inputs.copy(weightL = (inputs.weightL ?: 0.0) + (ex.step ?: 1.0))) },
                        onValueChange = { onChange(inputs.copy(weightL = it.toDoubleOrNull())) },
                    )
                    Text("Left", fontSize = 11.sp, color = colors.muted)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Stepper(
                        value = inputs.weightR?.toString() ?: "",
                        placeholder = "BW",
                        onDecrement = { onChange(inputs.copy(weightR = ((inputs.weightR ?: 0.0) - (ex.step ?: 1.0)).coerceAtLeast(0.0))) },
                        onIncrement = { onChange(inputs.copy(weightR = (inputs.weightR ?: 0.0) + (ex.step ?: 1.0))) },
                        onValueChange = { onChange(inputs.copy(weightR = it.toDoubleOrNull())) },
                    )
                    Text("Right", fontSize = 11.sp, color = colors.muted)
                }
            }
        } else if (ex.unit != null && ex.unit != "BW" && ex.unit != "sec") {
            // Symmetric weight
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Weight\n${ex.unit}", fontSize = 12.sp, color = colors.muted, modifier = Modifier.width(56.dp))
                Stepper(
                    value = inputs.weight?.toString() ?: "",
                    placeholder = "BW",
                    onDecrement = { onChange(inputs.copy(weight = ((inputs.weight ?: 0.0) - (ex.step ?: 1.0)).coerceAtLeast(0.0))) },
                    onIncrement = { onChange(inputs.copy(weight = (inputs.weight ?: 0.0) + (ex.step ?: 1.0))) },
                    onValueChange = { onChange(inputs.copy(weight = it.toDoubleOrNull())) },
                )
            }
        }

        // Reps / seconds
        val repLabel = if (ex.unit == "sec") "Sec" else "Reps"
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(repLabel, fontSize = 12.sp, color = colors.muted, modifier = Modifier.width(56.dp))
            Stepper(
                value = inputs.reps.toString(),
                onDecrement = { onChange(inputs.copy(reps = (inputs.reps - (if (ex.unit == "sec") (ex.step?.toInt() ?: 5) else 1)).coerceAtLeast(0))) },
                onIncrement = { onChange(inputs.copy(reps = inputs.reps + (if (ex.unit == "sec") (ex.step?.toInt() ?: 5) else 1))) },
                onValueChange = { onChange(inputs.copy(reps = it.toIntOrNull() ?: inputs.reps)) },
            )
        }

        // Notes field
        var notesOpen by remember { mutableStateOf(false) }
        Text(
            "+ Add note",
            fontSize = 13.sp, color = colors.muted,
            modifier = Modifier.clickable { notesOpen = !notesOpen },
        )
        if (notesOpen) {
            OutlinedTextField(
                value = inputs.notes,
                onValueChange = { onChange(inputs.copy(notes = it)) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Note for set $setNum…", color = colors.muted) },
                minLines = 2,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.accent,
                    unfocusedBorderColor = colors.border,
                    focusedTextColor = colors.text,
                    unfocusedTextColor = colors.text,
                    cursorColor = colors.accent,
                ),
            )
        }

        Button(
            onClick = onLog,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.accent,
                contentColor = Color.Black,
            ),
        ) {
            Text(
                "LOG SET $setNum",
                fontSize = 17.sp, fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
    }
}

fun fmtDuration(totalSecs: Long): String {
    val h = totalSecs / 3600
    val m = (totalSecs % 3600) / 60
    val s = totalSecs % 60
    return if (h > 0) "${h}h ${m}m"
    else "%d:%02d".format(m, s)
}
