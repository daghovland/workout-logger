package no.daglifts.workout

import android.app.Application
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.functions.Functions
import io.github.jan.supabase.postgrest.Postgrest
import no.daglifts.workout.data.db.WorkoutDatabase
import no.daglifts.workout.repository.SamsungHealthRepository
import no.daglifts.workout.repository.SupabaseRepository
import no.daglifts.workout.repository.WorkoutRepository

/**
 * Application singleton — wires up all singletons (DB, repos, Supabase client).
 *
 * As a backend dev: think of this as your Spring ApplicationContext / Guice injector.
 * Android doesn't have DI built-in; this manual wiring is the lightweight alternative
 * to Hilt/Dagger for a project of this size.
 */
class WorkoutApp : Application() {

    val database by lazy { WorkoutDatabase.getInstance(this) }

    val supabaseClient by lazy {
        createSupabaseClient(
            supabaseUrl    = BuildConfig.SUPABASE_URL,
            supabaseKey    = BuildConfig.SUPABASE_ANON_KEY,
        ) {
            install(Auth) {
                scheme = "no.daglifts.workout"
                host   = "login-callback"
            }
            install(Postgrest)
            install(Functions)
        }
    }

    val workoutRepo by lazy {
        WorkoutRepository(
            sessionDao   = database.sessionDao(),
            dailyLogDao  = database.dailyLogDao(),
        )
    }

    val supabaseRepo by lazy { SupabaseRepository(supabaseClient) }

    val healthRepo by lazy { SamsungHealthRepository(this) }
}
