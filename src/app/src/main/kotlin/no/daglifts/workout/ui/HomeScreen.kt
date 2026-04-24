package no.daglifts.workout.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import no.daglifts.workout.data.SessionType
import no.daglifts.workout.ui.theme.AccentGym
import no.daglifts.workout.ui.theme.AccentNoEquip
import no.daglifts.workout.ui.theme.AccentOutdoor
import no.daglifts.workout.ui.theme.LocalWorkoutColors
import no.daglifts.workout.viewmodel.HomeUiState
import no.daglifts.workout.viewmodel.WorkoutViewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun HomeScreen(
    vm: WorkoutViewModel,
    onStartSession: (SessionType) -> Unit,
    onShowHistory: () -> Unit,
    onShowProfile: () -> Unit,
    onShowChat: () -> Unit,
) {
    val state by vm.home.collectAsState()
    val colors = LocalWorkoutColors.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ── Header ──────────────────────────────────────────────────────────
        Column(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Workout Log",
                fontSize = 28.sp, fontWeight = FontWeight.Black, color = colors.text,
            )
            val today = LocalDate.now()
            val dayName = today.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
            val date = today.format(DateTimeFormatter.ofPattern("d MMMM", Locale.ENGLISH))
            Text("$dayName, $date", fontSize = 13.sp, color = colors.muted)

            Row(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.isSignedIn) {
                    Box(
                        modifier = Modifier.size(8.dp)
                            .clip(CircleShape)
                            .background(AccentGym)
                    )
                }
                IconButton(onClick = onShowProfile) {
                    Icon(Icons.Default.Person, contentDescription = "Profile", tint = colors.text)
                }
                if (state.isSignedIn) {
                    IconButton(onClick = onShowChat) {
                        Icon(Icons.Default.Chat, contentDescription = "Coach Chat", tint = colors.text)
                    }
                }
                IconButton(onClick = onShowHistory) {
                    Icon(Icons.Default.History, contentDescription = "History", tint = colors.text)
                }
            }
        }

        // ── Home brief (AI training recommendation) ──────────────────────
        if (state.isSignedIn) {
            HomeBriefCard(state, onRefresh = { vm.refreshHomeBrief() })
        }

        // ── Session type cards ───────────────────────────────────────────
        SessionTypeCard(
            icon = "🏋️",
            title = "Gym",
            description = "Rehab block + deadlift, OHP, pull-ups, row, DB bench",
            accent = AccentGym,
            lastText = state.lastGym?.let { "Last: ${fmtDate(it.date)}" } ?: "",
            onClick = { onStartSession(SessionType.GYM) },
        )
        SessionTypeCard(
            icon = "🌤️",
            title = "Outdoor / Street",
            description = "Squat machine, bench, deadlift, OHP, pull-ups, row",
            accent = AccentOutdoor,
            lastText = state.lastOutdoor?.let { "Last: ${fmtDate(it.date)}" } ?: "",
            onClick = { onStartSession(SessionType.OUTDOOR) },
        )
        SessionTypeCard(
            icon = "🏠",
            title = "No Equipment",
            description = "Push-ups, pull-ups, step-up, 1-leg RDL, inverted rows, wall sit, bird-dog…",
            accent = AccentNoEquip,
            lastText = state.lastNoEquip?.let { "Last: ${fmtDate(it.date)}" } ?: "",
            onClick = { onStartSession(SessionType.NOEQUIP) },
        )

        // ── Decline squat tracker ────────────────────────────────────────
        DeclineSquatCard(
            count   = state.todayDeclineSquats,
            target  = 6,
            onAdd   = { vm.incrementDeclineSquat() },
            onUndo  = { vm.decrementDeclineSquat() },
        )

        // ── Samsung Health snapshot (if available) ───────────────────────
        state.healthSnapshot?.let { snap ->
            val parts = mutableListOf<String>()
            snap.stepCountToday?.let    { parts += "${it.toInt()} steps" }
            snap.sleepHoursLastNight?.let { parts += "%.1fh sleep".format(it) }
            snap.restingHeartRate?.let  { parts += "${it} bpm resting HR" }
            snap.bodyWeightKg?.let      { parts += "%.1f kg".format(it) }
            if (parts.isNotEmpty()) {
                InfoCard(label = "Health Today", text = parts.joinToString(" · "))
            }
        }
    }
}

@Composable
private fun SessionTypeCard(
    icon: String,
    title: String,
    description: String,
    accent: Color,
    lastText: String,
    onClick: () -> Unit,
) {
    val colors = LocalWorkoutColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surface)
            .border(2.dp, accent, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(22.dp),
    ) {
        Text(icon, fontSize = 32.sp)
        Spacer(Modifier.height(10.dp))
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = colors.text)
        Text(description, fontSize = 13.sp, color = colors.muted, lineHeight = 18.sp)
        if (lastText.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(lastText, fontSize = 12.sp, color = accent)
        }
    }
}

@Composable
private fun HomeBriefCard(state: HomeUiState, onRefresh: () -> Unit) {
    val colors = LocalWorkoutColors.current
    if (state.homeBriefLoading || state.homeBrief != null) {
        val accentColor = when (state.homeBrief?.recommendation) {
            "gym"     -> AccentGym
            "outdoor" -> AccentOutdoor
            "home"    -> AccentNoEquip
            "light"   -> Color(0xFFF5A142)
            else      -> colors.muted
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(colors.surface)
                .border(
                    width = 1.dp,
                    color = colors.border,
                    shape = RoundedCornerShape(12.dp),
                )
                // Left accent bar is approximated by a start border — simplest approach
                .padding(start = 14.dp, top = 12.dp, end = 38.dp, bottom = 12.dp),
        ) {
            if (state.homeBriefLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp).align(Alignment.Center),
                    color = accentColor, strokeWidth = 2.dp,
                )
            } else {
                Text(
                    state.homeBrief?.message ?: "",
                    fontSize = 13.sp, color = colors.text, lineHeight = 20.sp,
                )
                IconButton(
                    onClick = onRefresh,
                    modifier = Modifier.align(Alignment.TopEnd).size(32.dp),
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = colors.muted, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun DeclineSquatCard(
    count: Int,
    target: Int,
    onAdd: () -> Unit,
    onUndo: () -> Unit,
) {
    val colors = LocalWorkoutColors.current
    val done = count >= target

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "DECLINE SQUAT",
                fontSize = 11.sp, fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.sp, color = colors.muted,
            )
            Text(
                "$count / $target",
                fontSize = 12.sp, fontWeight = FontWeight.Bold,
                color = if (done) colors.accent else colors.muted,
            )
        }
        // Dot indicators
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(target) { i ->
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(if (i < count) colors.accent else colors.surface2)
                        .border(2.dp, if (i < count) colors.accent else colors.border, CircleShape)
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onAdd,
                enabled = !done,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.surface2,
                    contentColor = colors.text,
                ),
            ) {
                Text(if (done) "Done! 🎉" else "+ Set", fontWeight = FontWeight.Bold)
            }
            if (count > 0) {
                Button(
                    onClick = onUndo,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.surface2,
                        contentColor = colors.muted,
                    ),
                ) {
                    Text("↩")
                }
            }
        }
    }
}

@Composable
private fun InfoCard(label: String, text: String) {
    val colors = LocalWorkoutColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(12.dp))
            .padding(14.dp),
    ) {
        Text(label.uppercase(), fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp, color = colors.muted)
        Spacer(Modifier.height(6.dp))
        Text(text, fontSize = 13.sp, color = colors.text)
    }
}

private fun fmtDate(iso: String): String = try {
    val instant = java.time.Instant.parse(iso)
    val local = instant.atZone(java.time.ZoneId.systemDefault()).toLocalDate()
    local.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH))
} catch (_: Exception) { iso.take(10) }
