package no.daglifts.workout.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import no.daglifts.workout.data.ExerciseDefinitions
import no.daglifts.workout.ui.components.SetChip
import no.daglifts.workout.ui.theme.DangerColor
import no.daglifts.workout.ui.theme.LocalWorkoutColors
import no.daglifts.workout.viewmodel.SessionUiState
import no.daglifts.workout.viewmodel.WorkoutViewModel
import no.daglifts.workout.viewmodel.formatSetShort

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SummaryScreen(
    vm: WorkoutViewModel,
    onSaved: () -> Unit,
    onDiscard: () -> Unit,
) {
    val sessionState by vm.session.collectAsState()
    val s = sessionState as? SessionUiState.Summary ?: return
    val colors = LocalWorkoutColors.current

    val exercises = ExerciseDefinitions.allForType(s.type)
        .filter { ex -> (s.session.exercises[ex.id]?.sets?.size ?: 0) > 0 }
    val totalSets = s.session.exercises.values.sumOf { it.sets.size }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg),
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.bg)
                .border(1.dp, colors.border, RoundedCornerShape(0.dp))
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onDiscard) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = colors.text)
            }
            Text(
                "Session Summary",
                modifier = Modifier.weight(1f),
                fontSize = 17.sp, fontWeight = FontWeight.Bold, color = colors.text,
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Stats
            StatCard(totalSets = totalSets, exerciseCount = exercises.size, durationMs = s.session.durationMs)

            // Exercise breakdown
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.surface)
                    .border(1.dp, colors.border, RoundedCornerShape(12.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("EXERCISES", fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, color = colors.muted)
                exercises.forEach { ex ->
                    val sets = s.session.exercises[ex.id]?.sets ?: return@forEach
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(ex.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.text)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            sets.forEachIndexed { i, set ->
                                SetChip(label = formatSetShort(ex, set), index = i)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Actions
            Button(
                onClick = {
                    vm.saveSession(s.session)
                    onSaved()
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.accent,
                    contentColor = Color.Black,
                ),
            ) {
                Text("Save Session", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(vertical = 4.dp))
            }

            OutlinedButton(
                onClick = {
                    if (true /* always allow */) {
                        vm.cancelSession()
                        onDiscard()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, DangerColor),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = DangerColor),
            ) {
                Text("Discard", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun StatCard(totalSets: Int, exerciseCount: Int, durationMs: Long) {
    val colors = LocalWorkoutColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(12.dp))
            .padding(14.dp),
    ) {
        Text("SESSION STATS", fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, color = colors.muted)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            StatItem(value = "$totalSets",     label = "Total sets")
            StatItem(value = "$exerciseCount", label = "Exercises")
            StatItem(value = fmtDuration(durationMs / 1000), label = "Duration")
        }
    }
}

@Composable
private fun StatItem(value: String, label: String) {
    val colors = LocalWorkoutColors.current
    Column {
        Text(value, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = colors.text)
        Text(label, fontSize = 12.sp, color = colors.muted)
    }
}
