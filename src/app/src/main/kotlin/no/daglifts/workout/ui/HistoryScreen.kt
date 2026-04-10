package no.daglifts.workout.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import no.daglifts.workout.data.ExerciseDefinitions
import no.daglifts.workout.data.model.Session
import no.daglifts.workout.ui.components.SetChip
import no.daglifts.workout.ui.theme.DangerColor
import no.daglifts.workout.ui.theme.LocalWorkoutColors
import no.daglifts.workout.viewmodel.WorkoutViewModel
import no.daglifts.workout.viewmodel.formatSetShort
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun HistoryScreen(
    vm: WorkoutViewModel,
    onBack: () -> Unit,
) {
    val sessions by vm.sessions.collectAsState()
    val colors = LocalWorkoutColors.current

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
                "History",
                modifier = Modifier.weight(1f),
                fontSize = 17.sp, fontWeight = FontWeight.Bold, color = colors.text,
            )
        }

        if (sessions.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("No sessions yet.", fontSize = 15.sp, color = colors.muted)
                Text("Log a workout to see it here.", fontSize = 15.sp, color = colors.muted)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(sessions, key = { it.id }) { session ->
                    HistorySessionCard(
                        session  = session,
                        onDelete = { vm.deleteSession(session.id) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HistorySessionCard(
    session: Session,
    onDelete: () -> Unit,
) {
    val colors = LocalWorkoutColors.current
    var expanded by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    val typeLabel = when (session.type) {
        "gym"     -> "GYM"
        "noequip" -> "HOME"
        else      -> "OUTDOOR"
    }
    val typeColor = when (session.type) {
        "gym"     -> no.daglifts.workout.ui.theme.AccentGym
        "outdoor" -> no.daglifts.workout.ui.theme.AccentOutdoor
        else      -> no.daglifts.workout.ui.theme.AccentNoEquip
    }

    val totalSets = session.exercises.values.sumOf { it.sets.size }
    val durText = if (session.durationMs > 0) {
        " · ${fmtDuration(session.durationMs / 1000)}"
    } else ""
    val dateText = fmtDateLong(session.date)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(12.dp)),
    ) {
        // Collapsed header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Type badge
            Text(
                typeLabel,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(typeColor.copy(alpha = 0.15f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                fontSize = 10.sp, fontWeight = FontWeight.Bold, color = typeColor,
                letterSpacing = 0.5.sp,
            )
            Column(Modifier.weight(1f)) {
                Text(dateText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.text)
                Text("$totalSets sets$durText", fontSize = 12.sp, color = colors.muted)
            }
            Text(if (expanded) "▴" else "▾", color = colors.muted, fontSize = 14.sp)
        }

        // Expanded detail
        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        color = colors.border,
                        shape = RoundedCornerShape(0.dp),
                    )
                    .padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val exs = ExerciseDefinitions.allForType(
                    no.daglifts.workout.data.SessionType.valueOf(session.type.uppercase())
                ).filter { ex -> (session.exercises[ex.id]?.sets?.size ?: 0) > 0 }

                exs.forEach { ex ->
                    val sets = session.exercises[ex.id]?.sets ?: return@forEach
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(ex.name, fontSize = 12.sp, color = colors.muted, fontWeight = FontWeight.Medium)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            sets.forEachIndexed { i, set ->
                                SetChip(label = formatSetShort(ex, set), index = i)
                            }
                        }
                    }
                }

                session.notes?.let { notes ->
                    Text(notes, fontSize = 13.sp, color = colors.muted, lineHeight = 18.sp,
                        modifier = Modifier.padding(top = 4.dp))
                }

                // Actions row
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = { showDeleteDialog = true },
                        colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                            contentColor = DangerColor
                        ),
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, DangerColor.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 4.dp),
                    ) {
                        Text("Delete", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete session?") },
            text  = { Text("This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { showDeleteDialog = false; onDelete() }) {
                    Text("Delete", color = DangerColor)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            },
            containerColor = no.daglifts.workout.ui.theme.SurfaceColor,
            titleContentColor = no.daglifts.workout.ui.theme.TextColor,
            textContentColor = no.daglifts.workout.ui.theme.MutedColor,
        )
    }
}

private fun fmtDateLong(iso: String): String = try {
    val instant = Instant.parse(iso)
    val local = instant.atZone(ZoneId.systemDefault()).toLocalDate()
    local.format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.ENGLISH))
} catch (_: Exception) { iso.take(10) }
