package no.daglifts.workout

import android.os.Bundle
import androidx.activity.ComponentActivity
import io.github.jan.supabase.auth.handleDeeplinks
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import no.daglifts.workout.data.SessionType
import no.daglifts.workout.data.body.BodyCalc
import no.daglifts.workout.data.body.BodyMetric
import no.daglifts.workout.ui.BodyScreen
import no.daglifts.workout.ui.ChatScreen
import no.daglifts.workout.ui.HistoryScreen
import no.daglifts.workout.ui.HomeScreen
import no.daglifts.workout.ui.SessionScreen
import no.daglifts.workout.ui.ProfileScreen
import no.daglifts.workout.ui.SummaryScreen
import no.daglifts.workout.ui.theme.AccentGym
import no.daglifts.workout.ui.theme.AccentNoEquip
import no.daglifts.workout.ui.theme.AccentOutdoor
import no.daglifts.workout.ui.theme.WorkoutTheme
import no.daglifts.workout.viewmodel.BodyViewModel
import no.daglifts.workout.viewmodel.SessionUiState
import no.daglifts.workout.viewmodel.WorkoutViewModel
import no.daglifts.workout.viewmodel.WorkoutViewModelFactory
import androidx.compose.material3.Scaffold as Scaffold

class MainActivity : ComponentActivity() {

    private val vm: WorkoutViewModel by viewModels { WorkoutViewModelFactory(this) }
    private val bodyVm: BodyViewModel by viewModels { WorkoutViewModelFactory(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Handle OAuth redirect on cold-start (app launched fresh from browser callback).
        // onNewIntent handles the warm-start case; we need both.
        (application as WorkoutApp).supabaseClient.handleDeeplinks(intent) {
            vm.onOAuthSessionImported()
        }

        setContent {
            WorkoutApp(vm, bodyVm)
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Let the Supabase SDK extract the JWT from the OAuth redirect URL.
        // handleDeeplinks matches on Auth config scheme/host and handles PKCE/implicit.
        (application as WorkoutApp).supabaseClient.handleDeeplinks(intent) {
            vm.onOAuthSessionImported()
        }
    }
}

@Composable
fun WorkoutApp(vm: WorkoutViewModel, bodyVm: BodyViewModel) {
    val sessionState by vm.session.collectAsState()
    val homeState    by vm.home.collectAsState()
    val bodyEntries  by bodyVm.entries.collectAsState()
    val bodyTargets  by bodyVm.targets.collectAsState()
    val bodySummary = remember(bodyEntries, bodyTargets) {
        BodyCalc.status(bodyEntries, bodyTargets, BodyMetric.WAIST)?.let { st ->
            "Waist ${BodyCalc.fmt(st.value)} cm" +
                (st.state?.let { s -> " · ${s.label.lowercase()} (${BodyCalc.fmtSigned(st.diff)})" } ?: "")
        } ?: "Log weight, waist, skinfolds"
    }

    // Derive accent colour from active session type
    val accent = when {
        sessionState is SessionUiState.Active  -> (sessionState as SessionUiState.Active).type.let {
            when (it) { SessionType.GYM -> AccentGym; SessionType.OUTDOOR -> AccentOutdoor; else -> AccentNoEquip }
        }
        sessionState is SessionUiState.Summary -> (sessionState as SessionUiState.Summary).type.let {
            when (it) { SessionType.GYM -> AccentGym; SessionType.OUTDOOR -> AccentOutdoor; else -> AccentNoEquip }
        }
        else -> AccentGym
    }

    WorkoutTheme(accent = accent) {
        val navController = rememberNavController()
        val snackbarHostState = remember { SnackbarHostState() }
        val toast by vm.toast.collectAsState()
        val bodyToast by bodyVm.toast.collectAsState()

        // Show toasts as snackbars
        LaunchedEffect(toast) {
            toast?.let {
                snackbarHostState.showSnackbar(it)
                vm.clearToast()
            }
        }
        LaunchedEffect(bodyToast) {
            bodyToast?.let {
                bodyVm.clearToast()
                snackbarHostState.showSnackbar(it)
            }
        }

        // Navigate automatically when session state changes
        LaunchedEffect(sessionState) {
            when (sessionState) {
                is SessionUiState.Active  -> if (navController.currentDestination?.route != "session")
                    navController.navigate("session") { launchSingleTop = true }
                is SessionUiState.Summary -> if (navController.currentDestination?.route != "summary")
                    navController.navigate("summary") { launchSingleTop = true }
                is SessionUiState.Idle    -> { /* stay on current screen */ }
            }
        }

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            snackbarHost = { SnackbarHost(snackbarHostState) },
            containerColor = no.daglifts.workout.ui.theme.BgColor,
        ) { _ ->
            NavHost(navController = navController, startDestination = "home") {
                composable("home") {
                    HomeScreen(
                        vm             = vm,
                        onStartSession = { type ->
                            vm.startSession(type)
                            // Navigation happens via LaunchedEffect above
                        },
                        onShowHistory  = { navController.navigate("history") },
                        onShowProfile  = { navController.navigate("profile") },
                        onShowChat     = { navController.navigate("chat") },
                        onShowBody     = { navController.navigate("body") },
                        bodySummary    = bodySummary,
                    )
                }
                composable("session") {
                    SessionScreen(
                        vm        = vm,
                        onFinished = {
                            vm.finishSession()
                            // Navigation to summary happens via LaunchedEffect
                        },
                        onCancel  = {
                            vm.cancelSession()
                            navController.popBackStack("home", inclusive = false)
                        },
                    )
                }
                composable("summary") {
                    SummaryScreen(
                        vm       = vm,
                        onSaved  = { openChat ->
                            navController.popBackStack("home", inclusive = false)
                            // Post-session coach review was requested — show it in the chat
                            if (openChat) navController.navigate("chat")
                        },
                        onDiscard = {
                            vm.cancelSession()
                            navController.popBackStack("home", inclusive = false)
                        },
                    )
                }
                composable("history") {
                    HistoryScreen(vm = vm, onBack = { navController.popBackStack() })
                }
                composable("profile") {
                    ProfileScreen(vm = vm, onBack = { navController.popBackStack() })
                }
                composable("chat") {
                    ChatScreen(vm = vm, onBack = { navController.popBackStack() })
                }
                composable("body") {
                    BodyScreen(vm = bodyVm, onBack = { navController.popBackStack() })
                }
            }
        }
    }
}
