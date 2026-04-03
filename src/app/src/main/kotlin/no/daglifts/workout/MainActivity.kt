package no.daglifts.workout

import android.os.Bundle
import androidx.activity.ComponentActivity
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
import no.daglifts.workout.ui.HistoryScreen
import no.daglifts.workout.ui.HomeScreen
import no.daglifts.workout.ui.SessionScreen
import no.daglifts.workout.ui.SummaryScreen
import no.daglifts.workout.ui.theme.AccentGym
import no.daglifts.workout.ui.theme.AccentNoEquip
import no.daglifts.workout.ui.theme.AccentOutdoor
import no.daglifts.workout.ui.theme.WorkoutTheme
import no.daglifts.workout.viewmodel.SessionUiState
import no.daglifts.workout.viewmodel.WorkoutViewModel
import no.daglifts.workout.viewmodel.WorkoutViewModelFactory
import androidx.compose.material3.Scaffold as Scaffold

class MainActivity : ComponentActivity() {

    private val vm: WorkoutViewModel by viewModels { WorkoutViewModelFactory(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Handle deep links for Supabase OAuth redirect
        // (the intent arrives here after Google sign-in)
        intent?.data?.let { uri ->
            if (uri.scheme == "no.daglifts.workout" && uri.host == "login-callback") {
                // Supabase SDK picks up the session from the URL automatically
                // via the auth state change listener wired in WorkoutViewModel.initAuth()
            }
        }

        setContent {
            WorkoutApp(vm)
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.data?.let { uri ->
            if (uri.scheme == "no.daglifts.workout") {
                vm.onAuthStateChanged(true)
            }
        }
    }
}

@Composable
fun WorkoutApp(vm: WorkoutViewModel) {
    val sessionState by vm.session.collectAsState()
    val homeState    by vm.home.collectAsState()

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

        // Show toasts as snackbars
        LaunchedEffect(toast) {
            toast?.let {
                snackbarHostState.showSnackbar(it)
                vm.clearToast()
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
                        onSaved  = { navController.popBackStack("home", inclusive = false) },
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
            }
        }
    }
}
