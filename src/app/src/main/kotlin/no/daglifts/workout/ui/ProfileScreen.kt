package no.daglifts.workout.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import no.daglifts.workout.ui.theme.LocalWorkoutColors
import no.daglifts.workout.viewmodel.WorkoutViewModel

/**
 * Profile screen: sign-in status, coaching context.
 *
 * The coaching context text is stored in the Supabase user_profiles table
 * (same as the PWA). It's sent along with AI coaching requests.
 */
@Composable
fun ProfileScreen(vm: WorkoutViewModel, onBack: () -> Unit) {
    val homeState by vm.home.collectAsState()
    val colors = LocalWorkoutColors.current
    val scope = rememberCoroutineScope()

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
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = colors.text)
            }
            Text(
                "Profile",
                modifier = Modifier.weight(1f),
                fontSize = 17.sp, fontWeight = FontWeight.Bold, color = colors.text,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!homeState.isSignedIn) {
                // Sign-in prompt
                Text("🏋️", fontSize = 52.sp)
                Text("Cloud Sync + AI Coaching", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = colors.text)
                Text(
                    "Sign in to sync sessions across devices and get AI-powered training recommendations.",
                    fontSize = 14.sp, color = colors.muted, lineHeight = 20.sp,
                )
                Button(
                    onClick = { scope.launch { vm.signInWithGoogle() } },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.accent, contentColor = Color.Black,
                    ),
                ) {
                    Text("Sign in with Google", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            } else {
                // Samsung Health status
                homeState.healthSnapshot?.let { snap ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(colors.surface, RoundedCornerShape(12.dp))
                            .border(1.dp, colors.border, RoundedCornerShape(12.dp))
                            .padding(14.dp),
                    ) {
                        Text("Samsung Health", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = colors.text)
                        Spacer(Modifier.height(6.dp))
                        val items = mutableListOf<String>()
                        snap.stepCountToday?.let    { items += "Steps today: ${it.toInt()}" }
                        snap.sleepHoursLastNight?.let { items += "Sleep last night: %.1fh".format(it) }
                        snap.restingHeartRate?.let  { items += "Resting HR: ${it} bpm" }
                        snap.bodyWeightKg?.let      { items += "Body weight: %.1f kg".format(it) }
                        snap.stressScore?.let       { items += "Stress score: $it/100" }
                        if (items.isEmpty()) {
                            Text("No health data available. Check Samsung Health SDK setup.", fontSize = 13.sp, color = colors.muted)
                        } else {
                            items.forEach { Text(it, fontSize = 13.sp, color = colors.muted) }
                        }
                    }
                } ?: run {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(colors.surface, RoundedCornerShape(12.dp))
                            .border(1.dp, colors.border, RoundedCornerShape(12.dp))
                            .padding(14.dp),
                    ) {
                        Text("Samsung Health", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = colors.text)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Not connected. See SETUP.md to configure the Samsung Health SDK.",
                            fontSize = 13.sp, color = colors.muted,
                        )
                    }
                }

                // Sign-out
                Button(
                    onClick = { scope.launch { vm.signOut() } },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.surface2, contentColor = colors.text,
                    ),
                ) {
                    Text("Sign Out", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
